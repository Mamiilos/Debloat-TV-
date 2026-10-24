package io.freemytv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeProvider;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The optional tvOS 26-style home screen (theme "appletv"), after Aerial (github.com/pengchujin/aerial-tv, MIT): a large
 * clock over a Liquid Glass plate that carries the top row, the other apps in a grid under it, folders, and a Control
 * Center behind the status pill at the top right. HomeMode, the screensaver and the watchdog stay shared.
 * Nothing is blurred live: the wallpaper's frosted copy is a tiny bitmap stretched over the screen.
 */
final class AppleHomeView extends View implements Choreographer.FrameCallback, HomeData.Listener {
    // Aerial's 960×540 dp layout on the 1920×1080 canvas: 5 columns of 16:9 tiles, the top row a little past the middle.
    private static final int COLS = 5;
    private static final float MARGIN = 112, TW = 294, TH = 166, PX = 350, PY = 266, TOP_ROW = 600, REST = 400,
            COLLAPSE = TOP_ROW - REST, PAD_H = 52, PAD_V = 40, NAME_ROOM = 56, PLATE_R = 64, CORNER = 24, SWELL = 28,
            FOCUSED = 1.15f, PRESSED = 1.05f, FOLDER_TOP = 250;
    private static final float PANEL_W = 720, PANEL_RIGHT = 1856, PANEL_TOP = 48, PANEL_PAD = 44, ROW_H = 88,
            MENU_W = 520, MENU_ROW = 76;
    private static final int PILL_ID = 10000, FOLDER_ID = 20000, PANEL_ID = 30000, MENU_ID = 40000;
    private static final int NONE = 0, FOLDER = 1, PANEL = 2, MENU = 3;
    private static final int NETWORK = -2;   // Control Center's own button kind
    private static final int ACT_MOVE = 0, ACT_NEW_FOLDER = 1, ACT_ADD = 2, ACT_OUT = 3;
    private static final int NET_WIFI = 0, NET_ETHERNET = 1, NET_OFFLINE = 2;
    private static final long HOLD_MS = 550;
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    private static final String[] MEDIA = {"com.apple.atve.androidtv.appletv", "ru.kinopoisk.tv", "com.kinopub",
            "com.lazycatsoftware.lmd", "mobi.zona", "ru.rutube.app", "ru.ivi.client", "ru.rt.video.app.tv",
            "ru.mts.mtstv", "org.videolan.vlc", "com.valvesoftware.steamlink"};

    /** One place in the grid: an app, or a folder of apps. */
    private static final class Entry {
        final HomeApps.Item item;
        String folder;
        final List<HomeApps.Item> apps;
        final Spring focus = new Spring(0.26f, 1, 0);
        String label = "";

        Entry(HomeApps.Item item) { this.item = item; folder = null; apps = null; }
        Entry(String name, List<HomeApps.Item> apps) { item = null; folder = name; this.apps = apps; }
        String name() { return item != null ? item.label : folder; }
    }

    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final HomeData data;
    private final Click click;
    private final Shadows shadows = new Shadows();
    private final AudioManager audio;
    private final boolean reduceMotion;
    private final Spoken spoken;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint wallPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint platePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint panelPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint edgeFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgeRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint topFade = new Paint();
    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clock = Ui.text(Ui.displayMedium, 208, 0xFFFFFFFF);
    private final Paint date = Ui.text(Ui.displaySemibold, 38, 0xE6FFFFFF);
    private final Paint temp = Ui.text(Ui.displaySemibold, 38, 0xFFFFFFFF);
    private final Paint weather = Ui.text(Ui.semibold, 30, 0xC7FFFFFF);
    private final Paint pillTime = Ui.text(Ui.semibold, 30, 0xE6FFFFFF);
    private final Paint folderTitle = Ui.text(Ui.displaySemibold, 52, 0xFFFFFFFF);
    private final Paint panelTime = Ui.text(Ui.displaySemibold, 65, 0xFFFFFFFF);
    private final Paint secondary = Ui.text(Ui.medium, 26, 0x99EBEBF5);
    private final Paint caption = Ui.text(Ui.medium, 22, 0x99EBEBF5);
    private final Paint row = Ui.text(Ui.medium, 28, 0xFFFFFFFF);
    private final TextPaint name = new TextPaint(Ui.text(Ui.semibold, 30, 0xFFFFFFFF));
    private final Paint[] faded;   // the clock's text, with a soft shadow for bright photos
    private final Shader sheenShader, edgeSheen, edgeRimShader;
    private final RectF box = new RectF(), art = new RectF(), shade = new RectF(), plate = new RectF(),
            panel = new RectF(), cell = new RectF(), dst = new RectF(0, 0, 1920, 1080);
    private final Matrix matrix = new Matrix(), unit = new Matrix(), plateMatrix = new Matrix(), panelMatrix = new Matrix();
    private final Path wifiArc = new Path();

    private final Spring scroll = new Spring(0.34f, 1, 0);
    private final Spring plateGap = new Spring(0.3f, 1, NAME_ROOM);
    private final Spring press = new Spring(0.12f, 1, 0);
    private final Spring pillFocus = new Spring(0.22f, 1, 0);
    private final Spring pillTimeIn = new Spring(0.25f, 1, 0);
    private final Spring nudgeX = new Spring(0.32f, 0.45f, 0), nudgeY = new Spring(0.32f, 0.45f, 0);
    private final Spring folderIn = new Spring(0.3f, 1, 0), panelIn = new Spring(0.3f, 1, 0), menuIn = new Spring(0.2f, 1, 0);
    private final Spring folderScroll = new Spring(0.34f, 1, 0);

    // What is on screen.
    private final List<Entry> entries = new ArrayList<>();
    private final List<Entry> folderEntries = new ArrayList<>();
    private List<HomeApps.Item> inputs = new ArrayList<>();
    private final List<HomeApps.Item> panelButtons = new ArrayList<>();
    private final List<String> menuLabels = new ArrayList<>();
    private final List<int[]> menuActions = new ArrayList<>();   // {action, folder index}
    private Entry openFolder;
    private List<Entry> moveBackup;
    private int overlay = NONE, selected, folderSel, panelRow, panelCol, menuSel, moving = -1, net = NET_WIFI;
    private boolean pill, inputsPage, menuInFolder, holding, held;
    private boolean running, destroyed, loading, receiversOn, framePending;
    private long lastFrame, appearAt, movingAt;
    private float scale = 1, offsetX, offsetY;
    private int volume, volumeMax = 1;
    private boolean muted;

    // Text made when the minute or the weather changes, never while drawing.
    private String time = "", day = "", tempText = "", weatherText = "", weatherSymbol, netLabel = "",
            volumeText = "", folderName = "", moveHint;
    private String[] buttonLabels = new String[0];
    private String[] inputLabels = new String[0];

    // The wallpaper, its frosted copy and that copy darkened for the grid.
    private Bitmap wall, frost, dark;
    private java.io.File photo, loadingPhoto;

    // 4K wallpapers (Walls4k): a still video on the TV's video layer, behind this view, which leaves a hole for it.
    private android.view.SurfaceView videoView;
    private android.media.MediaPlayer player;
    private java.io.File video, loadingVideo;
    private boolean surfaceReady, videoShown;
    private final Paint videoShade = new Paint(Paint.DITHER_FLAG);

    AppleHomeView(Activity a) {
        super(a);
        activity = a;
        data = HomeData.get(a);
        click = new Click(a);
        audio = (AudioManager) a.getSystemService(Context.AUDIO_SERVICE);
        reduceMotion = Settings.Global.getFloat(a.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1) == 0;
        setFocusable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);

        clock.setLetterSpacing(-0.02f);
        folderTitle.setLetterSpacing(-0.015f);
        faded = new Paint[] {clock, date, temp, weather};
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(1.5f);
        glyph.setStrokeCap(Paint.Cap.ROUND);
        glyph.setStrokeJoin(Paint.Join.ROUND);
        // Light from above on a lifted tile, and the lit edge of glass: unit-sized, placed with a local matrix.
        sheenShader = new RadialGradient(0, 0, 1, 0x47FFFFFF, 0, Shader.TileMode.CLAMP);
        sheen.setShader(sheenShader);
        edgeSheen = new LinearGradient(0, 0, 0, 1, new int[] {0x1AFFFFFF, 0}, new float[] {0, 0.5f}, Shader.TileMode.CLAMP);
        edgeFill.setShader(edgeSheen);
        edgeRimShader = new LinearGradient(0, 0, 1, 1, new int[] {0x8CFFFFFF, 0x1AFFFFFF, 0x0FFFFFFF, 0x4DFFFFFF},
                new float[] {0, 0.35f, 0.7f, 1}, Shader.TileMode.CLAMP);
        edgeRim.setShader(edgeRimShader);
        edgeRim.setStyle(Paint.Style.STROKE);
        edgeRim.setStrokeWidth(2.4f);
        topFade.setShader(new LinearGradient(0, 0, 0, 240, new int[] {0xFF000000, 0xD1000000, 0}, new float[] {0, 0.5f, 1},
                Shader.TileMode.CLAMP));
        // Milky glass: the frosted picture a little darker, lifted with white. Panels: at half its light.
        platePaint.setColorFilter(tint(0.8f, 30));
        panelPaint.setColorFilter(tint(0.48f, 14));
        moveHint = Ui.tr("← → to move · OK to drop · Back to cancel");

