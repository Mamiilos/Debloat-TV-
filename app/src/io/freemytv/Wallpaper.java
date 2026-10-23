package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Bing daily photography, adapted from upstream PR102; remains visible behind TMDB cards. No key needed.
 * Rotates through ~150 of Bing’s recent photos (no repeats until all have been shown, history kept on disk) every 30 min while the home screen is visible; the last photo stays on disk, so it shows right away
 * after a restart and offline. Built once, in the background, into a Backdrop with the dark scrims; let go when the
 * theme changes (release), and rebuilt from disk if it comes back.
 */
final class Wallpaper {
    interface Listener { void onWallpaper(); }

    /** Bing answers 8 photos per request and reaches back ~16 days (idx 0 and 8); other markets show other photos. */
    private static final String API = "https://www.bing.com/HPImageArchive.aspx?format=js&n=8&mkt=";
    private static final String[] MARKETS = {"es-MX", "en-US", "en-GB", "de-DE", "fr-FR", "ja-JP", "en-AU", "pt-BR", "it-IT", "en-CA", "en-IN", "zh-CN"};
    private static final long POOL_EVERY = 24 * 3600_000L;
    private static final int SEEN_MAX = 400;
    private static final long EVERY = 30 * 60_000L, RETRY = 30 * 60_000L;
    private static Wallpaper instance;

    private final File image, info, pool;
    private final android.content.SharedPreferences seenStore;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Backdrop backdrop;
    private volatile String caption = "";
    private volatile Listener listener;
    private volatile boolean day;      // build the day look (white scrims) or the night one (dark scrims)
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
        pool = new File(c.getFilesDir(), "wallpaper-pool.json");
        seenStore = c.getSharedPreferences("wallpaper", Context.MODE_PRIVATE);
    }

    /** The photo with its scrims, or null (not downloaded yet). */
    Backdrop backdrop() { return backdrop; }

    /** What is in the photo and whose it is, as Bing gives it. */
    String caption() { return caption; }

    void setListener(Listener l) { listener = l; }

    /** By day the photo gets white scrims (light interface over it); at night the dark ones. Rebuilt from the saved file. */
    void setDay(boolean on) {
        if (day == on) return;
        day = on;
        if (!wanted || busy || !image.exists()) return;
        busy = true;
        worker.execute(() -> {
            try {
                load();
            } finally {
                main.post(() -> busy = false);
            }
        });
    }

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

    /**
     * Next photo: the pool is a day's worth of Bing's photo lists (a dozen markets, two pages each: ~150 different
     * photos, only their addresses and captions), the history of photos already shown is kept on disk, and one new one
     * is picked at random among those not shown yet; only that one is downloaded. When all have been shown, the history
     * starts over (keeping the current one out of the next pick).
     */
    private void fetch() {
        try {
            JSONArray all = loadPool();
            if (all.length() == 0) throw new IOException("no photos from Bing");
            Set<String> seen = new HashSet<>(seenStore.getStringSet("seen", new HashSet<String>()));
            JSONObject current = saved();
            String now = current == null ? "" : current.optString("id");
            List<JSONObject> fresh = new ArrayList<>();
            for (int i = 0; i < all.length(); i++) {
                JSONObject o = all.getJSONObject(i);
                if (!seen.contains(o.getString("id")) && !o.getString("id").equals(now)) fresh.add(o);
            }
            if (fresh.isEmpty()) {
                seen.clear();
                for (int i = 0; i < all.length(); i++) {
                    JSONObject o = all.getJSONObject(i);
                    if (!o.getString("id").equals(now)) fresh.add(o);
                }
            }
            JSONObject pick = fresh.get(new Random().nextInt(fresh.size()));
            download("https://www.bing.com" + pick.getString("url"));
            seen.add(pick.getString("id"));
            if (seen.size() > SEEN_MAX) seen.clear();
            seenStore.edit().putStringSet("seen", seen).apply();
            JSONObject next = new JSONObject().put("id", pick.getString("id")).put("caption", pick.optString("copyright"));
            try (FileOutputStream out = new FileOutputStream(info)) { out.write(next.toString().getBytes(StandardCharsets.UTF_8)); }
            AppLog.w("wallpaper: " + (all.length() - fresh.size()) + "/" + all.length() + " already shown; next: " + pick.optString("copyright"));
            load();
        } catch (Exception e) {
            failedAt = System.currentTimeMillis();
            AppLog.w("wallpaper: " + e.getMessage());
        }
    }

    /** The pool of photos (id, address, caption), made again once a day; the old one serves if Bing doesn't answer. */
    private JSONArray loadPool() throws Exception {
        JSONArray saved = null;
        if (pool.exists()) {
            try (FileInputStream in = new FileInputStream(pool); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096]; int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                saved = new JSONArray(new String(out.toByteArray(), StandardCharsets.UTF_8));
            } catch (Exception ignored) {
            }
        }
        if (saved != null && saved.length() > 0 && System.currentTimeMillis() - pool.lastModified() < POOL_EVERY) return saved;
        Set<String> ids = new HashSet<>();
        JSONArray out = new JSONArray();
        for (String market : MARKETS) {
            for (int idx = 0; idx <= 8; idx += 8) {
                try {
                    JSONArray images = new JSONObject(HomeData.get(API + market + "&idx=" + idx)).getJSONArray("images");
                    for (int i = 0; i < images.length(); i++) {
                        JSONObject o = images.getJSONObject(i);
                        String id = o.optString("urlbase");
                        if (id.isEmpty() || !ids.add(id)) continue;
                        out.put(new JSONObject().put("id", id).put("url", o.getString("url")).put("copyright", o.optString("copyright")));
                    }
                } catch (Exception ignored) {
                }
            }
        }
        if (out.length() == 0) return saved == null ? out : saved;
        try (FileOutputStream f = new FileOutputStream(pool)) { f.write(out.toString().getBytes(StandardCharsets.UTF_8)); }
        return out;
    }

    private JSONObject saved() {
        try {
            try (FileInputStream in = new FileInputStream(info); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[1024]; int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            return null;
        }
    }

    private void download(String url) throws IOException {
        File part = new File(image.getPath() + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "FreeMyTV");
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(image)) throw new IOException("couldn't save the image");
    }

    /** Decodes the saved photo (≥ 1920 wide) and builds the background with its scrims. */
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
            Backdrop built = day ? Backdrop.makeDay(b) : Backdrop.make(b, false);
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
