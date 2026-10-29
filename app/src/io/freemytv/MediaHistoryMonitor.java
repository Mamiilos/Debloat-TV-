package io.freemytv;

import android.content.Context;
import android.database.ContentObserver;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Background media observations, never notification contents. Unknown episodes remain unknown. */
final class MediaHistoryMonitor {
    static MediaHistoryMonitor instance; // accessed only on RecentMedia.WORK
    private final Context context;
    private final Map<String, Session> active = new HashMap<>();
    private final List<Session> ended = new ArrayList<>();
    private final Map<String, Long> providerChanges = new HashMap<>();
    private ScheduledFuture<?> timer;
    private boolean stopped;
    private final ContentObserver observer =
            new ContentObserver(RecentMedia.MAIN) {
                @Override
                public void onChange(boolean selfChange) {
                    RecentMedia.WORK.execute(
                            () -> {
                                if (!stopped) {
                                    sample();
                                    RecentMedia.refreshNow(context);
                                }
                            });
                }
            };

    private static final class Session {
        String pkg, identity = "";
        RecentMedia.Item item;
        PlaybackEvidence evidence = new PlaybackEvidence();
        long started = System.currentTimeMillis(), endedAt, lastSaved;
        boolean offered;
    }

    MediaHistoryMonitor(Context context) {
        this.context = context.getApplicationContext();
    }

    void start() {
        RecentMedia.WORK.execute(
                () -> {
                    if (stopped || !RecentMedia.enabled(context)) return;
                    instance = this;
                    RecentMedia.load(context);
                    RecentMedia.refreshNow(context);
                    try {
                        context.getContentResolver()
                                .registerContentObserver(RecentMedia.WATCH_NEXT, true, observer);
                    } catch (RuntimeException ignored) {
                    }
                    timer =
                            RecentMedia.WORK.scheduleWithFixedDelay(
                                    this::sample, 0, 5, TimeUnit.SECONDS);
                });
    }

    void stop() {
        RecentMedia.WORK.execute(
                () -> {
                    stopped = true;
                    if (timer != null) timer.cancel(false);
                    try {
                        context.getContentResolver().unregisterContentObserver(observer);
                    } catch (RuntimeException ignored) {
                    }
                    clear();
                    if (instance == this) instance = null;
                });
    }

    void clear() {
        active.clear();
        ended.clear();
        providerChanges.clear();
    }

