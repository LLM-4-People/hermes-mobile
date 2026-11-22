package org.llm4people.hermes.places;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public final class PlaceCompassGeometryTest {
    // © OpenStreetMap contributors, 2026-11-22 Montréal snapshot. Indices are gateway distance order.
    private static final double[] LAT = {45.5105406,45.5104122,45.5083743,45.5085397,45.5092442,45.5107331,
            45.5087752,45.5088388,45.5088864,45.5093528,45.5093761,45.5089786};
    private static final double[] LON = {-73.5725473,-73.5726866,-73.5716569,-73.5720223,-73.5727432,-73.5672027,
            -73.5724857,-73.5726571,-73.5727667,-73.5730767,-73.5731358,-73.5729364};
    private static final int[] METERS = {207,214,222,226,230,233,237,244,249,250,254,255};
    private static final List<Integer> ALL = List.of(0,1,2,3,4,5,6,7,8,9,10,11);

    private static PlaceCompass.Plot plot(int width, int height, float font, List<Integer> indices) {
        double[] bearings = new double[METERS.length];
        for (int i = 0; i < bearings.length; i++)
            bearings[i] = PlaceCompass.bearing(45.51, -73.57, LAT[i], LON[i]);
        return new PlaceCompass.Plot(width, height, font, Math.max(9, font * .62f + 3),
                PlaceCompass.scale(METERS), bearings, METERS, indices, font * 3.5f);
    }

    @Test public void everyTrueAnchorKeepsItsDistanceAndAllTwelveLabelsCanBeSelected() {
        assertEquals(300, PlaceCompass.scale(METERS));
        for (int width : new int[]{274, 310, 440}) {
            for (float font : new float[]{12, 18, 24}) {
                PlaceCompass.Plot plot = plot(width, width < 400 ? 136 : 185, font, ALL);
                for (int i : ALL) {
                    assertEquals(METERS[i], 300 * Math.hypot(plot.x[i] - plot.cx, plot.y[i] - plot.cy)
                            / plot.radius, .001);
                    assertEquals(i, plot.nearest(plot.labelX[i], plot.labelY[i]));
                    for (int j : ALL) if (j < i) {
                        double separation = Math.hypot(plot.labelX[i] - plot.labelX[j], plot.labelY[i] - plot.labelY[j]);
                        assertTrue("Two venues share a label at width " + width + " font " + font,
                                separation >= 2 * Math.max(9, font * .62f + 3));
                    }
                }
                assertTrue(plot.x[0] < plot.cx && plot.y[0] < plot.cy); // Yamu Yamu Sushi
                assertTrue(plot.x[1] < plot.cx && plot.y[1] < plot.cy); // Mae Sri Thai
                assertTrue(plot.x[2] < plot.cx && plot.y[2] > plot.cy); // Tiamo
                assertTrue(Math.hypot(plot.x[1]-plot.x[0], plot.y[1]-plot.y[0]) < 8);
            }
        }
    }

    @Test public void onlyVisibleVenuesCanBeHitAndFilteringPreservesTheirTrueAnchors() {
        PlaceCompass.Plot complete = plot(310,136,12,ALL);
        PlaceCompass.Plot pair = plot(310,136,12,List.of(1,2));
        for (int i : List.of(1,2)) {
            assertEquals(complete.x[i], pair.x[i], 0);
            assertEquals(complete.y[i], pair.y[i], 0);
            assertEquals(i, pair.nearest(pair.labelX[i], pair.labelY[i]));
        }
        assertEquals(-1, pair.nearest(complete.labelX[5], complete.labelY[5]));
        assertEquals(-1, plot(310,136,12,List.of()).nearest(0, 0));
    }
}
