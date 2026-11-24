package org.llm4people.hermes.onboarding;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/**
 * ISO/IEC 18004 verification suite for our zero-dependency pure-Java QR Code generator:
 * tests version selection (v1–v10), finder/alignment/timing geometry, BCH format & version words,
 * GF(256) Reed-Solomon zero-syndrome proofs, and full matrix unmask-and-decode round-trips.
 */
public final class QrCodeMatrixTest {
    // ISO/IEC 18004 Annex C Table C.1 — EC Level M (00) format strings for masks 0..7
    private static final int[] ISO_EC_M_FORMAT_BITS = {
            0x5412, 0x5125, 0x5E7C, 0x5B4B, 0x45F9, 0x40CE, 0x4F97, 0x4AA0
    };

    // ISO/IEC 18004 Annex D Table D.1 — Version information strings for v7..v10
    private static final int[] ISO_VERSION_BITS = {
            0, 0, 0, 0, 0, 0, 0,
            0x07C94, // v7
            0x085BC, // v8
            0x09A99, // v9
            0x0A4D3  // v10
    };

    @Test
    public void formatAndVersionBchWordsMatchIso18004ReferenceConstants() {
        for (int mask = 0; mask < 8; mask++) {
            assertEquals("Format word mismatch for mask " + mask,
                    ISO_EC_M_FORMAT_BITS[mask], QrIsoSpec.computeFormatBits(QrIsoSpec.EC_M, mask));
        }
        for (int v = 7; v <= 10; v++) {
            assertEquals("Version word mismatch for v" + v,
                    ISO_VERSION_BITS[v], QrIsoSpec.computeVersionBits(v));
        }
    }

    @Test
    public void versionSelectionCoversAllTenBoundariesAndRejectsInvalidPayloads() {
        int[] boundaries = {14, 26, 42, 62, 84, 106, 122, 152, 180, 213};
        for (int v = 1; v <= 10; v++) {
            QrCodeMatrix m = QrCodeMatrix.encode("a".repeat(boundaries[v - 1]));
            assertEquals(v, m.version());
            assertEquals(v * 4 + 17, m.size());
            assertTrue(m.maskPattern() >= 0 && m.maskPattern() < 8);
            assertGeometryAndMarkersValid(m);
        }

        // Exact boundary crossing: 15 bytes upgrades v1 -> v2; 181 bytes upgrades v9 -> v10.
        assertEquals(2, QrCodeMatrix.encode("a".repeat(15)).version());
        assertEquals(10, QrCodeMatrix.encode("a".repeat(181)).version());

        // Reject null, empty, and > 213-byte payloads.
        for (String bad : new String[]{null, "", "x".repeat(QrCodeMatrix.MAX_PAYLOAD_BYTES + 1)}) {
            try {
                QrCodeMatrix.encode(bad);
                fail("Expected IllegalArgumentException for payload");
            } catch (IllegalArgumentException expected) {
                // expected
            }
        }
        QrCodeMatrix sample = QrCodeMatrix.encode("hermes");
        try {
            sample.get(-1, 0);
            fail("Expected IndexOutOfBoundsException");
        } catch (IndexOutOfBoundsException expected) {}
        try {
            sample.get(sample.size(), 0);
            fail("Expected IndexOutOfBoundsException");
        } catch (IndexOutOfBoundsException expected) {}
    }

