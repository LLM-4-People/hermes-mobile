package org.llm4people.hermes.places;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import org.llm4people.hermes.R;
import java.util.List;
import java.util.function.IntConsumer;

/** A north-up distance diagram. All points come from the current attributed result set. */
final class PlaceCompass extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<NearbyResult.Place> places;
    private final float[] east, north;
    private final int radiusMeters;
    private final IntConsumer choose;
    private final float dp;
    private final int ground, grid, label, highlight;
    private int selected;
    private float downX, downY;

    PlaceCompass(Context c, NearbyResult result, double lat, double lon, int selected, IntConsumer choose) {
        super(c);
        this.places = result.places;
        this.radiusMeters = result.radiusMeters;
        this.choose = choose;
        this.selected = selected;
        dp = c.getResources().getDisplayMetrics().density;
        ground = c.getColor(R.color.places_chart);
        grid = c.getColor(R.color.places_grid);
        label = c.getColor(R.color.places_label);
        highlight = c.getColor(R.color.places_focus);
        east = new float[places.size()];
        north = new float[places.size()];
        for (int i = 0; i < places.size(); i++) {
            NearbyResult.Place p = places.get(i);
            double angle = bearing(lat, lon, p.latitude, p.longitude);
            double range = Math.min(1.0, (double) p.distanceMeters / radiusMeters);
            east[i] = (float) (Math.sin(angle) * range);
            north[i] = (float) (Math.cos(angle) * range);
        }
        setFocusable(true);
        setClickable(true);
        setContentDescription("Repères des restaurants, nord en haut. " + places.get(selected).name);
    }

    static double bearing(double lat, double lon, double pointLat, double pointLon) {
        double a = Math.toRadians(lat), b = Math.toRadians(pointLat), delta = Math.toRadians(pointLon - lon);
        return Math.atan2(Math.sin(delta) * Math.cos(b), Math.cos(a) * Math.sin(b)
                - Math.sin(a) * Math.cos(b) * Math.cos(delta));
    }

    // Use the available space for comparing the returned places; the scale always describes the outer circle.
    private int scale() {
        int farthest = places.get(places.size() - 1).distanceMeters;
        for (int step : new int[]{250, 500, 750, 1000}) if (farthest <= step) return step;
        return radiusMeters;
    }

    private float radius() { return Math.max(1, Math.min(getWidth() * .45f - 10 * dp, getHeight() * .5f - 16 * dp)); }
    private float cx() { return getWidth() * .50f; }
    private float cy() { return getHeight() * .5f; }
    private float pointX(int i) { return cx() + east[i] * radius() * radiusMeters / scale(); }
    private float pointY(int i) { return cy() - north[i] * radius() * radiusMeters / scale(); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float x = cx(), y = cy(), r = radius();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(ground);
        canvas.drawRoundRect(new RectF(0, 0, getWidth(), getHeight()), 9 * dp, 9 * dp, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp);
        paint.setColor(grid);
        canvas.drawCircle(x, y, r, paint);
        canvas.drawCircle(x, y, r * .5f, paint);
        canvas.drawLine(12 * dp, y, getWidth() - 12 * dp, y, paint);
        canvas.drawLine(x, y - r - 4 * dp, x, y + r + 4 * dp, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(label);
        canvas.drawCircle(x, y, 2.5f * dp, paint);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setTextSize(12 * dp);
        canvas.drawText("N", x - paint.measureText("N") / 2, 12 * dp, paint);
        canvas.drawText(NearbyGuide.distance(scale()), 10 * dp, getHeight() - 10 * dp, paint);
        for (int i = 0; i < places.size(); i++) if (i != selected) {
            paint.setColor(ground);
            canvas.drawCircle(pointX(i), pointY(i), 5.5f * dp, paint);
            paint.setColor(label);
            canvas.drawCircle(pointX(i), pointY(i), 3.7f * dp, paint);
        }
        float sx = pointX(selected), sy = pointY(selected);
        paint.setColor(ground);
        canvas.drawCircle(sx, sy, 10 * dp, paint);
        paint.setColor(highlight);
        canvas.drawCircle(sx, sy, 7 * dp, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.4f * dp);
        canvas.drawCircle(sx, sy, 11 * dp, paint);
        if (isFocused()) canvas.drawRoundRect(new RectF(dp, dp, getWidth()-dp, getHeight()-dp), 9*dp, 9*dp, paint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downX = event.getX(); downY = event.getY();
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            if (Math.hypot(event.getX()-downX, event.getY()-downY) > ViewConfiguration.get(getContext()).getScaledTouchSlop()) return false;
            int closest = -1;
            double best = 24 * dp;
            // Among coincident markers, a repeated tap advances beyond the selected one.
            for (int offset = 1; offset <= places.size(); offset++) {
                int i = (selected + offset) % places.size();
                double d = Math.hypot(pointX(i)-event.getX(), pointY(i)-event.getY());
                if (d < best) { best = d; closest = i; }
            }
            if (closest >= 0) { super.performClick(); choose.accept(closest); }
            return true;
        }
        return super.onTouchEvent(event);
    }

    @Override public boolean performClick() {
        super.performClick();
        choose.accept((selected + 1) % places.size());
        return true;
    }
    @Override public boolean onKeyDown(int key, KeyEvent event) {
        if (key == KeyEvent.KEYCODE_DPAD_RIGHT && selected + 1 < places.size()) { choose.accept(selected + 1); return true; }
        if (key == KeyEvent.KEYCODE_DPAD_LEFT && selected > 0) { choose.accept(selected - 1); return true; }
        return super.onKeyDown(key, event);
    }
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.SeekBar");
        info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 1, places.size(), selected + 1));
        if (selected + 1 < places.size()) info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, "Restaurant suivant"));
        if (selected > 0) info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, "Restaurant précédent"));
    }
    @Override public boolean performAccessibilityAction(int action, Bundle args) {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD && selected + 1 < places.size()) {
            choose.accept(selected + 1); return true;
        }
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD && selected > 0) {
            choose.accept(selected - 1); return true;
        }
        return super.performAccessibilityAction(action, args);
    }
}
