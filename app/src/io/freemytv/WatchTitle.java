package io.freemytv;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Display, artwork and recommendation hints. Never used to identify a completed watch for Trakt. */
final class WatchTitle {
    private static final Pattern EPISODE =
            Pattern.compile("(?i)^(.+?)[ ._-]+S(\\d{1,3})[ ._-]*E(\\d{1,4})(?:[^0-9].*)?$");
    private static final Pattern MOVIE_FILE =
            Pattern.compile(
                    "(?i)^(.+?)[ ._(]+((?:19|20)\\d{2})[ ._)]+.*(?:\\d{3,4}p|WEB["
                        + " ._-]?(?:DL|RIP)|BLURAY|BDRIP|[HX]26[45]).*$");
    final String title, type;
    final int season, episode, year;

    WatchTitle(String title, int mediaType, int season, int episode, int year) {
        String name = title == null ? "" : title.trim();
        String kind =
                mediaType >= 1 && mediaType <= 3
                        ? "tv"
                        : mediaType == 0
                                ? "movie"
                                : mediaType == -1 || mediaType == 4 ? "multi" : "";
        // File names from video players often have no structured episode or media type.
        if (mediaType == -1 || mediaType == 4) {
            Matcher ep = EPISODE.matcher(name), movie = MOVIE_FILE.matcher(name);
            if (ep.matches()) {
                name = cleanFileTitle(ep.group(1));
                if (season < 0) season = Integer.parseInt(ep.group(2));
                if (episode < 1) episode = Integer.parseInt(ep.group(3));
                kind = "tv";
            } else if (movie.matches()) {
                name = cleanFileTitle(movie.group(1));
                if (year == 0) year = Integer.parseInt(movie.group(2));
                kind = "movie";
            }
        }
        if (!kind.isEmpty() && season >= 0 && episode > 0) kind = "tv";
        this.title = name;
        this.type = kind;
        this.season = season;
        this.episode = episode;
        this.year = year;
    }

    boolean numberedEpisode() {
        return season >= 0 && episode > 0;
    }

    boolean sameSeries(WatchTitle other) {
        return type.equals("tv")
                && other.type.equals("tv")
                // For episodes release_date can be the episode's year, not the series premiere.
                && (numberedEpisode()
                        || other.numberedEpisode()
                        || year == 0
                        || other.year == 0
                        || year == other.year)
                && normalize(title).equals(normalize(other.title));
    }

    static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    private static String cleanFileTitle(String value) {
        return value.replace('.', ' ').replace('_', ' ').trim().replaceAll("\\s+", " ");
    }
}
