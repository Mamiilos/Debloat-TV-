package io.freemytv;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * When each app was last opened from Free My TV (the shelf, All Apps, the picks, the screensaver), for the shelf's
 * "Last opened first" order (Prefs.shelfRecent). Apps opened some other way (the TV's own buttons, another app) aren't
 * seen: that would need an accessibility service, which only some TVs run. Kept in memory in the home screen's process
 * and saved to a small file ("package<TAB>time" a line), replaced whole on each save so a crash never leaves half of it.
 */
final class Recent {
    private Recent() {}

    private static final String FILE = "recent-apps";
    private static final int MAX = 40;
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor();
    private static Map<String, Long> opened;   // package → when (System.currentTimeMillis); read from the file once
    /** Goes up with every app opened: the home screen orders its shelf again only when it changed. */
    static volatile int version;

    /** The app was just opened (saved in the background). */
    static void note(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        final Context app = c.getApplicationContext();
        final long now = System.currentTimeMillis();
        WRITER.execute(() -> {
            String text;
            synchronized (Recent.class) {
                opened = null;   // read again: the screensaver can open an app from another process
                Map<String, Long> map = load(app);
                map.put(pkg, now);
                List<Map.Entry<String, Long>> newest = newestFirst(map);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < newest.size(); i++) {
                    if (i >= MAX) map.remove(newest.get(i).getKey());
                    else sb.append(newest.get(i).getKey()).append('\t').append(newest.get(i).getValue()).append('\n');
                }
                text = sb.toString();
                version++;
            }
            File dir = app.getFilesDir(), tmp = new File(dir, FILE + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                tmp.delete();
                return;
            }
            if (!tmp.renameTo(new File(dir, FILE))) tmp.delete();
        });
    }

    /** The items opened before first, the most recent first; the others after them, in their own order. */
    static List<HomeApps.Item> order(Context c, List<HomeApps.Item> items) {
        final Map<String, Long> when;
        synchronized (Recent.class) {
            when = new HashMap<>(load(c));
        }
        List<HomeApps.Item> out = new ArrayList<>(items);
        Collections.sort(out, (a, b) -> Long.compare(at(when, b), at(when, a)));   // stable: the rest keep their order
        return out;
    }

    private static long at(Map<String, Long> when, HomeApps.Item it) {
        Long t = it.pkg == null ? null : when.get(it.pkg);
        return t == null ? 0 : t;
    }

    private static List<Map.Entry<String, Long>> newestFirst(Map<String, Long> map) {
        List<Map.Entry<String, Long>> list = new ArrayList<>(map.entrySet());
        Collections.sort(list, (a, b) -> Long.compare(b.getValue(), a.getValue()));
        return list;
    }

    /** Callers hold the lock. Reads the file the first time (a missing or damaged file is an empty list). */
    private static Map<String, Long> load(Context c) {
        if (opened != null) return opened;
        opened = new HashMap<>();
        try {
            String s = new String(Files.readAllBytes(new File(c.getFilesDir(), FILE).toPath()), StandardCharsets.UTF_8);
            for (String line : s.split("\n")) {
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                try {
                    opened.put(line.substring(0, tab), Long.parseLong(line.substring(tab + 1).trim()));
                } catch (NumberFormatException ignored) {
                    // a damaged line: skip it
                }
            }
        } catch (IOException | RuntimeException e) {
            // never saved yet
        }
        return opened;
    }
}
