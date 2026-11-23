package org.llm4people.hermes.onboarding;

import org.junit.Test;
import org.llm4people.hermes.profile.FirstRunWizard;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import static org.junit.Assert.*;

/**
 * Pure-JVM verification suite for {@link QrIsoSpec}, {@link QrCodeDecoder}, and
 * {@link QrScannerActivity} payload validation:
 * covers Versions 1–10, all 4 EC levels (L/M/Q/H), all 8 masks, Numeric/Alphanumeric/Byte/ECI
 * modes, 0°/90°/180°/270° and 25° rotations, perspective-skewed camera frames, uneven lighting,
 * GF(256) Reed-Solomon error repair & fail-closed rejection, and negative/garbage frames.
 */
public final class QrCodeDecoderTest {

    @Test
    public void roundTripAllVersionsOneThroughTenPixelsAndStridedLuminance() {
        int[] boundaries = {10, 20, 35, 55, 76, 98, 118, 145, 172, 205};
        for (int v = 1; v <= 10; v++) {
            String payload = v == 1
                    ? "hermes-v01"
                    : buildPaddedPairingLink(boundaries[v - 1], v);
            QrCodeMatrix matrix = QrCodeMatrix.encode(payload);
            assertEquals("Expected version " + v, v, matrix.version());

            int scale = 6;
            int quiet = 4;
            int imgSize = (matrix.size() + quiet * 2) * scale;
            int[] argb = renderMatrixArgb(matrix, scale, quiet);
            assertEquals("decodePixels failed for v" + v, payload,
                    QrCodeDecoder.decodePixels(argb, imgSize, imgSize));

            // Test YUV luminance plane with padded rowStride > width (matching Android Camera2)
            int rowStride = imgSize + 32;
            byte[] yPlane = renderMatrixLuma(matrix, scale, quiet, rowStride);
            assertEquals("decodeLuminance (strided) failed for v" + v, payload,
                    QrCodeDecoder.decodeLuminance(yPlane, imgSize, imgSize, rowStride));
        }

        // Exact real-world Hermes pairing links (ASCII + UTF-8 French display name)
        String[] realLinks = {
                "hermes://pair?host=https%3A%2F%2Fhermes.ai.localserver.ca&code=pair_AbCdEf1234567890&profile=alex",
                "hermes://pair?host=https%3A%2F%2Fhermes.ai.localserver.ca&code=pair_ZzYyXx9876543210&name=C%C3%A9line&mode=invite"
        };
        for (String link : realLinks) {
            QrCodeMatrix m = QrCodeMatrix.encode(link);
            int imgSize = (m.size() + 8) * 7;
            int[] argb = renderMatrixArgb(m, 7, 4);
            assertEquals(link, QrCodeDecoder.decodePixels(argb, imgSize, imgSize));
        }
    }

    @Test
    public void decodesAllEightMasksAllFourEcLevelsAndNumericAlphanumericByteModes() {
        int[] ecLevels = {QrIsoSpec.EC_L, QrIsoSpec.EC_M, QrIsoSpec.EC_Q, QrIsoSpec.EC_H};
        for (int ec : ecLevels) {
            for (int mask = 0; mask < 8; mask++) {
                String msg = "HERMES-" + ec + "-M" + mask;
                boolean[] grid = encodeSyntheticGrid(msg.getBytes(StandardCharsets.UTF_8), 2, ec, mask, 0b0100);
                assertEquals("Byte mode failed for ec=" + ec + " mask=" + mask,
                        msg, QrCodeDecoder.decodeMatrix(grid, QrIsoSpec.dimensionForVersion(2)));
            }
        }

        // Numeric mode (0001): 1, 2, and 3+ digit remainders
        for (String digits : new String[]{"7", "42", "20261123", "0123456789012345"}) {
            boolean[] grid = encodeNumericGrid(digits, 1, QrIsoSpec.EC_M, 3);
            assertEquals("Numeric mode failed for " + digits, digits, QrCodeDecoder.decodeMatrix(grid, 21));
        }

        // Alphanumeric mode (0010): even and odd lengths
        for (String alpha : new String[]{"HERMES", "HTTPS://HERMES.AI:443/P/ALEX", "A"}) {
            boolean[] grid = encodeAlphanumericGrid(alpha, 3, QrIsoSpec.EC_Q, 5);
            assertEquals("Alphanumeric mode failed for " + alpha,
                    alpha, QrCodeDecoder.decodeMatrix(grid, QrIsoSpec.dimensionForVersion(3)));
        }

        // FNC1 first-position (0101, 0 bits) and second-position (1001, 8-bit Application Indicator)
        String fncMsg = "HERMES-FNC1";
        byte[] fncBytes = fncMsg.getBytes(StandardCharsets.UTF_8);
        assertEquals(fncMsg, QrCodeDecoder.decodeMatrix(
                encodeFnc1ByteGrid(fncBytes, false, 0, 2, QrIsoSpec.EC_M, 2),
                QrIsoSpec.dimensionForVersion(2)));
        assertEquals(fncMsg, QrCodeDecoder.decodeMatrix(
                encodeFnc1ByteGrid(fncBytes, true, 0x37, 2, QrIsoSpec.EC_M, 4),
                QrIsoSpec.dimensionForVersion(2)));
    }

