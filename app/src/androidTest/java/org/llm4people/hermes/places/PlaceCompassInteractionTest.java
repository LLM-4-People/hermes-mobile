package org.llm4people.hermes.places;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.test.InstrumentationTestCase;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;

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
        runTestOnUiThread(() -> {
            Context context = getInstrumentation().getTargetContext();
            float density = context.getResources().getDisplayMetrics().density;
            List<Integer> chosen = new ArrayList<>();
            PlaceCompass chart = new PlaceCompass(context, result, 45.51, -73.57, 0, chosen::add);
            chart.layout(0, 0, Math.round(274 * density), Math.round(136 * density));
            chart.setSelection(1, 2, Set.of(0, 1, 2));
            assertTrue(chart.requestFocus());
            String description = chart.getContentDescription().toString();
            assertTrue(description.contains("Mae Sri Thai, environ 214 m à l’ouest du centre"));
            assertTrue(description.contains("Tiamo, environ 222 m au sud-ouest du centre"));

            // Locate the filled primary marker in the rendered chart. Its much thinner distance line
            // and anchor cannot create enough highlighted pixels in a scan disk to be mistaken for it.
            Bitmap frame = Bitmap.createBitmap(chart.getWidth(), chart.getHeight(), Bitmap.Config.ARGB_8888);
            chart.draw(new Canvas(frame));
            int gold = context.getColor(R.color.places_focus);
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
            touch(chart, MotionEvent.ACTION_DOWN, px, py);
            touch(chart, MotionEvent.ACTION_UP, px, py);
            assertEquals(List.of(1), chosen);

            chart.setSelection(0, 1, Set.of(0, 2));
            assertTrue(chart.isFocused());
            assertFalse(chart.getContentDescription().toString().contains("En comparaison"));
            AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
            chart.onInitializeAccessibilityNodeInfo(node);
            assertEquals(1f, node.getRangeInfo().getCurrent(), 0f);
            assertEquals(2f, node.getRangeInfo().getMax(), 0f);
            node.recycle();
            assertTrue(chart.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null));
            assertEquals(List.of(1,2), chosen);
            Bundle value = new Bundle();
            value.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 2);
            assertTrue(chart.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(), value));
            assertEquals(List.of(1,2,2), chosen);
            chart.setSelection(1, -1, Set.of(1));
            assertFalse(chart.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null));
        });
    }

    private static void touch(PlaceCompass chart, int action, int x, int y) {
        MotionEvent input = MotionEvent.obtain(0, 1, action, x, y, 0);
        try { assertTrue(chart.onTouchEvent(input)); }
        finally { input.recycle(); }
    }
}
