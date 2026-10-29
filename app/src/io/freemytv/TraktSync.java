package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Device OAuth and delayed, deduplicated history sync. No client secret or password. */
final class TraktSync {
    private static final String API = "https://api.trakt.tv";
    static final String ACTIVATE = "https://auth.trakt.tv/activate";
    static final String REDIRECT = "https://freemytv.io";
    private static final ScheduledExecutorService NET =
            Executors.newSingleThreadScheduledExecutor();
    private static final java.util.concurrent.atomic.AtomicLong LOGIN =
            new java.util.concurrent.atomic.AtomicLong();
    private static long nextSync;
    private static boolean syncing;

    interface Login {
        void code(String code, int expires);

        void done(boolean success);
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("trakt", Context.MODE_PRIVATE);
    }

    static String client(Context c) {
        return prefs(c).getString("client", Config.TRAKT_CLIENT_ID);
    }

    static boolean connected(Context c) {
        return !prefs(c).getString("access", "").isEmpty();
    }

    static boolean enabled(Context c) {
        return RecentMedia.enabled(c) && connected(c) && prefs(c).getBoolean("sync", false);
    }

    static String status(Context c) {
        return prefs(c).getString("status", "");
    }

    static int pending(Context c) {
        return queue(c).length();
    }

    static void setClient(Context c, String value) {
        if (client(c).equals(value.trim())) return;
        cancelLogin();
        prefs(c).edit().putBoolean("sync", false).commit();
        NET.execute(
                () -> {
                    prefs(c).edit().clear().putString("client", value.trim()).commit();
                    nextSync = 0;
                });
    }

    static void disconnect(Context c) {
        cancelLogin();
        // The UI disables writes immediately; token cleanup and an in-flight request finish in the
        // serial worker.
        prefs(c).edit().putBoolean("sync", false).commit();
        NET.execute(
                () -> {
                    String old = prefs(c).getString("access", ""), id = client(c);
                    prefs(c).edit().clear().putString("client", id).commit();
                    if (!old.isEmpty())
                        try {
                            call(
                                    c,
                                    "POST",
                                    "/oauth/revoke",
                                    new JSONObject().put("token", old).put("client_id", id),
                                    false);
                        } catch (Exception ignored) {
                        }
                });
    }

    static void setSync(Context c, boolean on) {
        prefs(c).edit().putBoolean("sync", on).commit();
        if (on) kick(c);
    }

    static void cancelLogin() {
        LOGIN.incrementAndGet();
    }

    static void login(Context c, Login listener) {
        final long generation = LOGIN.incrementAndGet();
        NET.execute(
                () -> {
                    try {
                        prefs(c).edit()
                                .putBoolean("sync", false)
                                .remove("access")
                                .remove("refresh")
                                .commit();
                        JSONObject code =
                                new JSONObject(
                                        call(
                                                c,
                                                "POST",
                                                "/oauth/device/code",
                                                new JSONObject().put("client_id", client(c)),
                                                false));
                        int interval = Math.max(5, code.getInt("interval")),
                                expires = Math.min(1800, code.getInt("expires_in"));
                        String device = code.getString("device_code"),
                                user = code.getString("user_code");
                        if (generation != LOGIN.get()) return;
                        RecentMedia.MAIN.post(
                                () -> {
                                    if (generation == LOGIN.get()) listener.code(user, expires);
                                });
                        poll(
                                c,
                                listener,
                                generation,
                                device,
                                interval,
                                SystemClock.elapsedRealtime() + expires * 1000L);
                    } catch (Exception e) {
                        loginDone(listener, generation, false);
                    }
                });
    }

    private static void poll(
            Context c, Login listener, long generation, String device, int interval, long until) {
        NET.schedule(
                () -> {
                    if (generation != LOGIN.get()) return;
                    if (!TraktDeviceFlow.active(
                            generation, LOGIN.get(), SystemClock.elapsedRealtime(), until)) {
                        loginDone(listener, generation, false);
                        return;
                    }
                    try {
                        JSONObject token =
                                new JSONObject(
                                        call(
                                                c,
                                                "POST",
                                                "/oauth/device/token",
                                                new JSONObject()
                                                        .put("code", device)
                                                        .put("client_id", client(c)),
                                                false));
                        if (generation != LOGIN.get()) return;
                        saveTokens(c, token, true);
                        loginDone(listener, generation, true);
                    } catch (Failure e) {
                        int delay = TraktDeviceFlow.delay(e.status, interval, e.retry);
                        if (delay > 0) poll(c, listener, generation, device, delay, until);
                        else {
                            prefs(c).edit().remove("access").remove("refresh").commit();
                            loginDone(listener, generation, false);
                        }
                    } catch (Exception e) {
                        prefs(c).edit().remove("access").remove("refresh").commit();
                        loginDone(listener, generation, false);
                    }
                },
                interval,
                TimeUnit.SECONDS);
    }