    @Test
    public void decodesTransparentPngBackgroundSmallModuleScaleAndRejectsNonFiniteHomography() {
        String link = "hermes://pair?host=https%3A%2F%2Fhermes.ai.localserver.ca&code=pair_AlphaPng12345678&profile=alex";
        QrCodeMatrix m = QrCodeMatrix.encode(link);

        // 1. Transparent background (0x00000000) + semi-transparent dark modules composited over white
        int scale = 6;
        int quiet = 4;
        int dim = (m.size() + quiet * 2) * scale;
        int[] transparentArgb = new int[dim * dim];
        Arrays.fill(transparentArgb, 0x00000000); // fully transparent background!
        for (int my = 0; my < m.size(); my++) {
            for (int mx = 0; mx < m.size(); mx++) {
                if (!m.get(mx, my)) continue;
                int x0 = (mx + quiet) * scale;
                int y0 = (my + quiet) * scale;
                for (int dy = 0; dy < scale; dy++) {
                    int row = (y0 + dy) * dim + x0;
                    for (int dx = 0; dx < scale; dx++) {
                        transparentArgb[row + dx] = 0xFF000000;
                    }
                }
            }
        }
        assertEquals("Transparent-background PNG QR must decode cleanly",
                link, QrCodeDecoder.decodePixels(transparentArgb, dim, dim));

        // 2. Small 1-px module rendering (exercises floor-vs-round pixel-edge alignment centers)
        int smallQuiet = 4;
        int smallDim = m.size() + smallQuiet * 2;
        int[] smallArgb = renderMatrixArgb(m, 1, smallQuiet);
        assertEquals("1px-per-module QR must decode without off-by-one center shift",
                link, QrCodeDecoder.decodePixels(smallArgb, smallDim, smallDim));

        // 3. PerspectiveTransform rejects NaN/Infinity inputs fail-closed -> {-1, -1} (never 0)
        QrCodeDecoder.PerspectiveTransform id = QrCodeDecoder.PerspectiveTransform.quadToQuad(
                0, 0, 10, 0, 10, 10, 0, 10,
                0, 0, 10, 0, 10, 10, 0, 10);
        assertNotNull(id);
        double[] nanOut = id.transform(Double.NaN, 5.0);
        assertEquals(-1.0, nanOut[0], 0.0);
        assertEquals(-1.0, nanOut[1], 0.0);
        double[] infOut = id.transform(Double.POSITIVE_INFINITY, 5.0);
        assertEquals(-1.0, infOut[0], 0.0);
        assertEquals(-1.0, infOut[1], 0.0);
    }

    @Test
    public void decodesAllOrientationsZero90180270AndTilted25Degrees() {
        String payload = "hermes://pair?host=https%3A%2F%2Fhermes.ai.localserver.ca&code=pair_RotTest123456789&profile=jacob";
        QrCodeMatrix matrix = QrCodeMatrix.encode(payload);
        int scale = 6;
        int quiet = 5;
        int baseSize = (matrix.size() + quiet * 2) * scale;
        byte[] upright = renderMatrixLuma(matrix, scale, quiet, baseSize);

        for (int deg : new int[]{0, 90, 180, 270}) {
            byte[] rotated = rotateLuma90(upright, baseSize, deg);
            assertEquals("Failed at rotation " + deg + "°",
                    payload, QrCodeDecoder.decodeLuminance(rotated, baseSize, baseSize, baseSize));
        }

        // Arbitrary 25-degree handheld rotation on a larger canvas
        int canvas = (int) (baseSize * 1.6);
        byte[] tilted = renderWarpedLuma(matrix, canvas, canvas,
                canvas * 0.5, canvas * 0.5, scale * 0.95, Math.toRadians(25.0), 0.0, 0.0);
        assertEquals("Failed at 25° rotation",
                payload, QrCodeDecoder.decodeLuminance(tilted, canvas, canvas, canvas));
    }

