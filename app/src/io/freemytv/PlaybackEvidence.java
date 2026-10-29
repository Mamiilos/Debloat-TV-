package io.freemytv;

import java.util.ArrayList;
import java.util.List;

/** Only advancing playback earns coverage. Seeking, pausing and time asleep do not. */
final class PlaybackEvidence {
    final List<long[]> ranges = new ArrayList<>();
    long position = -1, elapsed = -1;
    boolean playing;

    boolean discontinuity(long nextPosition, long now) {
        long dt = now - elapsed, advance = nextPosition - position;
        return elapsed >= 0
                && position >= 0
                && nextPosition >= 0
                && (advance < -2500 || advance > Math.max(0, dt) * 2 + 2500);
    }

    boolean sample(long nextPosition, boolean nextPlaying, long now) {
        long dt = now - elapsed, advance = nextPosition - position;
        boolean jump = discontinuity(nextPosition, now);
        if (!jump
                && playing
                && position >= 0
                && nextPosition >= position
                && dt > 0
                && dt <= 15000
                && advance <= dt * 2 + 2500
                && advance > 0) add(position, nextPosition);
        position = nextPosition;
        elapsed = now;
        playing = nextPlaying;
        return jump;
    }

    void add(long from, long to) {
        if (from < 0 || to <= from) return;
        int i = 0;
        while (i < ranges.size() && ranges.get(i)[1] < from) i++;
        while (i < ranges.size() && ranges.get(i)[0] <= to) {
            long[] r = ranges.remove(i);
            from = Math.min(from, r[0]);
            to = Math.max(to, r[1]);
        }
        ranges.add(i, new long[] {from, to});
        // A pathological player must not grow the persistent history without bound.
        if (ranges.size() > 512) ranges.remove(0);
    }

    long covered(long duration) {
        long total = 0;
        for (long[] r : ranges)
            total += Math.max(0, Math.min(duration, r[1]) - Math.min(duration, r[0]));
        return total;
    }

    boolean completed(long duration) {
        return duration >= 300000
                && position >= duration * .8
                && covered(duration) >= duration * .8;
    }

    static boolean matchesExit(long position, long providerPosition, long endedAt, long now) {
        return position >= 120000
                && providerPosition > 0
                && endedAt > 0
                && now >= endedAt
                && now - endedAt <= 120000
                && Math.abs(position - providerPosition) <= 10000;
    }
}
