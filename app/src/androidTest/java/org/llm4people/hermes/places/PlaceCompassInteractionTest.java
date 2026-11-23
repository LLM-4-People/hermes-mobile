package org.llm4people.hermes.places;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;

import org.json.JSONObject;
import org.llm4people.hermes.R;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@SuppressWarnings("deprecation")
public final class PlaceCompassInteractionTest extends InstrumentationTestCase {
    public void testTouchFilteredAdjustmentAndPairDescriptionOnTheSameChart() throws Throwable {
        NearbyResult result = NearbyResult.parse(new JSONObject("""
                {"source":"OpenStreetMap","attribution":"© OpenStreetMap contributors",
                 "source_date":"2026-11-22T07:27:46Z","radius_m":1500,"places":[
                  {"name":"Yamu Yamu Sushi","osm_type":"node","osm_id":11280151110,"latitude":45.5105406,"longitude":-73.5725473,"distance_m":207},
                  {"name":"Mae Sri Thai","osm_type":"node","osm_id":6732094290,"latitude":45.5104122,"longitude":-73.5726866,"distance_m":214},
                  {"name":"Tiamo","osm_type":"node","osm_id":12194959181,"latitude":45.5083743,"longitude":-73.5716569,"distance_m":222}]}
                """));
        getInstrumentation().setInTouchMode(false);
        Context target = getInstrumentation().getTargetContext();
        Intent launch = target.getPackageManager().getLaunchIntentForPackage(target.getPackageName());
        assertNotNull(launch);
        Activity activity = getInstrumentation().startActivitySync(launch);
        try {
            List<Integer> chosen = new ArrayList<>();
            PlaceCompass[] mounted = new PlaceCompass[1];
            int[] size = new int[2];
            runTestOnUiThread(() -> {
                float density = activity.getResources().getDisplayMetrics().density;
                size[0] = Math.round(274 * density);
                size[1] = Math.round(136 * density);
                PlaceCompass chart = new PlaceCompass(activity, result, 45.51, -73.57, 0, chosen::add);
                FrameLayout host = new FrameLayout(activity);
                host.addView(chart, new FrameLayout.LayoutParams(size[0], size[1]));
                activity.setContentView(host);
                mounted[0] = chart;
            });
            PlaceCompass chart = mounted[0];
            awaitWindowTouchMode(chart, size[0], size[1], false);
            runTestOnUiThread(() -> {
                float density = activity.getResources().getDisplayMetrics().density;
                assertTrue(chart.isAttachedToWindow());
                assertFalse(chart.isInTouchMode());
                assertTrue(chart.isFocusable());
                assertFalse(chart.isFocusableInTouchMode());
                chart.setSelection(1, 2, Set.of(0, 1, 2));
                assertTrue(chart.requestFocus());
                assertTrue(chart.isFocused());
                String description = chart.getContentDescription().toString();
                assertTrue(description.contains(activity.getString(R.string.places_compass_relative,
                        "Mae Sri Thai", activity.getString(R.string.places_distance_meters, "214"),
                        activity.getString(R.string.places_compass_west))));
                assertTrue(description.contains(activity.getString(R.string.places_compass_relative,
                        "Tiamo", activity.getString(R.string.places_distance_meters, "222"),
                        activity.getString(R.string.places_compass_southwest))));

                // Locate the filled primary marker in the rendered chart. Its much thinner distance line
                // and anchor cannot create enough highlighted pixels in a scan disk to be mistaken for it.
                Bitmap frame = Bitmap.createBitmap(chart.getWidth(), chart.getHeight(), Bitmap.Config.ARGB_8888);
                chart.draw(new Canvas(frame));
                int gold = activity.getColor(R.color.places_focus);
                int px = -1, py = -1, best = 0;
                int disk = Math.max(2, Math.round(8 * density));
                for (int y = disk; y < frame.getHeight() - disk; y += 2) {
                    for (int x = disk; x < frame.getWidth() - disk; x += 2) {
                        if (frame.getPixel(x, y) != gold) continue;
                        int score = 0;
                        for (int dy = -disk; dy <= disk; dy += 2)
                            for (int dx = -disk; dx <= disk; dx += 2)
                                if (dx*dx + dy*dy <= disk*disk && frame.getPixel(x+dx, y+dy) == gold) score++;
                        if (score > best) { best = score; px = x; py = y; }
                    }
                }
                frame.recycle();
                assertTrue("Selected restaurant has a distinct filled marker", best >= disk * disk * .20);
                chart.setSelection(0, 2, Set.of(0, 1, 2));
                int slop = ViewConfiguration.get(activity).getScaledTouchSlop();
                touch(chart, MotionEvent.ACTION_DOWN, px, py);
                assertFalse(dispatchTouch(chart, MotionEvent.ACTION_UP, px + slop + 4, py));
                assertTrue(chosen.isEmpty());
                touch(chart, MotionEvent.ACTION_DOWN, px, py);
                touch(chart, MotionEvent.ACTION_UP, px, py);
                assertEquals(List.of(1), chosen);

                chart.setSelection(0, 1, Set.of(0, 2));
                assertTrue(chart.isFocused());
                assertFalse(chart.getContentDescription().toString().contains(activity.getString(R.string.places_guide_in_comparison)));
                assertFalse(chart.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)));
                assertTrue(chart.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)));
                assertEquals(List.of(1, 2), chosen);
                AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
                chart.onInitializeAccessibilityNodeInfo(node);
                assertEquals(1f, node.getRangeInfo().getCurrent(), 0f);
                assertEquals(2f, node.getRangeInfo().getMax(), 0f);
                node.recycle();
                assertTrue(chart.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null));
                assertEquals(List.of(1, 2, 2), chosen);
                Bundle invalid = new Bundle();
                invalid.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, Float.NaN);
                assertFalse(chart.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(), invalid));
                invalid.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 3);
                assertFalse(chart.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(), invalid));
                Bundle value = new Bundle();
                value.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 2);
                assertTrue(chart.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(), value));
                assertEquals(List.of(1, 2, 2, 2), chosen);
                chart.setSelection(1, -1, Set.of(1));
                assertFalse(chart.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null));
                assertFalse(chart.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)));
            });
            awaitWindowTouchMode(chart, size[0], size[1], true);
            runTestOnUiThread(() -> {
                assertTrue(chart.isInTouchMode());
                assertFalse(chart.isFocused());
                assertFalse(chart.requestFocus());
            });
        } finally {
            try { runTestOnUiThread(activity::finish); }
            finally { getInstrumentation().setInTouchMode(true); }
        }
    }

    private void awaitWindowTouchMode(PlaceCompass chart, int width, int height, boolean inTouchMode) throws Throwable {
        long deadline = SystemClock.uptimeMillis() + 5_000L;
        boolean[] ready = new boolean[1];
        while (true) {
            getInstrumentation().setInTouchMode(inTouchMode);
            getInstrumentation().waitForIdleSync();
            runTestOnUiThread(() -> ready[0] = chart.isAttachedToWindow()
                    && chart.getWidth() == width && chart.getHeight() == height
                    && chart.isInTouchMode() == inTouchMode);
            if (ready[0]) return;
            if (SystemClock.uptimeMillis() >= deadline) {
                fail("Timed out waiting for PlaceCompass window attachment/layout/touchMode=" + inTouchMode);
            }
            SystemClock.sleep(25);
        }
    }

    private static void touch(PlaceCompass chart, int action, int x, int y) {
        assertTrue(dispatchTouch(chart, action, x, y));
    }

    private static boolean dispatchTouch(PlaceCompass chart, int action, int x, int y) {
        MotionEvent input = MotionEvent.obtain(0, 1, action, x, y, 0);
        try { return chart.onTouchEvent(input); }
        finally { input.recycle(); }
    }
}