    @Test
    public void reedSolomonSyndromesAreIdenticallyZeroAcrossSingleAndMultiGroupVersions() {
        int[][] specs = {
                {1, 16, 10, 1, 16, 0, 0},
                {4, 64, 18, 2, 32, 0, 0},
                {7, 124, 18, 4, 31, 0, 0},
                {8, 154, 22, 2, 38, 2, 39},
                {9, 182, 22, 3, 36, 2, 37},
                {10, 216, 26, 4, 43, 1, 44},
        };
        for (int[] spec : specs) {
            int version = spec[0];
            int ecLen = spec[2];
            int g1Blocks = spec[3], g1Len = spec[4];
            int g2Blocks = spec[5], g2Len = spec[6];
            int totalBlocks = g1Blocks + g2Blocks;
            int maxDataLen = Math.max(g1Len, g2Len);

            String raw = version == 1
                    ? "hermes-v1"
                    : "hermes://pair?host=https://hermes.example.com&v=" + version;
            byte[] payload = raw.getBytes(StandardCharsets.UTF_8);
            byte[] dataCw = QrCodeMatrix.encodeDataCodewords(payload, version);
            byte[] interleaved = QrCodeMatrix.interleaveWithEcc(dataCw, version);

            // De-interleave back into individual (data + ec) blocks and verify every RS syndrome == 0!
            byte[][] blocks = new byte[totalBlocks][];
            for (int b = 0; b < totalBlocks; b++) {
                int dLen = b < g1Blocks ? g1Len : g2Len;
                blocks[b] = new byte[dLen + ecLen];
            }
            int pos = 0;
            for (int col = 0; col < maxDataLen; col++) {
                for (int b = 0; b < totalBlocks; b++) {
                    int dLen = b < g1Blocks ? g1Len : g2Len;
                    if (col < dLen) blocks[b][col] = interleaved[pos++];
                }
            }
            for (int col = 0; col < ecLen; col++) {
                for (int b = 0; b < totalBlocks; b++) {
                    int dLen = b < g1Blocks ? g1Len : g2Len;
                    blocks[b][dLen + col] = interleaved[pos++];
                }
            }
            assertEquals(interleaved.length, pos);

            for (int b = 0; b < totalBlocks; b++) {
                int alpha = 1;
                for (int rootIdx = 0; rootIdx < ecLen; rootIdx++) {
                    int syndrome = evalPolyGf256(blocks[b], alpha);
                    assertEquals("Non-zero RS syndrome for v" + version + " block " + b + " root " + rootIdx,
                            0, syndrome);
                    alpha = QrIsoSpec.gfMul(alpha, 0x02);
                }
            }
        }
    }

    @Test
    public void fullMatrixUnmaskAndDecodeRecoversExactPairingDeepLinksIncludingUtf8AndV10() {
        String[] payloads = {
                "hermes",
                "hermes://pair?host=https%3A%2F%2Fhermes.example.com&code=pair_AbCdEf1234567890&profile=alex",
                "hermes://pair?host=https%3A%2F%2Fhome.local.example.com%3A8443&code=invite_Français_Été_1234567890&name=Céline",
                // Force Version 10 (16-bit byte-count indicator, >= 181 bytes):
                "hermes://pair?host=https%3A%2F%2Fhermes.long-domain-name.example.org%3A8443&code="
                        + "A1B2C3D4E5F6G7H8I9J0K1L2M3N4O5P6Q7R8S9T0U1V2W3X4Y5Z6a7b8c9d0e1f2g3h4i5j6k7l8m9n0"
                        + "&profile=famille&mode=invite"
        };
        for (String payload : payloads) {
            QrCodeMatrix matrix = QrCodeMatrix.encode(payload);
            assertGeometryAndMarkersValid(matrix);
            String decoded = decodeBytePayloadFromMatrix(matrix);
            assertEquals("Decoded QR payload mismatch for v" + matrix.version(), payload, decoded);
        }
    }

