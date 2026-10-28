package io.freemytv;

import android.content.Context;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;

/** Optional catalog artwork, never used as playback identity or evidence for Trakt. */
final class WatchImages {
    private static final long WEEK = 7L * 86400000;
    private static final int MAX_BYTES = 2 << 20;

    static String key(WatchNext.Item item) {
        return "tmdb:"
                + RecentMedia.hash(
                        "episode-v2|"
                                + item.title
                                + "|"
                                + item.seasonNumber
                                + "|"
                                + item.episodeNumber
                                + "|"
                                + item.mediaType
                                + "|"
                                + item.releaseYear);
    }

    /**
     * Called only on WatchArt's serial image worker. Cached files contain images, not tokens or
     * titles.
     */
    static byte[] load(Context c, WatchNext.Item item) throws Exception {
        if (!RecentMedia.artEnabled(c) || Recs.key().isEmpty()) return null;
        File dir = new File(c.getCacheDir(), "watch-images");
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        String hash = key(item).substring(5);
        File image = new File(dir, hash + ".jpg"), miss = new File(dir, hash + ".miss");
        long now = System.currentTimeMillis();
        if (image.isFile() && image.length() <= MAX_BYTES && now - image.lastModified() < WEEK)
            return Files.readAllBytes(image.toPath());
        if (miss.isFile() && now - miss.lastModified() < 6 * 3600000L) return null;
        String path = resolve(item);
        if (path == null) {
            Files.write(miss.toPath(), new byte[0]);
            prune(dir);
            return null;
        }
        if (!path.matches("/[A-Za-z0-9_-]{1,128}\\.(jpg|png|webp)")) return null;
        HttpURLConnection conn =
                (HttpURLConnection)
                        new URL("https://image.tmdb.org/t/p/w780" + path).openConnection();
        try {
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setInstanceFollowRedirects(false);
            if (conn.getResponseCode() != 200) throw new java.io.IOException("artwork unavailable");
            byte[] bytes;
            try (InputStream in = conn.getInputStream();
                    ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                for (int n; (n = in.read(buffer)) >= 0; ) {
                    if (out.size() + n > MAX_BYTES)
                        throw new java.io.IOException("artwork too large");
                    out.write(buffer, 0, n);
                }
                bytes = out.toByteArray();
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            File temporary = new File(dir, hash + ".new");
            Files.write(temporary.toPath(), bytes);
            Files.move(
                    temporary.toPath(),
                    image.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            if (miss.exists()) miss.delete();
            prune(dir);
            return bytes;
        } finally {
            conn.disconnect();
        }
    }

    private static void prune(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length <= 80) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (int n = 0; n < files.length - 80; n++) files[n].delete();
    }

    private static String resolve(WatchNext.Item item) throws Exception {
        boolean episode = item.seasonNumber >= 0 && item.episodeNumber > 0;
        String type =
                episode || item.mediaType == 3 || item.mediaType == 2 || item.mediaType == 1
                        ? "tv"
                        : item.mediaType == 0 ? "movie" : "";
        if (type.isEmpty()) return null;
        JSONArray found =
                Recs.catalog(
                                "/search/"
                                        + type
                                        + "?query="
                                        + URLEncoder.encode(item.title, "UTF-8")
                                        + "&include_adult=false")
                        .optJSONArray("results");
        if (found == null) return null;
        JSONObject chosen = null;
        String chosenStill = null;
        String wanted = TraktSync.normalize(item.title);
        for (int n = 0; n < Math.min(10, found.length()); n++) {
            JSONObject result = found.getJSONObject(n);
            if (type.equals("movie")
                    && item.releaseYear > 1900
                    && !result.optString("release_date")
                            .startsWith(String.valueOf(item.releaseYear))) continue;
            String name = type.equals("tv") ? "name" : "title";
            boolean match =
                    wanted.equals(TraktSync.normalize(result.optString(name)))
                            || wanted.equals(
                                    TraktSync.normalize(result.optString("original_" + name)));
            if (!match && n < 3) {
                JSONObject aliases =
                        Recs.catalog(
                                "/" + type + "/" + result.getLong("id") + "/alternative_titles");
                JSONArray titles = aliases.optJSONArray(type.equals("tv") ? "results" : "titles");
                for (int a = 0; titles != null && a < titles.length(); a++)
                    if (wanted.equals(
                            TraktSync.normalize(titles.getJSONObject(a).optString("title")))) {
                        match = true;
                        break;
                    }
            }
            if (!match) continue;
            String still = null;
            if (episode) {
                // Different shows can share a title. The requested episode must exist in the chosen
                // show.
                JSONObject ep;
                try {
                    ep =
                            Recs.catalog(
                                    "/tv/"
                                            + result.getLong("id")
                                            + "/season/"
                                            + item.seasonNumber
                                            + "/episode/"
                                            + item.episodeNumber);
                } catch (java.io.IOException e) {
                    if ("TMDB returned HTTP 404".equals(e.getMessage())) continue;
                    throw e;
                }
                long duration = ep.optLong("runtime") * 60000L;
                if (duration > 0
                        && item.duration > 0
                        && Math.abs(duration - item.duration) > 300000) continue;
                still = ep.optString("still_path", "");
            }
            if (chosen != null && chosen.getLong("id") != result.getLong("id")) return null;
            chosen = result;
            chosenStill = still;
        }
        if (chosen == null) return null;
        if (chosenStill != null && chosenStill.startsWith("/")) return chosenStill;
        String backdrop = chosen.optString("backdrop_path", "");
        return backdrop.startsWith("/") ? backdrop : null;
    }
}
