package io.freemytv;

import android.app.Application;
import android.graphics.Bitmap;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.File;
import java.io.FileOutputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 28, application = Application.class)
public class MaInfoWidgetTest {
    private static final long NOW = Instant.parse("2026-10-24T13:30:00Z").toEpochMilli();
    private static final String[] LABELS = {"Карта тривог", "Немає даних тривог", "Дані тривог застаріли",
        "Немає актуальних даних", "Є відключення сьогодні", "Відключень до кінця дня немає",
        "Оновіть MaInfo", "Група", "Активних тривог немає", "Є активні тривоги", "Частина тривог поза картою"};

    private JSONObject snapshot() throws Exception {
        return new JSONObject().put("version", 1).put("regionName", "Львівська область").put("group", "1.1")
            .put("selectedArea", "state-27").put("alertsAt", NOW).put("powerAt", NOW).put("powerSourceAt", NOW)
            .put("levels", new JSONObject().put("state-22", 2).put("state-12", 1))
            .put("available", new JSONObject().put("2026-10-24", true))
            .put("days", new JSONObject().put("2026-10-24", new JSONArray().put(new JSONObject().put("from", "18:00").put("to", "20:00"))));
    }
    private void save(Bitmap image, String name) throws Exception {
        File file = new File("build/outputs/freemytv/" + name + ".png"); file.getParentFile().mkdirs();
        try (FileOutputStream stream = new FileOutputStream(file)) { image.compress(Bitmap.CompressFormat.PNG, 100, stream); }
    }
    @Test public void rendersActualMapAtBothTvResolutionsAndShowsUpcomingInterval() throws Exception {
        MaInfoBridge bridge = new MaInfoBridge(RuntimeEnvironment.getApplication(), () -> {});
        try {
            bridge.loadMap(new JSONObject(new String(getClass().getResourceAsStream("/mainfo/alert_map.json").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
            MaInfoBridge.Result result = bridge.render(snapshot(), true, LABELS, 840, NOW);
            assertTrue(result.description.contains("18:00–20:00"));
            assertEquals(0xFFF6C965, result.bitmap.getPixel(42, 459));
            save(result.bitmap, "upcoming-1080p");
            MaInfoBridge.Result small = bridge.render(snapshot(), true, LABELS, 560, NOW);
            assertEquals(333, small.bitmap.getHeight()); save(small.bitmap, "upcoming-720p");
            int red = 0, yellow = 0;
            for (int y = 55; y < 400; y++) for (int x = 100; x < 740; x++) {
                int pixel = result.bitmap.getPixel(x, y);
                if (pixel == 0xFFEF5350) red++;
                if (pixel == 0xFFF6C965) yellow++;
            }
            assertTrue("Red map area must be drawn", red > 100);
            assertTrue("Yellow map area must be drawn", yellow > 100);
        } finally { bridge.close(); }
    }
    @Test public void greenRequiresFreshConfirmedScheduleAndNoRemainingIntervals() throws Exception {
        MaInfoBridge bridge = new MaInfoBridge(RuntimeEnvironment.getApplication(), () -> {});
        try {
            bridge.loadMap(new JSONObject(new String(getClass().getResourceAsStream("/mainfo/alert_map.json").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
            JSONObject data = snapshot().put("days", new JSONObject().put("2026-10-24", new JSONArray()));
            MaInfoBridge.Result green = bridge.render(data, true, LABELS, 840, NOW);
            assertEquals(0xFF67E8C0, green.bitmap.getPixel(42, 459)); save(green.bitmap, "no-outages");
            data.put("available", new JSONObject().put("2026-10-24", false));
            assertFalse(bridge.render(data, true, LABELS, 840, NOW).description.contains(LABELS[5]));
            data.put("available", new JSONObject().put("2026-10-24", true)).put("powerAt", NOW - 600_001);
            assertFalse(bridge.render(data, true, LABELS, 840, NOW).description.contains(LABELS[5]));
            data.put("powerAt", NOW).put("powerSourceAt", NOW - 5_400_001);
            assertFalse(bridge.render(data, true, LABELS, 840, NOW).description.contains(LABELS[5]));
            assertFalse(bridge.render(snapshot(), false, LABELS, 840, NOW).description.contains(LABELS[5]));
            JSONObject stale = snapshot().put("alertsAt", NOW - 120_001).put("powerAt", NOW - 600_001);
            save(bridge.render(stale, true, LABELS, 840, NOW).bitmap, "stale");
        } finally { bridge.close(); }
    }
    @Test public void scheduleUsesKyivDateAndIntervalEndsIncludingMidnightAndDst() {
        LocalDate today = LocalDate.of(2026, 10, 24);
        PowerSchedule.Interval item = new PowerSchedule.Interval(today, "16:00", "18:00");
        assertSame(item, PowerSchedule.remaining(Collections.singletonList(item), NOW));
        assertNull(PowerSchedule.remaining(Collections.singletonList(item), Instant.parse("2026-10-24T15:00:00Z").toEpochMilli()));
        PowerSchedule.Interval midnight = new PowerSchedule.Interval(today, "23:00", "24:00");
        assertSame(midnight, PowerSchedule.remaining(Collections.singletonList(midnight), NOW));
        assertNull(PowerSchedule.remaining(Collections.singletonList(midnight), Instant.parse("2026-10-24T21:00:00Z").toEpochMilli()));
        PowerSchedule.Interval tomorrow = new PowerSchedule.Interval(today.plusDays(1), "08:00", "09:00");
        assertNull(PowerSchedule.remaining(Collections.singletonList(tomorrow), NOW));
        PowerSchedule.Interval overnight = new PowerSchedule.Interval(today, "23:00", "02:00");
        assertSame(overnight, PowerSchedule.remaining(Collections.singletonList(overnight), Instant.parse("2026-10-24T22:00:00Z").toEpochMilli()));
        assertThrows(IllegalArgumentException.class, () -> PowerSchedule.minutes("25:00", true));
        assertThrows(IllegalArgumentException.class, () -> PowerSchedule.minutes("24:00", false));
        assertFalse(PowerSchedule.fresh(NOW + 1, NOW, 120_000));
        assertFalse(PowerSchedule.fresh(0, NOW, 120_000));
        PowerSchedule.Interval dst = new PowerSchedule.Interval(LocalDate.of(2026, 10, 25), "03:00", "04:00");
        assertSame(dst, PowerSchedule.remaining(Collections.singletonList(dst), Instant.parse("2026-10-25T01:30:00Z").toEpochMilli()));
    }
}
