package org.llm4people.hermes.artifact;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.JsonReader;
import android.util.JsonToken;
import android.view.View;
import java.io.IOException;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.llm4people.hermes.Ui;

import static org.llm4people.hermes.artifact.WorkspaceLayout.invalid;
import static org.llm4people.hermes.artifact.WorkspaceLayout.require;

/** Zero-dependency native Canvas sparkline & bar/progress chart with deterministic bounds and TalkBack summary. */
public final class SparklineView extends View {
    public static final int MIN_POINTS = 2;
    public static final int MAX_POINTS = 64;
    public static final List<String> STYLES = Collections.unmodifiableList(Arrays.asList("line", "bar"));

    /** Pure-Java validated data series and normalized geometry coordinates. */
    public static final class Series {
        public final List<Double> values;
        public final String style;     // line | bar
        public final String unit;      // optional
        public final String caption;   // optional
        public final Double target;    // optional reference/goal line
        public final double min;
        public final double max;
        public final double latest;
        public final double delta;
        public final float[] normalized; // values in [0f, 1f]

        private Series(List<Double> values, String style, String unit, String caption,
                       Double target, double min, double max) {
            this.values = Collections.unmodifiableList(values);
            this.style = style;
            this.unit = unit == null ? "" : unit;
            this.caption = caption == null ? "" : caption;
            this.target = target;
            this.min = min;
            this.max = max;
            this.latest = values.get(values.size() - 1);
            this.delta = latest - values.get(0);
            this.normalized = new float[values.size()];
            double span = max - min;
            for (int i = 0; i < values.size(); i++) {
                normalized[i] = span <= 0 ? 0.5f : (float) ((values.get(i) - min) / span);
            }
        }

        public static Series of(List<Double> rawValues, String style, String unit, String caption,
                                Double target, Double explicitMin, Double explicitMax) throws IOException {
            require(rawValues != null && rawValues.size() >= MIN_POINTS && rawValues.size() <= MAX_POINTS);
            String safeStyle = style == null || style.isEmpty() ? "line" : style;
            require(STYLES.contains(safeStyle));
            List<Double> clean = new ArrayList<>(rawValues.size());
            double computedMin = Double.POSITIVE_INFINITY;
            double computedMax = Double.NEGATIVE_INFINITY;
            for (Double d : rawValues) {
                require(d != null && Double.isFinite(d));
                clean.add(d);
                if (d < computedMin) computedMin = d;
                if (d > computedMax) computedMax = d;
            }
            if (target != null) {
                require(Double.isFinite(target));
                if (target < computedMin) computedMin = target;
                if (target > computedMax) computedMax = target;
            }
            double min = explicitMin != null ? explicitMin : ("bar".equals(safeStyle) && computedMin > 0 ? 0.0 : computedMin);
            double max = explicitMax != null ? explicitMax : computedMax;
            require(Double.isFinite(min) && Double.isFinite(max) && max >= min);
            if (computedMin < min) min = computedMin;
            if (computedMax > max) max = computedMax;
            return new Series(clean, safeStyle, unit, caption, target, min, max);
        }

        public float normalizedTarget() {
            if (target == null) return -1f;
            double span = max - min;
            return span <= 0 ? 0.5f : (float) ((target - min) / span);
        }

        public String accessibilitySummary() {
            NumberFormat fmt = NumberFormat.getNumberInstance(Locale.FRANCE);
            fmt.setMaximumFractionDigits(2);
            String suffix = unit.isEmpty() ? "" : " " + unit;
            String kind = "bar".equals(style) ? "Graphique en barres" : "Courbe d’évolution";
            StringBuilder sb = new StringBuilder(kind)
                    .append(" · ").append(values.size()).append(" points, ")
                    .append("minimum ").append(fmt.format(min)).append(suffix)
                    .append(", maximum ").append(fmt.format(max)).append(suffix)
                    .append(", dernière valeur ").append(fmt.format(latest)).append(suffix);
            if (!caption.isEmpty()) sb.append(". ").append(caption);
            return sb.toString();
        }
    }

    static List<Double> readNumbers(JsonReader in) throws IOException {
        List<Double> values = new ArrayList<>();
        in.beginArray();
        while (in.hasNext()) {
            require(values.size() < MAX_POINTS && in.peek() == JsonToken.NUMBER);
            double d = in.nextDouble();
            require(Double.isFinite(d));
            values.add(d);
        }
        in.endArray();
        require(values.size() >= MIN_POINTS);
        return values;
    }

