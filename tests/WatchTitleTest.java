package io.freemytv;

public final class WatchTitleTest {
    private static int assertions;

    private static void check(boolean value, String why) {
        assertions++;
        if (!value) throw new AssertionError(why);
    }

    public static void main(String[] args) {
        WatchTitle old = new WatchTitle("Example Show", 3, 8, 8, 0);
        WatchTitle latest = new WatchTitle("Example Show", 3, 8, 10, 0);
        check(old.sameSeries(latest), "episodes share one resume card");
        check(latest.sameSeries(old), "replaying an earlier episode is still the same series");
        check(
                new WatchTitle("Example Show", 3, 1, 1, 2000)
                        .sameSeries(new WatchTitle("Example Show", 3, 2, 1, 2001)),
                "episode release years do not split a series");
        check(
                !old.sameSeries(new WatchTitle("Other Show", 3, 8, 10, 0)),
                "different series stay separate");
        check(
                !old.sameSeries(new WatchTitle("Example Show", 0, -1, -1, 0)),
                "movie and series stay separate");
        check(
                !new WatchTitle("Remake", 1, -1, -1, 1990)
                        .sameSeries(new WatchTitle("Remake", 1, -1, -1, 2020)),
                "known remakes stay separate");
        WatchTitle file = new WatchTitle("Example.Show.S01E07.1080p.WEB.h264-GROUP", 4, -1, -1, 0);
        check(
                file.title.equals("Example Show") && file.season == 1 && file.episode == 7,
                "video filenames supply clean artwork queries and episode numbers");
        check(
                file.sameSeries(new WatchTitle("Example Show", 3, 1, 8, 0)),
                "filename and provider series deduplicate");
        check(file.type.equals("tv"), "a generic video clip can be an episode");
        WatchTitle movie =
                new WatchTitle("Example.Movie.2018.1080p.BluRay.x264-GROUP", 4, -1, -1, 0);
        check(
                movie.title.equals("Example Movie")
                        && movie.year == 2018
                        && movie.type.equals("movie"),
                "movie release filenames retain the year");
        check(
                new WatchTitle("The Movie", -1, -1, -1, 0).type.equals("multi"),
                "unknown type is searchable");
        check(new WatchTitle("Song", 7, -1, -1, 0).type.isEmpty(), "music does not become a movie");
        check(
                new WatchTitle("S01E07", 4, -1, -1, 0).title.equals("S01E07"),
                "no fabricated series title");
        check(
                new WatchTitle("Dr. Example", 1, 1, 1, 0).title.equals("Dr. Example"),
                "normal punctuation preserved");
        check(
                new WatchTitle("Show.S01E02.web", 4, 2, 5, 0).episode == 5,
                "explicit provider numbers win over filename hints");
        System.out.println("WatchTitle: " + assertions + " assertions passed");
    }
}