    private static void loginDone(Login listener, long generation, boolean ok) {
        RecentMedia.MAIN.post(
                () -> {
                    if (generation == LOGIN.get()) listener.done(ok);
                });
    }

    private static void saveTokens(Context c, JSONObject j, boolean newLogin) throws Exception {
        String access = j.getString("access_token"), refresh = j.getString("refresh_token");
        if (access.isEmpty() || refresh.isEmpty()) throw new java.io.IOException();
        SharedPreferences.Editor edit =
                prefs(c).edit()
                        .putString("access", access)
                        .putString("refresh", refresh)
                        .putLong(
                                "expires",
                                System.currentTimeMillis()
                                        + j.optLong("expires_in", 604800) * 1000L);
        if (newLogin)
            edit.putString("account", RecentMedia.hash(access))
                    .putBoolean("sync", true)
                    .putString("queue", "[]")
                    .putString("status", "");
        if (!edit.commit()) throw new java.io.IOException();
    }

    static void enqueue(Context c, RecentMedia.Item item, long at) {
        if (!enabled(c)) return;
        try {
            final JSONObject copy = item.json();
            NET.execute(
                    () -> {
                        if (!enabled(c)) return;
                        try {
                            String account = prefs(c).getString("account", "");
                            if (account.isEmpty()) return;
                            String key = RecentMedia.hash(account + "|" + item.key + "|" + at);
                            if (wasSent(c, key)) return;
                            JSONArray queue = queue(c);
                            for (int n = 0; n < queue.length(); n++)
                                if (key.equals(queue.getJSONObject(n).optString("key"))) return;
                            if (queue.length() >= 100) return;
                            queue.put(
                                    new JSONObject()
                                            .put("key", key)
                                            .put("account", account)
                                            .put("item", copy)
                                            .put("at", at));
                            prefs(c).edit().putString("queue", queue.toString()).commit();
                            nextSync = 0;
                            sync(c);
                        } catch (Exception ignored) {
                        }
                    });
        } catch (Exception ignored) {
        }
    }

    static void kick(Context c) {
        NET.execute(() -> sync(c));
    }

    static void clearPending(Context c) {
        NET.execute(() -> prefs(c).edit().putString("queue", "[]").commit());
    }