    private void sample() {
        if (stopped || !RecentMedia.enabled(context)) {
            stop();
            return;
        }
        try {
            long wall = System.currentTimeMillis(), elapsed = SystemClock.elapsedRealtime();
            List<MediaController> controllers =
                    context.getSystemService(MediaSessionManager.class)
                            .getActiveSessions(NowPlaying.component(context));
            Map<String, Boolean> seen = new HashMap<>();
            for (MediaController c : controllers) {
                String pkg = c.getPackageName();
                PlaybackState state = c.getPlaybackState();
                if (state == null
                        || state.getState() != PlaybackState.STATE_PLAYING
                                && state.getState() != PlaybackState.STATE_PAUSED
                                && state.getState() != PlaybackState.STATE_BUFFERING) continue;
                if (seen.put(pkg, true) != null) continue;
                boolean playing = state.getState() == PlaybackState.STATE_PLAYING;
                long position = state.getPosition();
                // Extrapolate only a fresh playing state; a stale or destroyed session never earns
                // hours of coverage.
                long age = elapsed - state.getLastPositionUpdateTime();
                if (playing && position >= 0 && age >= 0 && age < 15000)
                    position += (long) (age * state.getPlaybackSpeed());
                MediaMetadata metadata = c.getMetadata();
                MediaDescription description = metadata == null ? null : metadata.getDescription();
                String title =
                        description == null || description.getTitle() == null
                                ? ""
                                : description.getTitle().toString().trim();
                String mediaId =
                        description == null || description.getMediaId() == null
                                ? ""
                                : description.getMediaId();
                String identity = title.isEmpty() ? "" : title + "|" + mediaId;
                Session s = active.get(pkg);
                if (s != null && !s.identity.equals(identity)) {
                    finish(s, wall);
                    active.remove(pkg);
                    s = null;
                }
                if (s == null) {
                    if (!playing) continue;
                    s = new Session();
                    s.pkg = pkg;
                    s.identity = identity;
                    active.put(pkg, s);
                }
                boolean jump = s.evidence.discontinuity(position, elapsed);
                if (jump) {
                    // With no title, a seek and a different episode cannot be distinguished.
                    finish(s, wall);
                    s = new Session();
                    s.pkg = pkg;
                    s.identity = identity;
                    active.put(pkg, s);
                }
                boolean progressed = position > s.evidence.position;
                s.evidence.sample(position, playing, elapsed);
                if (!title.isEmpty()) {
                    RecentMedia.Item i = new RecentMedia.Item();
                    i.pkg = pkg;
                    i.title = title;
                    i.key = RecentMedia.hash(pkg + "|session|" + identity);
                    i.position = Math.max(0, position);
                    i.duration = Math.max(0, metadata.getLong(MediaMetadata.METADATA_KEY_DURATION));
                    // A long-lived paused session must not keep promoting an old episode above newer playback.
                    i.observed = progressed || playing && age >= 0 && age < 15000 ? wall
                            : s.item == null ? s.started : s.item.observed;
                    i.available = true;
                    if (description.getIconUri() != null)
                        i.image = description.getIconUri().toString();
                    if (description.getMediaUri() != null)
                        i.link = description.getMediaUri().toString();
                    s.item = i;
                    if (wall - s.lastSaved > 15000) {
                        RecentMedia.remember(context, i);
                        s.lastSaved = wall;
                    }
                }
            }
            for (String pkg : new ArrayList<>(active.keySet()))
                if (!seen.containsKey(pkg)) finish(active.remove(pkg), wall);
            ended.removeIf(s -> wall - s.endedAt > 120000);
            if (!ended.isEmpty()) RecentMedia.refreshNow(context);
            TraktSync.kick(context);
        } catch (SecurityException e) {
            stop();
        } catch (Exception e) {
            AppLog.w("media history sample unavailable");
        }
    }

    private void finish(Session s, long now) {
        s.endedAt = now;
        s.evidence.playing = false;
        ended.add(s);
        if (ended.size() > 12) ended.remove(0);
        if (s.item != null) {
            s.item.position = Math.max(0, s.evidence.position);
            RecentMedia.remember(context, s.item);
        }
    }

    void provider(RecentMedia.Item item, boolean changed) {
        // Querying a stale row at startup, or a NEXT recommendation, never establishes playback
        // identity.
        if (item.watchType != 0 || item.duration <= 0) return;
        long now = System.currentTimeMillis();
        if (changed) providerChanges.put(item.key, now);
        Long changedAt = providerChanges.get(item.key);
        if (changedAt == null) return;
        if (providerChanges.size() > 160)
            providerChanges.entrySet().removeIf(e -> now - e.getValue() > 120000);
        Session match = null;
        for (Session s : ended) {
            if (!s.pkg.equals(item.pkg) || changedAt < s.started || !compatible(s, item)) continue;
            if (item.engaged < s.started - 120000
                    || !PlaybackEvidence.matchesExit(
                            s.evidence.position, item.position, s.endedAt, now)) continue;
            if (match != null) return; // Two sessions fit: no guess, no Trakt write.
            match = s;
        }
        if (match == null) {
            // Netflix can publish the row just before MediaSession disappears. It must already be
            // paused.
            Session s = active.get(item.pkg);
            if (s != null
                    && changedAt >= s.started
                    && compatible(s, item)
                    && !s.evidence.playing
                    && item.engaged >= s.started - 120000
                    && PlaybackEvidence.matchesExit(s.evidence.position, item.position, now, now))
                match = s;
        }
        if (match == null) return;
        item.observed = now;
        if (match.item != null) RecentMedia.forgetSession(match.item.key);
        if (!match.offered && match.evidence.completed(item.duration)) {
            match.offered = true;
            TraktSync.enqueue(context, item, match.endedAt > 0 ? match.endedAt : now);
        }
    }

    private static boolean compatible(Session s, RecentMedia.Item item) {
        return s.identity.isEmpty()
                || s.item != null
                        && TraktSync.normalize(s.item.title)
                                .equals(TraktSync.normalize(item.title));
    }
}
