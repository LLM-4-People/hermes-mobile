package org.llm4people.hermes.voice;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;

/** Lightweight Canvas RMS voice-level meter. Purely decorative; status live region informs TalkBack. */
public final class AudioLevelMeter extends View {
    private static final int BARS = 7;
    private static final float[] WEIGHTS = {0.45f, 0.72f, 0.92f, 1.0f, 0.92f, 0.72f, 0.45f};
    private static final float MIN_DB = -2.0f;
    private static final float MAX_DB = 10.0f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF barRect = new RectF();
    private final float[] levels = new float[BARS];
    private final int activeColor;
    private final int idleColor;
    private final int defaultWidthPx;
    private final int defaultHeightPx;
    private boolean active;
    private float smoothed;
    private int phase;

    public AudioLevelMeter(Context context) {
        super(context);
        activeColor = context.getColor(R.color.accent);
        idleColor = context.getColor(R.color.line);
        defaultWidthPx = Ui.dp(context, 54);
        defaultHeightPx = Ui.dp(context, 24);
        paint.setStyle(Paint.Style.FILL);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** Map Android SpeechRecognizer RMS dB (roughly -2..10 dB) into [0, 1]. */
    public static float normalizeRms(float rmsdB) {
        if (Float.isNaN(rmsdB) || Float.isInfinite(rmsdB)) return 0f;
        float ratio = (rmsdB - MIN_DB) / (MAX_DB - MIN_DB);
        return Math.max(0f, Math.min(1f, ratio));
    }

    public void setActive(boolean listening) {
        if (active == listening) return;
        active = listening;
        if (!listening) reset();
        else invalidate();
    }

    public void setRms(float rmsdB) {
        float target = normalizeRms(rmsdB);
        // Fast attack, gentle release for fluid visual motion without jitter.
        smoothed = target > smoothed ? smoothed * 0.35f + target * 0.65f : smoothed * 0.72f + target * 0.28f;
        phase = (phase + 1) % 60;
        for (int i = 0; i < BARS; i++) {
            float ripple = 0.85f + 0.15f * (float) Math.sin((phase * 0.4f) + i * 0.9f);
            levels[i] = Math.max(0.14f, Math.min(1f, smoothed * WEIGHTS[i] * ripple + 0.12f));
        }
        active = true;
        invalidate();
    }

    public void reset() {
        active = false;
        smoothed = 0f;
        for (int i = 0; i < BARS; i++) levels[i] = 0.16f;
        invalidate();
    }

    float currentLevel() { return smoothed; }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(resolveSize(defaultWidthPx, widthMeasureSpec),
                resolveSize(defaultHeightPx, heightMeasureSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth() - getPaddingLeft() - getPaddingRight();
        int h = getHeight() - getPaddingTop() - getPaddingBottom();
        if (w <= 0 || h <= 0) return;

        paint.setColor(active ? activeColor : idleColor);
        float gap = Math.max(2f, w / (BARS * 3f));
        float barW = Math.max(2f, (w - gap * (BARS - 1)) / BARS);
        float radius = barW / 2f;
        float centerY = getPaddingTop() + h / 2f;
        float x = getPaddingLeft();

        for (int i = 0; i < BARS; i++) {
            float level = active ? Math.max(0.16f, levels[i]) : 0.16f;
            float barH = Math.max(barW, h * level);
            barRect.set(x, centerY - barH / 2f, x + barW, centerY + barH / 2f);
            canvas.drawRoundRect(barRect, radius, radius, paint);
            x += barW + gap;
        }
    }
}