    @Test
    public void decodesPerspectiveSkewedHandheldFramesAndUnevenLighting() {
        String[] payloads = {
                "hermes://pair?host=https%3A%2F%2Fhermes.ai.localserver.ca&code=pair_SkewCheck98765432&profile=alex",
                buildPaddedPairingLink(195, 10) // Version 10
        };
        for (String payload : payloads) {
            QrCodeMatrix matrix = QrCodeMatrix.encode(payload);
            int w = 640;
            int h = 640;
            // Synthetic trapezoidal perspective skew (simulating camera pitched & yawed at a screen)
            byte[] skewed = renderPerspectiveLuma(matrix, w, h,
                    110, 95,    // TL
                    520, 130,   // TR
                    565, 535,   // BR (foreshortened/flared quad)
                    75, 490,    // BL
                    false);
            assertEquals("Perspective skew failed for v" + matrix.version(),
                    payload, QrCodeDecoder.decodeLuminance(skewed, w, h, w));

            // Same perspective skew PLUS strong uneven illumination gradient (left dark shadow, right bright glare)
            byte[] shaded = renderPerspectiveLuma(matrix, w, h,
                    115, 105,
                    515, 125,
                    550, 520,
                    85, 500,
                    true);
            assertEquals("Uneven lighting + perspective failed for v" + matrix.version(),
                    payload, QrCodeDecoder.decodeLuminance(shaded, w, h, w));
        }
    }

    @Test
    public void reedSolomonCorrectsCorruptedCodewordsAndFailsClosedBeyondCapacity() {
        // 1. Direct GF(256) RS unit test across single-error, max-capacity, and over-capacity
        byte[] data = "hermes-rs-proof!".getBytes(StandardCharsets.UTF_8); // 16 bytes (v1 EC-M: ecLen=10 -> fixes 5)
        int ecLen = 10;
        byte[] gen = QrIsoSpec.reedSolomonGenerator(ecLen);
        byte[] ec = QrIsoSpec.reedSolomonRemainder(data, gen);
        byte[] clean = new byte[data.length + ecLen];
        System.arraycopy(data, 0, clean, 0, data.length);
        System.arraycopy(ec, 0, clean, data.length, ecLen);

        // Corrupt 5 bytes (exact max t = ecLen / 2) across both data & parity positions
        byte[] fiveErr = Arrays.copyOf(clean, clean.length);
        int[] errPos = {0, 4, 11, 17, 25};
        int[] errMag = {0x5A, 0xA5, 0x33, 0xF0, 0x0F};
        for (int i = 0; i < errPos.length; i++) fiveErr[errPos[i]] ^= (byte) errMag[i];
        assertTrue("RS decoder should repair 5/10 errors", QrIsoSpec.reedSolomonDecode(fiveErr, ecLen));
        assertArrayEquals("Repaired codeword mismatch", clean, fiveErr);

        // Corrupt 6 bytes (> t = 5): must fail closed and NOT leave corrupted partial mutations
        byte[] sixErr = Arrays.copyOf(clean, clean.length);
        for (int i = 0; i < 6; i++) sixErr[i * 4] ^= (byte) (0x21 + i * 17);
        byte[] snapshot = Arrays.copyOf(sixErr, sixErr.length);
        assertFalse("RS decoder must reject > ecLen/2 errors", QrIsoSpec.reedSolomonDecode(sixErr, ecLen));
        assertArrayEquals("Failed RS decode must leave buffer unmodified", snapshot, sixErr);

        // 2. Full matrix decode with 3 corrupted data modules + 2 corrupted format bits
        String link = "hermes://pair?host=https%3A%2F%2Fhermes.ai.localserver.ca&code=pair_EccRepair123456";
        QrCodeMatrix m = QrCodeMatrix.encode(link);
        int s = m.size();
        boolean[] modules = new boolean[s * s];
        for (int y = 0; y < s; y++) {
            for (int x = 0; x < s; x++) {
                modules[y * s + x] = m.get(x, y);
            }
        }
        // Flip 2 bits in format copy 1 AND 3 data modules inside the code
        modules[1 * s + 8] = !modules[1 * s + 8];
        modules[8 * s + 2] = !modules[8 * s + 2];
        modules[(s - 2) * s + (s - 2)] = !modules[(s - 2) * s + (s - 2)];
        modules[(s - 4) * s + (s - 3)] = !modules[(s - 4) * s + (s - 3)];
        modules[(s - 6) * s + (s - 5)] = !modules[(s - 6) * s + (s - 5)];

        assertEquals("Matrix decode should repair format + data errors via BCH + RS",
                link, QrCodeDecoder.decodeMatrix(modules, s));
    }

