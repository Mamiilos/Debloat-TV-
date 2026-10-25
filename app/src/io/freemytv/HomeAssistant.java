package io.freemytv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Home Assistant in the bottom drawer: a few entities picked on the computer (tools/homeassistant.py), each a button
 * that toggles it (lights, switches, fans, covers), runs it (scenes, scripts, buttons) or shows its value (sensors;
 * locks only show theirs: a remote's stray OK must not open a door).
 * The address, the long-lived token and the list come over adb into HaInbox (homeassistant.json, private to the app):
 * nothing is in the APK or in Prefs. States come from HA's REST API when the drawer opens and after a press.
 */
final class HomeAssistant {
    private HomeAssistant() {}

    static final String FILE = "homeassistant.json";
    private static final long STALE_MS = 15_000;
    private static final ExecutorService NET = Executors.newSingleThreadExecutor();

    private static final List<String> TOGGLE = Arrays.asList("light", "switch", "fan", "input_boolean", "automation",
            "siren", "humidifier", "media_player");
    private static final List<String> RUN = Arrays.asList("scene", "script");
    private static final List<String> PRESS = Arrays.asList("button", "input_button");
    private static final List<String> ON = Arrays.asList("on", "open", "opening", "playing", "home");

    /** What Home Settings lists to pick from: what OK can change, and what shows a value. */
    private static final List<String> LISTED = Arrays.asList("light", "switch", "fan", "input_boolean", "automation",
            "siren", "humidifier", "media_player", "cover", "scene", "script", "button", "input_button", "sensor",
            "binary_sensor", "lock");
    /** Buttons the drawer holds, picked in Home Settings (more get too narrow to read). */
    static final int MAX = 8;

    private static final class Entity {
        String id, custom = "", name = "", state = "", unit = "";   // custom: a name given on the computer
    }

    private static long configAt = -1;
    private static String url = "", token = "";
    private static List<Entity> entities = Collections.emptyList();
    private static long fetchedAt;
    private static boolean fetching;

    private static String domain(String id) {
        int dot = id.indexOf('.');
        return dot < 0 ? "" : id.substring(0, dot);
    }

    /** Something to do on OK; otherwise the button only shows the entity's value. */
    private static boolean pressable(String id) {
        String d = domain(id);
        return TOGGLE.contains(d) || d.equals("cover") || RUN.contains(d) || PRESS.contains(d);
    }

    private static String symbol(String id) {
        switch (domain(id)) {
            case "light": return "lightbulb";
            case "fan": return "fan";
            case "cover": return "blinds";
            case "scene": case "script": return "sparkles";
            case "button": case "input_button": return "play";
            case "media_player": return "speaker";
            case "switch": case "input_boolean": case "automation": case "siren": case "humidifier": return "toggle_on";
            default: return "sensors";
        }
    }

    /** Reads homeassistant.json again when HaInbox wrote a new one (or removed it). */
    private static synchronized void load(Context c) {
        File f = new File(c.getFilesDir(), FILE);
        // With the length too: Home Settings and the computer can write it twice within the clock's resolution.
        long at = f.exists() ? f.lastModified() * 31 + f.length() : 0;
        if (at == configAt) return;
        configAt = at;
        url = "";
        token = "";
        entities = Collections.emptyList();
        fetchedAt = 0;
        if (at == 0) return;
        try (InputStream in = new FileInputStream(f)) {
            JSONObject o = new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
            String u = o.optString("url").trim();
            while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
            if (!u.startsWith("http://") && !u.startsWith("https://")) throw new IOException("url must start with http:// or https://");
            JSONArray list = o.optJSONArray("entities");
            List<Entity> out = new ArrayList<>();
            for (int i = 0; list != null && i < list.length(); i++) {
                Entity e = new Entity();
                JSONObject one = list.optJSONObject(i);
                e.id = one != null ? one.optString("id") : list.optString(i);
                if (one != null) e.custom = e.name = one.optString("name");
                if (e.id.contains(".")) out.add(e);
            }
            url = u;
            token = o.optString("token").trim();
            entities = out;
            AppLog.w("homeassistant: " + out.size() + " entities from " + u);
        } catch (Exception e) {
            AppLog.w("homeassistant: bad " + FILE + ": " + e.getMessage());
        }
    }

    /** The address and token came from a computer: Home Settings can list and pick the entities. */
    static synchronized boolean connected(Context c) {
        load(c);
        return !url.isEmpty() && !token.isEmpty();
    }

    static synchronized boolean configured(Context c) {
        return connected(c) && !entities.isEmpty();
    }

    /** The picked entity ids, in drawer order. */
    static synchronized List<String> entityIds(Context c) {
        load(c);
        List<String> out = new ArrayList<>();
        for (Entity e : entities) out.add(e.id);
        return out;
    }

    /**
     * Home Settings' picks: written with the address and token (a name given on the computer stays), where every
     * process reads them the next time it looks.
     */
    static synchronized void setEntities(Context c, List<String> ids) {
        load(c);
        if (url.isEmpty()) return;
        Map<String, String> custom = new HashMap<>();
        for (Entity e : entities) custom.put(e.id, e.custom);
        try {
            JSONArray list = new JSONArray();
            for (String id : ids) {
                String name = custom.get(id);
                list.put(new JSONObject().put("id", id).put("name", name == null ? "" : name));
            }
            byte[] data = new JSONObject().put("url", url).put("token", token).put("entities", list).toString()
                    .getBytes(StandardCharsets.UTF_8);
            File dir = c.getFilesDir(), part = new File(dir, "." + FILE + ".settings");
            try (FileOutputStream out = new FileOutputStream(part)) {
                out.write(data);
            }
            if (!part.renameTo(new File(dir, FILE))) throw new IOException("can't save " + FILE);
        } catch (Exception e) {
            AppLog.w("homeassistant: " + e.getMessage());
        }
    }

    /**
     * Everything Home Assistant has that the drawer can show, as {entity id, name}, by name; null when it can't be
     * reached. Network: never on the UI thread.
     */
    static List<String[]> available(Context c) {
        String u, t;
        synchronized (HomeAssistant.class) {
            load(c);
            u = url;
            t = token;
        }
        if (u.isEmpty()) return null;
        try {
            JSONArray all = new JSONArray(call(u, t, "GET", "/api/states", null));
            List<String[]> out = new ArrayList<>();
            for (int i = 0; i < all.length(); i++) {
                JSONObject o = all.optJSONObject(i);
                String id = o == null ? "" : o.optString("entity_id");
                if (!LISTED.contains(domain(id))) continue;
                JSONObject attrs = o.optJSONObject("attributes");
                String name = attrs == null ? "" : attrs.optString("friendly_name");
                out.add(new String[] {id, name.isEmpty() ? id : name});
            }
            Collections.sort(out, (a, b) -> a[1].compareToIgnoreCase(b[1]));
            return out;
        } catch (Exception e) {
            AppLog.w("homeassistant: list: " + e.getMessage());
            return null;
        }
    }

    /** The drawer's buttons, from the last states read (call refresh for newer ones). Any thread. */
    static synchronized List<HomeApps.Item> items(Context c) {
        List<HomeApps.Item> out = new ArrayList<>();
        if (!Prefs.homeAssistant() || !configured(c)) return out;
        for (Entity e : entities) {
            HomeApps.Item it = new HomeApps.Item();
            it.kind = HomeApps.HOME_ASSISTANT;
            it.entity = e.id;
            it.label = e.name.isEmpty() ? e.id : e.name;
            it.symbol = symbol(e.id);
            it.connected = ON.contains(e.state);
            if (!pressable(e.id)) it.value = value(e);
            it.key = "ha:" + e.id + "=" + e.state;
            out.add(it);
        }
        return out;
    }

    private static String value(Entity e) {
        switch (e.state) {
            case "": case "unknown": case "unavailable": return "–";
            case "on": return Ui.tr("On");
            case "off": return Ui.tr("Off");
            default: return e.unit.isEmpty() ? e.state : e.state + " " + e.unit;
        }
    }

    /** Reads every entity's state in the background (unless it just did), then runs done. */
    static void refresh(Context c, Runnable done) {
        synchronized (HomeAssistant.class) {
            if (!configured(c) || fetching || System.currentTimeMillis() - fetchedAt < STALE_MS) return;
            fetching = true;
        }
        NET.execute(() -> {
            boolean changed = fetch();
            synchronized (HomeAssistant.class) {
                fetching = false;
                fetchedAt = System.currentTimeMillis();
            }
            if (changed) done.run();
        });
    }

    private static boolean fetch() {
        String u, t;
        List<Entity> list;
        synchronized (HomeAssistant.class) {
            u = url;
            t = token;
            list = entities;
        }
        Map<String, Entity> read = new HashMap<>();
        for (Entity e : list) {
            Entity n = new Entity();
            n.id = e.id;
            n.custom = e.custom;
            n.name = e.name;
            n.state = "unavailable";
            try {
                JSONObject o = new JSONObject(call(u, t, "GET", "/api/states/" + URLEncoder.encode(e.id, "UTF-8"), null));
                JSONObject attrs = o.optJSONObject("attributes");
                n.state = o.optString("state");
                if (attrs != null) {
                    if (n.custom.isEmpty()) n.name = attrs.optString("friendly_name");
                    n.unit = attrs.optString("unit_of_measurement");
                }
            } catch (Exception ex) {
                AppLog.w("homeassistant: " + e.id + ": " + ex.getMessage());
            }
            read.put(e.id, n);
        }
        synchronized (HomeAssistant.class) {
            if (entities != list) return false;   // a new config came meanwhile: its own refresh follows
            boolean changed = false;
            List<Entity> out = new ArrayList<>();
            for (Entity e : list) {
                Entity n = read.get(e.id);
                changed |= !n.state.equals(e.state) || !n.unit.equals(e.unit) || !n.name.equals(e.name);
                out.add(n);
            }
            entities = out;
            return changed;
        }
    }

    /** OK on an entity's button: toggles it, runs it or presses it; done gets whether HA took it. */
    static void press(Context c, String id, Consumer<Boolean> done) {
        String u, t;
        synchronized (HomeAssistant.class) {
            load(c);
            u = url;
            t = token;
        }
        String d = domain(id);
        String service = TOGGLE.contains(d) ? "homeassistant/toggle" : d.equals("cover") ? "cover/toggle"
                : RUN.contains(d) ? d + "/turn_on" : PRESS.contains(d) ? d + "/press" : null;
        if (service == null || u.isEmpty()) return;
        NET.execute(() -> {
            boolean ok;
            try {
                call(u, t, "POST", "/api/services/" + service, new JSONObject().put("entity_id", id).toString());
                ok = true;
            } catch (Exception e) {
                AppLog.w("homeassistant: " + service + " " + id + ": " + e.getMessage());
                ok = false;
            }
            synchronized (HomeAssistant.class) {
                fetchedAt = 0;   // read the new states at once
            }
            done.accept(ok);
        });
    }

    /** One REST call; the body of a 2xx answer, else an IOException with the status. */
    private static String call(String base, String token, String method, String path, String body) throws IOException {
        URL u = new URL(base + path);
        byte[] data = body == null ? null : body.getBytes(StandardCharsets.UTF_8);
        if (u.getProtocol().equals("http")) return plainCall(u, token, method, data);
        HttpURLConnection con = (HttpURLConnection) u.openConnection();
        try {
            con.setConnectTimeout(4000);
            con.setReadTimeout(6000);
            con.setRequestMethod(method);
            con.setRequestProperty("Authorization", "Bearer " + token);
            if (data != null) {
                con.setDoOutput(true);
                con.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = con.getOutputStream()) {
                    out.write(data);
                }
            }
            int status = con.getResponseCode();
            if (status / 100 != 2) throw new IOException("HTTP " + status);
            try (InputStream in = con.getInputStream()) {
                return new String(readAll(in), StandardCharsets.UTF_8);
            }
        } finally {
            con.disconnect();
        }
    }

    /**
     * http:// (most HA installs on the home network) over a plain socket: the app keeps cleartext off
     * (usesCleartextTraffic), and that stays true for everything else. HTTP/1.0, so the answer isn't chunked.
     */
    private static String plainCall(URL u, String token, String method, byte[] data) throws IOException {
        int port = u.getPort() < 0 ? 80 : u.getPort();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(u.getHost(), port), 4000);
            s.setSoTimeout(6000);
            StringBuilder req = new StringBuilder();
            req.append(method).append(' ').append(u.getFile()).append(" HTTP/1.0\r\n")
                    .append("Host: ").append(u.getHost()).append(':').append(port).append("\r\n")
                    .append("Authorization: Bearer ").append(token).append("\r\n")
                    .append("Connection: close\r\n");
            if (data != null) req.append("Content-Type: application/json\r\nContent-Length: ").append(data.length).append("\r\n");
            req.append("\r\n");
            OutputStream out = s.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.UTF_8));
            if (data != null) out.write(data);
            out.flush();
            String all = new String(readAll(s.getInputStream()), StandardCharsets.UTF_8);
            int end = all.indexOf("\r\n\r\n");
            String[] status = all.substring(0, Math.max(0, all.indexOf("\r\n"))).split(" ");
            if (end < 0 || status.length < 2 || !status[1].startsWith("2")) {
                throw new IOException(status.length > 1 ? "HTTP " + status[1] : "no answer");
            }
            return all.substring(end + 4);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) {
            out.write(buf, 0, n);
            if (out.size() > 16 << 20) throw new IOException("answer too large");   // /api/states of a big home: a few MB
        }
        return out.toByteArray();
    }
}
