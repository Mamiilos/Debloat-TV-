package io.freemytv;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Select each visible page separately so rotation cannot turn a mixed row into only one source. */
final class RecommendationMix {
    interface Candidate {
        String identity();

        String family();

        boolean personal();

        boolean library();
    }

    static <T extends Candidate> List<T> choose(
            List<T> candidates, int count, long turn, boolean personalized) {
        List<T> related = new ArrayList<>(), popular = new ArrayList<>(), out = new ArrayList<>();
        for (T item : candidates) {
            if (item.personal()) {
                if (personalized) related.add(item);
            } else popular.add(item);
        }
        Set<String> ids = new HashSet<>(), families = new HashSet<>();
        // Until personal candidates are ready, preserve the existing hourly page and its cached images.
        long offset = personalized && !related.isEmpty() ? turn : turn * count;
        for (int slot = 0; slot < count; slot++) {
            boolean preferRelated =
                    personalized && Math.floorMod((count < 3 ? turn * count : 0) + slot, 3L) != 2;
            T next = take(preferRelated ? related : popular, offset, count, out, ids, families);
            if (next == null)
                next = take(preferRelated ? popular : related, offset, count, out, ids, families);
            if (next == null) break;
            out.add(next);
            ids.add(next.identity());
            families.add(next.family());
        }
        return out;
    }

    private static <T extends Candidate> T take(
            List<T> pool,
            long turn,
            int count,
            List<T> out,
            Set<String> ids,
            Set<String> families) {
        if (pool.isEmpty()) return null;
        boolean hasLibrary = false;
        for (T item : out) hasLibrary |= item.library();
        int start = (int) Math.floorMod(turn, (long) pool.size());
        for (int i = 0; i < pool.size(); i++) {
            T item = pool.get((start + i) % pool.size());
            if (!ids.contains(item.identity())
                    && !families.contains(item.family())
                    && (!item.library() || !hasLibrary)) return item;
        }
        return null;
    }

    /** Progress is an interest signal, not proof of a completed watch. */
    static boolean enoughProgress(long position, long duration) {
        return duration >= 300000 && position >= Math.min(600000, Math.max(300000, duration / 5));
    }
}