    @Test
    public void negativeGarbageAndBlankFramesReturnNullWithoutThrowing() {
        assertNull(QrCodeDecoder.decodeLuminance(null, 100, 100, 100));
        assertNull(QrCodeDecoder.decodeLuminance(new byte[10], 100, 100, 100));
        assertNull(QrCodeDecoder.decodeLuminance(new byte[400], 20, 20, 20));
        assertNull(QrCodeDecoder.decodeLuminance(new byte[2500], 50, 50, 40)); // stride < width
        assertNull(QrCodeDecoder.decodePixels(null, 100, 100));
        assertNull(QrCodeDecoder.decodePixels(new int[10], 100, 100));
        assertNull(QrCodeDecoder.decodePixels(new int[400], 20, 20));

        // Blank white and blank black frames
        byte[] white = new byte[200 * 200];
        Arrays.fill(white, (byte) 0xFF);
        assertNull(QrCodeDecoder.decodeLuminance(white, 200, 200, 200));
        assertNull(QrCodeDecoder.decodeLuminance(new byte[200 * 200], 200, 200, 200));

        // Deterministic pseudo-random noise
        byte[] noise = new byte[240 * 240];
        new Random(42L).nextBytes(noise);
        assertNull(QrCodeDecoder.decodeLuminance(noise, 240, 240, 240));

        // High-contrast checkerboard
        int[] checker = new int[200 * 200];
        for (int y = 0; y < 200; y++) {
            for (int x = 0; x < 200; x++) {
                checker[y * 200 + x] = (((x / 4) + (y / 4)) & 1) == 0 ? 0xFF000000 : 0xFFFFFFFF;
            }
        }
        assertNull(QrCodeDecoder.decodePixels(checker, 200, 200));

        // Invalid matrix dimensions
        assertNull(QrCodeDecoder.decodeMatrix(null, 21));
        assertNull(QrCodeDecoder.decodeMatrix(new boolean[22 * 22], 22));
        assertNull(QrCodeDecoder.decodeMatrix(new boolean[21 * 21], 21));
    }

