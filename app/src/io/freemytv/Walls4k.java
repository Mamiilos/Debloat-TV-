package io.freemytv;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 4K wallpapers for the Apple TV theme, sent by tools/walls4k.py: for each one a still 3840×2160 HEVC video (NAME.mp4),
 * shown on the TV's video layer at the panel's own size, and NAME.jpg at 1920×1080 for the frosted glass and the
 * moment before the video shows. Android draws its interface at 1080p on most 4K TVs, so only video reaches 4K.
 * A time of day in the name (dawn, day, dusk, night) works as in My Photos (Photos.forNow); one per hour.
 */
final class Walls4k {
    private Walls4k() {}

    private static File dir(Context c) {
        return c.getExternalFilesDir("walls4k");
    }

    /** {video, still} for now, or null when none were sent. */
    static File[] now(Context c) {
        File d = dir(c);
        File[] all = d == null ? null : d.listFiles();
        if (all == null) return null;
        List<File> videos = new ArrayList<>();
        for (File f : all) {
            String n = f.getName();
            if (n.endsWith(".mp4") && new File(d, n.substring(0, n.length() - 4) + ".jpg").isFile()) videos.add(f);
        }
        if (videos.isEmpty()) return null;
        Collections.sort(videos);
        List<File> list = Photos.forNow(videos);
        File video = list.get((int) ((System.currentTimeMillis() / 3_600_000L) % list.size()));
        String n = video.getName();
        return new File[] {video, new File(d, n.substring(0, n.length() - 4) + ".jpg")};
    }
}
