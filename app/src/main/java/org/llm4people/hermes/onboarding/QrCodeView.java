package org.llm4people.hermes.onboarding;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import org.llm4people.hermes.Ui;

/**
 * Zero-dependency vector Android {@link View} rendering a {@link QrCodeMatrix} onto a crisp
 * high-contrast white quiet-zone canvas (always scannable in both light and dark themes).
 * Zero allocations inside {@link #onDraw(Canvas)}.
 */
public final class QrCodeView extends View {
    public static final int QUIET_ZONE_MODULES = 4;
    private static final int LIGHT_GROUND = 0xFFFFFFFF;
    private static final int DARK_MODULE = 0xFF172521;
    private static final int BORDER_STROKE = 0xFFCED4CB;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint modulePaint = new Paint();
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final float cornerRadiusPx;
    private QrCodeMatrix matrix;

    public QrCodeView(Context context) {
        super(context);
        bgPaint.setStyle(Paint.Style.FILL);
        bgPaint.setColor(LIGHT_GROUND);
        modulePaint.setStyle(Paint.Style.FILL);
        modulePaint.setColor(DARK_MODULE);
        modulePaint.setAntiAlias(false);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setColor(BORDER_STROKE);
        borderPaint.setStrokeWidth(Math.max(1f, Ui.dp(context, 1)));
        cornerRadiusPx = Ui.dp(context, 14);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    public void setMatrix(QrCodeMatrix matrix) {
        this.matrix = matrix;
        invalidate();
    }

    public boolean setPayload(String payload) {
        try {
            this.matrix = QrCodeMatrix.encode(payload);
            invalidate();
            return true;
        } catch (IllegalArgumentException tooLongOrEmpty) {
            this.matrix = null;
            invalidate();
            return false;
        }
    }

    public QrCodeMatrix matrix() {
        return matrix;
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int preferred = Ui.dp(getContext(), 220);
        int w = resolveSize(preferred, widthMeasureSpec);
        int h = resolveSize(preferred, heightMeasureSpec);
        int side = Math.min(w, h);
        if (side <= 0) side = Math.max(w, h);
        if (side <= 0) side = preferred;
        setMeasuredDimension(side, side);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        int side = Math.min(w, h);
        if (side <= 0) return;

        float left = (w - side) / 2f;
        float top = (h - side) / 2f;
        float inset = borderPaint.getStrokeWidth() / 2f;
        bounds.set(left + inset, top + inset, left + side - inset, top + side - inset);
        canvas.drawRoundRect(bounds, cornerRadiusPx, cornerRadiusPx, bgPaint);
        canvas.drawRoundRect(bounds, cornerRadiusPx, cornerRadiusPx, borderPaint);

        QrCodeMatrix current = matrix;
        if (current == null) return;
        int n = current.size();
        int totalModules = n + QUIET_ZONE_MODULES * 2;
        float cell = (float) side / totalModules;
        float originX = left + QUIET_ZONE_MODULES * cell;
        float originY = top + QUIET_ZONE_MODULES * cell;

        for (int y = 0; y < n; y++) {
            float py = originY + y * cell;
            float pyBottom = originY + (y + 1) * cell;
            for (int x = 0; x < n; x++) {
                if (current.get(x, y)) {
                    float px = originX + x * cell;
                    float pxRight = originX + (x + 1) * cell;
                    canvas.drawRect(px, py, pxRight, pyBottom, modulePaint);
                }
            }
        }
    }
}