    @Test
    public void scannerPayloadFilterAndFirstRunWizardWiringStayFailClosed() {
        // Empty / null -> NO_QR
        assertEquals(QrScannerActivity.ScanVerdict.NO_QR,
                QrScannerActivity.evaluateDecodedText(null).verdict());
        assertEquals(QrScannerActivity.ScanVerdict.NO_QR,
                QrScannerActivity.evaluateDecodedText("   ").verdict());

        // Non-Hermes QR payloads -> NOT_HERMES and rawText cleared
        String[] rejected = {
                "WIFI:T:WPA;S:HomeWifi;P:supersecret;;",
                "javascript:alert(1)",
                "http://remote.example.com/pair?code=pair_AbCdEf1234567890",
                "mailto:user@example.com",
                "plain random note without any url"
        };
        for (String junk : rejected) {
            QrScannerActivity.ScanEvaluation ev = QrScannerActivity.evaluateDecodedText(junk);
            assertEquals("Expected NOT_HERMES for " + junk,
                    QrScannerActivity.ScanVerdict.NOT_HERMES, ev.verdict());
            assertNull(ev.parsed());
            assertEquals("", ev.rawText());
        }

        // Valid Hermes pairing deep link -> VALID_HERMES + CLAIM_TICKET plan (awaits user confirmation!)
        String pairing = "hermes://pair?host=https%3A%2F%2Fhermes.ai.localserver.ca&code=pair_AbCdEf1234567890&profile=alex&name=Alex";
        QrScannerActivity.ScanEvaluation valid = QrScannerActivity.evaluateDecodedText(pairing);
        assertEquals(QrScannerActivity.ScanVerdict.VALID_HERMES, valid.verdict());
        assertNotNull(valid.parsed());
        assertEquals(pairing, valid.rawText());

        FirstRunWizard.Step1Plan plan = FirstRunWizard.planForInput(valid.parsed());
        assertNotNull(plan);
        assertEquals(FirstRunWizard.ApplyAction.CLAIM_TICKET, plan.action());
        assertEquals("https://hermes.ai.localserver.ca", plan.serverUrl());
        assertEquals("alex", plan.profileSlug());
        assertEquals("pair_AbCdEf1234567890", plan.pairingCode());
        assertEquals("Alex", plan.suggestedName());

        // Valid JSON direct credentials QR -> VALID_HERMES + FILL_CREDENTIALS plan
        String jsonCreds = "{\"host\":\"https://hermes.ai.localserver.ca\",\"profile\":\"alex\",\"api_key\":\"hm_live_key_1234567890\"}";
        QrScannerActivity.ScanEvaluation direct = QrScannerActivity.evaluateDecodedText(jsonCreds);
        assertEquals(QrScannerActivity.ScanVerdict.VALID_HERMES, direct.verdict());
        FirstRunWizard.Step1Plan directPlan = FirstRunWizard.planForInput(direct.parsed());
        assertNotNull(directPlan);
        assertEquals(FirstRunWizard.ApplyAction.FILL_CREDENTIALS, directPlan.action());
        assertEquals("hm_live_key_1234567890", directPlan.apiKey());
    }

    @Test
    public void cameraPreviewSelectionAndCenterCropPreserveExactAspectRatioAcrossOrientations() {
        // Sensor orientation vs display rotation axis swap table
        assertTrue(QrScannerActivity.shouldSwapAxes(90, 0));
        assertFalse(QrScannerActivity.shouldSwapAxes(90, 90));
        assertTrue(QrScannerActivity.shouldSwapAxes(90, 180));
        assertFalse(QrScannerActivity.shouldSwapAxes(90, 270));
        assertFalse(QrScannerActivity.shouldSwapAxes(0, 0));
        assertTrue(QrScannerActivity.shouldSwapAxes(270, 0));

        // 1. Tall portrait 20:9 screen (1080x2400) with 4:3 (1440x1080) and 16:9 (1920x1080) buffers (swapped)
        int[][] viewports = {{1080, 2400}, {2400, 1080}, {1080, 1920}, {720, 1600}};
        int[][] buffers = {{1920, 1080}, {1440, 1080}, {1280, 720}, {640, 480}};
        for (int[] vp : viewports) {
            boolean portrait = vp[1] > vp[0];
            for (int[] buf : buffers) {
                boolean swap = portrait; // landscape camera sensor on portrait display
                QrScannerActivity.PreviewLayout layout = QrScannerActivity.computeCenterCropLayout(
                        vp[0], vp[1], buf[0], buf[1], swap);
                assertTrue("Layout width must cover container", layout.width() >= vp[0]);
                assertTrue("Layout height must cover container", layout.height() >= vp[1]);
                double expectedAspect = swap ? (double) buf[1] / buf[0] : (double) buf[0] / buf[1];
                double actualAspect = (double) layout.width() / layout.height();
                assertEquals("Aspect ratio distortion exceeded 0.01", expectedAspect, actualAspect, 0.01);
            }
        }

        // 2. chooseBestSize picks 16:9 over 4:3 on tall portrait 20:9 phone, and respects maxLongEdge <= 1920
        int[][] candidates = {
                {4032, 3024}, // > 1920
                {1440, 1080}, // 4:3
                {1920, 1080}, // 16:9 (closest to 20:9 when swapped!)
                {1280, 720},  // 16:9 smaller
                {640, 480}    // 4:3 small
        };
        int[] chosenPortrait = QrScannerActivity.chooseBestSize(candidates, 1920, 1080.0 / 2400.0, true);
        assertArrayEquals(new int[]{1920, 1080}, chosenPortrait);

        int[] chosenAnalysis = QrScannerActivity.chooseBestSize(
                new int[][]{{1920, 1080}, {1280, 720}, {960, 540}, {640, 480}},
                960, 1080.0 / 1920.0, true);
        assertArrayEquals(new int[]{960, 540}, chosenAnalysis);
    }

