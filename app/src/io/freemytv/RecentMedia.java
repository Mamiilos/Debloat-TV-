package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/** Local, opt-in media history. Its reader, UI and media listener share the :history process. */
final class RecentMedia {
    static final Uri WATCH_NEXT = Uri.parse("content://android.media.tv/watch_next_program");
    static final ScheduledExecutorService WORK = Executors.newSingleThreadScheduledExecutor();
    static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, Item> items = new LinkedHashMap<>();
    private static boolean loaded;
    static volatile String problem = "";
    static volatile List<Item> visible = Collections.emptyList();

    static final class Item {
        String key = "",
                pkg = "",
                title = "",
                image = "",
                link = "",
                source = "session",
                providerId = "";
        int season = -1, episode = -1, year, type = -1, watchType;
        long position, duration, engaged, observed;
        boolean available;

        String subtitle() {
            String episodeText = season >= 0 && episode > 0 ? "S" + season + " · E" + episode : "";
            return episodeText
                    + (episodeText.isEmpty() ? "" : " · ")
                    + appName(Prefs.context(), pkg);
        }

        long recency() {
            return Math.max(engaged, observed);
        }

        boolean resume() {
            return available && watchType == 0 && (duration <= 0 || position < duration * .95);
        }

        JSONObject json() throws Exception {
            return new JSONObject()
                    .put("key", key)
                    .put("pkg", pkg)
                    .put("title", title)
                    .put("image", image)
                    .put("link", link)
                    .put("source", source)
                    .put("providerId", providerId)
                    .put("season", season)
                    .put("episode", episode)
                    .put("year", year)
                    .put("type", type)
                    .put("watchType", watchType)
                    .put("position", position)
                    .put("duration", duration)
                    .put("engaged", engaged)
                    .put("observed", observed)
                    .put("available", available);
        }

        static Item read(JSONObject j) {
            Item i = new Item();
            i.key = j.optString("key");
            i.pkg = j.optString("pkg");
            i.title = j.optString("title");
            i.image = j.optString("image");
            i.link = j.optString("link");
            i.source = j.optString("source", "session");
            i.providerId = j.optString("providerId");
            i.season = j.optInt("season", -1);
            i.episode = j.optInt("episode", -1);
            i.year = j.optInt("year");
            i.type = j.optInt("type", -1);
            i.watchType = j.optInt("watchType");
            i.position = j.optLong("position");
            i.duration = j.optLong("duration");
            i.engaged = j.optLong("engaged");
            i.observed = j.optLong("observed");
            i.available = j.optBoolean("available");
            return i;
        }
    }

    static boolean enabled(Context c) {
        return new File(c.getFilesDir(), "recent-enabled").isFile();
    }

    static void enable(Context c, boolean on) {
        try {
            File flag = new File(c.getFilesDir(), "recent-enabled");
            if (on) {
                try (FileOutputStream out = new FileOutputStream(flag)) {
                    out.write(1);
                }
            } else if (flag.exists() && !flag.delete()) throw new java.io.IOException();
            NowPlaying.historyChanged();
            HomeMode.nowPlayingAccess(c);
            if (on)
                android.service.notification.NotificationListenerService.requestRebind(
                        NowPlaying.component(c));
        } catch (Exception e) {
            problem = Ui.tr("Could not save. Try again.");
        }
    }

    static void load(Context c) {
        if (loaded) return;
        loaded = true;
        try {
            JSONArray a = new JSONArray(new String(file(c).readFully(), StandardCharsets.UTF_8));
            for (int n = 0; n < a.length() && n < 120; n++) {
                Item i = Item.read(a.getJSONObject(n));
                if (!i.key.isEmpty() && !i.pkg.isEmpty() && !i.title.isEmpty()) items.put(i.key, i);
            }
        } catch (Exception ignored) {
        }
        publish();
    }

    static void refresh(Context c, Runnable done) {
        WORK.execute(
                () -> {
                    load(c);
                    if (enabled(c)) refreshNow(c);
                    if (done != null) MAIN.post(done);
                });
    }

