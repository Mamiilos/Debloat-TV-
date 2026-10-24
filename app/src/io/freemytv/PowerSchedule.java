package io.freemytv;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/** Calendar dates and interval ends, independent of the TV's configured time zone. */
final class PowerSchedule {
    static final ZoneId ZONE = ZoneId.of("Europe/Kiev");
    static final class Interval {
        final LocalDate date;
        final String from, to;
        Interval(LocalDate date, String from, String to) { this.date = date; this.from = from; this.to = to; }
    }
    static int minutes(String value, boolean end) {
        if (value == null || !value.matches("[0-9]{2}:[0-9]{2}")) throw new IllegalArgumentException("Invalid time");
        int h = Integer.parseInt(value.substring(0, 2)), m = Integer.parseInt(value.substring(3));
        if (m > 59 || h > 23 && !(end && h == 24 && m == 0)) throw new IllegalArgumentException("Invalid time");
        return h * 60 + m;
    }
    static Interval remaining(List<Interval> intervals, long now) {
        ZonedDateTime time = Instant.ofEpochMilli(now).atZone(ZONE);
        LocalDate today = time.toLocalDate();
        long dayEnd = today.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli();
        Interval first = null;
        long firstStart = Long.MAX_VALUE;
        for (Interval item : intervals) {
            int start = minutes(item.from, false), end = minutes(item.to, true);
            if (start == end) throw new IllegalArgumentException("Empty interval");
            // Local wall-clock arithmetic also handles the daylight-saving transition.
            long a = item.date.atTime(start / 60, start % 60).atZone(ZONE).toInstant().toEpochMilli();
            LocalDate untilDate = end <= start || end == 1440 ? item.date.plusDays(1) : item.date;
            long b = untilDate.atTime((end % 1440) / 60, end % 60).atZone(ZONE).toInstant().toEpochMilli();
            if (a < dayEnd && b > now && a < firstStart) { first = item; firstStart = a; }
        }
        return first;
    }
    static boolean fresh(long timestamp, long now, long maxAge) {
        return timestamp > 0 && now >= timestamp && now - timestamp <= maxAge;
    }
}
