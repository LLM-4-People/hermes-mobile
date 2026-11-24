package org.llm4people.hermes.onboarding;

import java.util.Arrays;

/**
 * Single Source of Truth for ISO/IEC 18004 QR Code math and specification tables (Versions 1–10,
 * Error Correction Levels L/M/Q/H), shared by both {@link QrCodeMatrix} (encoder) and
 * {@link QrCodeDecoder} (decoder) with zero duplicated constants or GF(256) arithmetic.
 */
final class QrIsoSpec {
    static final int MIN_VERSION = 1;
    static final int MAX_VERSION = 10;

    // ISO/IEC 18004 2-bit format indicator codes for each Error Correction level
    static final int EC_M = 0b00;
    static final int EC_L = 0b01;
    static final int EC_H = 0b10;
    static final int EC_Q = 0b11;

    static final int FORMAT_MASK = 0x5412;
    private static final int FORMAT_POLY = 0x537;
    private static final int VERSION_POLY = 0x1F25;
    private static final int GF_POLY = 0x11D;

    private static final int[] GF_EXP = new int[512];
    private static final int[] GF_LOG = new int[256];
    private static final int[] VALID_FORMAT_WORDS = new int[32];
    private static final int[] VALID_VERSION_WORDS = new int[MAX_VERSION + 1];

    static {
        int x = 1;
        for (int i = 0; i < 255; i++) {
            GF_EXP[i] = x;
            GF_LOG[x] = i;
            x <<= 1;
            if ((x & 0x100) != 0) x ^= GF_POLY;
        }
        for (int i = 255; i < 512; i++) {
            GF_EXP[i] = GF_EXP[i - 255];
        }
        for (int ec = 0; ec < 4; ec++) {
            for (int mask = 0; mask < 8; mask++) {
                VALID_FORMAT_WORDS[(ec << 3) | mask] = computeFormatBits(ec, mask);
            }
        }
        for (int v = 7; v <= MAX_VERSION; v++) {
            VALID_VERSION_WORDS[v] = computeVersionBits(v);
        }
    }

    // ISO/IEC 18004 Table 9 — {totalDataCodewords, ecPerBlock, g1Blocks, g1DataLen, g2Blocks, g2DataLen}
    // Indexed by [ecFormatBits][version] where ecFormatBits: 0=M, 1=L, 2=H, 3=Q.
    private static final int[][][] EC_SPECS = {
            // 0b00 = EC Level M
            {
                    null,
                    {16, 10, 1, 16, 0, 0},   // v1  (26 total)
                    {28, 16, 1, 28, 0, 0},   // v2  (44 total)
                    {44, 26, 1, 44, 0, 0},   // v3  (70 total)
                    {64, 18, 2, 32, 0, 0},   // v4  (100 total)
                    {86, 24, 2, 43, 0, 0},   // v5  (134 total)
                    {108, 16, 4, 27, 0, 0},  // v6  (172 total)
                    {124, 18, 4, 31, 0, 0},  // v7  (196 total)
                    {154, 22, 2, 38, 2, 39}, // v8  (242 total)
                    {182, 22, 3, 36, 2, 37}, // v9  (292 total)
                    {216, 26, 4, 43, 1, 44}, // v10 (346 total)
            },
            // 0b01 = EC Level L
            {
                    null,
                    {19, 7, 1, 19, 0, 0},    // v1
                    {34, 10, 1, 34, 0, 0},   // v2
                    {55, 15, 1, 55, 0, 0},   // v3
                    {80, 20, 1, 80, 0, 0},   // v4
                    {108, 26, 1, 108, 0, 0}, // v5
                    {136, 18, 2, 68, 0, 0},  // v6
                    {156, 20, 2, 78, 0, 0},  // v7
                    {194, 24, 2, 97, 0, 0},  // v8
                    {232, 30, 2, 116, 0, 0}, // v9
                    {274, 18, 2, 68, 2, 69}, // v10
            },
            // 0b10 = EC Level H
            {
                    null,
                    {9, 17, 1, 9, 0, 0},     // v1
                    {16, 28, 1, 16, 0, 0},   // v2
                    {26, 22, 2, 13, 0, 0},   // v3
                    {36, 16, 4, 9, 0, 0},    // v4
                    {46, 22, 2, 11, 2, 12},  // v5
                    {60, 28, 4, 15, 0, 0},   // v6
                    {66, 26, 4, 13, 1, 14},  // v7
                    {86, 26, 4, 14, 2, 15},  // v8
                    {100, 24, 4, 12, 4, 13}, // v9
                    {122, 28, 6, 15, 2, 16}, // v10
            },
            // 0b11 = EC Level Q
            {
                    null,
                    {13, 13, 1, 13, 0, 0},   // v1
                    {22, 22, 1, 22, 0, 0},   // v2
                    {34, 18, 2, 17, 0, 0},   // v3
                    {48, 26, 2, 24, 0, 0},   // v4
                    {62, 18, 2, 15, 2, 16},  // v5
                    {76, 24, 4, 19, 0, 0},   // v6
                    {88, 18, 2, 14, 4, 15},  // v7
                    {110, 22, 4, 18, 2, 19}, // v8
                    {132, 20, 4, 16, 4, 17}, // v9
                    {154, 24, 6, 19, 2, 20}, // v10
            },
    };

