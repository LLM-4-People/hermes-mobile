package org.llm4people.hermes.onboarding;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Zero-dependency pure-Java ISO/IEC 18004 QR Code decoder (Versions 1–10, Error Correction
 * Levels L/M/Q/H, all 8 masks, Byte / Alphanumeric / Numeric modes) with full GF(256)
 * Reed-Solomon error correction and a real-world camera image pipeline:
 * integral-image adaptive thresholding, Otsu fallback, 1:1:3:1:1 finder-pattern detection,
 * arbitrary 0°/90°/180°/270° orientation, alignment-pattern localization, and 3×3 perspective
 * homography. Fail-closed: returns {@code null} on invalid or unreadable frames.
 */
public final class QrCodeDecoder {
    private static final int MAX_ANALYSIS_DIM = 960;
    private static final int MAX_INPUT_DIM = 16384;
    private static final int MAX_FINDER_CANDIDATES = 10;
    private static final String ALPHANUMERIC_TABLE = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:";

    private QrCodeDecoder() {}

    /**
     * Decode a QR code from a grayscale/YUV {@code Y} luminance plane.
     * Returns the decoded text or {@code null} when no valid QR code is detected.
     */
    public static String decodeLuminance(byte[] yPlane, int width, int height, int rowStride) {
        if (yPlane == null || width < 21 || height < 21 || rowStride < width
                || width > MAX_INPUT_DIM || height > MAX_INPUT_DIM) {
            return null;
        }
        long required = (long) (height - 1) * rowStride + width;
        if (required > yPlane.length) {
            return null;
        }
        try {
            int scale = computeDownscale(width, height);
            int w = width / scale;
            int h = height / scale;
            if (w < 21 || h < 21) return null;
            byte[] compact = new byte[w * h];
            if (scale == 1 && rowStride == width) {
                System.arraycopy(yPlane, 0, compact, 0, w * h);
            } else {
                for (int y = 0; y < h; y++) {
                    int srcRow = (y * scale) * rowStride;
                    int dstRow = y * w;
                    for (int x = 0; x < w; x++) {
                        compact[dstRow + x] = yPlane[srcRow + x * scale];
                    }
                }
            }
            return decodeCompactLuma(compact, w, h);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * Decode a QR code from packed {@code ARGB_8888} pixels (e.g. a picked gallery image).
     * Returns the decoded text or {@code null} when no valid QR code is detected.
     */
    public static String decodePixels(int[] argb, int width, int height) {
        if (argb == null || width < 21 || height < 21
                || width > MAX_INPUT_DIM || height > MAX_INPUT_DIM) {
            return null;
        }
        long required = (long) width * height;
        if (required > argb.length) {
            return null;
        }
        try {
            int scale = computeDownscale(width, height);
            int w = width / scale;
            int h = height / scale;
            if (w < 21 || h < 21) return null;
            byte[] compact = new byte[w * h];
            for (int y = 0; y < h; y++) {
                int srcRow = (y * scale) * width;
                int dstRow = y * w;
                for (int x = 0; x < w; x++) {
                    int px = argb[srcRow + x * scale];
                    int a = (px >>> 24) & 0xFF;
                    int r = (px >>> 16) & 0xFF;
                    int g = (px >>> 8) & 0xFF;
                    int b = px & 0xFF;
                    int luma = (77 * r + 150 * g + 29 * b) >>> 8;
                    if (a < 255) {
                        // Composite transparent/semi-transparent PNG QR backgrounds over white
                        luma = (luma * a + 255 * (255 - a) + 127) / 255;
                    }
                    compact[dstRow + x] = (byte) luma;
                }
            }
            return decodeCompactLuma(compact, w, h);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static int computeDownscale(int width, int height) {
        int maxSide = Math.max(width, height);
        int scale = 1;
        while (maxSide / scale > MAX_ANALYSIS_DIM) {
            scale *= 2;
        }
        return scale;
    }

    private static String decodeCompactLuma(byte[] luma, int w, int h) {
        long[] integral = buildIntegral(luma, w, h);
        int radius = clamp(Math.min(w, h) / 12, 8, 40);

        // Pass 1: local adaptive threshold (uneven camera lighting & shadows)
        boolean[] bin = binarizeAdaptive(luma, integral, w, h, radius, 7);
        String decoded = tryDecodeBinary(bin, w, h);
        if (decoded != null) return decoded;

        // Pass 2: global Otsu threshold (crisp digital screenshots & large quiet zones)
        bin = binarizeOtsu(luma, w, h);
        if (bin != null) {
            decoded = tryDecodeBinary(bin, w, h);
            if (decoded != null) return decoded;
        }

        // Pass 3: wider adaptive threshold with gentle margin (large modules / soft contrast)
        int wideRadius = clamp(Math.min(w, h) / 6, 14, 72);
        bin = binarizeAdaptive(luma, integral, w, h, wideRadius, 2);
        return tryDecodeBinary(bin, w, h);
    }

    private static long[] buildIntegral(byte[] luma, int w, int h) {
        int stride = w + 1;
        long[] integral = new long[stride * (h + 1)];
        for (int y = 0; y < h; y++) {
            long rowSum = 0L;
            int srcOff = y * w;
            int curRow = (y + 1) * stride;
            int prevRow = y * stride;
            for (int x = 0; x < w; x++) {
                rowSum += luma[srcOff + x] & 0xFF;
                integral[curRow + x + 1] = integral[prevRow + x + 1] + rowSum;
            }
        }
        return integral;
    }

    private static boolean[] binarizeAdaptive(byte[] luma, long[] integral, int w, int h, int radius, int delta) {
        boolean[] bits = new boolean[w * h];
        int stride = w + 1;
        for (int y = 0; y < h; y++) {
            int y0 = Math.max(0, y - radius);
            int y1 = Math.min(h - 1, y + radius);
            int r0 = y0 * stride;
            int r1 = (y1 + 1) * stride;
            int rowOff = y * w;
            for (int x = 0; x < w; x++) {
                int x0 = Math.max(0, x - radius);
                int x1 = Math.min(w - 1, x + radius);
                long sum = integral[r1 + x1 + 1] - integral[r0 + x1 + 1]
                        - integral[r1 + x0] + integral[r0 + x0];
                int area = (x1 - x0 + 1) * (y1 - y0 + 1);
                int threshold = (int) (sum / area) - delta;
                bits[rowOff + x] = (luma[rowOff + x] & 0xFF) < threshold;
            }
        }
        return bits;
    }

    private static boolean[] binarizeOtsu(byte[] luma, int w, int h) {
        int[] hist = new int[256];
        int minL = 255;
        int maxL = 0;
        for (byte b : luma) {
            int v = b & 0xFF;
            hist[v]++;
            if (v < minL) minL = v;
            if (v > maxL) maxL = v;
        }
        // Reject blank / flat low-contrast frames immediately
        if (maxL - minL < 16) return null;

        int total = w * h;
        double sumAll = 0.0;
        for (int i = 0; i < 256; i++) sumAll += (double) i * hist[i];

        double sumBg = 0.0;
        int wBg = 0;
        double maxVar = -1.0;
        int bestThresh = (minL + maxL) / 2;
        for (int t = minL; t < maxL; t++) {
            wBg += hist[t];
            if (wBg == 0) continue;
            int wFg = total - wBg;
            if (wFg == 0) break;
            sumBg += (double) t * hist[t];
            double meanBg = sumBg / wBg;
            double meanFg = (sumAll - sumBg) / wFg;
            double diff = meanBg - meanFg;
            double var = (double) wBg * wFg * diff * diff;
            if (var > maxVar) {
                maxVar = var;
                bestThresh = t;
            }
        }
        boolean[] bits = new boolean[total];
        for (int i = 0; i < total; i++) {
            bits[i] = (luma[i] & 0xFF) <= bestThresh;
        }
        return bits;
    }

    // -------------------------------------------------------------------------
    // Finder pattern detection & geometric sampling
    // -------------------------------------------------------------------------

    private static final class FinderCenter {
        double x;
        double y;
        double moduleSize;
        int count;

        FinderCenter(double x, double y, double moduleSize) {
            this.x = x;
            this.y = y;
            this.moduleSize = moduleSize;
            this.count = 1;
        }

        boolean matches(double cx, double cy, double mod) {
            double dist = Math.hypot(cx - x, cy - y);
            double maxDist = Math.max(moduleSize * 1.6, 4.0);
            if (dist > maxDist) return false;
            double modDiff = Math.abs(mod - moduleSize);
            return modDiff <= Math.max(1.5, moduleSize * 0.55);
        }

        void merge(double cx, double cy, double mod) {
            int next = count + 1;
            x = (x * count + cx) / next;
            y = (y * count + cy) / next;
            moduleSize = (moduleSize * count + mod) / next;
            count = next;
        }
    }

    private static String tryDecodeBinary(boolean[] bin, int w, int h) {
        int step = Math.max(1, Math.min(w, h) / 220);
        List<FinderCenter> finders = findFinders(bin, w, h, step);
        if (finders.size() < 3 && step > 1) {
            finders = findFinders(bin, w, h, 1);
        }
        if (finders.size() < 3) return null;

        finders.sort(Comparator.comparingInt((FinderCenter f) -> f.count).reversed());
        int limit = Math.min(finders.size(), MAX_FINDER_CANDIDATES);

        for (int i = 0; i < limit; i++) {
            for (int j = i + 1; j < limit; j++) {
                for (int k = j + 1; k < limit; k++) {
                    FinderCenter[] ordered = orderTriple(finders.get(i), finders.get(j), finders.get(k));
                    if (ordered == null) continue;
                    String decoded = decodeFromOrderedFinders(bin, w, h, ordered[0], ordered[1], ordered[2]);
                    if (decoded != null) return decoded;
                }
            }
        }
        return null;
    }

    private static List<FinderCenter> findFinders(boolean[] bin, int w, int h, int rowStep) {
        List<FinderCenter> clusters = new ArrayList<>();
        int[] state = new int[5];

        for (int y = rowStep / 2; y < h; y += rowStep) {
            Arrays.fill(state, 0);
            int cur = 0;
            int rowOff = y * w;

            for (int x = 0; x < w; x++) {
                boolean dark = bin[rowOff + x];
                if (dark) {
                    if ((cur & 1) == 1) cur++;
                    state[cur]++;
                } else {
                    if ((cur & 1) == 0) {
                        if (cur == 4) {
                            if (matchesFinderRatio(state, 0.55)) {
                                confirmAndRecordFinder(bin, w, h, state, y, x, clusters);
                            }
                            state[0] = state[2];
                            state[1] = state[3];
                            state[2] = state[4];
                            state[3] = 1;
                            state[4] = 0;
                            cur = 3;
                        } else {
                            cur++;
                            state[cur]++;
                        }
                    } else {
                        state[cur]++;
                    }
                }
            }
            if (cur == 4 && matchesFinderRatio(state, 0.55)) {
                confirmAndRecordFinder(bin, w, h, state, y, w, clusters);
            }
        }
        return clusters;
    }

    private static boolean matchesFinderRatio(int[] c, double toleranceRatio) {
        int total = c[0] + c[1] + c[2] + c[3] + c[4];
        if (total < 7) return false;
        double mod = total / 7.0;
        double maxVar = mod * toleranceRatio;
        return Math.abs(c[0] - mod) < maxVar
                && Math.abs(c[1] - mod) < maxVar
                && Math.abs(c[2] - 3.0 * mod) < 3.0 * maxVar
                && Math.abs(c[3] - mod) < maxVar
                && Math.abs(c[4] - mod) < maxVar;
    }

    private static void confirmAndRecordFinder(
            boolean[] bin, int w, int h, int[] hState, int y, int endX, List<FinderCenter> clusters) {
        double hTotal = hState[0] + hState[1] + hState[2] + hState[3] + hState[4];
        double estX = endX - hState[4] - hState[3] - hState[2] * 0.5;
        int ix = clamp((int) Math.floor(estX), 0, w - 1);
        double[] vert = crossCheckLine(bin, w, h, ix, y, 0, 1, hState[2], hTotal);
        if (vert == null) return;
        int iy = clamp((int) Math.floor(vert[0]), 0, h - 1);
        double[] horiz = crossCheckLine(bin, w, h, ix, iy, 1, 0, hState[2], vert[1]);
        if (horiz == null) return;
        int rix = clamp((int) Math.floor(horiz[0]), 0, w - 1);
        if (!crossCheckDiagonal(bin, w, h, rix, iy, hState[2], horiz[1])) return;

        double mod = (hTotal + vert[1] + horiz[1]) / 21.0;
        double cx = horiz[0];
        double cy = vert[0];
        for (FinderCenter existing : clusters) {
            if (existing.matches(cx, cy, mod)) {
                existing.merge(cx, cy, mod);
                return;
            }
        }
        clusters.add(new FinderCenter(cx, cy, mod));
    }

    /** Scan from (startX, startY) along ±(dx, dy) expecting 1:1:3:1:1 finder runs. Returns {centerCoord, totalLen}. */
    private static double[] crossCheckLine(
            boolean[] bin, int w, int h, int startX, int startY, int dx, int dy, int maxCenterCount, double origTotal) {
        if (!bin[startY * w + startX]) return null;
        int[] c = new int[5];
        int maxC = Math.max(4, maxCenterCount * 2);

        // Backward along (-dx, -dy)
        int x = startX;
        int y = startY;
        while (inBounds(x, y, w, h) && bin[y * w + x]) { c[2]++; x -= dx; y -= dy; }
        if (!inBounds(x, y, w, h) || c[2] > maxC) return null;
        while (inBounds(x, y, w, h) && !bin[y * w + x] && c[1] <= maxC) { c[1]++; x -= dx; y -= dy; }
        if (!inBounds(x, y, w, h) || c[1] == 0 || c[1] > maxC) return null;
        while (inBounds(x, y, w, h) && bin[y * w + x] && c[0] <= maxC) { c[0]++; x -= dx; y -= dy; }
        if (c[0] == 0 || c[0] > maxC) return null;

        // Forward along (+dx, +dy)
        x = startX + dx;
        y = startY + dy;
        while (inBounds(x, y, w, h) && bin[y * w + x]) { c[2]++; x += dx; y += dy; }
        if (!inBounds(x, y, w, h) || c[2] > maxC) return null;
        while (inBounds(x, y, w, h) && !bin[y * w + x] && c[3] <= maxC) { c[3]++; x += dx; y += dy; }
        if (!inBounds(x, y, w, h) || c[3] == 0 || c[3] > maxC) return null;
        while (inBounds(x, y, w, h) && bin[y * w + x] && c[4] <= maxC) { c[4]++; x += dx; y += dy; }
        if (c[4] == 0 || c[4] > maxC) return null;

        int total = c[0] + c[1] + c[2] + c[3] + c[4];
        if (Math.abs(total - origTotal) * 5 > origTotal * 3) return null;
        if (!matchesFinderRatio(c, 0.58)) return null;

        double endCoord = dx != 0 ? x : y;
        double center = endCoord - c[4] - c[3] - c[2] * 0.5;
        return new double[]{center, total};
    }

    private static boolean crossCheckDiagonal(
            boolean[] bin, int w, int h, int cx, int cy, int maxCenterCount, double origTotal) {
        return checkDiagDirection(bin, w, h, cx, cy, 1, 1, maxCenterCount, origTotal)
                || checkDiagDirection(bin, w, h, cx, cy, 1, -1, maxCenterCount, origTotal);
    }

    private static boolean checkDiagDirection(
            boolean[] bin, int w, int h, int cx, int cy, int dx, int dy, int maxCenterCount, double origTotal) {
        if (!bin[cy * w + cx]) return false;
        int[] c = new int[5];
        int maxC = Math.max(4, maxCenterCount * 2);

        int x = cx;
        int y = cy;
        while (inBounds(x, y, w, h) && bin[y * w + x]) { c[2]++; x -= dx; y -= dy; }
        if (!inBounds(x, y, w, h) || c[2] > maxC) return false;
        while (inBounds(x, y, w, h) && !bin[y * w + x] && c[1] <= maxC) { c[1]++; x -= dx; y -= dy; }
        if (!inBounds(x, y, w, h) || c[1] == 0 || c[1] > maxC) return false;
        while (inBounds(x, y, w, h) && bin[y * w + x] && c[0] <= maxC) { c[0]++; x -= dx; y -= dy; }
        if (c[0] == 0 || c[0] > maxC) return false;

        x = cx + dx;
        y = cy + dy;
        while (inBounds(x, y, w, h) && bin[y * w + x]) { c[2]++; x += dx; y += dy; }
        if (!inBounds(x, y, w, h) || c[2] > maxC) return false;
        while (inBounds(x, y, w, h) && !bin[y * w + x] && c[3] <= maxC) { c[3]++; x += dx; y += dy; }
        if (!inBounds(x, y, w, h) || c[3] == 0 || c[3] > maxC) return false;
        while (inBounds(x, y, w, h) && bin[y * w + x] && c[4] <= maxC) { c[4]++; x += dx; y += dy; }
        if (c[4] == 0 || c[4] > maxC) return false;

        int total = c[0] + c[1] + c[2] + c[3] + c[4];
        if (total < 7 || Math.abs(total - origTotal) > origTotal * 0.75) return false;
        return matchesFinderRatio(c, 0.72);
    }

    /** Order 3 finder centers into {TL, TR, BL} regardless of 0°/90°/180°/270° or arbitrary rotation. */
    private static FinderCenter[] orderTriple(FinderCenter a, FinderCenter b, FinderCenter c) {
        double minMod = Math.min(a.moduleSize, Math.min(b.moduleSize, c.moduleSize));
        double maxMod = Math.max(a.moduleSize, Math.max(b.moduleSize, c.moduleSize));
        if (minMod <= 0.0 || maxMod / minMod > 2.2) return null;

        double dab = distSq(a, b);
        double dbc = distSq(b, c);
        double dca = distSq(c, a);

        FinderCenter tl;
        FinderCenter p1;
        FinderCenter p2;
        if (dbc >= dab && dbc >= dca) {
            tl = a; p1 = b; p2 = c;
        } else if (dca >= dab && dca >= dbc) {
            tl = b; p1 = a; p2 = c;
        } else {
            tl = c; p1 = a; p2 = b;
        }

        double d1 = Math.hypot(p1.x - tl.x, p1.y - tl.y);
        double d2 = Math.hypot(p2.x - tl.x, p2.y - tl.y);
        if (d1 < minMod * 8.0 || d2 < minMod * 8.0) return null;
        if (Math.max(d1, d2) / Math.min(d1, d2) > 2.2) return null;

        double dot = (p1.x - tl.x) * (p2.x - tl.x) + (p1.y - tl.y) * (p2.y - tl.y);
        double cosTheta = dot / (d1 * d2);
        if (Math.abs(cosTheta) > 0.72) return null;

        // In image coords (+x right, +y down): (TR - TL) x (BL - TL) > 0
        double cross = (p1.x - tl.x) * (p2.y - tl.y) - (p1.y - tl.y) * (p2.x - tl.x);
        if (Math.abs(cross) < 1e-3) return null;
        return cross > 0.0 ? new FinderCenter[]{tl, p1, p2} : new FinderCenter[]{tl, p2, p1};
    }

    private static String decodeFromOrderedFinders(
            boolean[] bin, int w, int h, FinderCenter tl, FinderCenter tr, FinderCenter bl) {
        double avgMod = (tl.moduleSize + tr.moduleSize + bl.moduleSize) / 3.0;
        double dTop = Math.hypot(tr.x - tl.x, tr.y - tl.y);
        double dLeft = Math.hypot(bl.x - tl.x, bl.y - tl.y);
        int estDim = (int) Math.round(((dTop + dLeft) / (2.0 * avgMod))) + 7;
        int estVersion = clamp((int) Math.round((estDim - 17.0) / 4.0), QrIsoSpec.MIN_VERSION, QrIsoSpec.MAX_VERSION);

        // Try estimated version first, then ±1 (and ±2 for strong perspective foreshortening)
        int[] versionOrder = {estVersion, estVersion - 1, estVersion + 1, estVersion - 2, estVersion + 2};
        boolean[] tried = new boolean[QrIsoSpec.MAX_VERSION + 1];

        // Projective 3D depth estimate for BR from apparent finder scales (Z ~ 1/moduleSize)
        double[] projBr = estimateProjectiveBottomRight(tl, tr, bl);
        double affBrX = tr.x + bl.x - tl.x;
        double affBrY = tr.y + bl.y - tl.y;

        for (int v : versionOrder) {
            if (v < QrIsoSpec.MIN_VERSION || v > QrIsoSpec.MAX_VERSION || tried[v]) continue;
            tried[v] = true;
            int dim = QrIsoSpec.dimensionForVersion(v);

            if (v >= 2) {
                double f = (dim - 10.0) / (dim - 7.0);
                // 1. Search alignment pattern around projective estimate (accurate under strong 3D tilt)
                if (projBr != null) {
                    PerspectiveTransform rough = PerspectiveTransform.quadToQuad(
                            3.5, 3.5, dim - 3.5, 3.5, dim - 3.5, dim - 3.5, 3.5, dim - 3.5,
                            tl.x, tl.y, tr.x, tr.y, projBr[0], projBr[1], bl.x, bl.y);
                    if (rough != null) {
                        double[] estA = rough.transform(dim - 6.5, dim - 6.5);
                        double[] align = findAlignmentPattern(bin, w, h, estA[0], estA[1], avgMod, Math.max(10.0, avgMod * 5.0));
                        if (align != null) {
                            String res = sampleAndDecode(bin, w, h, dim, tl, tr, bl, dim - 6.5, align[0], align[1]);
                            if (res != null) return res;
                        }
                    }
                }
                // 2. Search alignment pattern around affine estimate
                double affAx = tl.x + f * ((tr.x - tl.x) + (bl.x - tl.x));
                double affAy = tl.y + f * ((tr.y - tl.y) + (bl.y - tl.y));
                double[] align = findAlignmentPattern(bin, w, h, affAx, affAy, avgMod, Math.max(10.0, avgMod * 5.5));
                if (align != null) {
                    String res = sampleAndDecode(bin, w, h, dim, tl, tr, bl, dim - 6.5, align[0], align[1]);
                    if (res != null) return res;
                }
            }

            // 3. Affine BR fallback (exact for flat/rotated v1..v10)
            String affineRes = sampleAndDecode(bin, w, h, dim, tl, tr, bl, dim - 3.5, affBrX, affBrY);
            if (affineRes != null) return affineRes;

            // 4. Projective-scale BR fallback (handles perspective-skewed v1 or occluded alignment pattern)
            if (projBr != null) {
                String projRes = sampleAndDecode(bin, w, h, dim, tl, tr, bl, dim - 3.5, projBr[0], projBr[1]);
                if (projRes != null) return projRes;
            }
        }
        return null;
    }

    /**
     * Estimate bottom-right finder corner in 3D projective coordinates from apparent finder scales:
     * in 3D world space P_br = P_tr + P_bl - P_tl with depth Z_i proportional to 1 / moduleSize_i.
     */
    private static double[] estimateProjectiveBottomRight(FinderCenter tl, FinderCenter tr, FinderCenter bl) {
        if (tl.moduleSize <= 0.0 || tr.moduleSize <= 0.0 || bl.moduleSize <= 0.0) return null;
        double zTl = 1.0 / tl.moduleSize;
        double zTr = 1.0 / tr.moduleSize;
        double zBl = 1.0 / bl.moduleSize;
        double zBr = zTr + zBl - zTl;
        if (zBr <= 1e-6) return null;
        double x = (zTr * tr.x + zBl * bl.x - zTl * tl.x) / zBr;
        double y = (zTr * tr.y + zBl * bl.y - zTl * tl.y) / zBr;
        return new double[]{x, y};
    }

    /** Locate a 5×5 alignment pattern (dark center, light 3×3 ring, dark 5×5 outer border) near (estX, estY). */
    private static double[] findAlignmentPattern(
            boolean[] bin, int w, int h, double estX, double estY, double mod, double radius) {
        int minX = clamp((int) Math.floor(estX - radius), 1, w - 2);
        int maxX = clamp((int) Math.ceil(estX + radius), 1, w - 2);
        int minY = clamp((int) Math.floor(estY - radius), 1, h - 2);
        int maxY = clamp((int) Math.ceil(estY + radius), 1, h - 2);
        int maxMod = Math.max(2, (int) Math.ceil(mod * 2.2));

        double bestX = 0.0;
        double bestY = 0.0;
        double bestScore = Double.POSITIVE_INFINITY;
        boolean found = false;

        int[] c = new int[3];
        for (int y = minY; y <= maxY; y++) {
            int rowOff = y * w;
            Arrays.fill(c, 0);
            int state = 0; // 0: white ring left, 1: dark center, 2: white ring right
            for (int x = minX; x <= maxX; x++) {
                boolean dark = bin[rowOff + x];
                if (dark) {
                    if (state == 1) {
                        c[1]++;
                    } else if (state == 0) {
                        if (c[0] > 0) {
                            state = 1;
                            c[1] = 1;
                        }
                    } else { // state == 2 -> hit right dark border
                        if (matchesAlignmentRatio(c, mod)) {
                            double cx = x - c[2] - c[1] * 0.5;
                            double cy = crossCheckAlignmentVertical(bin, w, h, (int) Math.floor(cx), y, maxMod, mod);
                            if (cy >= 0.0) {
                                double dist = Math.hypot(cx - estX, cy - estY);
                                if (dist < bestScore) {
                                    bestScore = dist;
                                    bestX = cx;
                                    bestY = cy;
                                    found = true;
                                }
                            }
                        }
                        c[0] = 0; c[1] = 0; c[2] = 0; state = 0;
                    }
                } else {
                    if (state == 0) {
                        c[0]++;
                    } else if (state == 1) {
                        state = 2;
                        c[2] = 1;
                    } else {
                        c[2]++;
                        if (c[2] > maxMod) {
                            c[0] = c[2]; c[1] = 0; c[2] = 0; state = 0;
                        }
                    }
                }
            }
        }
        return found ? new double[]{bestX, bestY} : null;
    }

    private static boolean matchesAlignmentRatio(int[] c, double mod) {
        if (c[0] <= 0 || c[1] <= 0 || c[2] <= 0) return false;
        double maxVar = Math.max(1.2, mod * 0.75);
        return Math.abs(c[0] - mod) <= maxVar
                && Math.abs(c[1] - mod) <= maxVar
                && Math.abs(c[2] - mod) <= maxVar;
    }

    private static double crossCheckAlignmentVertical(
            boolean[] bin, int w, int h, int cx, int startY, int maxMod, double mod) {
        if (!inBounds(cx, startY, w, h) || !bin[startY * w + cx]) return -1.0;
        int[] c = new int[3];
        int y = startY;
        while (y >= 0 && bin[y * w + cx] && c[1] <= maxMod) { c[1]++; y--; }
        if (y < 0 || c[1] > maxMod) return -1.0;
        while (y >= 0 && !bin[y * w + cx] && c[0] <= maxMod) { c[0]++; y--; }
        if (y < 0 || c[0] == 0 || c[0] > maxMod || !bin[y * w + cx]) return -1.0;

        y = startY + 1;
        while (y < h && bin[y * w + cx] && c[1] <= maxMod) { c[1]++; y++; }
        if (y >= h || c[1] > maxMod) return -1.0;
        while (y < h && !bin[y * w + cx] && c[2] <= maxMod) { c[2]++; y++; }
        if (y >= h || c[2] == 0 || c[2] > maxMod || !bin[y * w + cx]) return -1.0;

        if (!matchesAlignmentRatio(c, mod)) return -1.0;
        return y - c[2] - c[1] * 0.5;
    }

    private static String sampleAndDecode(
            boolean[] bin, int w, int h, int dim,
            FinderCenter tl, FinderCenter tr, FinderCenter bl,
            double brSrcCoord, double brDstX, double brDstY) {
        PerspectiveTransform transform = PerspectiveTransform.quadToQuad(
                3.5, 3.5,
                dim - 3.5, 3.5,
                brSrcCoord, brSrcCoord,
                3.5, dim - 3.5,
                tl.x, tl.y,
                tr.x, tr.y,
                brDstX, brDstY,
                bl.x, bl.y);
        if (transform == null) return null;

        boolean[] modules = new boolean[dim * dim];
        for (int my = 0; my < dim; my++) {
            double sy = my + 0.5;
            int rowOff = my * dim;
            for (int mx = 0; mx < dim; mx++) {
                double[] p = transform.transform(mx + 0.5, sy);
                int ix = (int) Math.floor(p[0]);
                int iy = (int) Math.floor(p[1]);
                if (ix < 0 || ix >= w || iy < 0 || iy >= h) return null;
                modules[rowOff + mx] = bin[iy * w + ix];
            }
        }
        return decodeMatrix(modules, dim);
    }

    // -------------------------------------------------------------------------
    // ISO/IEC 18004 matrix decoding (format, version, unmask, RS ECC, segments)
    // -------------------------------------------------------------------------

    /** Decode a sampled {@code size × size} QR module grid (Versions 1–10, EC L/M/Q/H). */
    static String decodeMatrix(boolean[] modules, int size) {
        if (modules == null || size < 21 || size > 57 || ((size - 17) & 3) != 0
                || modules.length != size * size) {
            return null;
        }
        int version = (size - 17) / 4;

        // Cross-check version info for v7..10 if readable
        if (version >= 7) {
            int rawV1 = 0;
            int rawV2 = 0;
            for (int i = 0; i < 18; i++) {
                int a = size - 11 + (i % 3);
                int b = i / 3;
                if (modules[b * size + a]) rawV1 |= (1 << i);
                if (modules[a * size + b]) rawV2 |= (1 << i);
            }
            int v1 = QrIsoSpec.decodeVersionBits(rawV1);
            int v2 = QrIsoSpec.decodeVersionBits(rawV2);
            if (v1 > 0 && v1 != version && v2 != version) return null;
            if (v2 > 0 && v2 != version && v1 != version) return null;
        }

        // Read both 15-bit format info copies
        int fmtCopy1 = readFormatCopy1(modules, size);
        int fmtCopy2 = readFormatCopy2(modules, size);
        int decodedFmt = pickBestFormat(fmtCopy1, fmtCopy2);
        if (decodedFmt < 0) return null;

        int ecLevel = (decodedFmt >>> 3) & 0x3;
        int mask = decodedFmt & 0x7;
        int[] spec = QrIsoSpec.ecSpec(ecLevel, version);
        int totalDataCw = spec[0];
        int ecLen = spec[1];
        int g1Blocks = spec[2];
        int g1Len = spec[3];
        int g2Blocks = spec[4];
        int g2Len = spec[5];
        int totalBlocks = g1Blocks + g2Blocks;
        int totalCodewords = QrIsoSpec.totalCodewords(spec);

        // Read raw interleaved codewords in ISO zigzag order while unmasking
        boolean[] fn = QrIsoSpec.buildFunctionMask(version);
        byte[] raw = new byte[totalCodewords];
        int bitIdx = 0;
        int totalBits = totalCodewords * 8;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                boolean upward = (((right + 1) & 2) == 0);
                int y = upward ? (size - 1 - vert) : vert;
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    int idx = y * size + x;
                    if (!fn[idx] && bitIdx < totalBits) {
                        boolean bit = modules[idx] ^ QrIsoSpec.maskBit(mask, x, y);
                        if (bit) {
                            raw[bitIdx >>> 3] |= (byte) (1 << (7 - (bitIdx & 7)));
                        }
                        bitIdx++;
                    }
                }
            }
        }
        if (bitIdx != totalBits) return null;

        // De-interleave into (data + ec) blocks and run GF(256) Reed-Solomon error correction
        byte[][] blocks = new byte[totalBlocks][];
        int[] dataLens = new int[totalBlocks];
        for (int b = 0; b < totalBlocks; b++) {
            dataLens[b] = b < g1Blocks ? g1Len : g2Len;
            blocks[b] = new byte[dataLens[b] + ecLen];
        }
        int pos = 0;
        int maxDataLen = Math.max(g1Len, g2Len);
        for (int col = 0; col < maxDataLen; col++) {
            for (int b = 0; b < totalBlocks; b++) {
                if (col < dataLens[b]) blocks[b][col] = raw[pos++];
            }
        }
        for (int col = 0; col < ecLen; col++) {
            for (int b = 0; b < totalBlocks; b++) {
                blocks[b][dataLens[b] + col] = raw[pos++];
            }
        }

        byte[] dataCodewords = new byte[totalDataCw];
        int dst = 0;
        for (int b = 0; b < totalBlocks; b++) {
            if (!QrIsoSpec.reedSolomonDecode(blocks[b], ecLen)) {
                return null;
            }
            System.arraycopy(blocks[b], 0, dataCodewords, dst, dataLens[b]);
            dst += dataLens[b];
        }

        return decodeBitStream(dataCodewords, version);
    }

    private static int readFormatCopy1(boolean[] m, int size) {
        int bits = 0;
        for (int i = 0; i <= 5; i++) if (m[i * size + 8]) bits |= (1 << i);
        if (m[7 * size + 8]) bits |= (1 << 6);
        if (m[8 * size + 8]) bits |= (1 << 7);
        if (m[8 * size + 7]) bits |= (1 << 8);
        for (int i = 9; i < 15; i++) if (m[8 * size + (14 - i)]) bits |= (1 << i);
        return bits;
    }

    private static int readFormatCopy2(boolean[] m, int size) {
        int bits = 0;
        for (int i = 0; i < 8; i++) if (m[8 * size + (size - 1 - i)]) bits |= (1 << i);
        for (int i = 8; i < 15; i++) if (m[(size - 15 + i) * size + 8]) bits |= (1 << i);
        return bits;
    }

    private static int pickBestFormat(int copy1, int copy2) {
        int f1 = QrIsoSpec.decodeFormatBits(copy1);
        int f2 = QrIsoSpec.decodeFormatBits(copy2);
        if (f1 < 0) return f2;
        if (f2 < 0 || f1 == f2) return f1;
        int d1 = Integer.bitCount((copy1 & 0x7FFF) ^ QrIsoSpec.computeFormatBits(f1 >>> 3, f1 & 7));
        int d2 = Integer.bitCount((copy2 & 0x7FFF) ^ QrIsoSpec.computeFormatBits(f2 >>> 3, f2 & 7));
        return d1 <= d2 ? f1 : f2;
    }

    private static String decodeBitStream(byte[] data, int version) {
        BitReader reader = new BitReader(data);
        StringBuilder out = new StringBuilder();
        Charset byteCharset = StandardCharsets.UTF_8;
        boolean sawSegment = false;

        while (reader.available() >= 4) {
            int mode = reader.readBits(4);
            if (mode == 0b0000) { // Terminator
                break;
            } else if (mode == 0b0111) { // ECI assignment
                int eci = parseEciValue(reader);
                if (eci < 0) return null;
                byteCharset = (eci == 3) ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8;
            } else if (mode == 0b0101) { // FNC1 in first position (0 extra bits)
                continue;
            } else if (mode == 0b1001) { // FNC1 in second position (ISO/IEC 18004 §7.4.8.2: 8-bit AI)
                if (reader.available() < 8) return null;
                reader.readBits(8);
                continue;
            } else if (mode == 0b0001) { // Numeric
                int countBits = version <= 9 ? 10 : 12;
                if (reader.available() < countBits) return null;
                int count = reader.readBits(countBits);
                if (!decodeNumeric(reader, count, out)) return null;
                sawSegment = true;
            } else if (mode == 0b0010) { // Alphanumeric
                int countBits = version <= 9 ? 9 : 11;
                if (reader.available() < countBits) return null;
                int count = reader.readBits(countBits);
                if (!decodeAlphanumeric(reader, count, out)) return null;
                sawSegment = true;
            } else if (mode == 0b0100) { // Byte
                int countBits = version <= 9 ? 8 : 16;
                if (reader.available() < countBits) return null;
                int count = reader.readBits(countBits);
                if (reader.available() < count * 8) return null;
                byte[] bytes = new byte[count];
                for (int i = 0; i < count; i++) {
                    bytes[i] = (byte) reader.readBits(8);
                }
                out.append(new String(bytes, byteCharset));
                sawSegment = true;
            } else {
                return null;
            }
        }
        return sawSegment ? out.toString() : null;
    }

    private static int parseEciValue(BitReader reader) {
        if (reader.available() < 8) return -1;
        int first = reader.readBits(8);
        if ((first & 0x80) == 0) return first & 0x7F;
        if ((first & 0xC0) == 0x80) {
            if (reader.available() < 8) return -1;
            return ((first & 0x3F) << 8) | reader.readBits(8);
        }
        if ((first & 0xE0) == 0xC0) {
            if (reader.available() < 16) return -1;
            return ((first & 0x1F) << 16) | reader.readBits(16);
        }
        return -1;
    }

    private static boolean decodeNumeric(BitReader reader, int count, StringBuilder out) {
        int remaining = count;
        while (remaining >= 3) {
            if (reader.available() < 10) return false;
            int val = reader.readBits(10);
            if (val >= 1000) return false;
            out.append((char) ('0' + (val / 100)));
            out.append((char) ('0' + ((val / 10) % 10)));
            out.append((char) ('0' + (val % 10)));
            remaining -= 3;
        }
        if (remaining == 2) {
            if (reader.available() < 7) return false;
            int val = reader.readBits(7);
            if (val >= 100) return false;
            out.append((char) ('0' + (val / 10)));
            out.append((char) ('0' + (val % 10)));
        } else if (remaining == 1) {
            if (reader.available() < 4) return false;
            int val = reader.readBits(4);
            if (val >= 10) return false;
            out.append((char) ('0' + val));
        }
        return true;
    }

    private static boolean decodeAlphanumeric(BitReader reader, int count, StringBuilder out) {
        int remaining = count;
        while (remaining >= 2) {
            if (reader.available() < 11) return false;
            int val = reader.readBits(11);
            int c1 = val / 45;
            int c2 = val % 45;
            if (c1 >= ALPHANUMERIC_TABLE.length()) return false;
            out.append(ALPHANUMERIC_TABLE.charAt(c1));
            out.append(ALPHANUMERIC_TABLE.charAt(c2));
            remaining -= 2;
        }
        if (remaining == 1) {
            if (reader.available() < 6) return false;
            int val = reader.readBits(6);
            if (val >= ALPHANUMERIC_TABLE.length()) return false;
            out.append(ALPHANUMERIC_TABLE.charAt(val));
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // 3×3 Perspective Homography (quadrilateral-to-quadrilateral projective map)
    // -------------------------------------------------------------------------

    static final class PerspectiveTransform {
        private final double a11, a12, a13;
        private final double a21, a22, a23;
        private final double a31, a32, a33;

        private PerspectiveTransform(
                double a11, double a21, double a31,
                double a12, double a22, double a32,
                double a13, double a23, double a33) {
            this.a11 = a11; this.a12 = a12; this.a13 = a13;
            this.a21 = a21; this.a22 = a22; this.a23 = a23;
            this.a31 = a31; this.a32 = a32; this.a33 = a33;
        }

        static PerspectiveTransform quadToQuad(
                double x0, double y0, double x1, double y1, double x2, double y2, double x3, double y3,
                double u0, double v0, double u1, double v1, double u2, double v2, double u3, double v3) {
            PerspectiveTransform sToQ = squareToQuad(x0, y0, x1, y1, x2, y2, x3, y3);
            PerspectiveTransform qToD = squareToQuad(u0, v0, u1, v1, u2, v2, u3, v3);
            if (sToQ == null || qToD == null) return null;
            return qToD.times(sToQ.adjoint());
        }

        private static PerspectiveTransform squareToQuad(
                double x0, double y0, double x1, double y1, double x2, double y2, double x3, double y3) {
            double dx3 = x0 - x1 + x2 - x3;
            double dy3 = y0 - y1 + y2 - y3;
            if (Math.abs(dx3) < 1e-9 && Math.abs(dy3) < 1e-9) {
                return new PerspectiveTransform(
                        x1 - x0, x2 - x1, x0,
                        y1 - y0, y2 - y1, y0,
                        0.0, 0.0, 1.0);
            }
            double dx1 = x1 - x2;
            double dx2 = x3 - x2;
            double dy1 = y1 - y2;
            double dy2 = y3 - y2;
            double denom = dx1 * dy2 - dx2 * dy1;
            if (Math.abs(denom) < 1e-9) return null;
            double a13 = (dx3 * dy2 - dx2 * dy3) / denom;
            double a23 = (dx1 * dy3 - dx3 * dy1) / denom;
            return new PerspectiveTransform(
                    x1 - x0 + a13 * x1, x3 - x0 + a23 * x3, x0,
                    y1 - y0 + a13 * y1, y3 - y0 + a23 * y3, y0,
                    a13, a23, 1.0);
        }

        private PerspectiveTransform adjoint() {
            return new PerspectiveTransform(
                    a22 * a33 - a23 * a32, a23 * a31 - a21 * a33, a21 * a32 - a22 * a31,
                    a13 * a32 - a12 * a33, a11 * a33 - a13 * a31, a12 * a31 - a11 * a32,
                    a12 * a23 - a13 * a22, a13 * a21 - a11 * a23, a11 * a22 - a12 * a21);
        }

        private PerspectiveTransform times(PerspectiveTransform o) {
            return new PerspectiveTransform(
                    a11 * o.a11 + a21 * o.a12 + a31 * o.a13,
                    a11 * o.a21 + a21 * o.a22 + a31 * o.a23,
                    a11 * o.a31 + a21 * o.a32 + a31 * o.a33,
                    a12 * o.a11 + a22 * o.a12 + a32 * o.a13,
                    a12 * o.a21 + a22 * o.a22 + a32 * o.a23,
                    a12 * o.a31 + a22 * o.a32 + a32 * o.a33,
                    a13 * o.a11 + a23 * o.a12 + a33 * o.a13,
                    a13 * o.a21 + a23 * o.a22 + a33 * o.a23,
                    a13 * o.a31 + a23 * o.a32 + a33 * o.a33);
        }

        double[] transform(double x, double y) {
            double denom = a13 * x + a23 * y + a33;
            if (!Double.isFinite(denom) || Math.abs(denom) < 1e-9) return new double[]{-1.0, -1.0};
            double rx = (a11 * x + a21 * y + a31) / denom;
            double ry = (a12 * x + a22 * y + a32) / denom;
            if (!Double.isFinite(rx) || !Double.isFinite(ry)) return new double[]{-1.0, -1.0};
            return new double[]{rx, ry};
        }
    }

    private static final class BitReader {
        private final byte[] bytes;
        private int bitPos;

        BitReader(byte[] bytes) {
            this.bytes = bytes;
        }

        int available() {
            return bytes.length * 8 - bitPos;
        }

        int readBits(int count) {
            int val = 0;
            for (int i = 0; i < count; i++) {
                int b = (bytes[bitPos >>> 3] >>> (7 - (bitPos & 7))) & 1;
                val = (val << 1) | b;
                bitPos++;
            }
            return val;
        }
    }

    private static boolean inBounds(int x, int y, int w, int h) {
        return x >= 0 && x < w && y >= 0 && y < h;
    }

    private static double distSq(FinderCenter a, FinderCenter b) {
        double dx = a.x - b.x;
        double dy = a.y - b.y;
        return dx * dx + dy * dy;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
