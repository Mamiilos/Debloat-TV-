package io.freemytv;

public final class ImageSizingTest {
    private static int assertions;

    private static void check(boolean condition) {
        assertions++;
        if (!condition) throw new AssertionError("ImageSizing assertion " + assertions);
    }

    public static void main(String[] args) {
        check(ImageSizing.sample(1920, 1080, 420, 236, 2_000_000) == 4);
        check(ImageSizing.sample(1920, 1080, 1092, 614, 8_000_000) == 1);
        check(ImageSizing.sample(4096, 2160, 1092, 614, 8_000_000) == 2);
        check(ImageSizing.sample(320, 180, 420, 236, 2_000_000) == 1);
        check(ImageSizing.sample(60000, 300, 420, 236, 2_000_000) == 4);
        check(ImageSizing.sample(300, 60000, 420, 236, 2_000_000) == 4);
        check(ImageSizing.sample(1001, 1001, 800, 800, 1_000_000) == 2);
        check(ImageSizing.sample(-1, 100, 420, 236, 2_000_000) == 1);
        check(ImageSizing.sample(100, 0, 420, 236, 2_000_000) == 1);
        check(ImageSizing.sample(100, 100, 0, 236, 2_000_000) == 1);
        java.util.Random random = new java.util.Random(42);
        for (int i = 0; i < 500; i++) {
            int w = 1 + random.nextInt(100000), h = 1 + random.nextInt(100000);
            int s = ImageSizing.sample(w, h, 420, 236, 2_000_000);
            long pixels = ((w + (long) s - 1) / s) * ((h + (long) s - 1) / s);
            check(s > 0 && (s & (s - 1)) == 0 && pixels <= 2_000_000);
        }
        System.out.println("ImageSizing: " + assertions + " assertions passed");
    }
}
