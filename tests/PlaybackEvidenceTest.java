package io.freemytv;

public final class PlaybackEvidenceTest {
    private static int assertions;

    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        PlaybackEvidence normal = new PlaybackEvidence();
        for (long t = 0; t <= 480000; t += 5000) normal.sample(t, true, t);
        check(normal.covered(600000) == 480000, "normal playback coverage");
        check(normal.completed(600000), "80 percent is eligible");
        check(!normal.completed(610000), "below threshold stays pending");
        PlaybackEvidence seek = new PlaybackEvidence();
        seek.sample(0, true, 0);
        seek.sample(5000, true, 5000);
        check(
                seek.discontinuity(590000, 10000),
                "seek detected before changing the previous session");
        check(seek.position == 5000, "checking a boundary preserves the old episode position");
        seek.sample(590000, true, 10000);
        seek.sample(595000, true, 15000);
        check(seek.covered(600000) == 10000, "seeking never earns skipped minutes");
        check(!seek.completed(600000), "seeking to credits is not a completed watch");
        PlaybackEvidence paused = new PlaybackEvidence();
        paused.sample(0, true, 0);
        paused.sample(5000, false, 5000);
        paused.sample(5000, false, 600000);
        check(paused.covered(600000) == 5000, "paused wall time never earns coverage");
        paused.sample(5000, true, 605000);
        paused.sample(10000, true, 610000);
        check(paused.covered(600000) == 10000, "resume starts a new measured interval");
        PlaybackEvidence asleep = new PlaybackEvidence();
        asleep.sample(0, true, 0);
        asleep.sample(300000, true, 300000);
        check(asleep.covered(600000) == 0, "process suspension is not evidence of playback");
        PlaybackEvidence repeat = new PlaybackEvidence();
        repeat.add(0, 120000);
        repeat.add(60000, 180000);
        repeat.add(0, 180000);
        check(
                repeat.covered(600000) == 180000,
                "rewatching a range does not double-count coverage");
        repeat.add(590000, 650000);
        check(repeat.covered(600000) == 190000, "coverage is clipped to duration");
        check(!repeat.completed(0), "unknown duration cannot complete");
        PlaybackEvidence unknown = new PlaybackEvidence();
        unknown.sample(-1, true, 0);
        unknown.sample(5000, true, 5000);
        check(unknown.covered(600000) == 0, "unknown position cannot establish an interval");
        check(
                PlaybackEvidence.matchesExit(184000, 184000, 1000, 2000),
                "observed episode exit matches");
        check(
                !PlaybackEvidence.matchesExit(184000, 1544000, 1000, 2000),
                "stale provider position does not match the new episode");
        check(
                !PlaybackEvidence.matchesExit(184000, 184000, 1000, 122000),
                "old exit is not attributed to a later row");
        check(
                !PlaybackEvidence.matchesExit(184000, 0, 1000, 2000),
                "next-episode position zero is not the previous watch");
        System.out.println("PlaybackEvidence: " + assertions + " assertions passed");
    }
}
