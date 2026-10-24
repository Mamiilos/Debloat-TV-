package io.freemytv;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.text.TextUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** All IPC, parsing and rasterization happen off the UI thread. onDraw only copies the finished bitmap. */
final class MaInfoBridge {
    private static final Uri URI = Uri.parse("content://ua.pp.ds7.mainfo.freemytv/snapshot");
    private final Context context;
    private final Runnable changed;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Area> areas = new ArrayList<>();
    private boolean busy, closed;
    private volatile Bitmap bitmap;
    private volatile String description = "";
    private long lastAttempt;
    private int lastWidth;
    private String lastLanguage = "";
    private JSONObject lastSnapshot;
    private static final class Area {
        final String key, parent;
        final boolean state;
        final Path path;
        Area(JSONObject json) throws Exception {
            key = json.getString("key"); parent = json.optString("stateKey", "");
            state = "STATE".equals(json.getString("kind")); path = path(json.getString("pathData"));
        }
    }
    MaInfoBridge(Context context, Runnable changed) { this.context = context.getApplicationContext(); this.changed = changed; }

    void refresh(float scale, String language, String[] labels) {
        if (closed || busy || scale <= 0) return;
        int width = Math.max(1, Math.round(840 * scale));
        long now = System.currentTimeMillis();
        if (width == lastWidth && language.equals(lastLanguage) && PowerSchedule.fresh(lastAttempt, now, 14_000)) return;
        lastWidth = width; lastLanguage = language; lastAttempt = now; busy = true;
        io.execute(() -> {
            Bitmap rendered;
            String spoken;
            try {
                loadMap();
                boolean available = false;
                try (Cursor cursor = context.getContentResolver().query(URI, null, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        JSONObject incoming = new JSONObject(cursor.getString(cursor.getColumnIndexOrThrow("json")));
                        if (incoming.getInt("version") != 1) throw new IllegalArgumentException("Unsupported bridge");
                        lastSnapshot = incoming; available = true;
                    }
                } catch (Exception ignored) { }
                JSONObject snapshot = lastSnapshot == null ? new JSONObject() : lastSnapshot;
                Result result = render(snapshot, available, labels, width, now);
                rendered = result.bitmap; spoken = result.description;
            } catch (Exception e) {
                android.util.Log.w("FreeMyTV", "MaInfo: " + e.getClass().getSimpleName());
                Result result = render(new JSONObject(), false, labels, width, now);
                rendered = result.bitmap; spoken = result.description;
            }
            final Bitmap ready = rendered;
            final String text = spoken;
            main.post(() -> {
                busy = false;
                if (closed) { ready.recycle(); return; }
                // Do not recycle an old bitmap still referenced by a hardware display list.
                bitmap = ready; description = text; changed.run();
            });
        });
    }
    void draw(Canvas canvas, float x, float y, float scale) {
        Bitmap ready = bitmap;
        if (ready == null) return;
        // A size change can arrive while the previous rasterization is still running.
        float ratio = 840 * scale / ready.getWidth();
        canvas.save(); canvas.translate(x, y); canvas.scale(ratio, ratio);
        canvas.drawBitmap(ready, 0, 0, null); canvas.restore();
    }
    String description() { return description; }
    void close() { closed = true; io.shutdown(); }

    void loadMap() throws Exception {
        if (!areas.isEmpty()) return;
        loadMap(new JSONObject(asset("mainfo/alert_map.json")));
    }

