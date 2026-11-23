package org.llm4people.hermes.onboarding;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Zero-dependency pure-Java ISO/IEC 18004 QR Code matrix generator (Versions 1–10, Byte mode,
 * Error Correction Level M — up to 213 UTF-8 bytes, 15% error recovery).
 * Computes exact GF(256) Reed-Solomon ECC, block interleaving, finder/alignment/timing patterns,
 * 8-mask N1–N4 penalty selection, BCH(15,5) format bits, and BCH(18,6) version bits (v7–v10).
 */
public final class QrCodeMatrix {
    public static final int MIN_VERSION = 1;
    public static final int MAX_VERSION = 10;
    public static final int MAX_PAYLOAD_BYTES = 213;

    // EC Level M format indicator bits = 00 (binary)
    private static final int EC_M_FORMAT_BITS = 0b00;
    private static final int FORMAT_MASK = 0x5412;
    private static final int GF_POLY = 0x11D;

    // ISO/IEC 18004 Table 9 — EC Level M for Versions 1..10 (1-indexed)
    // {totalDataCodewords, ecCodewordsPerBlock, group1Blocks, group1DataLen, group2Blocks, group2DataLen}
    private static final int[][] EC_M_SPEC = {
            null,
            {16, 10, 1, 16, 0, 0},   // v1  (21x21): max 14 bytes
            {28, 16, 1, 28, 0, 0},   // v2  (25x25): max 26 bytes
            {44, 26, 1, 44, 0, 0},   // v3  (29x29): max 42 bytes
            {64, 18, 2, 32, 0, 0},   // v4  (33x33): max 62 bytes
            {86, 24, 2, 43, 0, 0},   // v5  (37x37): max 84 bytes
            {108, 16, 4, 27, 0, 0},  // v6  (41x41): max 106 bytes
            {124, 18, 4, 31, 0, 0},  // v7  (45x45): max 122 bytes
            {154, 22, 2, 38, 2, 39}, // v8  (49x49): max 152 bytes
            {182, 22, 3, 36, 2, 37}, // v9  (53x53): max 180 bytes
            {216, 26, 4, 43, 1, 44}, // v10 (57x57): max 213 bytes
    };

    private static final int[] MAX_BYTES_BY_VERSION = {
            0, 14, 26, 42, 62, 84, 106, 122, 152, 180, 213
    };