    private static JSONArray queue(Context c) {
        try {
            return new JSONArray(prefs(c).getString("queue", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static void sync(Context c) {
        if (syncing || !enabled(c) || SystemClock.elapsedRealtime() < nextSync) return;
        syncing = true;
        nextSync = SystemClock.elapsedRealtime() + 60000;
        try {
            JSONArray q = queue(c), keep = new JSONArray();
            for (int n = 0; n < q.length(); n++) {
                JSONObject entry = q.getJSONObject(n);
                if (!entry.optString("account").equals(prefs(c).getString("account", ""))) continue;
                if (wasSent(c, entry.optString("key"))) continue;
                if (!enabled(c)) {
                    keep.put(entry);
                    continue;
                }
                if (entry.optLong("retry_at") > System.currentTimeMillis()) {
                    keep.put(entry);
                    continue;
                }
                try {
                    RecentMedia.Item item = RecentMedia.Item.read(entry.getJSONObject("item"));
                    String type =
                            item.season >= 0 && item.episode > 0
                                    ? "episodes"
                                    : item.type == 0 ? "movies" : "";
                    long id = entry.optLong("id");
                    if (id == 0 && !type.isEmpty()) {
                        id = resolve(c, item);
                        entry.put("id", id);
                    }
                    if (id == 0) {
                        entry.put("retry_at", System.currentTimeMillis() + 86400000L);
                        keep.put(entry);
                        prefs(c).edit().putString("status", "unmatched").commit();
                        continue;
                    }
                    long at = entry.getLong("at");
                    String from = enc(Instant.ofEpochMilli(at - 120000).toString()),
                            to = enc(Instant.ofEpochMilli(at + 120000).toString());
                    JSONArray existing =
                            new JSONArray(
                                    request(
                                            c,
                                            "GET",
                                            "/sync/history/"
                                                    + type
                                                    + "/"
                                                    + id
                                                    + "?start_at="
                                                    + from
                                                    + "&end_at="
                                                    + to,
                                            null));
                    if (existing.length() == 0) {
                        if (!enabled(c)) {
                            keep.put(entry);
                            continue;
                        }
                        JSONObject media =
                                new JSONObject()
                                        .put("ids", new JSONObject().put("trakt", id))
                                        .put("watched_at", Instant.ofEpochMilli(at).toString());
                        JSONObject result =
                                new JSONObject(
                                        request(
                                                c,
                                                "POST",
                                                "/sync/history",
                                                new JSONObject()
                                                        .put(type, new JSONArray().put(media))));
                        if (result.optJSONObject("added") == null
                                || result.getJSONObject("added").optInt(type) != 1)
                            throw new java.io.IOException();
                    }
                    markSent(c, entry.getString("key"));
                } catch (Failure e) {
                    keep.put(entry);
                    nextSync = SystemClock.elapsedRealtime() + Math.max(60000L, e.retry * 1000L);
                    prefs(c).edit()
                            .putString("status", e.status == 401 ? "auth" : "retry")
                            .commit();
                    for (int rest = n + 1; rest < q.length(); rest++)
                        keep.put(q.getJSONObject(rest));
                    break;
                } catch (Exception e) {
                    keep.put(entry);
                    nextSync = SystemClock.elapsedRealtime() + 300000;
                    prefs(c).edit().putString("status", "retry").commit();
                    for (int rest = n + 1; rest < q.length(); rest++)
                        keep.put(q.getJSONObject(rest));
                    break;
                }
            }
            // Persist resolved ids too, so a retry does not pick a different catalog result.
            prefs(c).edit().putString("queue", keep.toString()).commit();
        } catch (Exception ignored) {
        } finally {
            syncing = false;
        }
    }

    private static boolean wasSent(Context c, String key) {
        try {
            JSONArray sent = new JSONArray(prefs(c).getString("sent", "[]"));
            for (int n = 0; n < sent.length(); n++) if (key.equals(sent.getString(n))) return true;
        } catch (Exception ignored) {
        }
        return false;
    }

    private static void markSent(Context c, String key) throws Exception {
        JSONArray sent = new JSONArray(prefs(c).getString("sent", "[]")), kept = new JSONArray();
        for (int n = Math.max(0, sent.length() - 255); n < sent.length(); n++)
            kept.put(sent.getString(n));
        kept.put(key);
        prefs(c).edit().putString("sent", kept.toString()).putString("status", "").commit();
    }

    static final class Match {
        final long id;
        final String label;

        Match(long id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    private static String mappingKey(RecentMedia.Item item) {
        return "map_"
                + RecentMedia.hash(
                        item.pkg
                                + "|"
                                + item.title
                                + "|"
                                + item.year
                                + "|"
                                + (item.season >= 0 ? "show" : "movie"));
    }

    static void choices(
            Context c,
            RecentMedia.Item item,
            java.util.function.Consumer<java.util.List<Match>> done) {
        NET.execute(
                () -> {
                    java.util.List<Match> choices = new java.util.ArrayList<>();
                    try {
                        String type =
                                item.season >= 0 && item.episode > 0
                                        ? "show"
                                        : item.type == 0 ? "movie" : "";
                        if (!type.isEmpty()) {
                            JSONArray found =
                                    new JSONArray(
                                            request(
                                                    c,
                                                    "GET",
                                                    "/search/"
                                                            + type
                                                            + "?query="
                                                            + enc(item.title)
                                                            + "&limit=10",
                                                    null));
                            for (int n = 0; n < found.length(); n++) {
                                JSONObject value = found.getJSONObject(n).getJSONObject(type);
                                choices.add(
                                        new Match(
                                                value.getJSONObject("ids").getLong("trakt"),
                                                value.getString("title")
                                                        + " ("
                                                        + value.optInt("year")
                                                        + ")"));
                            }
                        }
                    } catch (Exception ignored) {
                    }
                    RecentMedia.MAIN.post(() -> done.accept(choices));
                });
    }

    static void selectMatch(Context c, RecentMedia.Item item, long id) {
        NET.execute(
                () -> {
                    prefs(c).edit().putLong(mappingKey(item), id).commit();
                    try {
                        JSONArray q = queue(c);
                        for (int n = 0; n < q.length(); n++) {
                            JSONObject e = q.getJSONObject(n);
                            if (mappingKey(RecentMedia.Item.read(e.getJSONObject("item")))
                                    .equals(mappingKey(item))) e.remove("retry_at");
                        }
                        prefs(c).edit().putString("queue", q.toString()).commit();
                        nextSync = 0;
                        sync(c);
                    } catch (Exception ignored) {
                    }
                });
    }

    private static long resolve(Context c, RecentMedia.Item item) throws Exception {
        boolean episode = item.season >= 0 && item.episode > 0;
        String type = episode ? "show" : "movie";
        long match = prefs(c).getLong(mappingKey(item), 0);
        JSONArray found =
                match == 0
                        ? new JSONArray(
                                request(
                                        c,
                                        "GET",
                                        "/search/"
                                                + type
                                                + "?query="
                                                + enc(item.title)
                                                + "&limit=10",
                                        null))
                        : new JSONArray();
        for (int n = 0; n < found.length(); n++) {
            JSONObject value = found.getJSONObject(n).optJSONObject(type);
            if (value == null) continue;
            // A provider's episode release year is not the show's premiere year.
            if (!episode && item.year > 1900 && value.optInt("year") != item.year) continue;
            boolean exact = normalize(item.title).equals(normalize(value.optString("title")));
            long id = value.getJSONObject("ids").getLong("trakt");
            if (!exact && n < 3) {
                JSONArray aliases =
                        new JSONArray(
                                request(
                                        c,
                                        "GET",
                                        "/"
                                                + (episode ? "shows" : "movies")
                                                + "/"
                                                + id
                                                + "/aliases",
                                        null));
                for (int a = 0; a < aliases.length(); a++)
                    if (normalize(item.title)
                            .equals(normalize(aliases.getJSONObject(a).optString("title")))) {
                        exact = true;
                        break;
                    }
            }
            if (!exact) continue;
            if (match != 0 && match != id) return 0;
            match = id;
        }
        if (match == 0) return 0;
        if (episode) {
            JSONObject ep =
                    new JSONObject(
                            request(
                                    c,
                                    "GET",
                                    "/shows/"
                                            + match
                                            + "/seasons/"
                                            + item.season
                                            + "/episodes/"
                                            + item.episode
                                            + "?extended=full",
                                    null));
            long duration = ep.optLong("runtime") * 60000;
            if (duration > 0 && Math.abs(duration - item.duration) > 300000) return 0;
            return ep.getJSONObject("ids").getLong("trakt");
        }
        return match;
    }

    static String normalize(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }

    private static String request(Context c, String method, String path, JSONObject body)
            throws Exception {
        if (prefs(c).getLong("expires", 0) - System.currentTimeMillis() < 60000) refresh(c);
        try {
            return call(c, method, path, body, true);
        } catch (Failure e) {
            if (e.status != 401) throw e;
            refresh(c);
            return call(c, method, path, body, true);
        }
    }

    private static void refresh(Context c) throws Exception {
        String token = prefs(c).getString("refresh", "");
        if (token.isEmpty()) throw new Failure(401, 60);
        try {
            JSONObject result =
                    new JSONObject(
                            call(
                                    c,
                                    "POST",
                                    "/oauth/token",
                                    new JSONObject()
                                            .put("client_id", client(c))
                                            .put("refresh_token", token)
                                            .put("grant_type", "refresh_token")
                                            .put("redirect_uri", REDIRECT),
                                    false));
            saveTokens(c, result, false);
        } catch (Exception e) {
            // A timed-out rotation may have consumed the single-use token. Reconnect instead of
            // reusing it.
            prefs(c).edit()
                    .remove("access")
                    .remove("refresh")
                    .putBoolean("sync", false)
                    .putString("status", "auth")
                    .commit();
            throw e;
        }
    }

    private static final class Failure extends Exception {
        final int status, retry;

        Failure(int status, int retry) {
            this.status = status;
            this.retry = retry;
        }
    }

    private static String call(Context c, String method, String path, JSONObject body, boolean auth)
            throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(API + path).openConnection();
        try {
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod(method);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("trakt-api-version", "2");
            conn.setRequestProperty("trakt-api-key", client(c));
            conn.setRequestProperty(
                    "User-Agent", "FreeMyTV/1.2 (+https://github.com/simbasjungles/free-my-tv)");
            if (auth)
                conn.setRequestProperty(
                        "Authorization", "Bearer " + prefs(c).getString("access", ""));
            if (body != null) {
                conn.setDoOutput(true);
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(bytes.length);
                try (java.io.OutputStream out = conn.getOutputStream()) {
                    out.write(bytes);
                }
            }
            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                int retry = 60;
                try {
                    retry =
                            Math.max(
                                    1,
                                    Math.min(
                                            86400,
                                            Integer.parseInt(conn.getHeaderField("Retry-After"))));
                } catch (Exception ignored) {
                }
                throw new Failure(status, retry);
            }
            if (status == 204) return "{}";
            try (InputStream in = conn.getInputStream();
                    ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] bytes = new byte[4096];
                for (int n; (n = in.read(bytes)) >= 0; ) {
                    if (out.size() + n > 1048576) throw new java.io.IOException();
                    out.write(bytes, 0, n);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            conn.disconnect();
        }
    }
}
