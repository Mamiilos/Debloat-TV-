package io.freemytv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A remote-friendly media library; app-provided resume links are kept separate from app shortcuts.
 */
public class RecentActivity extends Activity {
    private static final ExecutorService IMAGES = Executors.newFixedThreadPool(2);
    private final android.os.Handler handler = new android.os.Handler();
    private LinearLayout page;
    private boolean settings;
    private volatile boolean stopped;
    private volatile int imageGeneration;
    private KeyPage clientPage;
    private AlertDialog codeDialog;
    private String fingerprint = "";

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        settings = getIntent().getBooleanExtra("settings", false);
        render();
    }

    @Override
    public void onResume() {
        super.onResume();
        boolean returning = stopped;
        stopped = false;
        if (returning) render();
        refresh();
        handler.postDelayed(this::refresh, 2000);
        handler.postDelayed(this::refresh, 6000);
    }

    @Override
    public void onPause() {
        stopped = true;
        imageGeneration++;
        handler.removeCallbacksAndMessages(null);
        closeClientPage();
        TraktSync.cancelLogin();
        if (codeDialog != null) codeDialog.dismiss();
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (settings) {
            settings = false;
            render();
        } else super.onBackPressed();
    }

    private int dp(float n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }

    private TextView text(String value, int size) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTypeface(size >= 22 ? Ui.displaySemibold : Ui.regular);
        v.setTextColor(size >= 17 ? 0xffedf4f8 : 0xffaebdcb);
        v.setPadding(0, dp(6), 0, dp(6));
        return v;
    }

    private Button button(LinearLayout parent, String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTypeface(Ui.medium);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setPadding(dp(18), dp(10), dp(18), dp(10));
        b.setMinHeight(dp(52));
        b.setStateListAnimator(null);
        b.setBackgroundTintList(null);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[] {android.R.attr.state_focused}, buttonBackground(true));
        bg.addState(new int[] {android.R.attr.state_pressed}, buttonBackground(true));
        bg.addState(new int[0], buttonBackground(false));
        b.setBackground(bg);
        b.setTextColor(new ColorStateList(new int[][] {
                {android.R.attr.state_focused}, {android.R.attr.state_pressed}, new int[0]},
                new int[] {0xff13252d, 0xff13252d, 0xffedf4f8}));
        int colon = label.indexOf(':');
        b.setTag(colon < 0 ? label : label.substring(0, colon));
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(parent == page ? -1 : -2, -2);
        lp.setMargins(0, dp(8), parent == page ? 0 : dp(12), dp(8));
        parent.addView(b, lp);
        return b;
    }

    private GradientDrawable buttonBackground(boolean focused) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(focused ? 0xffe6f4f1 : 0xff1d2b3a);
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), focused ? 0xff8ce8d4 : 0xff354553);
        return bg;
    }

    private void refresh() {
        if (stopped) return;
        RecentMedia.refresh(
                this,
                () -> {
                    if (stopped || settings || codeDialog != null && codeDialog.isShowing()) return;
                    StringBuilder f = new StringBuilder(RecentMedia.problem);
                    for (RecentMedia.Item i : RecentMedia.visible)
                        f.append(i.key).append(i.position).append(i.available);
                    if (!fingerprint.equals(f.toString())) {
                        fingerprint = f.toString();
                        render();
                    }
                });
    }

    private void render() {
        imageGeneration++;
        Object focused = getCurrentFocus() == null ? null : getCurrentFocus().getTag();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {0xff172d38, 0xff090f1a}));
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(56), dp(32), dp(56), dp(32));
        scroll.addView(page);
        setContentView(scroll);
        page.addView(
                text(settings ? "Trakt · " + Ui.tr("Settings") : Ui.tr("Recently played"), 30));
        LinearLayout bar = new LinearLayout(this);
        page.addView(bar);
        if (settings) {
            showSettings(bar);
            restoreFocus(focused);
            return;
        }
        button(
                bar,
                Ui.tr("Playback history")
                        + ": "
                        + (RecentMedia.enabled(this) ? Ui.tr("On") : Ui.tr("Off")),
                () -> {
                    boolean on = !RecentMedia.enabled(this);
                    RecentMedia.enable(this, on);
                    if (on
                            && checkSelfPermission("android.permission.READ_TV_LISTINGS")
                                    != PackageManager.PERMISSION_GRANTED)
                        requestPermissions(new String[] {"android.permission.READ_TV_LISTINGS"}, 1);
                    render();
                    refresh();
                });
        button(
                bar,
                "Trakt",
                () -> {
                    settings = true;
                    render();
                });
        button(
                bar,
                Ui.tr("Clear history"),
                () ->
                        new AlertDialog.Builder(this)
                                .setTitle(Ui.tr("Clear history"))
                                .setPositiveButton(
                                        Ui.tr("Remove"),
                                        (d, w) ->
                                                RecentMedia.clear(
                                                        this,
                                                        () -> {
                                                            render();
                                                        }))
                                .setNegativeButton(Ui.tr("Cancel"), null)
                                .show());
        page.addView(text(Ui.tr("Apps decide which titles and resume links they share."), 14));
        button(page, Ui.tr("Personalized recommendations") + ": "
                + (PersonalRecs.selected(this) ? Ui.tr("On") : Ui.tr("Off")), () -> {
            PersonalRecs.setEnabled(this, !PersonalRecs.selected(this));
            render();
        });
        page.addView(text(Ui.tr("Mix similar titles from your playback history with trending picks. Sends titles to TMDB."), 14));
        button(page, Ui.tr("TMDB images") + ": " + (RecentMedia.artEnabled(this) ? Ui.tr("On") : Ui.tr("Off")), () -> {
            RecentMedia.setArt(this, !RecentMedia.artEnabled(this));
            render();
        });
        page.addView(text(Ui.tr("Find episode images and movie backdrops on TMDB. Sends titles to TMDB."), 14));
        if ((RecentMedia.artEnabled(this) || PersonalRecs.selected(this)) && Recs.key().isEmpty())
            page.addView(text(Ui.tr("TMDB key") + ": " + Ui.tr("Not set"), 14));
        if (!RecentMedia.problem.isEmpty()) page.addView(text(RecentMedia.problem, 14));
        if (!RecentMedia.enabled(this)) {
            restoreFocus(focused);
            return;
        }
        if (checkSelfPermission("android.permission.READ_TV_LISTINGS")
                != PackageManager.PERMISSION_GRANTED)
            button(
                    page,
                    Ui.tr("Allow TV listings"),
                    () ->
                            requestPermissions(
                                    new String[] {"android.permission.READ_TV_LISTINGS"}, 1));
        if (!NowPlaying.allowed(this))
            page.addView(text(Ui.tr("Playback history") + ": " + Ui.tr("Unavailable"), 14));
        List<RecentMedia.Item> resume = new ArrayList<>(), recent = new ArrayList<>();
        for (RecentMedia.Item i : RecentMedia.visible) {
            if (i.resume()) resume.add(i);
            else recent.add(i);
        }
        row(Ui.tr("Continue Watching"), resume);
        row(Ui.tr("Recently played"), recent);
        if (resume.isEmpty() && recent.isEmpty())
            page.addView(text(Ui.tr("No recent media yet."), 18));
        restoreFocus(focused);
    }

    private void restoreFocus(Object focused) {
        if (focused != null) {
            View v = page.findViewWithTag(focused);
            if (v != null) v.requestFocus();
        }
    }

    private void row(String title, List<RecentMedia.Item> items) {
        if (items.isEmpty()) return;
        TextView heading = text(title, 22);
        heading.setPadding(0, dp(24), 0, dp(10));
        page.addView(heading);
        HorizontalScrollView row = new HorizontalScrollView(this);
        row.setClipToPadding(false);
        row.setPadding(dp(4), dp(8), dp(4), dp(12));
        LinearLayout cards = new LinearLayout(this);
        row.addView(cards);
        page.addView(row);
        for (int index = 0; index < items.size() && index < 24; index++) {
            RecentMedia.Item item = items.get(index);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(8), dp(8), dp(8), dp(8));
            card.setFocusable(true);
            card.setClickable(true);
            card.setTag(item.key);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(220), dp(242));
            lp.setMargins(0, 0, dp(16), 0);
            cards.addView(card, lp);
            card.setBackground(cardBackground(false));
            card.setOnFocusChangeListener(
                    (v, on) -> {
                        card.setBackground(cardBackground(on));
                    });
            ImageView art = new ImageView(this);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.setBackgroundColor(0xff101b27);
            card.addView(art, new LinearLayout.LayoutParams(-1, dp(114)));
            artwork(item, art);
            TextView name = text(item.title, 17);
            name.setMaxLines(2);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            card.addView(name);
            TextView detail = text(item.subtitle(), 12);
            detail.setMaxLines(1);
            card.addView(detail);
            if (item.duration > 0 && item.position > 0) {
                ProgressBar progress =
                        new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
                progress.setMax(1000);
                progress.setProgress((int) Math.min(1000, item.position * 1000 / item.duration));
                card.addView(progress, new LinearLayout.LayoutParams(-1, dp(5)));
            }
            String action = item.link.isEmpty() ? Ui.tr("Open app") : Ui.tr("Continue Watching");
            card.addView(text(action, 12));
            card.setContentDescription(item.title + ". " + item.subtitle() + ". " + action);
            card.setOnClickListener(
                    v -> {
                        if (!RecentMedia.open(this, item))
                            Toast.makeText(this, Ui.tr("Unavailable"), Toast.LENGTH_SHORT).show();
                    });
            card.setOnLongClickListener(
                    v -> {
                        if (!TraktSync.connected(this)) return false;
                        TraktSync.choices(
                                this,
                                item,
                                matches -> {
                                    if (stopped) return;
                                    if (matches.isEmpty()) {
                                        Toast.makeText(
                                                        this,
                                                        Ui.tr("Unavailable"),
                                                        Toast.LENGTH_SHORT)
                                                .show();
                                        return;
                                    }
                                    String[] names = new String[matches.size()];
                                    for (int n = 0; n < names.length; n++)
                                        names[n] = matches.get(n).label;
                                    new AlertDialog.Builder(this)
                                            .setTitle(Ui.tr("Identify on Trakt"))
                                            .setItems(
                                                    names,
                                                    (d, n) ->
                                                            TraktSync.selectMatch(
                                                                    this, item, matches.get(n).id))
                                            .setNegativeButton(Ui.tr("Cancel"), null)
                                            .show();
                                });
                        return true;
                    });
        }
    }

    private GradientDrawable cardBackground(boolean focus) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(focus ? 0xff293e4b : 0xff1d2b3a);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(focus ? 3 : 1), focus ? 0xff8ce8d4 : 0xff354553);
        return bg;
    }

    private void artwork(RecentMedia.Item item, ImageView target) {
        final int generation = imageGeneration;
        IMAGES.execute(
                () -> {
                    if (stopped || generation != imageGeneration) return;
                    Bitmap bitmap = null;
                    try {
                        if (!item.image.isEmpty()) {
                            Uri uri = Uri.parse(item.image);
                            InputStream stream = null;
                            try {
                                if ("content".equals(uri.getScheme())
                                        || "android.resource".equals(uri.getScheme()))
                                    stream = getContentResolver().openInputStream(uri);
                                if (stream != null)
                                    try (InputStream in = stream;
                                            ByteArrayOutputStream out =
                                                    new ByteArrayOutputStream()) {
                                        byte[] chunk = new byte[4096];
                                        for (int n; (n = in.read(chunk)) >= 0; ) {
                                            if (out.size() + n > 4194304)
                                                throw new java.io.IOException();
                                            out.write(chunk, 0, n);
                                        }
                                        byte[] bytes = out.toByteArray();
                                        BitmapFactory.Options options = new BitmapFactory.Options();
                                        options.inJustDecodeBounds = true;
                                        BitmapFactory.decodeByteArray(
                                                bytes, 0, bytes.length, options);
                                        options.inSampleSize = 1;
                                        while (options.outWidth / options.inSampleSize > 640
                                                || options.outHeight / options.inSampleSize > 360)
                                            options.inSampleSize *= 2;
                                        options.inJustDecodeBounds = false;
                                        bitmap =
                                                BitmapFactory.decodeByteArray(
                                                        bytes, 0, bytes.length, options);
                                    }
                            } finally {
                                if (stream != null)
                                    try {
                                        stream.close();
                                    } catch (Exception ignored) {
                                    }
                            }
                        }
                        final Bitmap loaded = bitmap;
                        if (loaded != null)
                            runOnUiThread(
                                    () -> {
                                        if (!stopped && generation == imageGeneration) target.setImageBitmap(loaded);
                                    });
                        else {
                            final android.graphics.drawable.Drawable icon =
                                    getPackageManager().getApplicationIcon(item.pkg);
                            runOnUiThread(
                                    () -> {
                                        if (!stopped && generation == imageGeneration) {
                                            target.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                                            target.setImageDrawable(icon);
                                        }
                                    });
                        }
                    } catch (Exception ignored) {
                    }
                });
    }

    private void showSettings(LinearLayout bar) {
        button(
                bar,
                Ui.tr("Recently played"),
                () -> {
                    settings = false;
                    render();
                });
        button(
                page,
                Ui.tr("Trakt Client ID")
                        + ": "
                        + (TraktSync.client(this).isEmpty() ? Ui.tr("Not set") : "••••"),
                this::configureClient);
        button(
                page,
                TraktSync.connected(this) ? Ui.tr("Disconnect Trakt") : Ui.tr("Connect Trakt"),
                () -> {
                    if (TraktSync.connected(this)) {
                        TraktSync.disconnect(this);
                        handler.postDelayed(this::render, 500);
                    } else if (TraktSync.client(this).isEmpty()) configureClient();
                    else connect();
                });
        button(
                page,
                Ui.tr("Record completed watches")
                        + ": "
                        + (TraktSync.enabled(this) ? Ui.tr("On") : Ui.tr("Off")),
                () -> {
                    if (TraktSync.connected(this)) {
                        boolean on = !TraktSync.enabled(this);
                        if (on && !RecentMedia.enabled(this)) RecentMedia.enable(this, true);
                        TraktSync.setSync(this, on);
                        render();
                    }
                });
        if (TraktSync.pending(this) > 0)
            page.addView(text(Ui.trf("Pending: {n}", "n", TraktSync.pending(this)), 16));
        if (!TraktSync.status(this).isEmpty())
            page.addView(
                    text("Trakt: " + Ui.tr("Unavailable"), 16));
        page.addView(
                text(
                        Ui.tr(
                                "Uncertain matches are kept pending. Nothing is marked watched from"
                                    + " the viewing list alone."),
                        14));
    }

    private void configureClient() {
        closeClientPage();
        EditText input = new EditText(this);
        input.setSingleLine();
        input.setText(TraktSync.client(this));
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), 0, dp(20), 0);
        body.addView(
                text(
                        Ui.tr(
                                "Register an app at trakt.tv/oauth/applications and enter its"
                                    + " Client ID. No Client Secret is needed."),
                        16));
        body.addView(input);
        try {
            java.net.InetAddress lan = KeyPage.lanAddress(this);
            if (lan != null) {
                clientPage =
                        new KeyPage(
                                lan,
                                new KeyPage.Listener() {
                                    public void checking() {}

                                    public String accept(String id) {
                                        if (!id.trim().matches("[A-Za-z0-9_-]{32,128}"))
                                            return Ui.tr("Unavailable");
                                        TraktSync.setClient(RecentActivity.this, id.trim());
                                        runOnUiThread(
                                                () -> {
                                                    input.setText(id.trim());
                                                });
                                        return null;
                                    }

                                    public void closed(KeyPage p, boolean expired) {}
                                },
                                Ui.tr("Trakt Client ID"),
                                Ui.tr(
                                        "Register an app at trakt.tv/oauth/applications and enter"
                                            + " its Client ID. No Client Secret is needed."));
                body.addView(qr(clientPage.url));
            }
        } catch (Exception ignored) {
        }
        new AlertDialog.Builder(this)
                .setTitle(Ui.tr("Trakt Client ID"))
                .setView(body)
                .setPositiveButton(
                        Ui.tr("Save"),
                        (d, w) -> {
                            String id = input.getText().toString().trim();
                            if (id.matches("[A-Za-z0-9_-]{32,128}")) {
                                TraktSync.setClient(this, id);
                                handler.postDelayed(this::render, 500);
                            }
                        })
                .setNegativeButton(Ui.tr("Cancel"), null)
                .setOnDismissListener(d -> closeClientPage())
                .show();
    }

    private void closeClientPage() {
        if (clientPage != null) {
            clientPage.close();
            clientPage = null;
        }
    }

    private void connect() {
        TextView code = text("…", 32);
        code.setGravity(Gravity.CENTER);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), dp(12), dp(24), dp(12));
        body.addView(qr(TraktSync.ACTIVATE));
        body.addView(text("trakt.tv/activate", 22));
        body.addView(code);
        codeDialog =
                new AlertDialog.Builder(this)
                        .setTitle(Ui.tr("Connect Trakt"))
                        .setView(body)
                        .setNegativeButton(Ui.tr("Cancel"), (d, w) -> TraktSync.cancelLogin())
                        .create();
        codeDialog.setOnCancelListener(d -> TraktSync.cancelLogin());
        codeDialog.show();
        TraktSync.login(
                this,
                new TraktSync.Login() {
                    public void code(String user, int expires) {
                        code.setText(user);
                    }

                    public void done(boolean ok) {
                        if (stopped) return;
                        if (ok) {
                            codeDialog.dismiss();
                            render();
                        } else code.setText(Ui.tr("Unavailable"));
                    }
                });
    }

    private ImageView qr(String url) {
        boolean[][] bits = Qr.encode(url);
        int n = bits.length + 8;
        Bitmap b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        b.eraseColor(Color.WHITE);
        for (int y = 0; y < bits.length; y++)
            for (int x = 0; x < bits.length; x++)
                if (bits[y][x]) b.setPixel(x + 4, y + 4, Color.BLACK);
        ImageView v = new ImageView(this);
        v.setImageBitmap(Bitmap.createScaledBitmap(b, dp(160), dp(160), false));
        v.setContentDescription(url);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(160), dp(160)));
        return v;
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        refresh();
    }
}