    static void refreshNow(Context c) {
        try (Cursor rows = c.getContentResolver().query(WATCH_NEXT, null, null, null, null)) {
            if (rows == null) return;
            for (Item old : items.values())
                if (old.source.equals("provider")) old.available = false;
            long now = System.currentTimeMillis();
            while (rows.moveToNext()) {
                Item i = new Item();
                i.pkg = text(rows, "package_name");
                i.title = text(rows, "title");
                if (i.pkg.isEmpty() || i.title.isEmpty() || i.pkg.equals(HomeMode.PKG)) continue;
                if (HomeApps.launchIntent(c.getPackageManager(), i.pkg) == null) continue;
                i.watchType = number(rows, "watch_next_type", -1);
                if (i.watchType != 0 || number(rows, "browsable", 1) == 0)
                    continue; // Next episodes, suggestions and watchlists are not playback.
                i.source = "provider";
                i.available = true;
                i.providerId = text(rows, "internal_provider_id");
                if (i.providerId.isEmpty()) i.providerId = text(rows, "_id");
                i.season = number(rows, "season_display_number", -1);
                i.episode = number(rows, "episode_display_number", -1);
                i.type = number(rows, "type", -1);
                String release = text(rows, "release_date");
                if (release.matches("[0-9]{4}.*"))
                    i.year = Integer.parseInt(release.substring(0, 4));
                i.position = value(rows, "last_playback_position_millis");
                i.duration = value(rows, "duration_millis");
                i.engaged = Math.min(value(rows, "last_engagement_time_utc_millis"), now);
                if (i.engaged <= 0 || now - i.engaged > 90L * 86400000) continue;
                i.link = text(rows, "intent_uri");
                i.image = text(rows, "poster_art_uri");
                i.key =
                        hash(
                                i.pkg
                                        + "|"
                                        + i.title
                                        + "|"
                                        + i.season
                                        + "|"
                                        + i.episode
                                        + "|"
                                        + i.providerId);
                Item old = items.get(i.key);
                if (old != null) i.observed = old.observed;
                boolean changed =
                        old != null && (old.position != i.position || old.engaged != i.engaged);
                // The initial import is display-only; never infer a watch from existing rows.
                if (MediaHistoryMonitor.instance != null)
                    MediaHistoryMonitor.instance.provider(i, changed || old == null);
                items.put(i.key, i);
            }
            problem = "";
            save(c);
        } catch (SecurityException e) {
            problem = Ui.tr("Allow access to TV listings to show Continue watching.");
        } catch (Exception e) {
            problem = Ui.tr("This TV could not provide its viewing list.");
        }
    }

    static void remember(Context c, Item i) {
        if (i.title.isEmpty()) return;
        Item old = items.get(i.key);
        if (old != null && i.link.isEmpty()) i.link = old.link;
        items.put(i.key, i);
        save(c);
    }

    static void forgetSession(String key) {
        items.remove(key);
    }

    static void clear(Context c, Runnable done) {
        WORK.execute(
                () -> {
                    items.clear();
                    save(c);
                    if (MediaHistoryMonitor.instance != null) MediaHistoryMonitor.instance.clear();
                    TraktSync.clearPending(c);
                    MAIN.post(done);
                });
    }

    private static AtomicFile file(Context c) {
        return new AtomicFile(new File(c.getFilesDir(), "recent-media.json"));
    }

    private static void save(Context c) {
        publish();
        FileOutputStream out = null;
        AtomicFile f = file(c);
        try {
            JSONArray a = new JSONArray();
            for (Item i : visible) a.put(i.json());
            out = f.startWrite();
            out.write(a.toString().getBytes(StandardCharsets.UTF_8));
            f.finishWrite(out);
        } catch (Exception e) {
            if (out != null) f.failWrite(out);
        }
    }

    private static void publish() {
        List<Item> sorted = new ArrayList<>(items.values());
        sorted.sort((a, b) -> Long.compare(b.recency(), a.recency()));
        if (sorted.size() > 80) sorted = new ArrayList<>(sorted.subList(0, 80));
        items.clear();
        List<Item> copies = new ArrayList<>();
        for (Item i : sorted) {
            items.put(i.key, i);
            try {
                copies.add(Item.read(i.json()));
            } catch (Exception ignored) {
            }
        }
        visible = Collections.unmodifiableList(copies);
    }

    static boolean open(Context c, Item i) {
        if (!i.link.isEmpty()) {
            try {
                Intent raw = Intent.parseUri(i.link, Intent.URI_INTENT_SCHEME);
                Uri data = raw.getData();
                if (data != null
                        && ("file".equals(data.getScheme())
                                || "javascript".equals(data.getScheme())))
                    throw new SecurityException();
                Intent intent = new Intent(Intent.ACTION_VIEW, data).setPackage(i.pkg);
                if (raw.getComponent() != null && raw.getComponent().getPackageName().equals(i.pkg))
                    intent.setComponent(raw.getComponent());
                if (raw.getExtras() != null) intent.putExtras(raw.getExtras());
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(intent);
                return true;
            } catch (Exception ignored) {
            }
        }
        try {
            Intent intent = HomeApps.launchIntent(c.getPackageManager(), i.pkg);
            if (intent != null) {
                c.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    static String appName(Context c, String pkg) {
        try {
            return c.getPackageManager()
                    .getApplicationLabel(c.getPackageManager().getApplicationInfo(pkg, 0))
                    .toString();
        } catch (Exception ignored) {
            return pkg;
        }
    }

    static String hash(String value) {
        try {
            byte[] bytes =
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder s = new StringBuilder();
            for (byte b : bytes) s.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return s.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String text(Cursor c, String name) {
        int n = c.getColumnIndex(name);
        return n < 0 || c.isNull(n) ? "" : c.getString(n);
    }

    private static long value(Cursor c, String name) {
        try {
            return Math.max(0, Long.parseLong(text(c, name)));
        } catch (Exception e) {
            return 0;
        }
    }

    private static int number(Cursor c, String name, int fallback) {
        try {
            return Integer.parseInt(text(c, name));
        } catch (Exception e) {
            return fallback;
        }
    }
}