    private static void assertGeometryAndMarkersValid(QrCodeMatrix m) {
        int s = m.size();
        // 3 Finder patterns (7x7)
        int[][] origins = {{0, 0}, {s - 7, 0}, {0, s - 7}};
        for (int[] o : origins) {
            for (int dy = 0; dy < 7; dy++) {
                for (int dx = 0; dx < 7; dx++) {
                    int dist = Math.max(Math.abs(dx - 3), Math.abs(dy - 3));
                    boolean expected = dist != 2;
                    assertEquals("Finder mismatch at " + (o[0] + dx) + "," + (o[1] + dy),
                            expected, m.get(o[0] + dx, o[1] + dy));
                }
            }
        }
        // Timing patterns (row 6, col 6 between finders)
        for (int i = 8; i <= s - 9; i++) {
            assertEquals((i & 1) == 0, m.get(i, 6));
            assertEquals((i & 1) == 0, m.get(6, i));
        }
        // Fixed dark module
        assertTrue(m.get(8, s - 8));
        // Format bits verified in both copies
        int expectedFmt = QrIsoSpec.computeFormatBits(QrIsoSpec.EC_M, m.maskPattern());
        int readCopy1 = 0;
        for (int i = 0; i <= 5; i++) if (m.get(8, i)) readCopy1 |= (1 << i);
        if (m.get(8, 7)) readCopy1 |= (1 << 6);
        if (m.get(8, 8)) readCopy1 |= (1 << 7);
        if (m.get(7, 8)) readCopy1 |= (1 << 8);
        for (int i = 9; i < 15; i++) if (m.get(14 - i, 8)) readCopy1 |= (1 << i);
        assertEquals(expectedFmt, readCopy1);

        int readCopy2 = 0;
        for (int i = 0; i < 8; i++) if (m.get(s - 1 - i, 8)) readCopy2 |= (1 << i);
        for (int i = 8; i < 15; i++) if (m.get(8, s - 15 + i)) readCopy2 |= (1 << i);
        assertEquals(expectedFmt, readCopy2);

        // Version bits verified in both blocks for v7..10
        if (m.version() >= 7) {
            int expectedVer = QrIsoSpec.computeVersionBits(m.version());
            int vBlock1 = 0, vBlock2 = 0;
            for (int i = 0; i < 18; i++) {
                int a = s - 11 + (i % 3);
                int b = i / 3;
                if (m.get(a, b)) vBlock1 |= (1 << i);
                if (m.get(b, a)) vBlock2 |= (1 << i);
            }
            assertEquals(expectedVer, vBlock1);
            assertEquals(expectedVer, vBlock2);
        }
    }

    private static int evalPolyGf256(byte[] poly, int x) {
        int acc = 0;
        for (byte coeff : poly) {
            acc = QrIsoSpec.gfMul(acc, x) ^ (coeff & 0xFF);
        }
        return acc;
    }

