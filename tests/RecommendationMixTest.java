package io.freemytv;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

public final class RecommendationMixTest {
    private static int assertions;

    private static void check(boolean value, String why) {
        assertions++;
        if (!value) throw new AssertionError(why);
    }

    static final class Pick implements RecommendationMix.Candidate {
        final String id, family;
        final boolean personal, library;

        Pick(String id, String family, boolean personal, boolean library) {
            this.id = id;
            this.family = family;
            this.personal = personal;
            this.library = library;
        }

        public String identity() {
            return id;
        }

        public String family() {
            return family;
        }

        public boolean personal() {
            return personal;
        }

        public boolean library() {
            return library;
        }
    }

    private static long personal(List<Pick> list) {
        return list.stream().filter(Pick::personal).count();
    }

    public static void main(String[] args) {
        List<Pick> picks = new ArrayList<>();
        for (int n = 0; n < 6; n++) {
            picks.add(new Pick("p" + n, "p" + n, true, false));
            picks.add(new Pick("t" + n, "t" + n, false, false));
        }
        for (int hour = 0; hour < 6; hour++) {
            List<Pick> three = RecommendationMix.choose(picks, 3, hour, true);
            check(
                    three.size() == 3 && personal(three) == 2,
                    "two related and one trending at every hour");
            List<Pick> six = RecommendationMix.choose(picks, 6, hour, true);
            check(six.size() == 6 && personal(six) == 4, "four related and two trending");
        }
        check(
                personal(RecommendationMix.choose(picks, 3, 0, false)) == 0,
                "off excludes cached personal cards");
        List<Pick> general = new ArrayList<>(), related = new ArrayList<>();
        for (Pick p : picks) (p.personal ? related : general).add(p);
        check(
                RecommendationMix.choose(general, 6, 0, true).size() == 6,
                "no history keeps all general picks");
        check(
                RecommendationMix.choose(related, 6, 0, true).size() == 6,
                "general shortage fills from related");
        check(
                RecommendationMix.choose(related, 6, 0, false).isEmpty(),
                "no personal fallback while off");
        List<Pick> duplicates =
                Arrays.asList(
                        new Pick("a", "same", true, false),
                        new Pick("b", "same", true, false),
                        new Pick("a", "different", false, false),
                        new Pick("x", "x", false, true),
                        new Pick("y", "y", false, true));
        List<Pick> unique = RecommendationMix.choose(duplicates, 6, 0, true);
        check(unique.size() == 2, "deduplicates identity, franchise and library picks");
        HashSet<String> rotated = new HashSet<>();
        for (int turn = 0; turn < 3; turn++)
            for (Pick p : RecommendationMix.choose(related.subList(0, 3), 2, turn, true))
                rotated.add(p.id);
        check(rotated.size() == 3, "rotation reaches every candidate in a three-title pool");
        int smallPersonal = 0;
        for (int turn = 0; turn < 3; turn++)
            smallPersonal += personal(RecommendationMix.choose(picks, 1, turn, true));
        check(smallPersonal == 2, "one-card layouts mix across hours");
        check(
                !RecommendationMix.enoughProgress(15000, 2400000),
                "brief opening is not an interest seed");
        check(!RecommendationMix.enoughProgress(600000, 0), "unknown duration is not a seed");
        check(!RecommendationMix.enoughProgress(90000, 120000), "short clips are not seeds");
        check(
                RecommendationMix.enoughProgress(480000, 2400000),
                "substantial episode progress is a signal");
        check(
                RecommendationMix.enoughProgress(600000, 7200000),
                "long movies need at least ten minutes");
        check(
                RecommendationMix.choose(new ArrayList<Pick>(), 3, 0, true).isEmpty(),
                "empty inputs stay empty");
        System.out.println("RecommendationMix: " + assertions + " assertions passed");
    }
}