    static double readFiniteDouble(JsonReader in) throws IOException {
        require(in.peek() == JsonToken.NUMBER);
        double d = in.nextDouble();
        if (!Double.isFinite(d)) throw invalid();
        return d;
    }

    private final Series series;
    private final ThemePalette palette;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint targetPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path linePath = new Path();
    private final Path fillPath = new Path();
    private final RectF frameRect = new RectF();
    private final RectF barRect = new RectF();
    private final float dp;
    private final int desiredWidthPx;
    private final int desiredHeightPx;
    private final int mutedBarColor;

    public SparklineView(Context context, Series series, ThemePalette palette) {
        super(context);
        this.series = series;
        this.palette = palette == null ? ThemePalette.resolve(ThemePalette.OverrideSpec.defaults(), ThemePalette.isNight(context)) : palette;
        this.dp = context.getResources().getDisplayMetrics().density;
        this.desiredWidthPx = Ui.dp(context, 240);
        this.desiredHeightPx = Ui.dp(context, 96);
        this.mutedBarColor = ThemePalette.blendTint(this.palette.accent, this.palette.paper, 0.45f);
        targetPaint.setStyle(Paint.Style.STROKE);
        targetPaint.setStrokeWidth(1.25f * dp);
        targetPaint.setColor(this.palette.muted);
        targetPaint.setPathEffect(new DashPathEffect(new float[]{4 * dp, 4 * dp}, 0));
        setContentDescription(series.accessibilitySummary());
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int h = resolveSize(desiredHeightPx, heightMeasureSpec);
        int w = resolveSize(desiredWidthPx, widthMeasureSpec);
        setMeasuredDimension(w, h);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float padX = 8 * dp;
        float padY = 10 * dp;
        float w = getWidth() - 2 * padX;
        float h = getHeight() - 2 * padY;
        if (w <= 4 || h <= 4) return;

        // Calm paper surface frame
        float corner = 8 * dp;
        frameRect.set(0, 0, getWidth(), getHeight());
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(palette.surface);
        canvas.drawRoundRect(frameRect, corner, corner, paint);
        float halfStroke = 0.5f * dp;
        frameRect.set(halfStroke, halfStroke, getWidth() - halfStroke, getHeight() - halfStroke);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, dp));
        paint.setColor(palette.line);
        canvas.drawRoundRect(frameRect, corner, corner, paint);

        // Optional goal reference line
        float targetNorm = series.normalizedTarget();
        if (targetNorm >= 0f) {
            float ty = padY + h * (1f - targetNorm);
            canvas.drawLine(padX, ty, padX + w, ty, targetPaint);
        }

        int n = series.normalized.length;
        if ("bar".equals(series.style)) {
            float gap = Math.max(2 * dp, w / (n * 5f));
            float barW = Math.max(2 * dp, (w - gap * (n - 1)) / n);
            float barCorner = 3 * dp;
            paint.setStyle(Paint.Style.FILL);
            for (int i = 0; i < n; i++) {
                float left = padX + i * (barW + gap);
                float barH = Math.max(barCorner, h * series.normalized[i]);
                float top = padY + h - barH;
                paint.setColor(i == n - 1 ? palette.accent : mutedBarColor);
                barRect.set(left, top, left + barW, padY + h);
                canvas.drawRoundRect(barRect, barCorner, barCorner, paint);
            }
            return;
        }

        // Line sparkline with subtle accent-surface area fill
        linePath.rewind();
        fillPath.rewind();
        float stepX = w / (n - 1);
        for (int i = 0; i < n; i++) {
            float px = padX + i * stepX;
            float py = padY + h * (1f - series.normalized[i]);
            if (i == 0) {
                linePath.moveTo(px, py);
                fillPath.moveTo(px, padY + h);
                fillPath.lineTo(px, py);
            } else {
                linePath.lineTo(px, py);
                fillPath.lineTo(px, py);
            }
        }
        fillPath.lineTo(padX + w, padY + h);
        fillPath.close();

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(palette.accentSurface);
        canvas.drawPath(fillPath, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.2f * dp);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(palette.accent);
        canvas.drawPath(linePath, paint);

        // Highlight latest measurement dot
        float lastX = padX + w;
        float lastY = padY + h * (1f - series.normalized[n - 1]);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(palette.accent);
        canvas.drawCircle(lastX, lastY, 4 * dp, paint);
    }
}
