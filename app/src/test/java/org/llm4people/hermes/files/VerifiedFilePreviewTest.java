package org.llm4people.hermes.files;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Regression tests for VerifiedFile.previewImage() pixel-bomb and overflow guards.
 */
public final class VerifiedFilePreviewTest {

    @Test
    public void previewSampleSizeComputesPowerOfTwoWithinMaxEdge() throws Exception {
        assertEquals(1, VerifiedFile.previewSampleSize(800, 600));
        assertEquals(1, VerifiedFile.previewSampleSize(1024, 768));
        assertEquals(2, VerifiedFile.previewSampleSize(2048, 1536));
        assertEquals(4, VerifiedFile.previewSampleSize(4096, 3072));
        assertEquals(8, VerifiedFile.previewSampleSize(8192, 6144));
    }

    @Test
    public void previewSampleSizeRejectsInvalidPixelBombAndOverflowDimensions() {
        int[][] rejected = new int[][]{
                {0, 100},
                {100, 0},
                {-10, 500},
                {20_000, 20_000},        // 400 MP pixel bomb (> 128 MP ceiling)
                {1_500_000_000, 1},      // signed 32-bit inSampleSize shift overflow hazard
                {Integer.MAX_VALUE, 1},
        };
        for (int[] dims : rejected) {
            try {
                VerifiedFile.previewSampleSize(dims[0], dims[1]);
                fail("Expected IOException for dimensions " + dims[0] + "x" + dims[1]);
            } catch (IOException expected) {
                assertEquals("Invalid image", expected.getMessage());
            }
        }
    }
}