    static final int[][] ALIGNMENT_CENTERS = {
            null,
            {},
            {6, 18},
            {6, 22},
            {6, 26},
            {6, 30},
            {6, 34},
            {6, 22, 38},
            {6, 24, 42},
            {6, 26, 46},
            {6, 28, 50},
    };

    private QrIsoSpec() {}

    static int dimensionForVersion(int version) {
        return version * 4 + 17;
    }

    static int[] ecSpec(int ecFormatBits, int version) {
        if (ecFormatBits < 0 || ecFormatBits > 3 || version < MIN_VERSION || version > MAX_VERSION) {
            throw new IllegalArgumentException("Unsupported QR EC level or version");
        }
        return EC_SPECS[ecFormatBits][version];
    }

    static int totalCodewords(int[] spec) {
        return spec[0] + (spec[2] + spec[4]) * spec[1];
    }

    static int gfMul(int a, int b) {
        int x = a & 0xFF;
        int y = b & 0xFF;
        if (x == 0 || y == 0) return 0;
        return GF_EXP[GF_LOG[x] + GF_LOG[y]];
    }

    static int gfInv(int a) {
        int x = a & 0xFF;
        if (x == 0) throw new ArithmeticException("GF(256) inverse of zero");
        return GF_EXP[255 - GF_LOG[x]];
    }

    static int gfDiv(int a, int b) {
        int x = a & 0xFF;
        int y = b & 0xFF;
        if (y == 0) throw new ArithmeticException("GF(256) division by zero");
        if (x == 0) return 0;
        return GF_EXP[GF_LOG[x] + 255 - GF_LOG[y]];
    }

