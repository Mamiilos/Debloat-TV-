package io.freemytv;

import android.content.Context;
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
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Bing's photo of the day behind the clock: the "Photo of the day" theme in Home Settings (without picks, and only
 * while your own photos are off). No key needed. Checked at most every 6 h while the home screen is visible; the last
 * photo stays on disk, so it shows right away after a restart and offline. Built once, in the background, into a
 * Backdrop with the dark scrims; let go when the theme changes (release), and rebuilt from disk if it comes back.
 * Only www.bing.com is asked: the image path must be Bing's own ("/th?id=..."), no redirects are followed, and the
 * download stops past MAX_BYTES or if it isn't a JPEG.
 */
final class Wallpaper {
    interface Listener { void onWallpaper(); }

    /** The last 8 days: the newest one Bing allows as a wallpaper ("wp") is used. */
    private static final String API = "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=";
    private static final String HOST = "www.bing.com";
    private static final long EVERY = 6 * 60 * 60_000L, RETRY = 30 * 60_000L;
    private static final int MAX_BYTES = 5 * 1024 * 1024, MAX_JSON = 256 * 1024;
    private static Wallpaper instance;

    private final File image, info;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Backdrop backdrop;
    private volatile String caption = "";
    private volatile Listener listener;
    private volatile boolean wanted;   // the theme is on: a photo built now is kept (off: release() dropped it)
    private long failedAt;
    private boolean busy;

    static synchronized Wallpaper get(Context c) {
        if (instance == null) instance = new Wallpaper(c.getApplicationContext());
        return instance;
    }

    private Wallpaper(Context c) {
        image = new File(c.getFilesDir(), "wallpaper.jpg");
        info = new File(c.getFilesDir(), "wallpaper.json");
    }

    /** The photo with its scrims, or null (not downloaded yet, or let go). */
    Backdrop backdrop() { return backdrop; }

    /** What is in the photo and whose it is, as Bing gives it. */
    String caption() { return caption; }

    void setListener(Listener l) { listener = l; }

    /** Loads the saved photo, then checks for a new one if the last check is old (on the main thread; works in the background). */
    void refreshIfStale() {
        wanted = true;
        if (busy) return;
        long now = System.currentTimeMillis();
        boolean stale = !info.exists() || now - info.lastModified() >= EVERY;
        if (backdrop != null && (!stale || now - failedAt < RETRY)) return;
        busy = true;
        worker.execute(() -> {
            try {
                if (backdrop == null && image.exists()) load();
                if (stale && System.currentTimeMillis() - failedAt >= RETRY) fetch();
            } finally {
                main.post(() -> busy = false);
            }
        });
    }

    /** The theme is off: drops the photo (the GPU frees its texture once nothing draws it); the file stays on disk. */
    void release() {
        wanted = false;
        backdrop = null;
    }

    private void fetch() {
        try {
            JSONArray days = new JSONObject(new String(read(API + Ui.locale().toLanguageTag(), "application/json", MAX_JSON),
                    StandardCharsets.UTF_8)).getJSONArray("images");
            JSONObject day = null;
            URL url = null;
            for (int i = 0; i < days.length() && url == null; i++) {
                JSONObject d = days.getJSONObject(i);
                if (!d.optBoolean("wp", true)) continue;   // Bing: not available as a wallpaper
                url = imageUrl(d.optString("url"));
                if (url != null) day = d;
            }
            if (day == null) throw new IOException("no photo available as a wallpaper");
            String date = day.optString("startdate");
            JSONObject saved = saved();
            if (image.exists() && saved != null && date.equals(saved.optString("date"))) {
                //noinspection ResultOfMethodCallIgnored
                info.setLastModified(System.currentTimeMillis());   // the same photo: checked
                return;
            }
            save(read(url.toString(), "image/jpeg", MAX_BYTES), image);
            JSONObject next = new JSONObject().put("date", date).put("caption", day.optString("copyright"));
            Files.write(info.toPath(), next.toString().getBytes(StandardCharsets.UTF_8));
            load();
        } catch (Exception e) {
            failedAt = System.currentTimeMillis();
            AppLog.w("wallpaper: " + e.getMessage());
        }
    }

    /** Bing's image path ("/th?id=..."), on Bing's own host; anything else (another host, "//host", "\") is refused. */
    static URL imageUrl(String path) {
        if (path == null || path.length() < 2 || path.charAt(0) != '/' || path.charAt(1) == '/' || path.indexOf('\\') >= 0) return null;
        try {
            URL u = new URL("https", HOST, path);
            return HOST.equals(u.getHost()) && u.getPort() == -1 ? u : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** GET without following redirects, only the expected type, at most `max` bytes. */
    private static byte[] read(String url, String type, int max) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "FreeMyTV");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            String got = c.getContentType();
            if (got == null || !got.toLowerCase(java.util.Locale.ROOT).startsWith(type)) throw new IOException("not " + type + ": " + got);
            if (c.getContentLengthLong() > max) throw new IOException("too large: " + c.getContentLengthLong());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    if (out.size() + n > max) throw new IOException("too large");
                    out.write(buffer, 0, n);
                }
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    private static void save(byte[] bytes, File to) throws IOException {
        File part = new File(to.getPath() + ".part");
        try (OutputStream out = new FileOutputStream(part)) {
            out.write(bytes);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        }
        if (!part.renameTo(to)) throw new IOException("couldn't save the image");
    }

    private JSONObject saved() {
        try {
            return new JSONObject(new String(Files.readAllBytes(info.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    /** Decodes the saved photo (≥ the UI's width) and builds the background with its scrims. */
    private void load() {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(image.getPath(), bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= Backdrop.W) opts.inSampleSize *= 2;
            Bitmap b = BitmapFactory.decodeFile(image.getPath(), opts);
            if (b == null) throw new IOException("unreadable image");
            Backdrop built = Backdrop.make(b, false);
            JSONObject saved = saved();
            caption = saved == null ? "" : saved.optString("caption");
            if (!wanted) return;   // the theme changed meanwhile
            backdrop = built;
            AppLog.w("wallpaper: " + bounds.outWidth + "×" + bounds.outHeight + " " + caption);
            main.post(() -> {
                Listener l = listener;
                if (l != null) l.onWallpaper();
            });
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            //noinspection ResultOfMethodCallIgnored
            image.delete();
            //noinspection ResultOfMethodCallIgnored
            info.delete();   // fetch it again next time
            AppLog.w("wallpaper image: " + e.getMessage());
        }
    }
}
