package org.llm4people.hermes.perf;

/**
 * Pure-Java Single Source of Truth for safe bitmap downsampling and pixel-bomb protection.
 * Ensures full multi-megapixel images are never decoded uncompressed onto the Java heap.
 */
public final class BitmapSampling {
    public static final long MAX_PIXELS = 128_000_000L;

    private BitmapSampling() {}

    /** Returns true when raw dimensions are positive and within the safe pixel-count ceiling. */
    public static boolean validDimensions(int width, int height) {
        return width > 0 && height > 0 && (long) width * height <= MAX_PIXELS;
    }

    /**
     * Compute the largest power-of-2 {@code inSampleSize} (>= 1) that keeps the longest decoded
     * edge at or near {@code maxEdge} without exceeding twice {@code maxEdge}.
     */
    public static int calculateInSampleSize(int rawWidth, int rawHeight, int maxEdge) {
        if (rawWidth <= 0 || rawHeight <= 0 || maxEdge <= 0) {
            throw new IllegalArgumentException("Image dimensions and maxEdge must be positive");
        }
        int sample = 1;
        int longEdge = Math.max(rawWidth, rawHeight);
        while (longEdge / (sample * 2) >= maxEdge) {
            sample *= 2;
        }
        return sample;
    }

    /**
     * Compute exact aspect-preserving target {@code [width, height]} bounded by {@code maxEdge}.
     * Returns the original dimensions unchanged when already within bounds.
     */
    public static int[] targetDimensions(int rawWidth, int rawHeight, int maxEdge) {
        if (!validDimensions(rawWidth, rawHeight) || maxEdge <= 0) {
            throw new IllegalArgumentException("Unsupported image dimensions");
        }
        int longEdge = Math.max(rawWidth, rawHeight);
        if (longEdge <= maxEdge) {
            return new int[]{rawWidth, rawHeight};
        }
        int targetW = Math.max(1, (int) ((long) maxEdge * rawWidth / longEdge));
        int targetH = Math.max(1, (int) ((long) maxEdge * rawHeight / longEdge));
        return new int[]{targetW, targetH};
    }
}