    /** Compute Reed-Solomon generator polynomial of degree {@code degree} over GF(256). */
    static byte[] reedSolomonGenerator(int degree) {
        byte[] result = new byte[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                result[j] = (byte) gfMul(result[j] & 0xFF, root);
                if (j + 1 < degree) {
                    result[j] ^= result[j + 1];
                }
            }
            root = gfMul(root, 0x02);
        }
        return result;
    }

    /** Compute Reed-Solomon error-correction remainder bytes for {@code data} using {@code generator}. */
    static byte[] reedSolomonRemainder(byte[] data, byte[] generator) {
        byte[] rem = new byte[generator.length];
        for (byte b : data) {
            int factor = (b ^ rem[0]) & 0xFF;
            System.arraycopy(rem, 1, rem, 0, rem.length - 1);
            rem[rem.length - 1] = 0;
            for (int i = 0; i < generator.length; i++) {
                rem[i] ^= (byte) gfMul(generator[i] & 0xFF, factor);
            }
        }
        return rem;
    }

    /**
     * In-place GF(256) Reed-Solomon decoder (Extended Euclidean Key Equation + Chien search +
     * Forney error evaluator) for codewords generated by {@code prod_{i=0..ecLen-1}(x - alpha^i)}.
     * Returns true iff the codeword is valid or successfully corrected (verified via zero syndromes).
     */
    static boolean reedSolomonDecode(byte[] codeword, int ecLen) {
        if (codeword == null || ecLen <= 0 || codeword.length <= ecLen || codeword.length > 255) {
            return false;
        }
        int[] syndromes = new int[ecLen];
        boolean hasError = false;
        for (int i = 0; i < ecLen; i++) {
            int s = evalBigEndian(codeword, GF_EXP[i]);
            syndromes[i] = s;
            if (s != 0) hasError = true;
        }
        if (!hasError) return true;

        // Solve sigma(x) * S(x) == omega(x) (mod x^ecLen) via Extended Euclidean Algorithm.
        // Polynomial arrays store coeff[i] as coefficient of x^i (little-endian degree order).
        int[] rLast = new int[ecLen + 1];
        rLast[ecLen] = 1; // x^ecLen
        int[] r = trimPoly(syndromes);
        int[] tLast = {0};
        int[] t = {1};

        int halfEc = ecLen / 2;
        while (polyDegree(r) >= halfEc) {
            int[] rPrev = rLast;
            int[] tPrev = tLast;
            rLast = r;
            tLast = t;
            if (isZeroPoly(rLast)) return false;

            int[] q = new int[rPrev.length];
            int[] rem = Arrays.copyOf(rPrev, rPrev.length);
            int dLast = polyDegree(rLast);
            int invLead = gfInv(rLast[dLast]);
            while (polyDegree(rem) >= dLast && !isZeroPoly(rem)) {
                int shift = polyDegree(rem) - dLast;
                int scale = gfMul(rem[polyDegree(rem)], invLead);
                q[shift] ^= scale;
                for (int i = 0; i <= dLast; i++) {
                    rem[i + shift] ^= gfMul(rLast[i], scale);
                }
            }
            r = trimPoly(rem);
            t = polyAdd(polyMul(trimPoly(q), tLast), tPrev);
            if (polyDegree(r) >= polyDegree(rLast)) return false;
        }

        int sigmaAtZero = t.length > 0 ? t[0] : 0;
        if (sigmaAtZero == 0) return false;
        int invSigma0 = gfInv(sigmaAtZero);
        int[] sigma = polyScale(t, invSigma0);
        int[] omega = polyScale(r, invSigma0);

        int errorCount = polyDegree(sigma);
        if (errorCount <= 0 || errorCount > halfEc) return false;

        // Chien search: find roots x in GF(256)* where sigma(x) == 0 -> error locator X_k = x^-1.
        int[] locators = new int[errorCount];
        int[] positions = new int[errorCount];
        int found = 0;
        int n = codeword.length;
        for (int x = 1; x < 256 && found < errorCount; x++) {
            if (evalLittleEndian(sigma, x) == 0) {
                int locator = gfInv(x);
                int power = GF_LOG[locator];
                int idx = n - 1 - power;
                if (idx < 0 || idx >= n) return false;
                locators[found] = locator;
                positions[found] = idx;
                found++;
            }
        }
        if (found != errorCount) return false;

        // Forney algorithm (first consecutive root b = 0 -> alpha^0 = 1):
        // Y_k = omega(X_k^-1) / prod_{j != k} (1 + X_j * X_k^-1)
        int[] magnitudes = new int[errorCount];
        for (int k = 0; k < errorCount; k++) {
            int invXk = gfInv(locators[k]);
            int denom = 1;
            for (int j = 0; j < errorCount; j++) {
                if (j != k) {
                    denom = gfMul(denom, 1 ^ gfMul(locators[j], invXk));
                }
            }
            if (denom == 0) return false;
            int num = evalLittleEndian(omega, invXk);
            magnitudes[k] = gfDiv(num, denom);
        }

        // Apply corrections and fail-closed verify all syndromes are now zero.
        for (int k = 0; k < errorCount; k++) {
            codeword[positions[k]] ^= (byte) magnitudes[k];
        }
        for (int i = 0; i < ecLen; i++) {
            if (evalBigEndian(codeword, GF_EXP[i]) != 0) {
                // Roll back so caller never sees partially-mutated corrupted bytes
                for (int k = 0; k < errorCount; k++) {
                    codeword[positions[k]] ^= (byte) magnitudes[k];
                }
                return false;
            }
        }
        return true;
    }

    /** Compute 15-bit BCH(15,5) masked format word for EC indicator {@code 0..3} and mask {@code 0..7}. */
    static int computeFormatBits(int ecFormatBits, int mask) {
        int data = ((ecFormatBits & 0x3) << 3) | (mask & 0x7);
        int rem = data;
        for (int i = 0; i < 10; i++) {
            rem = (rem << 1) ^ ((rem >>> 9) * FORMAT_POLY);
        }
        return ((data << 10) | rem) ^ FORMAT_MASK;
    }

    /**
     * Decode a 15-bit raw format word by nearest-neighbor BCH Hamming distance (max 3 bit errors).
     * Returns packed {@code (ecFormatBits << 3) | mask} in {@code 0..31}, or {@code -1} if unreadable.
     */
    static int decodeFormatBits(int raw15) {
        int masked = raw15 & 0x7FFF;
        int bestIdx = -1;
        int bestDist = 4; // BCH(15,5) min distance is 7 -> corrects up to 3 bit errors
        for (int i = 0; i < VALID_FORMAT_WORDS.length; i++) {
            int dist = Integer.bitCount(masked ^ VALID_FORMAT_WORDS[i]);
            if (dist < bestDist) {
                bestDist = dist;
                bestIdx = i;
                if (dist == 0) break;
            }
        }
        return bestIdx;
    }

    /** Compute 18-bit BCH(18,6) version word for Versions 7..10. */
    static int computeVersionBits(int version) {
        int rem = version;
        for (int i = 0; i < 12; i++) {
            rem = (rem << 1) ^ ((rem >>> 11) * VERSION_POLY);
        }
        return (version << 12) | rem;
    }

    /**
     * Decode an 18-bit raw version word (v7..10) by nearest-neighbor BCH Hamming distance (<= 3).
     * Returns version in {@code 7..10}, or {@code -1} if unreadable.
     */
    static int decodeVersionBits(int raw18) {
        int masked = raw18 & 0x3FFFF;
        int bestVersion = -1;
        int bestDist = 4;
        for (int v = 7; v <= MAX_VERSION; v++) {
            int dist = Integer.bitCount(masked ^ VALID_VERSION_WORDS[v]);
            if (dist < bestDist) {
                bestDist = dist;
                bestVersion = v;
                if (dist == 0) break;
            }
        }
        return bestVersion;
    }

    /** ISO/IEC 18004 data mask predicate for mask {@code 0..7}. */
    static boolean maskBit(int mask, int x, int y) {
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

    /** Build the boolean mask marking all non-data function modules for {@code version}. */
    static boolean[] buildFunctionMask(int version) {
        int size = dimensionForVersion(version);
        boolean[] fn = new boolean[size * size];
        // Timing patterns
        for (int i = 0; i < size; i++) {
            fn[6 * size + i] = true;
            fn[i * size + 6] = true;
        }
        // Three finder patterns + 1-module separators
        markFinderArea(fn, size, 3, 3);
        markFinderArea(fn, size, size - 4, 3);
        markFinderArea(fn, size, 3, size - 4);
        // Alignment patterns
        int[] centers = ALIGNMENT_CENTERS[version];
        for (int i = 0; i < centers.length; i++) {
            for (int j = 0; j < centers.length; j++) {
                boolean nearFinder = (i == 0 && j == 0)
                        || (i == 0 && j == centers.length - 1)
                        || (i == centers.length - 1 && j == 0);
                if (!nearFinder) {
                    int cx = centers[i];
                    int cy = centers[j];
                    for (int dy = -2; dy <= 2; dy++) {
                        for (int dx = -2; dx <= 2; dx++) {
                            fn[(cy + dy) * size + (cx + dx)] = true;
                        }
                    }
                }
            }
        }
        // Format areas + dark module
        for (int i = 0; i <= 8; i++) {
            fn[i * size + 8] = true;
            fn[8 * size + i] = true;
        }
        for (int i = 0; i < 8; i++) {
            fn[8 * size + (size - 1 - i)] = true;
            fn[(size - 1 - i) * size + 8] = true;
        }
        // Version info blocks for v7..10
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

    private static void markFinderArea(boolean[] fn, int size, int cx, int cy) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int x = cx + dx;
                int y = cy + dy;
                if (x >= 0 && x < size && y >= 0 && y < size) {
                    fn[y * size + x] = true;
                }
            }
        }
    }

    private static int evalBigEndian(byte[] poly, int x) {
        int acc = 0;
        for (byte b : poly) {
            acc = gfMul(acc, x) ^ (b & 0xFF);
        }
        return acc;
    }

    private static int evalLittleEndian(int[] poly, int x) {
        int acc = 0;
        for (int i = poly.length - 1; i >= 0; i--) {
            acc = gfMul(acc, x) ^ poly[i];
        }
        return acc;
    }

    private static int polyDegree(int[] p) {
        for (int i = p.length - 1; i >= 0; i--) {
            if (p[i] != 0) return i;
        }
        return 0;
    }

    private static boolean isZeroPoly(int[] p) {
        for (int c : p) {
            if (c != 0) return false;
        }
        return true;
    }

    private static int[] trimPoly(int[] p) {
        int top = p.length - 1;
        while (top > 0 && p[top] == 0) top--;
        return Arrays.copyOf(p, top + 1);
    }

    private static int[] polyAdd(int[] a, int[] b) {
        int len = Math.max(a.length, b.length);
        int[] out = new int[len];
        for (int i = 0; i < a.length; i++) out[i] ^= a[i];
        for (int i = 0; i < b.length; i++) out[i] ^= b[i];
        return trimPoly(out);
    }

    private static int[] polyMul(int[] a, int[] b) {
        if (isZeroPoly(a) || isZeroPoly(b)) return new int[]{0};
        int[] out = new int[a.length + b.length - 1];
        for (int i = 0; i < a.length; i++) {
            if (a[i] == 0) continue;
            for (int j = 0; j < b.length; j++) {
                if (b[j] != 0) out[i + j] ^= gfMul(a[i], b[j]);
            }
        }
        return trimPoly(out);
    }

    private static int[] polyScale(int[] a, int scalar) {
        int[] out = new int[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = gfMul(a[i], scalar);
        }
        return trimPoly(out);
    }
}