    // -------------------------------------------------------------------------
    // Synthetic image renderers & ISO encoders for tests
    // -------------------------------------------------------------------------

    private static String buildPaddedPairingLink(int targetLength, int seed) {
        String prefix = "hermes://pair?host=https%3A%2F%2Fhermes.ai.localserver.ca&code=p" + seed + "_";
        if (prefix.length() >= targetLength) {
            return prefix.substring(0, targetLength);
        }
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(prefix);
        int i = seed;
        while (sb.length() < targetLength) {
            sb.append(alphabet.charAt((i * 7 + 13) % alphabet.length()));
            i++;
        }
        return sb.toString();
    }

    private static int[] renderMatrixArgb(QrCodeMatrix m, int scale, int quiet) {
        int s = m.size();
        int total = (s + quiet * 2) * scale;
        int[] argb = new int[total * total];
        Arrays.fill(argb, 0xFFFFFFFF);
        for (int my = 0; my < s; my++) {
            for (int mx = 0; mx < s; mx++) {
                if (!m.get(mx, my)) continue;
                int x0 = (mx + quiet) * scale;
                int y0 = (my + quiet) * scale;
                for (int dy = 0; dy < scale; dy++) {
                    int row = (y0 + dy) * total + x0;
                    for (int dx = 0; dx < scale; dx++) {
                        argb[row + dx] = 0xFF101214;
                    }
                }
            }
        }
        return argb;
    }

    private static byte[] renderMatrixLuma(QrCodeMatrix m, int scale, int quiet, int rowStride) {
        int s = m.size();
        int total = (s + quiet * 2) * scale;
        byte[] luma = new byte[total * rowStride];
        Arrays.fill(luma, (byte) 245);
        for (int my = 0; my < s; my++) {
            for (int mx = 0; mx < s; mx++) {
                if (!m.get(mx, my)) continue;
                int x0 = (mx + quiet) * scale;
                int y0 = (my + quiet) * scale;
                for (int dy = 0; dy < scale; dy++) {
                    int row = (y0 + dy) * rowStride + x0;
                    for (int dx = 0; dx < scale; dx++) {
                        luma[row + dx] = (byte) 18;
                    }
                }
            }
        }
        return luma;
    }