    void loadMap(JSONObject map) throws Exception {
        JSONArray list = map.getJSONArray("areas");
        List<Area> parsed = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) parsed.add(new Area(list.getJSONObject(i)));
        areas.addAll(parsed);
    }

    private String asset(String name) throws Exception {
        try (InputStream stream = context.getAssets().open(name); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            for (int n; (n = stream.read(buffer)) != -1;) bytes.write(buffer, 0, n);
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }
    static Path path(String data) {
        Path result = new Path(); result.setFillType(Path.FillType.EVEN_ODD);
        Matcher tokens = Pattern.compile("[MLZ]|-?\\d+(?:\\.\\d+)?").matcher(data);
        char command = ' ';
        while (tokens.find()) {
            String token = tokens.group();
            if (token.equals("Z")) { result.close(); continue; }
            if (token.equals("M") || token.equals("L")) { command = token.charAt(0); continue; }
            float x = Float.parseFloat(token);
            if (!tokens.find()) throw new IllegalArgumentException("Incomplete map path");
            float y = Float.parseFloat(tokens.group());
            if (command == 'M') { result.moveTo(x, y); command = 'L'; }
            else if (command == 'L') result.lineTo(x, y);
            else throw new IllegalArgumentException("Unsupported map path");
        }
        return result;
    }
    static final class Result {
        final Bitmap bitmap; final String description;
        Result(Bitmap bitmap, String description) { this.bitmap = bitmap; this.description = description; }
    }
    Result render(JSONObject snapshot, boolean connected, String[] labels, int width, long now) {
        Bitmap image = Bitmap.createBitmap(width, Math.max(1, Math.round(width * 500f / 840)), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(image); canvas.scale(width / 840f, width / 840f);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xED141D2C); canvas.drawRoundRect(new RectF(0, 0, 840, 500), 28, 28, paint);
        text(canvas, labels[0], 26, 39, 25, 0xFFF2F5F9, 400);
        text(canvas, snapshot.optString("regionName"), 430, 38, 19, 0xFF67E8C0, 380);
        long alertsAt = snapshot.optLong("alertsAt");
        boolean freshAlerts = connected && PowerSchedule.fresh(alertsAt, now, 120_000);
        JSONObject levels = snapshot.optJSONObject("levels");
        if (levels == null) levels = new JSONObject();
        canvas.save(); canvas.translate(145, 56); canvas.scale(.55f, .55f);
        for (Area area : areas) if (area.state) {
            paint.setStyle(Paint.Style.FILL); paint.setColor(mapColor(levels.optInt(area.key), alertsAt > 0));
            canvas.drawPath(area.path, paint);
        }
        for (Area area : areas) if (!area.state && levels.optInt(area.key) > 0) {
            paint.setStyle(Paint.Style.FILL); paint.setColor(mapColor(Math.max(levels.optInt(area.key), levels.optInt(area.parent)), alertsAt > 0));
            canvas.drawPath(area.path, paint);
        }
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.1f); paint.setColor(0x707C91AB);
        for (Area area : areas) if (!area.state) canvas.drawPath(area.path, paint);
        paint.setStrokeWidth(2); paint.setColor(0xFF7C91AB);
        for (Area area : areas) if (area.state) canvas.drawPath(area.path, paint);
        String selected = snapshot.optString("selectedArea", snapshot.optString("selectedState"));
        if (selected.isEmpty()) selected = snapshot.optString("selectedState");
        paint.setStrokeWidth(4); paint.setColor(0xFF67E8C0);
        for (Area area : areas) if (area.key.equals(selected)) canvas.drawPath(area.path, paint);
        canvas.restore(); paint.setStyle(Paint.Style.FILL);
        String alertStatus = !freshAlerts ? (alertsAt > 0 ? labels[2] : labels[1])
            : snapshot.optInt("unmatched") > 0 ? labels[10] : levels.length() == 0 ? labels[8] : labels[9];
        text(canvas, alertStatus, 26, 412, 19, freshAlerts ? 0xFFB4C4D8 : 0xFFF6C965, 780);
        String badge = labels[3]; int color = 0xFF9DAABD;
        if (!connected && lastSnapshot == null) badge = labels[6];
        if (connected && PowerSchedule.fresh(snapshot.optLong("powerAt"), now, 600_000)
                && PowerSchedule.fresh(snapshot.optLong("powerSourceAt"), now, 5_400_000)) {
            try {
                String date = Instant.ofEpochMilli(now).atZone(PowerSchedule.ZONE).toLocalDate().toString();
                JSONObject days = snapshot.getJSONObject("days"), available = snapshot.getJSONObject("available");
                if (available.optBoolean(date, false) && days.has(date)) {
                    List<PowerSchedule.Interval> intervals = new ArrayList<>();
                    java.util.Iterator<String> dates = days.keys();
                    while (dates.hasNext()) {
                        String day = dates.next(); JSONArray items = days.getJSONArray(day);
                        for (int i = 0; i < items.length(); i++) {
                            JSONObject item = items.getJSONObject(i);
                            intervals.add(new PowerSchedule.Interval(LocalDate.parse(day), item.getString("from"), item.getString("to")));
                        }
                    }
                    PowerSchedule.Interval next = PowerSchedule.remaining(intervals, now);
                    badge = next == null ? labels[5] : labels[4] + " · " + next.from + "–" + next.to;
                    color = next == null ? 0xFF67E8C0 : 0xFFF6C965;
                }
            } catch (Exception ignored) { }
        }
        paint.setColor((color & 0xFFFFFF) | 0x25000000);
        canvas.drawRoundRect(new RectF(20, 434, 820, 484), 16, 16, paint);
        paint.setColor(color); canvas.drawCircle(42, 459, 6, paint);
        text(canvas, badge, 61, 467, 22, color, 590);
        text(canvas, snapshot.optString("group"), 718, 467, 20, color, 85);
        return new Result(image, labels[0] + ". " + snapshot.optString("regionName") + ". " + alertStatus + ". " + badge + ". " + labels[7] + snapshot.optString("group"));
    }
    private static int mapColor(int level, boolean known) {
        return level >= 2 ? 0xFFEF5350 : level == 1 ? 0xFFF6C965 : known ? 0xFF304963 : 0xFF333C4A;
    }
    private static void text(Canvas canvas, String value, float x, float y, float size, int color, float maxWidth) {
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG); paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        paint.setTextSize(size); paint.setColor(color);
        canvas.drawText(TextUtils.ellipsize(value, paint, maxWidth, TextUtils.TruncateAt.END).toString(), x, y, paint);
    }
}
