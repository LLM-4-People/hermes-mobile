package org.llm4people.hermes.places;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.TypedValue;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import org.llm4people.hermes.R;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.IntConsumer;

/** A north-up distance diagram. All points come from the current attributed result set. */
final class PlaceCompass extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<NearbyResult.Place> places;
    private final NearbyCategory category;
    private static final int[] DIRECTION_RES = {
            R.string.places_compass_north, R.string.places_compass_northeast,
            R.string.places_compass_east, R.string.places_compass_southeast,
            R.string.places_compass_south, R.string.places_compass_southwest,
            R.string.places_compass_west, R.string.places_compass_northwest
    };
    private final double[] bearings;
    private final int[] distances;
    private final Path diamond = new Path();
    private final float textSize, badgeRadius, scaleLabelWidth, rankBaselineOffset;
    private final String scaleLabel, northSymbol;
    private final String[] rankLabels;
    private final float[] rankHalfWidths;
    private final int scaleMeters, touchSlop;
    private Plot plot;
    private final IntConsumer choose;
    private final float dp;
    private final int ground, grid, label, highlight;
    private final List<Integer> visible = new ArrayList<>();
    private int selected;
    private int comparison = -1;
    private float downX, downY;

    PlaceCompass(Context c, NearbyResult result, double lat, double lon, int selected, IntConsumer choose) {
        super(c);
        this.places = result.places;
        this.category = result.category == null ? NearbyCategory.RESTAURANT : result.category;
        this.choose = choose;
        dp = c.getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
        ground = c.getColor(R.color.places_chart);
        grid = c.getColor(R.color.places_grid);
        label = c.getColor(R.color.places_label);
        highlight = c.getColor(R.color.places_focus);
        bearings = new double[places.size()];
        distances = new int[places.size()];
        for (int i = 0; i < places.size(); i++) {
            NearbyResult.Place p = places.get(i);
            bearings[i] = bearing(lat, lon, p.latitude, p.longitude);
            distances[i] = p.distanceMeters;
        }
        scaleMeters = scale(distances);
        scaleLabel = NearbyGuide.distance(c, scaleMeters / 2);
        northSymbol = c.getString(R.string.places_compass_north_symbol);
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12,
                getResources().getDisplayMetrics()) / dp;
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setTextSize(textSize);
        scaleLabelWidth = paint.measureText(scaleLabel);
        rankBaselineOffset = (paint.ascent() + paint.descent()) / 2f;
        rankLabels = new String[places.size()];
        rankHalfWidths = new float[places.size()];
        for (int i = 0; i < places.size(); i++) {
            String rank = String.valueOf(i + 1);
            rankLabels[i] = rank;
            rankHalfWidths[i] = paint.measureText(rank) / 2f;
        }
        badgeRadius = Math.max(9, paint.measureText(String.valueOf(Math.max(1, places.size()))) / 2 + 3);
        setFocusable(true);
        setClickable(true);
        setSelection(selected, -1, null);
    }

    /** Indices always refer to the complete attributed result in distance order. */
    void setSelection(int primary, int second, Set<Integer> visibleIndices) {
        List<Integer> next = new ArrayList<>();
        for (int index = 0; index < places.size(); index++)
            if (visibleIndices == null || visibleIndices.contains(index)) next.add(index);
        if (!next.equals(visible)) {
            visible.clear();
            visible.addAll(next);
            updatePlot();
        }
        selected = visible.contains(primary) ? primary : visible.isEmpty() ? -1 : visible.get(0);
        comparison = second != selected && visible.contains(second) ? second : -1;
        setClickable(visible.size() > 1);
        String description = category.compassDescription(getContext());
        if (selected >= 0) description += " " + describe(selected) + ".";
        if (comparison >= 0) description += " " + getContext().getString(R.string.places_compass_compared, describe(comparison));
        setContentDescription(description);
        invalidate();
    }

    private String describe(int i) {
        NearbyResult.Place place = places.get(i);
        if (place.distanceMeters == 0) {
            return getContext().getString(R.string.places_compass_at_center, place.name);
        }
        int direction = Math.floorMod((int) Math.round(bearings[i] / (Math.PI / 4)), 8);
        return getContext().getString(
                R.string.places_compass_relative,
                place.name,
                NearbyGuide.distance(getContext(), place.distanceMeters),
                getContext().getString(DIRECTION_RES[direction]));
    }

    static double bearing(double lat, double lon, double pointLat, double pointLon) {
        double a = Math.toRadians(lat), b = Math.toRadians(pointLat), delta = Math.toRadians(pointLon - lon);
        return Math.atan2(Math.sin(delta) * Math.cos(b), Math.cos(a) * Math.sin(b)
                - Math.sin(a) * Math.cos(b) * Math.cos(delta));
    }

    static int scale(int[] distances) {
        int farthest = 0;
        for (int distance : distances) farthest = Math.max(farthest, distance);
        int step = farthest <= 250 ? 50 : farthest <= 1000 ? 100 : 250;
        return Math.max(step, ((farthest + step - 1) / step) * step);
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        updatePlot();
    }

    private void updatePlot() {
        if (getWidth() == 0 || getHeight() == 0) return;
        plot = new Plot(getWidth() / dp, getHeight() / dp, textSize, badgeRadius, scaleMeters,
                bearings, distances, visible, scaleLabelWidth);
    }

    /** The anchor is the actual geographic projection. Only a numbered label may move. All units are dp. */
    static final class Plot {
        final float width, height, cx, cy, radius;
        final float[] x, y, labelX, labelY;
        final int[] indices;
        private final float badge, font, scaleWidth;

        Plot(float width, float height, float font, float badge, int scale, double[] bearings,
             int[] distances, List<Integer> visible, float scaleWidth) {
            this.width = width;
            this.height = height;
            this.badge = badge;
            this.font = font;

            cx = width / 2;
            cy = height / 2;
            radius = Math.max(1, Math.min(cx - 12, cy - Math.max(12, font)));
            this.scaleWidth = Math.max(scaleWidth, radius / 2);
            indices = new int[visible.size()];
            for (int k = 0; k < visible.size(); k++) indices[k] = visible.get(k);
            x = new float[distances.length];
            y = new float[distances.length];
            labelX = new float[distances.length];
            labelY = new float[distances.length];
            for (int rank = 0; rank < indices.length; rank++) {
                int i = indices[rank];
                float range = radius * distances[i] / scale;
                x[i] = cx + (float) Math.sin(bearings[i]) * range;
                y[i] = cy - (float) Math.cos(bearings[i]) * range;
                placeLabel(rank);
            }
        }

        private void placeLabel(int rank) {
            int i = indices[rank];
            float margin = badge + 4;
            float spacing = 2 * badge + 8;
            double outward = Math.atan2(y[i] - cy, x[i] - cx);
            // Prefer the nearest clear spot outward from the anchor, alternating to either side.
            float best = -1;
            for (int ring = 0; ring <= 12; ring++) {
                float reach = ring * spacing / 2;
                int slots = Math.max(1, ring * 8);
                for (int n = 0; n < slots; n++) {
                    int side = (n % 2 == 0 ? -1 : 1) * ((n + 1) / 2);
                    double angle = outward + side * 2 * Math.PI / slots;
                    float px = clamp(x[i] + (float) Math.cos(angle) * reach, margin, width - margin);
                    float py = clamp(y[i] + (float) Math.sin(angle) * reach, margin, height - margin);
                    if (px < 8 + font + margin && py < font + 6 + margin) continue;
                    if (px > width - scaleWidth - 12 - margin && py > height - font - 10 - margin) continue;
                    float clearance = (float) Math.hypot(px - cx, py - cy) + spacing - badge - 7;
                    for (int previous = 0; previous < rank; previous++) {
                        int j = indices[previous];
                        clearance = Math.min(clearance, (float) Math.hypot(px - labelX[j], py - labelY[j]));
                    }
                    if (clearance > best) {
                        labelX[i] = px; labelY[i] = py;
                        best = clearance;
                    }
                    if (clearance >= spacing) return;
                }
            }
            if (best < 0) { labelX[i] = x[i]; labelY[i] = y[i]; }
        }

        private static float clamp(float value, float low, float high) {
            return Math.max(low, Math.min(high, value));
        }

        int nearest(float px, float py) {
            int result = -1;
            double best = 24;
            for (int i : indices) {
                double distance = Math.hypot(labelX[i] - px, labelY[i] - py);
                if (distance <= best) { best = distance; result = i; }
            }
            return result;
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (plot == null) return;
        int state = canvas.save();
        canvas.scale(dp, dp);
        float w = plot.width, h = plot.height, x = plot.cx, y = plot.cy, r = plot.radius;
        fill(ground);
        canvas.drawRoundRect(0, 0, w, h, 9, 9, paint);
        stroke(grid, 1);
        canvas.drawCircle(x, y, r, paint);
        canvas.drawCircle(x, y, r * .5f, paint);
        canvas.drawLine(x-r-4, y, x+r+4, y, paint);
        canvas.drawLine(x, y-r-4, x, y+r+4, paint);
        // The north key and a true-length scale bar sit outside the geographic projection.
        fill(label);
        canvas.drawText(northSymbol, 8, textSize + 4, paint);
        float bar = r * .5f;
        canvas.drawText(scaleLabel, w - 9 - scaleLabelWidth, h - 11, paint);
        stroke(label, 1);
        canvas.drawLine(w-9-bar, h-6, w-9, h-6, paint);
        canvas.drawLine(w-9-bar, h-9, w-9-bar, h-6, paint);
        canvas.drawLine(w-9, h-9, w-9, h-6, paint);

        if (selected >= 0) {
            stroke(highlight, 1.5f);
            canvas.drawLine(x, y, plot.x[selected], plot.y[selected], paint);
        }
        if (comparison >= 0) {
            stroke(label, 1);
            canvas.drawLine(x, y, plot.x[comparison], plot.y[comparison], paint);
        }
        for (int i : plot.indices) {
            stroke(i == selected ? highlight : grid, 1);
            canvas.drawLine(plot.x[i], plot.y[i], plot.labelX[i], plot.labelY[i], paint);
            fill(i == selected ? highlight : label);
            canvas.drawCircle(plot.x[i], plot.y[i], 2.1f, paint);
        }
        fill(ground);
        canvas.drawCircle(x, y, 3.5f, paint);
        stroke(label, 1.3f);
        canvas.drawCircle(x, y, 3.5f, paint);
        for (int i : plot.indices) drawMarker(canvas, i);
        if (isFocused()) {
            stroke(highlight, 2);
            canvas.drawRoundRect(1, 1, w-1, h-1, 9, 9, paint);
        }
        canvas.restoreToCount(state);
    }

    private void drawMarker(Canvas canvas, int i) {
        float x = plot.labelX[i], y = plot.labelY[i], r = badgeRadius;
        if (i == comparison) {
            float tip = r + Math.max(2, textSize * .2f);
            diamond.rewind();
            diamond.moveTo(x, y-tip); diamond.lineTo(x+tip, y);
            diamond.lineTo(x, y+tip); diamond.lineTo(x-tip, y); diamond.close();
            fill(ground);
            canvas.drawPath(diamond, paint);
            stroke(label, 2);
            canvas.drawPath(diamond, paint);
        } else {
            fill(i == selected ? highlight : ground);
            canvas.drawCircle(x, y, r, paint);
            if (i != selected) {
                stroke(grid, 1.2f);
                canvas.drawCircle(x, y, r, paint);
            }
        }
        fill(i == selected ? ground : label);
        canvas.drawText(rankLabels[i], x - rankHalfWidths[i], y - rankBaselineOffset, paint);
    }

    private void fill(int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
    }

    private void stroke(int color, float width) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        paint.setColor(color);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downX = event.getX(); downY = event.getY();
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            if (Math.hypot(event.getX() - downX, event.getY() - downY) > touchSlop) return false;
            int closest = plot == null ? -1 : plot.nearest(event.getX() / dp, event.getY() / dp);
            if (closest >= 0) { super.performClick(); choose.accept(closest); }
            return true;
        }
        return super.onTouchEvent(event);
    }

    @Override public boolean performClick() {
        super.performClick();
        if (visible.size() <= 1) return false;
        choose.accept(visible.get((visible.indexOf(selected) + 1) % visible.size()));
        return true;
    }

    private boolean step(int delta) {
        int next = visible.indexOf(selected) + delta;
        if (next < 0 || next >= visible.size()) return false;
        choose.accept(visible.get(next));
        return true;
    }

    @Override public boolean onKeyDown(int key, KeyEvent event) {
        if (key == KeyEvent.KEYCODE_DPAD_RIGHT && step(1)) return true;
        if (key == KeyEvent.KEYCODE_DPAD_LEFT && step(-1)) return true;
        return super.onKeyDown(key, event);
    }
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (visible.size() < 2) return;
        int rank = visible.indexOf(selected);
        info.setClassName("android.widget.SeekBar");
        info.setScrollable(true);
        info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,
                1, visible.size(), rank + 1));
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
        if (rank + 1 < visible.size()) info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, category.nextItemLabel(getContext())));
        if (rank > 0) info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, category.previousItemLabel(getContext())));
    }
    @Override public boolean performAccessibilityAction(int action, Bundle args) {
        if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId()) {
            if (args == null || visible.size() < 2
                    || !args.containsKey(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)) return false;
            float value = args.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE);
            if (!Float.isFinite(value) || value < 1 || value > visible.size()) return false;
            choose.accept(visible.get(Math.round(value) - 1));
            return true;
        }
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) return step(1);
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) return step(-1);
        return super.performAccessibilityAction(action, args);
    }
}
