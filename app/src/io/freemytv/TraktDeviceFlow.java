package io.freemytv;

/** Device-flow responses are not the same as errors from ordinary authenticated API requests. */
final class TraktDeviceFlow {
    static int delay(int status, int interval, int retryAfter) {
        if (status == 400) return Math.max(5, interval);
        if (status == 429) return Math.max(interval + 5, Math.min(86400, Math.max(5, retryAfter)));
        if (status >= 500) return Math.max(5, Math.max(interval, Math.min(86400, retryAfter)));
        return -1;
    }

    static boolean active(long generation, long current, long now, long deadline) {
        return generation == current && now < deadline;
    }
}
