package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home Settings > Background > Picture of the day: Bing's photo of the day as the home screen's background. Only with
 * it on does the app talk to Bing (one small JSON and one 1920×1080 JPEG a day). The last photo stays on disk, so the
 * home screen opens with it without the network; a new one is looked for every few hours.
 */
final class Wallpaper {
    interface Listener { void onWallpaper(); }

    private static final String BING = "https://www.bing.com";
    /** How often to ask Bing whether the photo changed (it changes once a day, at a time that depends on the market). */
    private static final long CHECK_EVERY = 3 * 3_600_000L;
    /** After a failure (no internet), the next try waits this long. */
    private static final long RETRY_AFTER = 10 * 60_000L;

    private static Wallpaper instance;

    static synchronized Wallpaper get(Context c) {
        if (instance == null) instance = new Wallpaper(c.getApplicationContext());
        return instance;
    }

    private final SharedPreferences cache;
    private final File file;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private volatile Backdrop backdrop;     // built from `file`; null while off or not built yet
    private volatile boolean busy;
    private volatile long failedAt;
    private boolean wasOn;                  // main thread: what the listeners were last told

    private Wallpaper(Context c) {
        cache = c.getSharedPreferences("wallpaper", Context.MODE_PRIVATE);
        file = new File(c.getFilesDir(), "wallpaper.jpg");
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    /** The picture of the day, ready to draw, or null (off, or not here yet). */
    Backdrop backdrop() {
        return Prefs.wallpaper() ? backdrop : null;
    }

    /** Turned on or off since the last call: tells the listeners; on, builds the saved photo and looks for a new one. */
    void refreshIfStale() {
        boolean on = Prefs.wallpaper();
        if (on != wasOn) {
            wasOn = on;
            if (!on) {
                backdrop = null;    // its 8 MB go back as soon as the home screen lets go of it
                notifyListeners();
                return;
            }
        }
        if (!on || busy) return;
        long now = System.currentTimeMillis(), checked = cache.getLong("checkedAt", 0);
        boolean stale = !file.exists() || now - checked > CHECK_EVERY || now < checked;
        long sinceFail = now - failedAt;
        if (stale && failedAt != 0 && sinceFail >= 0 && sinceFail < RETRY_AFTER) stale = false;
        if (!stale && backdrop != null) return;
        final boolean fetch = stale;
        busy = true;
        io.execute(() -> {
            try {
                boolean changed = fetch && download();
                if (changed || backdrop == null) build();
                failedAt = 0;
            } catch (IOException | RuntimeException e) {
                failedAt = System.currentTimeMillis();
                AppLog.w("picture of the day: " + e.getMessage());
                if (backdrop == null && file.exists()) build();   // offline: yesterday's photo is still better
            } finally {
                busy = false;
            }
        });
    }

    /** Asks Bing for today's photo; downloads it only when it's another one. True if the file changed. */
    private boolean download() throws IOException {
        JSONObject image;
        try {
            String market = Ui.locale().toLanguageTag();
            JSONArray images = new JSONObject(new String(fetch(BING + "/HPImageArchive.aspx?format=js&idx=0&n=1&mkt="
                    + market), StandardCharsets.UTF_8)).getJSONArray("images");
            image = images.getJSONObject(0);
        } catch (org.json.JSONException e) {
            throw new IOException("unexpected answer from Bing");
        }
        String base = image.optString("urlbase");
        if (!base.startsWith("/")) throw new IOException("no photo in Bing's answer");
        cache.edit().putLong("checkedAt", System.currentTimeMillis()).apply();
        if (base.equals(cache.getString("urlbase", "")) && file.exists()) return false;
        byte[] jpeg = fetch(BING + base + "_1920x1080.jpg");
        File part = new File(file.getPath() + ".part");
        try (FileOutputStream out = new FileOutputStream(part)) {
            out.write(jpeg);
        }
        if (!part.renameTo(file)) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw new IOException("couldn't save the photo");
        }
        cache.edit().putString("urlbase", base).apply();
        AppLog.w("picture of the day: " + image.optString("title", base));
        return true;
    }

    /** The saved photo, at the UI's size, as the background (Backdrop.wallpaper). */
    private void build() {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= Backdrop.W) opts.inSampleSize *= 2;
            Bitmap b = BitmapFactory.decodeFile(file.getPath(), opts);
            if (b == null) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                cache.edit().remove("urlbase").apply();
                return;
            }
            backdrop = Backdrop.wallpaper(b);
            notifyListeners();
        } catch (RuntimeException | OutOfMemoryError e) {
            AppLog.w("picture of the day: " + e.getMessage());
        }
    }

    private static byte[] fetch(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("Bing returned HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onWallpaper(); });
    }
}
