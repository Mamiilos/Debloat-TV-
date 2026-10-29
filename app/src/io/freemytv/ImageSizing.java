package io.freemytv;

/** Power-of-two decoding that fits the visible target and bounds unusual provider images. */
final class ImageSizing {
    private ImageSizing() {}

    static int sample(int width, int height, int targetWidth, int targetHeight, long maxPixels) {
        if (width <= 0 || height <= 0 || targetWidth <= 0 || targetHeight <= 0 || maxPixels < 1)
            return 1;
        int sample = 1;
        while (sample < 1 << 30) {
            long next = (long) sample * 2;
            boolean fits = width / next >= targetWidth && height / next >= targetHeight;
            long pixels = ((width + (long) sample - 1) / sample)
                    * ((height + (long) sample - 1) / sample);
            if (!fits && pixels <= maxPixels) break;
            sample *= 2;
        }
        return sample;
    }
}