        spoken = new Spoken(this, new Spoken.Source() {
            @Override public void items(List<Integer> out) {
                switch (overlay) {
                    case MENU: for (int i = 0; i < menuLabels.size(); i++) out.add(MENU_ID + i); break;
                    case PANEL: for (int i = 0; i < panelItems(); i++) out.add(PANEL_ID + i); break;
                    case FOLDER: for (int i = 0; i < folderEntries.size(); i++) out.add(FOLDER_ID + i); break;
                    default:
                        out.add(PILL_ID);
                        for (int i = 0; i < entries.size(); i++) out.add(i + 1);
                }
            }
            @Override public String text(int id) {
                if (id >= MENU_ID) return menuLabels.get(id - MENU_ID);
                if (id >= PANEL_ID) return panelText(id - PANEL_ID);
                if (id >= FOLDER_ID) return folderEntries.get(id - FOLDER_ID).name();
                if (id == PILL_ID) return Ui.tr("Control Center");
                return entries.get(id - 1).name();
            }
            @Override public boolean bounds(int id, Rect out) {
                if (id >= MENU_ID) menuRow(id - MENU_ID, box);
                else if (id >= PANEL_ID) panelItemRect(id - PANEL_ID, box);
                else if (id >= FOLDER_ID) { folderSlot(id - FOLDER_ID, box); box.offset(0, -folderScroll.value); }
                else if (id == PILL_ID) pillRect(box);
                else { slot(id - 1, box); box.offset(0, -scroll.value); }
                if (box.bottom < 0 || box.top > 1080) return false;
                out.set(Math.round(offsetX + box.left * scale), Math.round(offsetY + box.top * scale),
                        Math.round(offsetX + box.right * scale), Math.round(offsetY + box.bottom * scale));
                return true;
            }
            @Override public int focused() {
                switch (overlay) {
                    case MENU: return MENU_ID + menuSel;
                    case PANEL: return PANEL_ID + panelIndex();
                    case FOLDER: return folderEntries.isEmpty() ? -1 : FOLDER_ID + folderSel;
                    default: return pill ? PILL_ID : entries.isEmpty() ? -1 : selected + 1;
                }
            }
            @Override public void focus(int id) {
                if (id >= MENU_ID) menuSel = id - MENU_ID;
                else if (id >= PANEL_ID) setPanelIndex(id - PANEL_ID);
                else if (id >= FOLDER_ID) folderSel = id - FOLDER_ID;
                else if (id == PILL_ID) pill = true;
                else { pill = false; selected = id - 1; }
                retarget();
            }
            @Override public void click(int id) { focus(id); activate(); }
            @Override public boolean holdable(int id) { return id > 0 && id < PILL_ID || id >= FOLDER_ID && id < PANEL_ID; }
            @Override public void hold(int id) { focus(id); openMenu(); }
        });
        LOADER.execute(() -> {
            Symbols.warmWeather(activity, 38);
            for (String s : new String[] {"live_tv", "hdmi", "tv_settings", "settings", "speaker", "chevron_right"}) {
                Symbols.warm(activity, s, 40);
            }
        });
        refreshText();
        loadBackground();
    }

    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() { return spoken; }

    private static ColorMatrixColorFilter tint(float k, float lift) {
        return new ColorMatrixColorFilter(new float[] {k, 0, 0, 0, lift, 0, k, 0, 0, lift, 0, 0, k, 0, lift, 0, 0, 0, 1, 0});
    }

    // MARK: - Life cycle

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) { loadApps(); }
    };
    private final BroadcastReceiver ticks = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            refreshText();
            data.refreshIfStale();
            loadBackground();   // My Photos: the time of day may have turned (Photos.forNow)
            invalidate();
        }
    };
    private final Runnable holdTimer = () -> {
        if (holding && !held && canHold()) {
            held = true;
            press.target = 0;
            openMenu();
        }
    };

    void resume() {
        if (running) return;
        running = true;
        data.addListener(this);
        if (!receiversOn) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_PACKAGE_ADDED);
            filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
            filter.addDataScheme("package");
            activity.registerReceiver(packages, filter);
            IntentFilter time = new IntentFilter(Intent.ACTION_TIME_TICK);
            time.addAction(Intent.ACTION_TIME_CHANGED);
            time.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            activity.registerReceiver(ticks, time);
            receiversOn = true;
        }
        refreshText();
        data.refreshIfStale();
        loadApps();
        loadBackground();
        startVideo();
        Photos.importInbox(activity, this::loadBackground);   // photos a computer just sent (tools/photos.py)
        animateFrames();
        spoken.readAgain();
    }

    void pause() {
        running = false;
        data.removeListener(this);
        handler.removeCallbacks(holdTimer);
        Choreographer.getInstance().removeFrameCallback(this);
        framePending = false;
        lastFrame = 0;
        holding = false;
        press.snap(0);
        if (moving >= 0) endMove(false);
        stopVideo();   // the decoder goes back to the TV: the app opened next may need it
        if (receiversOn) {
            activity.unregisterReceiver(packages);
            activity.unregisterReceiver(ticks);
        }
        receiversOn = false;
    }

    void destroy() { destroyed = true; pause(); click.release(); }

    /** Home pressed: everything closes and the first app takes focus, as on tvOS. */
    void reset() {
        if (moving >= 0) endMove(false);
        overlay = NONE;
        openFolder = null;
        folderIn.snap(0);
        panelIn.snap(0);
        menuIn.snap(0);
        pill = false;
        selected = 0;
        retarget();
    }

    @Override public void onData() { refreshText(); if (running) invalidate(); }

    // MARK: - Crypto widget (CoinsCard: prices from HomeData, charts over Prefs.chart())

    private static final float CARD_TOP = 150;
    private CoinsCard coins;

    private float cardLeft() { return 1920 - MARGIN - CoinsCard.W; }

    /** Made when the data changes, never while drawing. */
    private void refreshCoins() {
        if (coins == null) coins = new CoinsCard(getContext());
        coins.refresh(data, cardLeft(), CARD_TOP, 1);
    }

    /** Top right, beside the clock, on glass. */
    private void drawCoins(Canvas c, float a) {
        float left = cardLeft();
        shade.set(left, CARD_TOP, left + CoinsCard.W, CARD_TOP + CoinsCard.height(coins.rows()));
        drawGlass(c, shade, 40, scroll.value, a);
        coins.draw(c, a);
    }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        scale = Math.min(w / 1920f, h / 1080f);
        offsetX = (w - 1920 * scale) / 2;
        offsetY = (h - 1080 * scale) / 2;
    }

    // MARK: - Loading

    private void loadApps() {
        if (loading || destroyed) return;
        loading = true;
        LOADER.execute(() -> {
            List<HomeApps.Item> loaded = new ArrayList<>();
            List<HomeApps.Item> foundInputs;
            boolean live;
            try {
                List<HomeApps.Item> sources = new ArrayList<>(HomeApps.shelf(activity, true));
                List<HomeApps.Item> remaining = HomeApps.others(activity);
                // Video apps first after the person's shelf, then the rest by name.
                Collections.sort(remaining, (x, y) -> Integer.compare(mediaRank(x), mediaRank(y)));
                sources.addAll(remaining);
                Set<String> seen = new HashSet<>();
                for (HomeApps.Item item : sources) {
                    if (seen.add(item.pkg == null ? item.key : item.pkg)) loaded.add(item);
                }
                foundInputs = HomeApps.allInputs(activity);
                live = HomeApps.liveTv(activity) != null;
            } catch (RuntimeException e) {
                AppLog.w("Apple TV apps: " + e.getMessage());
                foundInputs = new ArrayList<>();
                live = false;
            }
            List<HomeApps.Item> readyInputs = foundInputs;
            boolean hasLive = live;
            handler.post(() -> {
                loading = false;
                if (destroyed) return;
                String previous = entries.isEmpty() ? null : key(entries.get(selected));
                arrange(loaded);
                inputs = readyInputs;
                buildPanel(hasLive);
                selected = Math.min(selected, Math.max(0, entries.size() - 1));
                if (previous != null) {
                    for (int i = 0; i < entries.size(); i++) if (key(entries.get(i)).equals(previous)) selected = i;
                }
                if (appearAt == 0 && !reduceMotion) appearAt = SystemClock.uptimeMillis();
                if (overlay == FOLDER && openFolder != null) {
                    Entry again = null;
                    for (Entry e : entries) if (e.apps != null && e.folder.equals(openFolder.folder)) again = e;
                    if (again == null) closeFolder(); else showFolder(again, false);
                }
                retarget();
            });
        });
    }

    /** The saved order and folders over what is installed; apps new to the TV go at the end. */
    private void arrange(List<HomeApps.Item> items) {
        Map<String, HomeApps.Item> byKey = new HashMap<>();
        for (HomeApps.Item it : items) byKey.put(it.key, it);
        Set<String> used = new HashSet<>();
        List<Entry> out = new ArrayList<>();
        for (String line : Prefs.appleLayout().split("\n")) {
            String[] f = line.split("\t");
            if (f.length >= 2 && f[0].equals("a")) {
                HomeApps.Item it = byKey.get(f[1]);
                if (it != null && used.add(it.key)) out.add(new Entry(it));
            } else if (f.length >= 3 && f[0].equals("f")) {
                List<HomeApps.Item> apps = new ArrayList<>();
                for (String k : f[2].split("\u001f")) {
                    HomeApps.Item it = byKey.get(k);
                    if (it != null && used.add(it.key)) apps.add(it);
                }
                if (!apps.isEmpty()) out.add(new Entry(f[1], apps));
            }
        }
        for (HomeApps.Item it : items) if (!used.contains(it.key)) out.add(new Entry(it));
        // Springs carry on for what was already on screen, so a reload doesn't flash the focus.
        Map<String, Entry> old = new HashMap<>();
        for (Entry e : entries) old.put(key(e), e);
        entries.clear();
        for (Entry e : out) {
            Entry before = old.get(key(e));
            if (before != null) e.focus.snap(before.focus.value);
            e.label = ellipsize(e.name());
            entries.add(e);
        }
    }

    private void save() {
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) {
            if (e.item != null) sb.append("a\t").append(e.item.key).append('\n');
            else {
                sb.append("f\t").append(e.folder.replace('\t', ' ').replace('\n', ' ')).append('\t');
                for (int i = 0; i < e.apps.size(); i++) sb.append(i > 0 ? "\u001f" : "").append(e.apps.get(i).key);
                sb.append('\n');
            }
        }
        Prefs.setAppleLayout(sb.toString());
    }

    private static String key(Entry e) { return e.item != null ? e.item.key : "\u0000" + e.folder; }

    private String ellipsize(String s) {
        return TextUtils.ellipsize(s, name, TW + 72, TextUtils.TruncateAt.END).toString();
    }

    private static int mediaRank(HomeApps.Item item) {
        for (int i = 0; i < MEDIA.length; i++) if (MEDIA[i].equals(item.pkg)) return i;
        return MEDIA.length;
    }

    private void buildPanel(boolean live) {
        panelButtons.clear();
        if (live) panelButtons.add(HomeApps.action(HomeApps.LIVE_TV, "live", Ui.tr("Live TV"), "live_tv"));
        panelButtons.add(HomeApps.action(HomeApps.INPUTS, "inputs", Ui.tr("Inputs"), "hdmi"));
        panelButtons.add(HomeApps.action(NETWORK, "network", Ui.tr("Network"), null));
        panelButtons.add(HomeApps.action(HomeApps.ANDROID_SETTINGS, "tv-settings", Ui.tr("TV Settings"), "tv_settings"));
        buttonLabels = new String[panelButtons.size()];
        for (int i = 0; i < buttonLabels.length; i++) {
            buttonLabels[i] = TextUtils.ellipsize(panelButtons.get(i).label, new TextPaint(caption), 162,
                    TextUtils.TruncateAt.END).toString();
        }
        inputLabels = new String[inputs.size()];
        for (int i = 0; i < inputLabels.length; i++) {
            inputLabels[i] = TextUtils.ellipsize(inputs.get(i).label, new TextPaint(row), PANEL_W - 2 * PANEL_PAD - 120,
                    TextUtils.TruncateAt.END).toString();
        }
        panelCol = Math.min(panelCol, panelButtons.size() - 1);
    }

    /** 4K wallpapers when some were sent, else My Photos when it's on, else a dusk sky made here once. */
    private void loadBackground() {
        java.io.File[] four = videoView == null ? null : Walls4k.now(activity);
        if (four != null) {
            if (!four[0].equals(video) && !four[0].equals(loadingVideo)) showVideo(four);
            return;
        }
        if (video != null) {
            // The 4K wallpapers were removed: back to the photos or the sky.
            video = null;
            stopVideo();
            videoView.setVisibility(GONE);
            photo = null;
            wall = null;
        }
        java.io.File want = Photos.on(activity) ? Photos.now(activity) : null;
        if (want != null && !want.equals(photo) && !want.equals(loadingPhoto)) {
            loadingPhoto = want;
            LOADER.execute(() -> {
                Bitmap picture = null, small = null;
                try {
                    picture = shaded(android.graphics.BitmapFactory.decodeFile(want.getPath()));
                    if (picture != null) {
                        small = Ui.downscale(picture, 96, 54);
                        picture = Backdrop.gpu(picture);   // only drawn from now on: GPU memory, not 8 MB of the app's
                    }
                } catch (RuntimeException | OutOfMemoryError e) {
                    AppLog.w("Apple TV background " + want.getName() + ": " + e.getMessage());
                }
                Bitmap p = picture, f = small;
                handler.post(() -> {
                    if (want.equals(loadingPhoto)) loadingPhoto = null;
                    if (destroyed || p == null) return;
                    photo = want;
                    useFrost(p, f);
                });
            });
        } else if (want == null && (wall == null || photo != null)) {
            photo = null;
            LOADER.execute(() -> {
                Bitmap sky = dusk();
                Bitmap small = Ui.downscale(sky, 96, 54);
                handler.post(() -> { if (!destroyed && photo == null) useFrost(sky, small); });
            });
        }
    }

    // MARK: - 4K wallpapers on the video layer

    void attachVideo(android.view.SurfaceView v) {
        videoView = v;
        videoShade.setShader(new LinearGradient(0, 0, 0, 1080, SHADE, SHADE_AT, Shader.TileMode.CLAMP));
        v.setVisibility(GONE);   // no hole in the screen until there is a video to show through it
        v.getHolder().addCallback(new android.view.SurfaceHolder.Callback() {
            @Override public void surfaceCreated(android.view.SurfaceHolder h) { surfaceReady = true; startVideo(); }
            @Override public void surfaceChanged(android.view.SurfaceHolder h, int format, int w, int hh) {}
            @Override public void surfaceDestroyed(android.view.SurfaceHolder h) { surfaceReady = false; stopVideo(); }
        });
    }

    /** The still first (the frosted glass is made from it, and it shows until the video does), then the video. */
    private void showVideo(java.io.File[] four) {
        loadingVideo = four[0];
        LOADER.execute(() -> {
            Bitmap picture = null, small = null;
            try {
                picture = shaded(android.graphics.BitmapFactory.decodeFile(four[1].getPath()));
                if (picture != null) {
                    small = Ui.downscale(picture, 96, 54);
                    picture = Backdrop.gpu(picture);
                }
            } catch (RuntimeException | OutOfMemoryError e) {
                AppLog.w("4K wallpaper " + four[1].getName() + ": " + e.getMessage());
            }
            Bitmap p = picture, f = small;
            handler.post(() -> {
                if (four[0].equals(loadingVideo)) loadingVideo = null;
                if (destroyed || p == null) return;
                video = four[0];
                photo = null;
                useFrost(p, f);
                stopVideo();
                videoView.setVisibility(VISIBLE);   // its surface comes, and with it startVideo
                startVideo();
            });
        });
    }

    private void startVideo() {
        if (!running || !surfaceReady || video == null || videoView == null || player != null) return;
        android.media.MediaPlayer mp = new android.media.MediaPlayer();
        player = mp;
        try {
            mp.setDataSource(video.getPath());
            mp.setSurface(videoView.getHolder().getSurface());
            mp.setVolume(0, 0);
            mp.setOnPreparedListener(m -> {
                if (player != m) return;
                m.setVideoScalingMode(android.media.MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
                m.start();
            });
            mp.setOnInfoListener((m, what, extra) -> {
                if (player == m && what == android.media.MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    m.pause();   // a still: the frame stays on the video layer, and the decoder rests
                    videoShown = true;
                    invalidate();
                }
                return false;
            });
            mp.setOnErrorListener((m, what, extra) -> {
                AppLog.w("4K wallpaper: player error " + what + "/" + extra);
                if (player == m) stopVideo();
                return true;
            });
            mp.prepareAsync();
        } catch (java.io.IOException | RuntimeException e) {
            AppLog.w("4K wallpaper " + video.getName() + ": " + e.getMessage());
            stopVideo();
        }
    }

    private void stopVideo() {
        android.media.MediaPlayer mp = player;
        player = null;
        if (mp != null) {
            try {
                mp.release();
            } catch (RuntimeException ignored) {}
        }
        if (videoShown) {
            videoShown = false;
            invalidate();
        }
    }

    /** The light shading over the wallpaper: darker at the top (the clock) and the bottom, as `shaded` bakes in. */
    private static final int[] SHADE = {0x66000000, 0x14000000, 0x14000000, 0x59000000};
    private static final float[] SHADE_AT = {0, 0.4f, 0.6f, 1};

    /**
     * Your photo at the UI's size, only lightly shaded at the top (the clock) and the bottom: on this layout the text
     * stands on glass, so the photo keeps its own light, a day photo bright and a night one dark.
     */
    private static Bitmap shaded(Bitmap src) {
        if (src == null) return null;
        // At the UI's own size (1920×1080 here; 1280×720 on TVs that draw their UI at 720p), so the photo lands pixel
        // for pixel: made smaller and stretched back on screen, it read soft.
        int w = Backdrop.W, h = Backdrop.H;
        Bitmap small = Ui.downscale(src, w, h);
        Bitmap out = small.isMutable() ? small : small.copy(Bitmap.Config.ARGB_8888, true);
        if (small != out) small.recycle();
        if (src != small && src != out) src.recycle();
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.DITHER_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, h, SHADE, SHADE_AT, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        return out;
    }

    private void useFrost(Bitmap picture, Bitmap frosted) {
        wall = picture;
        frost = frosted;
        dark = frosted.copy(Bitmap.Config.ARGB_8888, true);
        new Canvas(dark).drawColor(0x8C000000);
        platePaint.setShader(new BitmapShader(frost, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        panelPaint.setShader(new BitmapShader(frost, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        invalidate();
    }

    /** Deep blue overhead, a little violet light and warmth low on the horizon: only gradients, at half size. */
    private static Bitmap dusk() {
        int w = 960, h = 540;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, h, new int[] {0xFF0A1530, 0xFF16204A, 0xFF2A1F45, 0xFF120E1C},
                new float[] {0, 0.45f, 0.78f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        glow(c, p, 0.16f * w, 0.18f * h, 0.62f * w, 0xA0587ED8);
        glow(c, p, 0.84f * w, 0.30f * h, 0.55f * w, 0x8C9A5AA8);
        glow(c, p, 0.58f * w, 1.05f * h, 0.70f * w, 0x80E2875A);
        glow(c, p, 0.40f * w, -0.05f * h, 0.40f * w, 0x33FFFFFF);
        return b;
    }

    private static void glow(Canvas c, Paint p, float x, float y, float r, int color) {
        int half = (color & 0x00FFFFFF) | (((color >>> 24) / 3) << 24);
        p.setShader(new RadialGradient(x, y, r, new int[] {color, half, 0}, new float[] {0, 0.5f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, c.getWidth(), c.getHeight(), p);
    }

    private void refreshText() {
        refreshCoins();
        long now = System.currentTimeMillis();
        time = Ui.clock(now);
        day = Ui.date(now);
        HomeData.Weather wx = data.weather;
        if (Prefs.weather() && !Double.isNaN(wx.temp)) {
            tempText = HomeData.degrees(wx.temp);
            weatherSymbol = Symbols.weather(wx.code, wx.isDay);
            String city = Prefs.city();
            StringBuilder sb = new StringBuilder();
            if (!city.isEmpty() && city.length() <= 14) sb.append(city).append("  ·  ");
            sb.append(HomeData.condition(wx.code, wx.isDay));
            if (!Double.isNaN(wx.max) && !Double.isNaN(wx.min)) sb.append("  ·  ").append(HomeData.highLow(wx, "  "));
            weatherText = sb.toString();
            refreshWeather(wx);
        } else {
            tempText = "";
            weatherText = "";
            weatherSymbol = null;
        }
        net = network();
        netLabel = net == NET_ETHERNET ? Ui.tr("Ethernet") : net == NET_OFFLINE ? Ui.tr("Offline") : Ui.tr("Wi-Fi");
    }

    private int network() {
        try {
            ConnectivityManager cm = (ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
            if (caps == null) return NET_OFFLINE;
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? NET_ETHERNET : NET_WIFI;
        } catch (RuntimeException e) {
            return NET_WIFI;
        }
    }

    private void readVolume() {
        try {
            volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            volumeMax = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            muted = audio.isStreamMute(AudioManager.STREAM_MUSIC);
        } catch (RuntimeException e) {
            volume = 0;
        }
        volumeText = muted ? Ui.tr("Muted") : String.valueOf(volume);
    }

    // MARK: - Geometry

    private int row(int index) { return index / COLS; }

    /** Where a grid place sits at scroll 0: the rows under the plate step down while it holds the focused name. */
    private void slot(int index, RectF out) {
        int r = row(index);
        float x = MARGIN + PX * (index % COLS), y = TOP_ROW + PY * r + (r > 0 ? plateGap.value : 0);
        out.set(x, y, x + TW, y + TH);
    }

    private void folderSlot(int index, RectF out) {
        float x = MARGIN + PX * (index % COLS), y = FOLDER_TOP + PY * row(index);
        out.set(x, y, x + TW, y + TH);
    }

    private float scrollFor(int r, int rows) {
        if (r <= 0) return 0;
        float wanted = TOP_ROW + PY * r - REST;
        float bottom = TOP_ROW + PY * rows + 72 - 1080;
        return Math.min(wanted, Math.max(bottom, TOP_ROW + PY - REST));
    }

    private void pillRect(RectF out) {
        float w = 28 + 34 + 24 + 34 + 28 + (pillTime.measureText(time) + 24) * pillTimeIn.value;
        float right = 1920 - MARGIN + 28;
        out.set(right - w, 56, right, 124);
    }

    private float panelHeight() {
        if (inputsPage) return 2 * PANEL_PAD + ROW_H * (1 + inputs.size()) + 8 * inputs.size();
        return 616;
    }

    private void panelRect(RectF out) { out.set(PANEL_RIGHT - PANEL_W, PANEL_TOP, PANEL_RIGHT, PANEL_TOP + panelHeight()); }

    private void buttonCell(int index, RectF out) {
        float left = PANEL_RIGHT - PANEL_W + PANEL_PAD, width = PANEL_W - 2 * PANEL_PAD;
        float step = panelButtons.size() <= 1 ? 0 : (width - 144) / (panelButtons.size() - 1);
        float x = left + step * index + 14, y = PANEL_TOP + 186;
        out.set(x, y, x + 116, y + 116);
    }

    private void panelRowRect(int r, RectF out) {
        float left = PANEL_RIGHT - PANEL_W + PANEL_PAD - 14, right = PANEL_RIGHT - PANEL_PAD + 14;
        float y = inputsPage ? PANEL_TOP + PANEL_PAD + (ROW_H + 8) * r : PANEL_TOP + (r == 1 ? 380 : 484);
        out.set(left, y, right, y + ROW_H);
    }

    private void menuRect(RectF out) {
        RectF anchor = cell;
        if (menuInFolder) { folderSlot(folderSel, anchor); anchor.offset(0, -folderScroll.value); }
        else { slot(selected, anchor); anchor.offset(0, -scroll.value); }
        float h = 32 + MENU_ROW * menuLabels.size();
        float x = Math.max(64, Math.min(anchor.left - 12, 1920 - 64 - MENU_W));
        float y = anchor.bottom + 78;
        if (y + h > 1040) y = anchor.top - 36 - h;
        out.set(x, y, x + MENU_W, y + h);
    }

    private void menuRow(int index, RectF out) {
        menuRect(panel);
        out.set(panel.left + 16, panel.top + 16 + MENU_ROW * index, panel.right - 16, panel.top + 16 + MENU_ROW * (index + 1));
    }

    // MARK: - Drawing

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        // With a 4K wallpaper on the video layer, this view is clear where it shows through.
        if (videoShown) c.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR); else c.drawColor(0xFF05060A);
        c.save();
        c.translate(offsetX, offsetY);
        c.scale(scale, scale);
        float folderV = folderIn.value, panelV = panelIn.value;
        // Under a whole frosted folder, Home isn't drawn at all.
        if (folderV < 0.999f) drawHome(c);
        if (folderV > 0.001f) drawFolder(c, folderV);
        if (panelV > 0.001f) drawPanel(c, panelV);
        if (menuIn.value > 0.001f) drawMenu(c, menuIn.value);
        if (moving >= 0) drawHint(c);
        c.restore();
    }

    private void drawHome(Canvas c) {
        float p = Ui.clamp(scroll.value / COLLAPSE);
        // The sharp picture and the grid's frosted one cross only through the middle of the scroll.
        float g = Ui.clamp((p - 0.3f) / 0.4f);
        if (videoShown) {
            if (g < 0.99f) c.drawRect(dst, videoShade);
            if (g > 0.01f) { wallPaint.setAlpha(Math.round(255 * g)); c.drawBitmap(dark, null, dst, wallPaint); }
        } else if (wall != null) {
            if (g < 0.99f) { wallPaint.setAlpha(255); c.drawBitmap(wall, null, dst, wallPaint); }
            if (g > 0.01f) { wallPaint.setAlpha(Math.round(255 * g)); c.drawBitmap(dark, null, dst, wallPaint); }
        } else {
            c.drawColor(0xFF0E1530);
        }

        c.save();
        c.translate(0, -scroll.value);
        float clockAlpha = 1 - Ui.clamp(scroll.value / COLLAPSE * 1.6f);
        if (clockAlpha > 0.01f) drawClock(c, clockAlpha);
        if (clockAlpha > 0.01f && coins != null && coins.rows() > 0) drawCoins(c, clockAlpha);
        float rest = 1 - smooth(Ui.clamp(p / 0.55f));
        if (rest > 0.01f && !entries.isEmpty()) drawPlate(c, rest);
        for (int i = 0; i < entries.size(); i++) if (i != selected || pill) drawEntry(c, entries.get(i), i, false);
        if (!entries.isEmpty() && !pill) drawEntry(c, entries.get(selected), selected, false);
        c.restore();

        if (p > 0.01f) {
            topFade.setAlpha(Math.round(255 * p));
            c.drawRect(0, 0, 1920, 240, topFade);
        }
        drawPill(c);
    }

    private static float smooth(float x) { return x * x * (3 - 2 * x); }

    /** The date over the time, and under it the weather, as on the lock screen. */
    private void drawClock(Canvas c, float a) {
        int alpha = Math.round(255 * a);
        // A see-through shadow keeps its own alpha: it fades with the text only when set again.
        int soft = Ui.alpha(0x47000000, a);
        for (Paint p : faded) p.setShadowLayer(18, 0, 2, soft);
        date.setAlpha(Math.round(0.9f * alpha));
        c.drawText(day, MARGIN, 186, date);
        clock.setAlpha(alpha);
        c.drawText(time, MARGIN - Ui.leftBearing(clock, time), 372, clock);
        if (weatherSymbol != null) drawWeather(c, a);
    }

    // MARK: - Weather widget: now, then the next hours, on glass under the clock

    private static final float WX_LEFT = MARGIN - 24, WX_TOP = 404, WX_W = 1060, WX_H = 128, WX_HOUR = 96;
    private static final int WX_HOURS = 5;
    private final Paint wxTemp = Ui.text(Ui.displaySemibold, 56, 0xFFFFFFFF), wxLine = Ui.text(Ui.semibold, 26, 0xFFFFFFFF),
            wxSmall = Ui.text(Ui.medium, 22, 0x99EBEBF5), wxHourTemp = Ui.numbers(Ui.semibold, 24, 0xFFFFFFFF);
    private final String[] hourTime = new String[WX_HOURS], hourTemp = new String[WX_HOURS], hourSymbol = new String[WX_HOURS];
    private int hourCount;
    private String wxCondition = "", wxPlace = "";

    private void drawWeather(Canvas c, float a) {
        shade.set(WX_LEFT, WX_TOP, WX_LEFT + WX_W, WX_TOP + WX_H);
        drawGlass(c, shade, 36, scroll.value, a);
        float cy = WX_TOP + WX_H / 2;
        Symbols.draw(getContext(), c, weatherSymbol, WX_LEFT + 60, cy - 4, 52, Ui.alpha(0xFFFFFFFF, a));
        wxTemp.setColor(Ui.alpha(0xFFFFFFFF, a));
        float x = WX_LEFT + 108;
        c.drawText(tempText, x, cy + Ui.halfCap(wxTemp), wxTemp);
        x += wxTemp.measureText(tempText) + 24;
        wxLine.setColor(Ui.alpha(0xFFFFFFFF, a));
        c.drawText(wxCondition, x, cy - 6, wxLine);
        wxSmall.setColor(Ui.alpha(0x99EBEBF5, a));
        c.drawText(wxPlace, x, cy + 30, wxSmall);
        float hx = WX_LEFT + WX_W - 24 - WX_HOUR * hourCount;
        for (int i = 0; i < hourCount; i++) {
            float mx = hx + WX_HOUR * i + WX_HOUR / 2;
            c.drawText(hourTime[i], mx - wxSmall.measureText(hourTime[i]) / 2, WX_TOP + 38, wxSmall);
            Symbols.draw(getContext(), c, hourSymbol[i], mx, WX_TOP + 66, 30, Ui.alpha(0xFFFFFFFF, a));
            wxHourTemp.setColor(Ui.alpha(0xFFFFFFFF, a));
            c.drawText(hourTemp[i], mx - wxHourTemp.measureText(hourTemp[i]) / 2, WX_TOP + 108, wxHourTemp);
        }
    }

    /** The widget's text, made with the rest when the minute or the data changes. */
    private void refreshWeather(HomeData.Weather wx) {
        hourCount = Math.min(WX_HOURS, wx.hours.size());
        for (int i = 0; i < hourCount; i++) {
            hourTime[i] = Ui.clock(wx.hours.get(i));
            hourTemp[i] = HomeData.degrees(wx.hourTemp.get(i));
            hourSymbol[i] = Symbols.weather(wx.hourCode.get(i), wx.hourDay.get(i));
        }
        // What fits left of the hours.
        float room = WX_W - 24 - WX_HOUR * hourCount - 108 - wxTemp.measureText(tempText) - 24 - 16;
        wxCondition = TextUtils.ellipsize(HomeData.condition(wx.code, wx.isDay), new TextPaint(wxLine), room,
                TextUtils.TruncateAt.END).toString();
        String city = Prefs.city();
        String range = !Double.isNaN(wx.max) && !Double.isNaN(wx.min) ? HomeData.highLow(wx, "  ") : "";
        String place = city.isEmpty() ? range : range.isEmpty() ? city : city + "  ·  " + range;
        wxPlace = TextUtils.ellipsize(place, new TextPaint(wxSmall), room, TextUtils.TruncateAt.END).toString();
    }

    /** The glass plate under the top row, its full width however few apps there are; it swells a little as it goes. */
    private void drawPlate(Canvas c, float rest) {
        float grow = SWELL * (1 - rest);
        plate.set(MARGIN - PAD_H - grow, TOP_ROW - PAD_V - grow,
                MARGIN + PX * (COLS - 1) + TW + PAD_H + grow, TOP_ROW + TH + PAD_V + plateGap.value + grow);
        if (frost != null) {
            // The frosted picture lies on the screen, which has moved up by the scroll.
            plateMatrix.setScale(1920f / frost.getWidth(), 1080f / frost.getHeight());
            plateMatrix.postTranslate(0, scroll.value);
            platePaint.getShader().setLocalMatrix(plateMatrix);
            platePaint.setAlpha(Math.round(255 * rest));
            c.drawRoundRect(plate, PLATE_R, PLATE_R, platePaint);
        } else {
            fill.setColor(Ui.alpha(0x40FFFFFF, rest));
            c.drawRoundRect(plate, PLATE_R, PLATE_R, fill);
        }
        drawGlassEdge(c, plate, PLATE_R, rest);
    }

    private void drawGlassEdge(Canvas c, RectF r, float radius, float a) {
        unit.setScale(r.width(), r.height());
        unit.postTranslate(r.left, r.top);
        edgeSheen.setLocalMatrix(unit);
        edgeRimShader.setLocalMatrix(unit);
        edgeFill.setAlpha(Math.round(255 * a));
        c.drawRoundRect(r, radius, radius, edgeFill);
        edgeRim.setAlpha(Math.round(255 * a));
        art.set(r);
        art.inset(1.2f, 1.2f);
        c.drawRoundRect(art, radius - 1.2f, radius - 1.2f, edgeRim);
    }

    /** One tile, the tvOS way: it lifts toward you when focused, light lies across its top, its name appears under it. */
    private void drawEntry(Canvas c, Entry e, int index, boolean inFolder) {
        if (inFolder) folderSlot(index, box); else slot(index, box);
        float onScreen = box.top - (inFolder ? folderScroll.value : scroll.value);
        if (onScreen > 1100 || onScreen + TH < -60) return;
        float a = 1;
        if (appearAt > 0 && !inFolder) {
            // First appearance: a short stagger, so the grid settles in rather than popping on.
            float t = Ui.clamp((SystemClock.uptimeMillis() - appearAt - Math.min(index * 28, 320)) / 420f);
            a = 1 - (1 - t) * (1 - t) * (1 - t);
            box.offset(0, (1 - a) * 36);
        }
        if (a < 0.01f) return;
        boolean current = inFolder ? index == folderSel && overlay == FOLDER : index == selected && !pill && overlay == NONE;
        float lift = Ui.clamp(e.focus.value), s = 1 + (FOCUSED - 1) * lift;
        if (current) s += (PRESSED - FOCUSED) * Ui.clamp(press.value);
        boolean jiggle = !inFolder && index == moving;
        c.save();
        if (current) c.translate(nudgeX.value, nudgeY.value);
        if (jiggle && !reduceMotion) {
            c.rotate(1.3f * (float) Math.sin((SystemClock.uptimeMillis() - movingAt) / 130.0 * Math.PI), box.centerX(), box.centerY());
        }
        c.scale(s, s, box.centerX(), box.centerY());
        if (lift > 0.01f) {
            shade.set(box);
            shade.offset(0, 24 * lift);
            shadow.setColor(Ui.alpha(0xFF000000, 0.55f * lift * a));
            shadows.draw(c, shade, CORNER, 28, shadow);
        }
        if (e.item != null) drawFace(c, e.item, box, CORNER, a);
        else drawFolderFace(c, e, box, a);
        border.setColor(Ui.alpha(0xFFFFFFFF, (0.07f + 0.1f * lift) * a));
        c.drawRoundRect(box, CORNER, CORNER, border);
        if (lift > 0.01f) {
            matrix.setScale(TW * 0.75f, TW * 0.75f);
            matrix.postTranslate(box.left + TW * 0.3f, box.top - TH * 0.25f);
            sheenShader.setLocalMatrix(matrix);
            sheen.setAlpha(Math.round(255 * lift * a));
            c.drawRoundRect(box, CORNER, CORNER, sheen);
        }
        c.restore();
        if (lift > 0.01f) {
            // Only the focused app shows its name, just under the lifted tile, rising into place.
            name.setAlpha(Math.round(255 * lift * a));
            name.setShadowLayer(8, 0, 0, Ui.alpha(0x99000000, lift * a));
            float y = box.bottom + TH * (s - 1) / 2 + 40 - (1 - lift) * 12;
            c.drawText(e.label, box.centerX() - name.measureText(e.label) / 2, y, name);
        }
    }

    private void drawFace(Canvas c, HomeApps.Item item, RectF r, float radius, float a) {
        fill.setColor(Ui.alpha(item.key.equals("youtube") ? 0xFF141416 : 0xFF202431, a));
        c.drawRoundRect(r, radius, radius, fill);
        image.setAlpha(Math.round(255 * a));
        if (item.logo != null) {
            Bitmap logo = item.logo;
            float ratio = Math.min(r.width() * 0.65f / logo.getWidth(), r.height() * 0.62f / logo.getHeight());
            float w = logo.getWidth() * ratio, h = logo.getHeight() * ratio;
            art.set(r.centerX() - w / 2, r.centerY() - h / 2, r.centerX() + w / 2, r.centerY() + h / 2);
            c.drawBitmap(logo, null, art, image);
        } else if (item.tileShader != null) {
            float ratio = Math.max(r.width() / item.tile.getWidth(), r.height() / item.tile.getHeight());
            matrix.setScale(ratio, ratio);
            matrix.postTranslate(r.centerX() - item.tile.getWidth() * ratio / 2, r.centerY() - item.tile.getHeight() * ratio / 2);
            item.tileShader.setLocalMatrix(matrix);
            image.setShader(item.tileShader);
            c.drawRoundRect(r, radius, radius, image);
            image.setShader(null);
        }
    }

    /** A folder: up to six of its apps, small, on a pane of light glass. */
    private void drawFolderFace(Canvas c, Entry e, RectF r, float a) {
        fill.setColor(Ui.alpha(0x3DFFFFFF, a));
        c.drawRoundRect(r, CORNER, CORNER, fill);
        float mw = (TW - 32 - 20) / 3, mh = mw * 9 / 16, top = r.top + (TH - 2 * mh - 10) / 2;
        for (int i = 0; i < Math.min(6, e.apps.size()); i++) {
            float x = r.left + 16 + (mw + 10) * (i % 3), y = top + (mh + 10) * (i / 3);
            cell.set(x, y, x + mw, y + mh);
            drawFace(c, e.apps.get(i), cell, 8, a);
        }
    }

    /** Top right: the network and, once the clock has scrolled away, the time. It opens Control Center. */
    private void drawPill(Canvas c) {
        pillRect(box);
        float f = Ui.clamp(pillFocus.value);
        c.save();
        c.scale(1 + 0.08f * f, 1 + 0.08f * f, box.centerX(), box.centerY());
        if (f > 0.01f) {
            fill.setColor(Ui.alpha(0xFFF5F5F7, f));
            c.drawRoundRect(box, 34, 34, fill);
        }
        int ink = mix(0xE6FFFFFF, 0xFF1D1D1F, f);
        float x = box.left + 28;
        drawNetwork(c, x + 17, box.centerY(), 34, ink, net);
        x += 34 + 24;
        float t = pillTimeIn.value;
        if (t > 0.01f) {
            pillTime.setColor(Ui.alpha(ink, t));
            c.drawText(time, x, box.centerY() + Ui.halfCap(pillTime), pillTime);
            x += (pillTime.measureText(time) + 24) * t;
        }
        drawSwitches(c, x + 17, box.centerY(), 34, ink);
        c.restore();
    }

    /** Wi-Fi as three arcs over a dot (struck through when offline), Ethernet as a port. */
    private void drawNetwork(Canvas c, float cx, float cy, float size, int color, int kind) {
        glyph.setColor(color);
        if (kind == NET_ETHERNET) {
            glyph.setStyle(Paint.Style.STROKE);
            glyph.setStrokeWidth(size * 0.1f);
            art.set(cx - size * 0.4f, cy - size * 0.32f, cx + size * 0.4f, cy + size * 0.36f);
            c.drawRoundRect(art, size * 0.08f, size * 0.08f, glyph);
            glyph.setStyle(Paint.Style.FILL);
            for (int i = -1; i <= 1; i++) c.drawRect(cx + i * size * 0.18f - size * 0.05f, cy - size * 0.1f,
                    cx + i * size * 0.18f + size * 0.05f, cy + size * 0.16f, glyph);
            return;
        }
        glyph.setStyle(Paint.Style.STROKE);
        glyph.setStrokeWidth(size * 0.11f);
        float base = cy + size * 0.34f;
        for (int i = 1; i <= 3; i++) {
            float r = size * 0.2f * i;
            art.set(cx - r, base - r, cx + r, base + r);
            wifiArc.rewind();
            wifiArc.addArc(art, 225, 90);
            c.drawPath(wifiArc, glyph);
        }
        glyph.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, base - size * 0.02f, size * 0.07f, glyph);
        if (kind == NET_OFFLINE) {
            glyph.setStyle(Paint.Style.STROKE);
            c.drawLine(cx - size * 0.42f, cy - size * 0.4f, cx + size * 0.42f, cy + size * 0.42f, glyph);
        }
    }

    /** Control Center's mark: two switches, one on, one off. */
    private void drawSwitches(Canvas c, float cx, float cy, float size, int color) {
        glyph.setColor(color);
        glyph.setStyle(Paint.Style.STROKE);
        glyph.setStrokeWidth(size * 0.09f);
        float w = size * 0.42f, h = size * 0.16f;
        for (int i = 0; i < 2; i++) {
            float y = cy + (i == 0 ? -size * 0.2f : size * 0.2f);
            art.set(cx - w, y - h, cx + w, y + h);
            c.drawRoundRect(art, h, h, glyph);
        }
        glyph.setStyle(Paint.Style.FILL);
        c.drawCircle(cx + w - h, cy - size * 0.2f, h * 0.9f, glyph);
        c.drawCircle(cx - w + h, cy + size * 0.2f, h * 0.9f, glyph);
    }

    /** An open folder, as on tvOS: its name over its apps, at Home's tile size, on Home frosted. */
    private void drawFolder(Canvas c, float v) {
        if (dark != null) {
            wallPaint.setAlpha(Math.round(255 * v));
            c.drawBitmap(dark, null, dst, wallPaint);
        } else {
            c.drawColor(Ui.alpha(0xFF000000, 0.55f * v));
        }
        c.save();
        float k = 0.94f + 0.06f * v;
        c.scale(k, k, 960, 540);
        c.translate(0, -folderScroll.value);
        folderTitle.setAlpha(Math.round(255 * v));
        c.drawText(folderName, MARGIN, 172, folderTitle);
        for (int i = 0; i < folderEntries.size(); i++) if (i != folderSel) drawEntryFaded(c, i, v);
        if (!folderEntries.isEmpty()) drawEntryFaded(c, folderSel, v);
        c.restore();
    }

    private void drawEntryFaded(Canvas c, int i, float v) {
        if (v < 0.999f) {
            folderSlot(i, cell);
            c.saveLayerAlpha(cell.left - 40, cell.top - 40, cell.right + 40, cell.bottom + 80, Math.round(255 * v));
            drawEntry(c, folderEntries.get(i), i, true);
            c.restore();
        } else {
            drawEntry(c, folderEntries.get(i), i, true);
        }
    }

    /** Control Center: a glass panel grown out of the corner where the status pill lives. */
    private void drawPanel(Canvas c, float v) {
        fill.setColor(Ui.alpha(0xFF000000, 0.45f * v));
        c.drawRect(0, 0, 1920, 1080, fill);
        panelRect(panel);
        c.save();
        float k = 0.9f + 0.1f * v;
        c.scale(k, k, panel.right, panel.top);
        if (v < 0.999f) c.saveLayerAlpha(panel.left - 4, panel.top - 4, panel.right + 4, panel.bottom + 4, Math.round(255 * v));
        drawGlass(c, panel, 60);
        if (inputsPage) drawInputsPage(c); else drawMainPage(c);
        if (v < 0.999f) c.restore();
        c.restore();
    }

    private void drawGlass(Canvas c, RectF r, float radius) { drawGlass(c, r, radius, 0, 1); }

    /** `dy`: how far the canvas has moved up (the frosted picture stays on the screen); `a`: opacity. */
    private void drawGlass(Canvas c, RectF r, float radius, float dy, float a) {
        if (frost != null) {
            panelMatrix.setScale(1920f / frost.getWidth(), 1080f / frost.getHeight());
            panelMatrix.postTranslate(0, dy);
            panelPaint.getShader().setLocalMatrix(panelMatrix);
            panelPaint.setAlpha(Math.round(255 * a));
            c.drawRoundRect(r, radius, radius, panelPaint);
        } else {
            fill.setColor(Ui.alpha(0xE6202022, a));
            c.drawRoundRect(r, radius, radius, fill);
        }
        drawGlassEdge(c, r, radius, a);
    }

    private void drawMainPage(Canvas c) {
        float left = panel.left + PANEL_PAD, right = panel.right - PANEL_PAD, top = panel.top + PANEL_PAD;
        c.drawText(time, left - Ui.leftBearing(panelTime, time), top + 60, panelTime);
        c.drawText(day, left, top + 104, secondary);
        float w = caption.measureText(netLabel);
        c.drawText(netLabel, right - w, top + 104, caption);
        drawNetwork(c, right - w - 26, top + 104 - Ui.halfCap(caption), 26, 0x99EBEBF5, net);

        for (int i = 0; i < panelButtons.size(); i++) {
            buttonCell(i, box);
            boolean f = panelRow == 0 && panelCol == i;
            c.save();
            if (f) c.scale(1.1f - 0.05f * Ui.clamp(press.value), 1.1f - 0.05f * Ui.clamp(press.value), box.centerX(), box.centerY());
            fill.setColor(f ? 0xFFF5F5F7 : 0x2E787880);
            c.drawOval(box, fill);
            int ink = f ? 0xFF1D1D1F : 0xFFFFFFFF;
            HomeApps.Item b = panelButtons.get(i);
            if (b.symbol != null) Symbols.draw(getContext(), c, b.symbol, box.centerX(), box.centerY(), 40, ink);
            else drawNetwork(c, box.centerX(), box.centerY(), 46, ink, net);
            c.restore();
            caption.setColor(f ? 0xFFFFFFFF : 0x99EBEBF5);
            c.drawText(buttonLabels[i], box.centerX() - caption.measureText(buttonLabels[i]) / 2, box.bottom + 40, caption);
            caption.setColor(0x99EBEBF5);
        }

        // Volume: Left and Right change it, OK mutes.
        panelRowRect(1, box);
        boolean f = panelRow == 1;
        fill.setColor(f ? 0xFFF5F5F7 : 0x2E787880);
        c.drawRoundRect(box, ROW_H / 2, ROW_H / 2, fill);
        int ink = f ? 0xFF1D1D1F : 0xFFFFFFFF;
        Symbols.draw(getContext(), c, "speaker", box.left + 48, box.centerY(), 40, ink);
        float vw = row.measureText(volumeText);
        row.setColor(ink);
        c.drawText(volumeText, box.right - 36 - vw, box.centerY() + Ui.halfCap(row), row);
        float bl = box.left + 96, br = box.right - 64 - Math.max(vw, row.measureText("100"));
        art.set(bl, box.centerY() - 6, br, box.centerY() + 6);
        fill.setColor(f ? 0x331D1D1F : 0x33FFFFFF);
        c.drawRoundRect(art, 6, 6, fill);
        float level = muted ? 0 : (float) volume / volumeMax;
        if (level > 0) {
            art.right = bl + Math.max(12, (br - bl) * level);
            fill.setColor(ink);
            c.drawRoundRect(art, 6, 6, fill);
        }

        panelRowRect(2, box);
        drawMenuRow(c, box, Ui.tr("Home Settings"), "settings", panelRow == 2, true);
        row.setColor(0xFFFFFFFF);
    }

    private void drawInputsPage(Canvas c) {
        panelRowRect(0, box);
        drawMenuRow(c, box, Ui.tr("HDMI Inputs"), null, panelRow == 0, false);
        // The header row goes back: a chevron pointing left.
        int ink = panelRow == 0 ? 0xFF1D1D1F : 0xFFFFFFFF;
        glyph.setColor(ink);
        glyph.setStyle(Paint.Style.STROKE);
        glyph.setStrokeWidth(4);
        float cx = box.left + 46, cy = box.centerY();
        c.drawLine(cx + 6, cy - 12, cx - 6, cy, glyph);
        c.drawLine(cx - 6, cy, cx + 6, cy + 12, glyph);
        for (int i = 0; i < inputs.size(); i++) {
            panelRowRect(i + 1, box);
            drawMenuRow(c, box, inputLabels[i], "hdmi", panelRow == i + 1, false);
        }
        row.setColor(0xFFFFFFFF);
    }

    private void drawMenuRow(Canvas c, RectF r, String text, String symbol, boolean focused, boolean chevron) {
        if (focused) {
            fill.setColor(0xFFF5F5F7);
            c.drawRoundRect(r, 28, 28, fill);
        }
        int ink = focused ? 0xFF1D1D1F : 0xFFFFFFFF;
        if (symbol != null) Symbols.draw(getContext(), c, symbol, r.left + 48, r.centerY(), 36, ink);
        row.setColor(ink);
        c.drawText(text, r.left + 96, r.centerY() + Ui.halfCap(row), row);
        if (chevron) Symbols.draw(getContext(), c, "chevron_right", r.right - 40, r.centerY(), 32, Ui.alpha(ink, 0.6f));
    }

    private void drawMenu(Canvas c, float v) {
        menuRect(panel);
        c.save();
        float k = 0.94f + 0.06f * v;
        c.scale(k, k, panel.left, panel.top);
        if (v < 0.999f) c.saveLayerAlpha(panel.left - 4, panel.top - 4, panel.right + 4, panel.bottom + 4, Math.round(255 * v));
        drawGlass(c, panel, 36);
        for (int i = 0; i < menuLabels.size(); i++) {
            menuRow(i, box);
            if (i == menuSel) {
                fill.setColor(0xFFF5F5F7);
                c.drawRoundRect(box, 22, 22, fill);
            }
            row.setColor(i == menuSel ? 0xFF1D1D1F : 0xFFFFFFFF);
            c.drawText(menuLabels.get(i), box.left + 28, box.centerY() + Ui.halfCap(row), row);
        }
        row.setColor(0xFFFFFFFF);
        if (v < 0.999f) c.restore();
        c.restore();
    }

    private void drawHint(Canvas c) {
        float w = row.measureText(moveHint) + 72;
        box.set(960 - w / 2, 1080 - 56 - 80, 960 + w / 2, 1080 - 56);
        drawGlass(c, box, 40);
        c.drawText(moveHint, box.left + 36, box.centerY() + Ui.halfCap(row), row);
    }

    private static int mix(int a, int b, float t) {
        int r = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            int x = a >>> shift & 0xFF, y = b >>> shift & 0xFF;
            r |= Math.round(x + (y - x) * t) << shift;
        }
        return r;
    }

    // MARK: - Animation

    private void retarget() {
        for (int i = 0; i < entries.size(); i++) {
            entries.get(i).focus.target = overlay != FOLDER && !pill && i == selected && (overlay == NONE || overlay == MENU && !menuInFolder) ? 1 : 0;
        }
        for (int i = 0; i < folderEntries.size(); i++) {
            folderEntries.get(i).focus.target = (overlay == FOLDER || overlay == MENU && menuInFolder) && i == folderSel ? 1 : 0;
        }
        int rows = (entries.size() + COLS - 1) / COLS;
        int r = pill ? 0 : row(selected);
        scroll.target = scrollFor(r, rows);
        plateGap.target = r == 0 ? NAME_ROOM : 0;
        pillFocus.target = pill && overlay == NONE ? 1 : 0;
        pillTimeIn.target = scroll.target > COLLAPSE / 2 ? 1 : 0;
        folderIn.target = overlay == FOLDER || overlay == MENU && menuInFolder ? 1 : 0;
        panelIn.target = overlay == PANEL ? 1 : 0;
        menuIn.target = overlay == MENU ? 1 : 0;
        int frows = (folderEntries.size() + COLS - 1) / COLS;
        float fr = FOLDER_TOP + PY * row(folderSel) + TH + 120 - 1080;
        folderScroll.target = Math.max(0, Math.min(fr, FOLDER_TOP + PY * frows - 1080 + 80));
        if (reduceMotion) {
            for (Spring s : new Spring[] {scroll, plateGap, pillFocus, pillTimeIn, folderIn, panelIn, menuIn, folderScroll}) s.snap(s.target);
            for (Entry e : entries) e.focus.snap(e.focus.target);
            for (Entry e : folderEntries) e.focus.snap(e.focus.target);
        }
        animateFrames();
        invalidate();
        spoken.focusMoved();
    }

    private void animateFrames() {
        if (!running || framePending) return;
        framePending = true;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override public void doFrame(long nanos) {
        framePending = false;
        if (!running) return;
        float dt = lastFrame == 0 ? 1 / 60f : Math.min(0.032f, (nanos - lastFrame) / 1_000_000_000f);
        lastFrame = nanos;
        boolean busy = scroll.step(dt) | plateGap.step(dt) | press.step(dt) | pillFocus.step(dt) | pillTimeIn.step(dt)
                | nudgeX.step(dt) | nudgeY.step(dt) | folderIn.step(dt) | panelIn.step(dt) | menuIn.step(dt)
                | folderScroll.step(dt);
        for (Entry e : entries) busy |= e.focus.step(dt);
        for (Entry e : folderEntries) busy |= e.focus.step(dt);
        if (appearAt > 0) {
            if (SystemClock.uptimeMillis() - appearAt > 760) appearAt = -1; else busy = true;
        }
        if (moving >= 0 && !reduceMotion) busy = true;
        if (overlay != FOLDER && overlay != MENU && folderIn.value < 0.001f && !folderEntries.isEmpty()) {
            folderEntries.clear();
        }
        invalidate();
        if (busy) animateFrames(); else lastFrame = 0;
    }

    /** Pushed against the edge of the grid: the tile leans that way and springs back. */
    private void nudge(float dx, float dy) {
        if (reduceMotion) return;
        nudgeX.velocity += dx * 500;
        nudgeY.velocity += dy * 500;
        animateFrames();
    }

    // MARK: - Keys

    boolean onKey(KeyEvent event) {
        int key = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN, up = event.getAction() == KeyEvent.ACTION_UP;
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) move(key);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                select(event);
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (up) back();
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (up && moving < 0) {
                    if (overlay == MENU) closeMenu();
                    else if (overlay == PANEL) closePanel();
                    else if (overlay == NONE && pill) openPanel();
                    else if (canHold()) openMenu();
                }
                return true;
            default: return false;
        }
    }

    private void select(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() == 0) {
                holding = true;
                held = false;
                press.target = 1;
                if (reduceMotion) press.snap(1);
                if (canHold()) handler.postDelayed(holdTimer, HOLD_MS);
            } else if (holding && !held && canHold()) {
                // Remotes that auto-repeat a held key report the long press themselves.
                handler.removeCallbacks(holdTimer);
                holdTimer.run();
            }
        } else if (e.getAction() == KeyEvent.ACTION_UP) {
            handler.removeCallbacks(holdTimer);
            press.target = 0;
            if (reduceMotion) press.snap(0);
            boolean clicked = holding && !held;
            holding = false;
            if (clicked && spoken.keyClick()) activate();
        }
        animateFrames();
    }

    private boolean canHold() {
        if (moving >= 0) return false;
        if (overlay == FOLDER) return !folderEntries.isEmpty();
        return overlay == NONE && !pill && !entries.isEmpty();
    }

    private void move(int key) {
        int dx = key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
        int dy = key == KeyEvent.KEYCODE_DPAD_UP ? -1 : key == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
        if (moving >= 0) { moveBy(dx, dy); return; }
        switch (overlay) {
            case MENU:
                if (dy != 0) {
                    int to = Math.max(0, Math.min(menuLabels.size() - 1, menuSel + dy));
                    if (to != menuSel) { menuSel = to; click.move(); }
                }
                retarget();
                return;
            case PANEL: movePanel(dx, dy); return;
            case FOLDER: moveIn(folderEntries.size(), dx, dy, true); return;
            default:
        }
        if (entries.isEmpty()) { pill = true; retarget(); return; }
        if (pill) {
            if (dy > 0) { pill = false; click.move(); retarget(); }
            return;
        }
        if (dy < 0 && row(selected) == 0) {
            // Up from the top row leads to the status pill, as on tvOS.
            pill = true;
            click.move();
            retarget();
            return;
        }
        moveIn(entries.size(), dx, dy, false);
    }

    private void moveIn(int count, int dx, int dy, boolean inFolder) {
        int at = inFolder ? folderSel : selected, to = at;
        if (dx < 0 && at % COLS > 0) to = at - 1;
        else if (dx > 0 && at % COLS < COLS - 1 && at + 1 < count) to = at + 1;
        else if (dy < 0 && at >= COLS) to = at - COLS;
        else if (dy > 0 && (row(at) + 1) * COLS < count) to = Math.min(count - 1, at + COLS);
        if (to == at) {
            nudge(dx, dy);
            return;
        }
        if (inFolder) folderSel = to; else selected = to;
        click.move();
        retarget();
    }

    private void back() {
        if (moving >= 0) { endMove(true); return; }
        switch (overlay) {
            case MENU: closeMenu(); return;
            case PANEL:
                if (inputsPage) { inputsPage = false; panelRow = 0; retarget(); } else closePanel();
                return;
            case FOLDER: closeFolder(); return;
            default:
        }
        // Back from inside the grid returns to the top row; on the top row it stays (Back must not close Home).
        if (pill) pill = false;
        else if (row(selected) > 0) selected %= COLS;
        retarget();
    }

    private void activate() {
        if (moving >= 0) { endMove(false); return; }
        switch (overlay) {
            case MENU: runMenu(); return;
            case PANEL: activatePanel(); return;
            case FOLDER:
                if (!folderEntries.isEmpty()) open(folderEntries.get(folderSel).item);
                return;
            default:
        }
        if (pill) { openPanel(); return; }
        if (entries.isEmpty()) return;
        Entry e = entries.get(selected);
        if (e.item == null) showFolder(e, true); else open(e.item);
    }

    private void open(HomeApps.Item item) {
        click.open();
        // The app grows out of its tile; what Control Center opens grows out of the pill.
        if (overlay == FOLDER) { folderSlot(folderSel, box); box.offset(0, -folderScroll.value); }
        else if (pill) pillRect(box);
        else { slot(selected, box); box.offset(0, -scroll.value); }
        if (!HomeApps.open(activity, item, this, (int) (offsetX + box.left * scale),
                (int) (offsetY + box.top * scale), (int) (box.width() * scale), (int) (box.height() * scale))) {
            Toast.makeText(activity, item.label + ": " + Ui.tr("Not installed"), Toast.LENGTH_SHORT).show();
        }
    }

    // MARK: - Folders

    private void showFolder(Entry folder, boolean fresh) {
        openFolder = folder;
        folderName = folder.folder;
        String previous = !fresh && !folderEntries.isEmpty() && folderSel < folderEntries.size()
                ? folderEntries.get(folderSel).item.key : null;
        folderEntries.clear();
        for (HomeApps.Item it : folder.apps) {
            Entry e = new Entry(it);
            e.label = ellipsize(it.label);
            folderEntries.add(e);
        }
        folderSel = 0;
        if (previous != null) {
            for (int i = 0; i < folderEntries.size(); i++) if (folderEntries.get(i).item.key.equals(previous)) folderSel = i;
        }
        if (fresh) { click.open(); folderScroll.snap(0); }
        overlay = FOLDER;
        retarget();
    }

    private void closeFolder() {
        overlay = NONE;
        if (openFolder != null) {
            int i = entries.indexOf(openFolder);
            if (i >= 0) selected = i;
        }
        openFolder = null;
        retarget();
    }

    private String nextFolderName() {
        Set<String> names = new HashSet<>();
        for (Entry e : entries) if (e.item == null) names.add(e.folder);
        String first = Ui.tr("Folder");
        if (!names.contains(first)) return first;
        for (int n = 2; ; n++) {
            String s = Ui.trf("Folder {n}", "n", n);
            if (!names.contains(s)) return s;
        }
    }

    // MARK: - The app menu (held OK, or Menu)

    private void openMenu() {
        menuLabels.clear();
        menuActions.clear();
        menuInFolder = overlay == FOLDER;
        if (menuInFolder) {
            if (folderEntries.isEmpty()) return;
            addMenu(Ui.tr("Remove from Folder"), ACT_OUT, -1);
        } else {
            if (entries.isEmpty() || pill) return;
            Entry e = entries.get(selected);
            addMenu(Ui.tr("Move"), ACT_MOVE, -1);
            if (e.item != null) {
                addMenu(Ui.tr("New Folder"), ACT_NEW_FOLDER, -1);
                for (int i = 0; i < entries.size(); i++) {
                    Entry f = entries.get(i);
                    if (f.item == null) addMenu(Ui.trf("Add to {folder}", "folder", f.folder), ACT_ADD, i);
                }
            }
        }
        menuSel = 0;
        overlay = MENU;
        click.open();
        retarget();
    }

    private void addMenu(String label, int action, int folder) {
        menuLabels.add(TextUtils.ellipsize(label, new TextPaint(row), MENU_W - 88, TextUtils.TruncateAt.END).toString());
        menuActions.add(new int[] {action, folder});
    }

    private void closeMenu() {
        overlay = menuInFolder ? FOLDER : NONE;
        retarget();
    }

    private void runMenu() {
        int[] act = menuActions.get(menuSel);
        boolean fromFolder = menuInFolder;
        closeMenu();
        click.open();
        switch (act[0]) {
            case ACT_MOVE:
                moving = selected;
                movingAt = SystemClock.uptimeMillis();
                moveBackup = new ArrayList<>(entries);
                break;
            case ACT_NEW_FOLDER: {
                Entry app = entries.get(selected);
                List<HomeApps.Item> apps = new ArrayList<>();
                apps.add(app.item);
                Entry folder = new Entry(nextFolderName(), apps);
                folder.label = ellipsize(folder.folder);
                folder.focus.snap(app.focus.value);
                entries.set(selected, folder);
                save();
                break;
            }
            case ACT_ADD: {
                Entry app = entries.get(selected), folder = entries.get(act[1]);
                folder.apps.add(app.item);
                entries.remove(selected);
                selected = entries.indexOf(folder);
                save();
                break;
            }
            case ACT_OUT:
                if (fromFolder && openFolder != null) {
                    int was = folderSel;
                    HomeApps.Item it = folderEntries.get(folderSel).item;
                    openFolder.apps.remove(it);
                    int at = entries.indexOf(openFolder);
                    Entry back = new Entry(it);
                    back.label = ellipsize(it.label);
                    entries.add(at + 1, back);
                    if (openFolder.apps.isEmpty()) {
                        entries.remove(at);
                        openFolder = null;
                        overlay = NONE;
                        selected = Math.max(0, at);
                        folderEntries.clear();
                    } else {
                        showFolder(openFolder, false);
                        folderSel = Math.min(was, folderEntries.size() - 1);
                    }
                    save();
                }
                break;
            default:
        }
        retarget();
    }

    // MARK: - Moving an app

    private void moveBy(int dx, int dy) {
        int to = moving;
        if (dx < 0 && moving % COLS > 0 || dx > 0 && moving % COLS < COLS - 1) to = moving + dx;
        else if (dy != 0) to = moving + dy * COLS;
        to = Math.max(0, Math.min(entries.size() - 1, to));
        if (to == moving) {
            nudge(dx, dy);
            return;
        }
        Entry e = entries.remove(moving);
        entries.add(to, e);
        moving = to;
        selected = to;
        click.move();
        retarget();
    }

    private void endMove(boolean cancel) {
        if (cancel && moveBackup != null) {
            Entry e = entries.get(moving);
            entries.clear();
            entries.addAll(moveBackup);
            selected = Math.max(0, entries.indexOf(e));
        } else {
            save();
        }
        moving = -1;
        moveBackup = null;
        click.open();
        retarget();
    }

    // MARK: - Control Center

    private void openPanel() {
        readVolume();
        refreshText();
        inputsPage = false;
        panelRow = 0;
        panelCol = 0;
        overlay = PANEL;
        click.open();
        retarget();
    }

    private void closePanel() {
        overlay = NONE;
        inputsPage = false;
        retarget();
    }

    private int panelItems() { return inputsPage ? 1 + inputs.size() : panelButtons.size() + 2; }

    /** Panel focus as one number, for TalkBack: the buttons, then volume, then Home Settings (or the inputs page's rows). */
    private int panelIndex() { return inputsPage ? panelRow : panelRow == 0 ? panelCol : panelButtons.size() + panelRow - 1; }

    private void setPanelIndex(int i) {
        if (inputsPage) { panelRow = i; return; }
        if (i < panelButtons.size()) { panelRow = 0; panelCol = i; } else panelRow = i - panelButtons.size() + 1;
    }

    private String panelText(int i) {
        if (inputsPage) return i == 0 ? Ui.tr("HDMI Inputs") : inputs.get(i - 1).label;
        if (i < panelButtons.size()) return panelButtons.get(i).label;
        return i == panelButtons.size() ? Ui.trf("Volume {level}", "level", volumeText) : Ui.tr("Home Settings");
    }

    private void panelItemRect(int i, RectF out) {
        if (!inputsPage && i < panelButtons.size()) buttonCell(i, out);
        else panelRowRect(inputsPage ? i : i - panelButtons.size() + 1, out);
    }

    private void movePanel(int dx, int dy) {
        int rows = inputsPage ? 1 + inputs.size() : 3;
        if (dy != 0) {
            int to = Math.max(0, Math.min(rows - 1, panelRow + dy));
            if (to != panelRow) { panelRow = to; click.move(); } else nudge(0, dy);
        } else if (!inputsPage && panelRow == 0) {
            int to = Math.max(0, Math.min(panelButtons.size() - 1, panelCol + dx));
            if (to != panelCol) { panelCol = to; click.move(); }
        } else if (!inputsPage && panelRow == 1) {
            try {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dx > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
            } catch (RuntimeException e) {
                AppLog.w("volume: " + e.getMessage());
            }
            readVolume();
            click.move();
        }
        retarget();
    }

    private void activatePanel() {
        if (inputsPage) {
            if (panelRow == 0) { inputsPage = false; panelRow = 0; retarget(); return; }
            HomeApps.Item input = inputs.get(panelRow - 1);
            closePanel();
            open(input);
            return;
        }
        if (panelRow == 1) {
            try {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0);
            } catch (RuntimeException e) {
                AppLog.w("mute: " + e.getMessage());
            }
            readVolume();
            invalidate();
            return;
        }
        if (panelRow == 2) {
            closePanel();
            open(HomeApps.settings());
            return;
        }
        HomeApps.Item b = panelButtons.get(panelCol);
        if (b.kind == HomeApps.INPUTS) {
            if (inputs.isEmpty()) {
                closePanel();
                start(new Intent(Settings.ACTION_SETTINGS));
            } else {
                inputsPage = true;
                panelRow = 1;
                click.open();
                retarget();
            }
            return;
        }
        closePanel();
        if (b.kind == NETWORK) {
            if (!start(new Intent(net == NET_ETHERNET ? Settings.ACTION_SETTINGS : Settings.ACTION_WIFI_SETTINGS))) {
                start(new Intent(Settings.ACTION_SETTINGS));
            }
        } else {
            open(b);
        }
    }

    private boolean start(Intent intent) {
        try {
            activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            AppLog.w("couldn't open " + intent.getAction() + ": " + e.getMessage());
            return false;
        }
    }
}