    private static final int[][] ALIGNMENT_CENTERS = {
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

    private final int version;
    private final int size;
    private final int maskPattern;
    private final boolean[] modules;

    private QrCodeMatrix(int version, int size, int maskPattern, boolean[] modules) {
        this.version = version;
        this.size = size;
        this.maskPattern = maskPattern;
        this.modules = modules;
    }

    public int version() { return version; }
    public int size() { return size; }
    public int maskPattern() { return maskPattern; }

    /** Returns true if module at column {@code x}, row {@code y} is dark. */
    public boolean get(int x, int y) {
        if (x < 0 || x >= size || y < 0 || y >= size) {
            throw new IndexOutOfBoundsException("Module coordinates out of bounds");
        }
        return modules[y * size + x];
    }

    /** Encode UTF-8 text into the smallest Version 1–10 EC-M QR code matrix. */
    public static QrCodeMatrix encode(String text) {
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("QR payload must not be empty");
        }
        return encodeBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    /** Encode raw bytes into the smallest Version 1–10 EC-M QR code matrix. */
    public static QrCodeMatrix encodeBytes(byte[] payload) {
        if (payload == null || payload.length == 0) {
            throw new IllegalArgumentException("QR payload must not be empty");
        }
        if (payload.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("QR payload exceeds 213-byte Version 10 EC-M capacity");
        }
        int version = selectVersion(payload.length);
        byte[] dataCodewords = encodeDataCodewords(payload, version);
        byte[] finalCodewords = interleaveWithEcc(dataCodewords, version);
        int size = version * 4 + 17;

        boolean[] base = new boolean[size * size];
        boolean[] isFunction = new boolean[size * size];
        drawFunctionPatterns(base, isFunction, version, size);
        drawCodewords(base, isFunction, size, finalCodewords);

        int bestMask = 0;
        int bestPenalty = Integer.MAX_VALUE;
        boolean[] bestModules = null;
        for (int mask = 0; mask < 8; mask++) {
            boolean[] candidate = Arrays.copyOf(base, base.length);
            applyMask(candidate, isFunction, size, mask);
            drawFormatBits(candidate, isFunction, size, mask);
            int penalty = computePenalty(candidate, size);
            if (penalty < bestPenalty) {
                bestPenalty = penalty;
                bestMask = mask;
                bestModules = candidate;
            }
        }
        return new QrCodeMatrix(version, size, bestMask, bestModules);
    }

    static int selectVersion(int byteLength) {
        for (int v = MIN_VERSION; v <= MAX_VERSION; v++) {
            if (byteLength <= MAX_BYTES_BY_VERSION[v]) return v;
        }
        throw new IllegalArgumentException("Payload exceeds Version 10 EC-M capacity");
    }

    static byte[] encodeDataCodewords(byte[] payload, int version) {
        if (version < MIN_VERSION || version > MAX_VERSION) {
            throw new IllegalArgumentException("Unsupported QR version");
        }
        if (payload.length > MAX_BYTES_BY_VERSION[version]) {
            throw new IllegalArgumentException("Payload exceeds QR version " + version + " capacity");
        }
        int totalDataCodewords = EC_M_SPEC[version][0];
        BitBuffer bits = new BitBuffer(totalDataCodewords * 8);
        // Byte mode indicator: 0100
        bits.appendBits(0b0100, 4);
        // Character count indicator: 8 bits for v1..9, 16 bits for v10
        int countBits = version <= 9 ? 8 : 16;
        bits.appendBits(payload.length, countBits);
        for (byte b : payload) {
            bits.appendBits(b & 0xFF, 8);
        }
        // Terminator (up to 4 zero bits)
        int totalDataBits = totalDataCodewords * 8;
        int terminator = Math.min(4, totalDataBits - bits.length());
        bits.appendBits(0, terminator);
        // Pad to byte boundary
        int mod8 = bits.length() % 8;
        if (mod8 != 0) bits.appendBits(0, 8 - mod8);
        // Alternating pad codewords 0xEC, 0x11
        int padIndex = 0;
        while (bits.length() < totalDataBits) {
            bits.appendBits((padIndex++ & 1) == 0 ? 0xEC : 0x11, 8);
        }
        return bits.toByteArray();
    }

    static byte[] interleaveWithEcc(byte[] dataCodewords, int version) {
        int[] spec = EC_M_SPEC[version];
        int ecLen = spec[1];
        int g1Blocks = spec[2];
        int g1Len = spec[3];
        int g2Blocks = spec[4];
        int g2Len = spec[5];
        int totalBlocks = g1Blocks + g2Blocks;
        int maxDataLen = Math.max(g1Len, g2Len);

        byte[][] dataBlocks = new byte[totalBlocks][];
        byte[][] ecBlocks = new byte[totalBlocks][];
        byte[] generator = reedSolomonGenerator(ecLen);

        int offset = 0;
        for (int b = 0; b < totalBlocks; b++) {
            int len = b < g1Blocks ? g1Len : g2Len;
            byte[] block = Arrays.copyOfRange(dataCodewords, offset, offset + len);
            offset += len;
            dataBlocks[b] = block;
            ecBlocks[b] = reedSolomonRemainder(block, generator);
        }

        byte[] out = new byte[dataCodewords.length + totalBlocks * ecLen];
        int pos = 0;
        for (int col = 0; col < maxDataLen; col++) {
            for (int b = 0; b < totalBlocks; b++) {
                if (col < dataBlocks[b].length) out[pos++] = dataBlocks[b][col];
            }
        }
        for (int col = 0; col < ecLen; col++) {
            for (int b = 0; b < totalBlocks; b++) {
                out[pos++] = ecBlocks[b][col];
            }
        }
        return out;
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

    static int gfMul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * GF_POLY);
            z ^= ((y >>> i) & 1) * x;
        }
        return z & 0xFF;
    }

    /** Compute 15-bit BCH(15,5) masked format word for EC Level M and mask {@code 0..7}. */
    static int computeFormatBits(int mask) {
        int data = (EC_M_FORMAT_BITS << 3) | (mask & 0x7);
        int rem = data;
        for (int i = 0; i < 10; i++) {
            rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        }
        return ((data << 10) | rem) ^ FORMAT_MASK;
    }

    /** Compute 18-bit BCH(18,6) version word for Versions 7..10. */
    static int computeVersionBits(int version) {
        int rem = version;
        for (int i = 0; i < 12; i++) {
            rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
        }
        return (version << 12) | rem;
    }

    private static void drawFunctionPatterns(boolean[] grid, boolean[] fn, int version, int size) {
        // Timing patterns
        for (int i = 0; i < size; i++) {
            setFn(grid, fn, size, 6, i, (i & 1) == 0);
            setFn(grid, fn, size, i, 6, (i & 1) == 0);
        }
        // Three finder patterns + white separators
        drawFinder(grid, fn, size, 3, 3);
        drawFinder(grid, fn, size, size - 4, 3);
        drawFinder(grid, fn, size, 3, size - 4);

        // Alignment patterns
        int[] centers = ALIGNMENT_CENTERS[version];
        for (int i = 0; i < centers.length; i++) {
            for (int j = 0; j < centers.length; j++) {
                boolean nearFinder = (i == 0 && j == 0)
                        || (i == 0 && j == centers.length - 1)
                        || (i == centers.length - 1 && j == 0);
                if (!nearFinder) {
                    drawAlignment(grid, fn, size, centers[i], centers[j]);
                }
            }
        }

        // Reserve format info areas (populated per mask later) + dark module
        drawFormatBits(grid, fn, size, 0);
        // Version information blocks for v7..10
        if (version >= 7) {
            drawVersionBits(grid, fn, version, size);
        }
    }

    private static void drawFinder(boolean[] grid, boolean[] fn, int size, int cx, int cy) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int x = cx + dx;
                int y = cy + dy;
                if (x < 0 || x >= size || y < 0 || y >= size) continue;
                int dist = Math.max(Math.abs(dx), Math.abs(dy));
                boolean dark = dist != 2 && dist != 4;
                setFn(grid, fn, size, x, y, dark);
            }
        }
    }

    private static void drawAlignment(boolean[] grid, boolean[] fn, int size, int cx, int cy) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                int dist = Math.max(Math.abs(dx), Math.abs(dy));
                setFn(grid, fn, size, cx + dx, cy + dy, dist != 1);
            }
        }
    }

    private static void drawFormatBits(boolean[] grid, boolean[] fn, int size, int mask) {
        int bits = computeFormatBits(mask);
        // First copy around top-left finder
        for (int i = 0; i <= 5; i++) setFn(grid, fn, size, 8, i, bit(bits, i));
        setFn(grid, fn, size, 8, 7, bit(bits, 6));
        setFn(grid, fn, size, 8, 8, bit(bits, 7));
        setFn(grid, fn, size, 7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) setFn(grid, fn, size, 14 - i, 8, bit(bits, i));
        // Second copy split across top-right and bottom-left finders
        for (int i = 0; i < 8; i++) setFn(grid, fn, size, size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) setFn(grid, fn, size, 8, size - 15 + i, bit(bits, i));
        // Fixed dark module
        setFn(grid, fn, size, 8, size - 8, true);
    }

    private static void drawVersionBits(boolean[] grid, boolean[] fn, int version, int size) {
        int bits = computeVersionBits(version);
        for (int i = 0; i < 18; i++) {
            boolean dark = bit(bits, i);
            int a = size - 11 + (i % 3);
            int b = i / 3;
            setFn(grid, fn, size, a, b, dark);
            setFn(grid, fn, size, b, a, dark);
        }
    }

    private static void drawCodewords(boolean[] grid, boolean[] fn, int size, byte[] data) {
        int bitIndex = 0;
        int totalBits = data.length * 8;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5; // Skip vertical timing column
            for (int vert = 0; vert < size; vert++) {
                boolean upward = (((right + 1) & 2) == 0);
                int y = upward ? (size - 1 - vert) : vert;
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    if (!fn[y * size + x]) {
                        boolean dark = false;
                        if (bitIndex < totalBits) {
                            dark = ((data[bitIndex >>> 3] >>> (7 - (bitIndex & 7))) & 1) != 0;
                            bitIndex++;
                        }
                        grid[y * size + x] = dark;
                    }
                }
            }
        }
    }

    private static void applyMask(boolean[] grid, boolean[] fn, int size, int mask) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int idx = y * size + x;
                if (fn[idx]) continue;
                boolean invert = switch (mask) {
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
                if (invert) grid[idx] = !grid[idx];
            }
        }
    }

    /** ISO/IEC 18004 N1 + N2 + N3 + N4 mask evaluation penalty. */
    private static int computePenalty(boolean[] grid, int size) {
        int penalty = 0;
        // N1: adjacent modules in row/col of same color (5+ -> 3 + (run - 5))
        for (int y = 0; y < size; y++) {
            int run = 1;
            boolean prev = grid[y * size];
            for (int x = 1; x < size; x++) {
                boolean cur = grid[y * size + x];
                if (cur == prev) {
                    run++;
                } else {
                    if (run >= 5) penalty += 3 + (run - 5);
                    prev = cur;
                    run = 1;
                }
            }
            if (run >= 5) penalty += 3 + (run - 5);
        }
        for (int x = 0; x < size; x++) {
            int run = 1;
            boolean prev = grid[x];
            for (int y = 1; y < size; y++) {
                boolean cur = grid[y * size + x];
                if (cur == prev) {
                    run++;
                } else {
                    if (run >= 5) penalty += 3 + (run - 5);
                    prev = cur;
                    run = 1;
                }
            }
            if (run >= 5) penalty += 3 + (run - 5);
        }
        // N2: 2x2 blocks of same color (+3 each)
        for (int y = 0; y < size - 1; y++) {
            for (int x = 0; x < size - 1; x++) {
                boolean c = grid[y * size + x];
                if (c == grid[y * size + x + 1]
                        && c == grid[(y + 1) * size + x]
                        && c == grid[(y + 1) * size + x + 1]) {
                    penalty += 3;
                }
            }
        }
        // N3: finder-like 1:1:3:1:1 with 4 light modules on either side (+40 each)
        for (int y = 0; y < size; y++) {
            for (int x = 0; x <= size - 11; x++) {
                if (matchesFinderLikeRow(grid, size, x, y)) penalty += 40;
            }
        }
        for (int x = 0; x < size; x++) {
            for (int y = 0; y <= size - 11; y++) {
                if (matchesFinderLikeCol(grid, size, x, y)) penalty += 40;
            }
        }
        // N4: dark ratio deviation from 50%
        int darkCount = 0;
        for (boolean b : grid) if (b) darkCount++;
        int total = size * size;
        int k = Math.abs(darkCount * 20 - total * 10) / total;
        penalty += k * 10;
        return penalty;
    }

    private static boolean matchesFinderLikeRow(boolean[] g, int size, int x, int y) {
        int base = y * size + x;
        boolean p1 = g[base] && !g[base + 1] && g[base + 2] && g[base + 3] && g[base + 4]
                && !g[base + 5] && g[base + 6] && !g[base + 7] && !g[base + 8] && !g[base + 9] && !g[base + 10];
        boolean p2 = !g[base] && !g[base + 1] && !g[base + 2] && !g[base + 3] && g[base + 4]
                && !g[base + 5] && g[base + 6] && g[base + 7] && g[base + 8] && !g[base + 9] && g[base + 10];
        return p1 || p2;
    }

    private static boolean matchesFinderLikeCol(boolean[] g, int size, int x, int y) {
        boolean p1 = g[y * size + x] && !g[(y + 1) * size + x] && g[(y + 2) * size + x]
                && g[(y + 3) * size + x] && g[(y + 4) * size + x] && !g[(y + 5) * size + x]
                && g[(y + 6) * size + x] && !g[(y + 7) * size + x] && !g[(y + 8) * size + x]
                && !g[(y + 9) * size + x] && !g[(y + 10) * size + x];
        boolean p2 = !g[y * size + x] && !g[(y + 1) * size + x] && !g[(y + 2) * size + x]
                && !g[(y + 3) * size + x] && g[(y + 4) * size + x] && !g[(y + 5) * size + x]
                && g[(y + 6) * size + x] && g[(y + 7) * size + x] && g[(y + 8) * size + x]
                && !g[(y + 9) * size + x] && g[(y + 10) * size + x];
        return p1 || p2;
    }

    private static void setFn(boolean[] grid, boolean[] fn, int size, int x, int y, boolean dark) {
        int idx = y * size + x;
        grid[idx] = dark;
        fn[idx] = true;
    }

    private static boolean bit(int value, int index) {
        return ((value >>> index) & 1) != 0;
    }

    private static final class BitBuffer {
        private final byte[] bytes;
        private int bitLength;

        BitBuffer(int capacityBits) {
            this.bytes = new byte[(capacityBits + 7) / 8];
        }

        int length() { return bitLength; }

        void appendBits(int value, int count) {
            for (int i = count - 1; i >= 0; i--) {
                if (((value >>> i) & 1) != 0) {
                    bytes[bitLength >>> 3] |= (byte) (1 << (7 - (bitLength & 7)));
                }
                bitLength++;
            }
        }

        byte[] toByteArray() {
            return bytes;
        }
    }
}
