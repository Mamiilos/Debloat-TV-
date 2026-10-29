package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Opt-in, household recommendations. Only selected catalog queries leave the device. */
final class PersonalRecs {
    static final int MAX_SEEDS = 4;

    static boolean selected(Context c) {
        try (FileInputStream in =
                new FileInputStream(new File(c.getFilesDir(), "recent-recommendations"))) {
            return in.read() == 1;
        } catch (Exception ignored) {
            return Config.PERSONAL_RECOMMENDATIONS;
        }
    }

    static boolean enabled(Context c) {
        return RecentMedia.enabled(c) && selected(c);
    }

    static void setEnabled(Context c, boolean on) {
        try (FileOutputStream out =
                new FileOutputStream(new File(c.getFilesDir(), "recent-recommendations"))) {
            out.write(on ? 1 : 0);
        } catch (Exception e) {
            RecentMedia.problem = Ui.tr("Could not save. Try again.");
        }
    }

    static long historyStamp(Context c) {
        return new File(c.getFilesDir(), "recent-media.json").lastModified();
    }

    static final class Seed {
        final WatchTitle title;
        final long duration;

        Seed(WatchTitle title, long duration) {
            this.title = title;
            this.duration = duration;
        }
    }

    static final class Snapshot {
        final List<Seed> seeds = new ArrayList<>();
        final Set<String> titles = new HashSet<>(), identities = new HashSet<>();
        String signature = "off";

        boolean excludes(String kind, int id, String title) {
            return identities.contains(kind + ":" + id)
                    || titles.contains(WatchTitle.normalize(title));
        }
    }

    /**
     * Read the base file only: AtomicFile.openRead in another process can disturb a pending writer.
     */
    static Snapshot snapshot(Context c) throws Exception {
        Snapshot out = new Snapshot();
        if (!enabled(c)) return out;
        File file = new File(c.getFilesDir(), "recent-media.json");
        List<RecentMedia.Item> history = new ArrayList<>();
        if (file.isFile()) {
            JSONArray rows =
                    new JSONArray(
                            new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            for (int n = 0; n < Math.min(80, rows.length()); n++)
                history.add(RecentMedia.Item.read(rows.getJSONObject(n)));
        }
        history.sort((a, b) -> Long.compare(b.recency(), a.recency()));
        Set<String> used = new HashSet<>();
        long cutoff = System.currentTimeMillis() - 90L * 86400000;
        for (RecentMedia.Item item : history) {
            if (item.recency() < cutoff || item.watchType != 0) continue;
            WatchTitle title =
                    new WatchTitle(item.title, item.type, item.season, item.episode, item.year);
            if (title.type.isEmpty() || title.title.isEmpty()) continue;
            out.titles.add(WatchTitle.normalize(title.title));
            String key = title.type + ":" + WatchTitle.normalize(title.title);
            if (out.seeds.size() < MAX_SEEDS
                    && RecommendationMix.enoughProgress(item.position, item.duration)
                    && used.add(key)) out.seeds.add(new Seed(title, item.duration));
        }
        List<String> excluded = new ArrayList<>(out.titles);
        Collections.sort(excluded);
        StringBuilder signature = new StringBuilder(String.join("|", excluded));
        for (Seed seed : out.seeds)
            signature
                    .append('|')
                    .append(seed.title.type)
                    .append(':')
                    .append(seed.title.title)
                    .append(':')
                    .append(seed.title.year);
        out.signature = RecentMedia.hash(signature.toString());
        return out;
    }

    static final class Suggestion {
        final JSONObject data;
        final String kind, basedOn;

        Suggestion(JSONObject data, String kind, String basedOn) {
            this.data = data;
            this.kind = kind;
            this.basedOn = basedOn;
        }
    }

    /**
     * Round-robin across recent titles, so a long-running series gets one vote rather than one per
     * episode.
     */
    static List<Suggestion> suggestions(Context c, Snapshot history, String language) {
        List<List<Suggestion>> pools = new ArrayList<>();
        SharedPreferences matches =
                c.getSharedPreferences("personal-recs-matches", Context.MODE_PRIVATE);
        if (matches.getAll().size() > 120) matches.edit().clear().apply();
        Set<String> seeds = new HashSet<>();
        for (Seed seed : history.seeds) {
            if (!enabled(c)) break;
            try {
                JSONObject match = resolve(matches, seed);
                if (match == null) continue;
                String kind = match.getString("media_type");
                int id = match.getInt("id");
                String identity = kind + ":" + id;
                history.identities.add(identity);
                if (!seeds.add(identity) || !enabled(c)) continue;
                JSONArray results =
                        Recs.catalog(
                                        "/"
                                                + kind
                                                + "/"
                                                + id
                                                + "/recommendations?language="
                                                + language)
                                .optJSONArray("results");
                List<Suggestion> pool = new ArrayList<>();
                for (int n = 0; results != null && n < Math.min(8, results.length()); n++) {
                    JSONObject result = results.getJSONObject(n);
                    if (result.optBoolean("adult") || result.optInt("id") <= 0) continue;
                    pool.add(new Suggestion(result, kind, seed.title.title));
                }
                pools.add(pool);
            } catch (Exception e) {
                // A failed seed never removes the general recommendations or logs a viewing title.
                AppLog.w("personal recommendations: catalog lookup unavailable");
            }
        }
        List<Suggestion> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int rank = 0; rank < 8 && out.size() < 24; rank++)
            for (List<Suggestion> pool : pools) {
                if (rank >= pool.size()) continue;
                Suggestion item = pool.get(rank);
                int id = item.data.optInt("id");
                String title = item.data.optString(item.kind.equals("tv") ? "name" : "title");
                String original =
                        item.data.optString(
                                item.kind.equals("tv") ? "original_name" : "original_title");
                if (history.excludes(item.kind, id, title)
                        || history.excludes(item.kind, id, original)) continue;
                if (seen.add(item.kind + ":" + id)) out.add(item);
            }
        return out;
    }

    private static JSONObject resolve(SharedPreferences cache, Seed seed) throws Exception {
        String key =
                RecentMedia.hash(
                        seed.title.type
                                + "|"
                                + seed.title.title
                                + "|"
                                + seed.title.year
                                + "|"
                                + seed.title.season
                                + "|"
                                + seed.title.episode);
        long now = System.currentTimeMillis();
        JSONObject saved = new JSONObject(cache.getString(key, "{}"));
        long age = now - saved.optLong("at");
        if (saved.has("at")
                && age >= 0
                && age < (saved.has("match") ? 7L * 86400000 : 6L * 3600000))
            return saved.optJSONObject("match");
        JSONObject match = WatchImages.match(seed.title, seed.duration);
        JSONObject entry = new JSONObject().put("at", now);
        if (match != null)
            entry.put(
                    "match",
                    new JSONObject()
                            .put("id", match.getInt("id"))
                            .put("media_type", match.getString("media_type")));
        cache.edit().putString(key, entry.toString()).apply();
        return match;
    }
}