    private static byte[] rotateLuma90(byte[] src, int size, int degrees) {
        if (degrees == 0) return Arrays.copyOf(src, src.length);
        byte[] dst = new byte[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int nx, ny;
                if (degrees == 90) {
                    nx = size - 1 - y;
                    ny = x;
                } else if (degrees == 180) {
                    nx = size - 1 - x;
                    ny = size - 1 - y;
                } else { // 270
                    nx = y;
                    ny = size - 1 - x;
                }
                dst[ny * size + nx] = src[y * size + x];
            }
        }
        return dst;
    }

    private static byte[] renderWarpedLuma(
            QrCodeMatrix m, int w, int h,
            double cx, double cy, double modPx, double angleRad, double shearX, double shearY) {
        byte[] out = new byte[w * h];
        Arrays.fill(out, (byte) 235);
        int s = m.size();
        double half = s * 0.5;
        double cos = Math.cos(-angleRad);
        double sin = Math.sin(-angleRad);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double dx = (x - cx) / modPx;
                double dy = (y - cy) / modPx;
                double rx = dx * cos - dy * sin + shearX * dy;
                double ry = dx * sin + dy * cos + shearY * dx;
                int mx = (int) Math.floor(rx + half);
                int my = (int) Math.floor(ry + half);
                if (mx >= 0 && mx < s && my >= 0 && my < s && m.get(mx, my)) {
                    out[y * w + x] = (byte) 22;
                }
            }
        }
        return out;
    }

    /** Project a QR matrix onto an arbitrary quadrilateral in a w×h frame, with optional uneven lighting. */
    private static byte[] renderPerspectiveLuma(
            QrCodeMatrix m, int w, int h,
            double x0, double y0, double x1, double y1, double x2, double y2, double x3, double y3,
            boolean unevenLighting) {
        int s = m.size();
        // Map destination image quad -> source module coordinates [0..s]
        QrCodeDecoder.PerspectiveTransform inv = QrCodeDecoder.PerspectiveTransform.quadToQuad(
                x0, y0, x1, y1, x2, y2, x3, y3,
                0.0, 0.0, s, 0.0, s, s, 0.0, s);
        assertNotNull(inv);

        byte[] out = new byte[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double[] src = inv.transform(x + 0.5, y + 0.5);
                int mx = (int) Math.floor(src[0]);
                int my = (int) Math.floor(src[1]);
                boolean dark = mx >= 0 && mx < s && my >= 0 && my < s && m.get(mx, my);

                int lightVal;
                int darkVal;
                if (unevenLighting) {
                    // Strong left-to-right + diagonal illumination gradient (75..245 light, 10..120 dark)
                    double t = (0.7 * x / w) + (0.3 * y / h);
                    lightVal = (int) Math.round(115 + 135 * t);
                    darkVal = (int) Math.round(10 + 75 * t);
                } else {
                    lightVal = 240;
                    darkVal = 20;
                }
                out[y * w + x] = (byte) (dark ? darkVal : lightVal);
            }
        }
        return out;
    }

    /** Build an arbitrary-EC, arbitrary-mask synthetic QR boolean grid for decoder coverage. */
    private static boolean[] encodeSyntheticGrid(byte[] payload, int version, int ecLevel, int mask, int mode) {
        int[] spec = QrIsoSpec.ecSpec(ecLevel, version);
        int totalDataCw = spec[0];
        BitBuilder bb = new BitBuilder(totalDataCw * 8);
        bb.append(mode, 4);
        int countBits = version <= 9 ? 8 : 16;
        bb.append(payload.length, countBits);
        for (byte b : payload) bb.append(b & 0xFF, 8);
        return finalizeGrid(bb, version, ecLevel, mask);
    }

    private static boolean[] encodeFnc1ByteGrid(
            byte[] payload, boolean secondPosition, int appIndicator, int version, int ecLevel, int mask) {
        int[] spec = QrIsoSpec.ecSpec(ecLevel, version);
        BitBuilder bb = new BitBuilder(spec[0] * 8);
        if (secondPosition) {
            bb.append(0b1001, 4);
            bb.append(appIndicator & 0xFF, 8);
        } else {
            bb.append(0b0101, 4);
        }
        bb.append(0b0100, 4);
        bb.append(payload.length, version <= 9 ? 8 : 16);
        for (byte b : payload) bb.append(b & 0xFF, 8);
        return finalizeGrid(bb, version, ecLevel, mask);
    }

    private static boolean[] encodeNumericGrid(String digits, int version, int ecLevel, int mask) {
        int[] spec = QrIsoSpec.ecSpec(ecLevel, version);
        BitBuilder bb = new BitBuilder(spec[0] * 8);
        bb.append(0b0001, 4);
        bb.append(digits.length(), version <= 9 ? 10 : 12);
        int i = 0;
        while (i + 3 <= digits.length()) {
            bb.append(Integer.parseInt(digits.substring(i, i + 3)), 10);
            i += 3;
        }
        if (digits.length() - i == 2) {
            bb.append(Integer.parseInt(digits.substring(i)), 7);
        } else if (digits.length() - i == 1) {
            bb.append(Integer.parseInt(digits.substring(i)), 4);
        }
        return finalizeGrid(bb, version, ecLevel, mask);
    }

    private static boolean[] encodeAlphanumericGrid(String text, int version, int ecLevel, int mask) {
        String table = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:";
        int[] spec = QrIsoSpec.ecSpec(ecLevel, version);
        BitBuilder bb = new BitBuilder(spec[0] * 8);
        bb.append(0b0010, 4);
        bb.append(text.length(), version <= 9 ? 9 : 11);
        int i = 0;
        while (i + 2 <= text.length()) {
            int v1 = table.indexOf(text.charAt(i));
            int v2 = table.indexOf(text.charAt(i + 1));
            bb.append(v1 * 45 + v2, 11);
            i += 2;
        }
        if (i < text.length()) {
            bb.append(table.indexOf(text.charAt(i)), 6);
        }
        return finalizeGrid(bb, version, ecLevel, mask);
    }

    private static boolean[] finalizeGrid(BitBuilder bb, int version, int ecLevel, int mask) {
        int[] spec = QrIsoSpec.ecSpec(ecLevel, version);
        int totalDataCw = spec[0];
        int ecLen = spec[1];
        int g1 = spec[2], g1Len = spec[3], g2 = spec[4], g2Len = spec[5];
        int totalBits = totalDataCw * 8;
        bb.append(0, Math.min(4, totalBits - bb.len));
        if (bb.len % 8 != 0) bb.append(0, 8 - (bb.len % 8));
        int pad = 0;
        while (bb.len < totalBits) bb.append((pad++ & 1) == 0 ? 0xEC : 0x11, 8);
        byte[] dataCw = bb.bytes;

        int totalBlocks = g1 + g2;
        int maxDataLen = Math.max(g1Len, g2Len);
        byte[][] dBlocks = new byte[totalBlocks][];
        byte[][] eBlocks = new byte[totalBlocks][];
        byte[] gen = QrIsoSpec.reedSolomonGenerator(ecLen);
        int off = 0;
        for (int b = 0; b < totalBlocks; b++) {
            int len = b < g1 ? g1Len : g2Len;
            dBlocks[b] = Arrays.copyOfRange(dataCw, off, off + len);
            eBlocks[b] = QrIsoSpec.reedSolomonRemainder(dBlocks[b], gen);
            off += len;
        }
        byte[] interleaved = new byte[QrIsoSpec.totalCodewords(spec)];
        int pos = 0;
        for (int col = 0; col < maxDataLen; col++) {
            for (int b = 0; b < totalBlocks; b++) {
                if (col < dBlocks[b].length) interleaved[pos++] = dBlocks[b][col];
            }
        }
        for (int col = 0; col < ecLen; col++) {
            for (int b = 0; b < totalBlocks; b++) {
                interleaved[pos++] = eBlocks[b][col];
            }
        }

        int size = QrIsoSpec.dimensionForVersion(version);
        boolean[] grid = new boolean[size * size];
        boolean[] fn = QrIsoSpec.buildFunctionMask(version);

        // Draw format bits + version bits (dark module + timing + finders aren't needed by decodeMatrix
        // beyond format/version bits, but set format bits cleanly in both copies)
        int fmt = QrIsoSpec.computeFormatBits(ecLevel, mask);
        for (int i = 0; i <= 5; i++) grid[i * size + 8] = ((fmt >>> i) & 1) != 0;
        grid[7 * size + 8] = ((fmt >>> 6) & 1) != 0;
        grid[8 * size + 8] = ((fmt >>> 7) & 1) != 0;
        grid[8 * size + 7] = ((fmt >>> 8) & 1) != 0;
        for (int i = 9; i < 15; i++) grid[8 * size + (14 - i)] = ((fmt >>> i) & 1) != 0;
        for (int i = 0; i < 8; i++) grid[8 * size + (size - 1 - i)] = ((fmt >>> i) & 1) != 0;
        for (int i = 8; i < 15; i++) grid[(size - 15 + i) * size + 8] = ((fmt >>> i) & 1) != 0;
        if (version >= 7) {
            int verBits = QrIsoSpec.computeVersionBits(version);
            for (int i = 0; i < 18; i++) {
                boolean dark = ((verBits >>> i) & 1) != 0;
                int a = size - 11 + (i % 3);
                int b = i / 3;
                grid[b * size + a] = dark;
                grid[a * size + b] = dark;
            }
        }

        // Place data bits in ISO zigzag with mask
        int bitIdx = 0;
        int totalStreamBits = interleaved.length * 8;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                boolean upward = (((right + 1) & 2) == 0);
                int y = upward ? (size - 1 - vert) : vert;
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    int idx = y * size + x;
                    if (!fn[idx] && bitIdx < totalStreamBits) {
                        boolean dark = ((interleaved[bitIdx >>> 3] >>> (7 - (bitIdx & 7))) & 1) != 0;
                        grid[idx] = dark ^ QrIsoSpec.maskBit(mask, x, y);
                        bitIdx++;
                    }
                }
            }
        }
        return grid;
    }

    private static final class BitBuilder {
        final byte[] bytes;
        int len;

        BitBuilder(int capacityBits) {
            this.bytes = new byte[(capacityBits + 7) / 8];
        }

        void append(int value, int count) {
            for (int i = count - 1; i >= 0; i--) {
                if (((value >>> i) & 1) != 0) {
                    bytes[len >>> 3] |= (byte) (1 << (7 - (len & 7)));
                }
                len++;
            }
        }
    }
}
