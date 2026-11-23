package org.llm4people.hermes.places;

import android.content.Context;
import android.test.InstrumentationTestCase;
import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.R;
import java.util.Arrays;
import java.util.LinkedHashSet;

@SuppressWarnings("deprecation")
public final class PlaceSelectionTest extends InstrumentationTestCase {
    private Context targetContext() { return getInstrumentation().getTargetContext(); }

    private NearbyResult result() throws Exception {
        JSONArray rows = new JSONArray();
        for (int i = 0; i < 4; i++) {
            JSONObject row = new JSONObject().put("osm_type","node").put("osm_id",i + 1)
                    .put("latitude",45.51).put("longitude",-73.57).put("distance_m",100 + i).put("name","Lieu " + i);
            if (i < 3) row.put("cuisine",new String[]{"asian","italian;pizza","italian"}[i]);
            rows.put(row);
        }
        return NearbyResult.parse(targetContext(), new JSONObject().put("source","OpenStreetMap")
                .put("attribution","© OpenStreetMap contributors").put("source_date","2026-11-22T07:27:46Z")
                .put("radius_m",1500).put("places",rows));
    }
    public void testFilterMembershipAndComparisonUseOriginalRecordIndexes() throws Exception {
        Context context = targetContext();
        String asian = context.getString(R.string.places_cuisine_asian);
        String italian = context.getString(R.string.places_cuisine_italian);
        String pizza = context.getString(R.string.places_cuisine_pizza);
        PlaceSelection choice = new PlaceSelection(result());
        assertEquals(Arrays.asList(asian, italian, pizza), choice.cuisines);
        choice.choose(1, false);
        choice.compare(2);
        choice.filter(italian);
        assertEquals(new LinkedHashSet<>(Arrays.asList(1, 2)), choice.visible());
        assertEquals(1, choice.selected());
        assertEquals(2, choice.comparison());
        choice.choose(2, false);
        assertEquals(2, choice.selected());
        assertEquals(1, choice.comparison());
        choice.filter(pizza);
        assertEquals(1, choice.selected());
        assertEquals(-1, choice.comparison());
        choice.compare(0);
        choice.choose(3, false);
        assertEquals(-1, choice.comparison());
        assertEquals(1, choice.selected());
        choice.filter("");
        assertEquals(4, choice.visible().size());
        choice.compare(3);
        choice.compare(-1);
        assertEquals(-1, choice.comparison());
    }
    public void testUnreportedCuisineNeverBecomesAFilterOrIntroducesAPoint() throws Exception {
        Context context = targetContext();
        String asian = context.getString(R.string.places_cuisine_asian);
        PlaceSelection choice = new PlaceSelection(result());
        choice.filter("Inconnue");
        assertEquals("", choice.cuisine());
        assertEquals(4, choice.visible().size());
        choice.all(true);
        assertTrue(choice.all());
        choice.filter("");
        assertTrue(choice.all());
        choice.filter(asian);
        assertFalse(choice.all());
        assertEquals(1, choice.visible().size());
    }
}