    /** Independent decoder reading raw bits from the rendered QR matrix to verify exact round-trip. */
    private static String decodeBytePayloadFromMatrix(QrCodeMatrix m) {
        int v = m.version();
        int s = m.size();
        boolean[] fn = buildFunctionMask(v, s);
        int mask = m.maskPattern();

        int[][] ecSpec = {
                null,
                {16, 10, 1, 16, 0, 0},
                {28, 16, 1, 28, 0, 0},
                {44, 26, 1, 44, 0, 0},
                {64, 18, 2, 32, 0, 0},
                {86, 24, 2, 43, 0, 0},
                {108, 16, 4, 27, 0, 0},
                {124, 18, 4, 31, 0, 0},
                {154, 22, 2, 38, 2, 39},
                {182, 22, 3, 36, 2, 37},
                {216, 26, 4, 43, 1, 44},
        };
        int totalDataCw = ecSpec[v][0];
        int ecLen = ecSpec[v][1];
        int g1 = ecSpec[v][2], g1Len = ecSpec[v][3];
        int g2 = ecSpec[v][4], g2Len = ecSpec[v][5];
        int totalBlocks = g1 + g2;
        int totalCodewords = totalDataCw + totalBlocks * ecLen;

        byte[] raw = new byte[totalCodewords];
        int bitIdx = 0;
        for (int right = s - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < s; vert++) {
                boolean upward = (((right + 1) & 2) == 0);
                int y = upward ? (s - 1 - vert) : vert;
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    if (!fn[y * s + x] && bitIdx < totalCodewords * 8) {
                        boolean bit = m.get(x, y) ^ maskBit(mask, x, y);
                        if (bit) raw[bitIdx >>> 3] |= (byte) (1 << (7 - (bitIdx & 7)));
                        bitIdx++;
                    }
                }
            }
        }

        // De-interleave data codewords
        byte[] data = new byte[totalDataCw];
        int[] blockOffsets = new int[totalBlocks];
        int off = 0;
        for (int b = 0; b < totalBlocks; b++) {
            blockOffsets[b] = off;
            off += (b < g1 ? g1Len : g2Len);
        }
        int pos = 0;
        int maxDataLen = Math.max(g1Len, g2Len);
        for (int col = 0; col < maxDataLen; col++) {
            for (int b = 0; b < totalBlocks; b++) {
                int len = b < g1 ? g1Len : g2Len;
                if (col < len) data[blockOffsets[b] + col] = raw[pos++];
            }
        }

        // Read mode (4 bits) + count (8 or 16 bits) + payload bytes
        int cursor = 0;
        int mode = readBits(data, cursor, 4);
        cursor += 4;
        assertEquals(0b0100, mode);
        int countBits = v <= 9 ? 8 : 16;
        int length = readBits(data, cursor, countBits);
        cursor += countBits;
        byte[] decoded = new byte[length];
        for (int i = 0; i < length; i++) {
            decoded[i] = (byte) readBits(data, cursor, 8);
            cursor += 8;
        }
        return new String(decoded, StandardCharsets.UTF_8);
    }

    private static int readBits(byte[] data, int bitPos, int count) {
        int val = 0;
        for (int i = 0; i < count; i++) {
            int p = bitPos + i;
            int b = (data[p >>> 3] >>> (7 - (p & 7))) & 1;
            val = (val << 1) | b;
        }
        return val;
    }

    private static boolean maskBit(int mask, int x, int y) {
        return switch (mask) {
            case 0 -> ((x + y) % 2) == 0;
            case 1 -> (y % 2) == 0;
            case 2 -> (x % 3) == 0;
            case 3 -> ((x + y) % 3) == 0;
            case 4 -> ((x / 3 + y / 2) % 2) == 0;
            case 5 -> ((x * y) % 2 + (x * y) % 3) == 0;
            case 6 -> ((((x * y) % 2 + (x * y) % 3) % 2)) == 0;
            case 7 -> ((((x + y) % 2 + (x * y) % 3) % 2)) == 0;
            default -> false;
        };
    }

    private static boolean[] buildFunctionMask(int version, int size) {
        boolean[] fn = new boolean[size * size];
        for (int i = 0; i < size; i++) {
            fn[6 * size + i] = true;
            fn[i * size + 6] = true;
        }
        int[][] finders = {{3, 3}, {size - 4, 3}, {3, size - 4}};
        for (int[] c : finders) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dx = -4; dx <= 4; dx++) {
                    int x = c[0] + dx, y = c[1] + dy;
                    if (x >= 0 && x < size && y >= 0 && y < size) fn[y * size + x] = true;
                }
            }
        }
        int[][] centersTable = {
                null, {}, {6, 18}, {6, 22}, {6, 26}, {6, 30}, {6, 34},
                {6, 22, 38}, {6, 24, 42}, {6, 26, 46}, {6, 28, 50}
        };
        int[] centers = centersTable[version];
        for (int i = 0; i < centers.length; i++) {
            for (int j = 0; j < centers.length; j++) {
                if ((i == 0 && j == 0) || (i == 0 && j == centers.length - 1)
                        || (i == centers.length - 1 && j == 0)) continue;
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        fn[(centers[j] + dy) * size + (centers[i] + dx)] = true;
                    }
                }
            }
        }
        for (int i = 0; i <= 8; i++) {
            fn[i * size + 8] = true;
            fn[8 * size + i] = true;
        }
        for (int i = 0; i < 8; i++) {
            fn[8 * size + (size - 1 - i)] = true;
            fn[(size - 1 - i) * size + 8] = true;
        }
        if (version >= 7) {
            for (int i = 0; i < 18; i++) {
                int a = size - 11 + (i % 3);
                int b = i / 3;
                fn[b * size + a] = true;
                fn[a * size + b] = true;
            }
        }
        return fn;
    }
}
