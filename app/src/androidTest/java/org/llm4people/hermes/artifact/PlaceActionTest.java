package org.llm4people.hermes.artifact;

import android.content.Context;
import android.content.Intent;
import android.test.InstrumentationTestCase;
import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.R;
import org.llm4people.hermes.places.NearbyResult;

@SuppressWarnings("deprecation")
public final class PlaceActionTest extends InstrumentationTestCase {
    public void testReviewedVenueOpensOnlyItsCoordinatesOrCanonicalOsmObject() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        JSONObject record = new JSONObject().put("osm_type","way").put("osm_id",123L)
                .put("name","Café A & B").put("latitude",0.0000015).put("longitude",-73.5674).put("distance_m",21)
                .put("website","https://example.invalid/").put("url","https://example.invalid/redirect");
        NearbyResult.Place place = NearbyResult.parse(new JSONObject().put("source","OpenStreetMap")
                .put("attribution","© OpenStreetMap contributors").put("source_date","2026-11-22T13:05:00Z")
                .put("radius_m",1500).put("places",new JSONArray().put(record))).places.get(0);
        DeviceAction map = new DeviceAction.PlaceMap(place);
        assertEquals("Café A & B\n\n" + context.getString(R.string.ws_dev_place_coords, "0.0000015,-73.5674"), map.preview(context));
        Intent geo = map.target();
        assertEquals("geo:0,0?q=0.0000015%2C-73.5674", geo.getDataString());
        checkView(geo);
        DeviceAction source = new DeviceAction.PlaceSource(place);
        assertEquals("Café A & B\n\nhttps://www.openstreetmap.org/way/123", source.preview(context));
        Intent web = source.target();
        assertEquals("https://www.openstreetmap.org/way/123", web.getDataString());
        checkView(web);
    }

    private void checkView(Intent intent) {
        assertEquals(Intent.ACTION_VIEW, intent.getAction());
        assertNull(intent.getPackage());
        assertNull(intent.getComponent());
        assertNull(intent.getExtras());
        assertEquals(0, intent.getFlags());
    }
}
