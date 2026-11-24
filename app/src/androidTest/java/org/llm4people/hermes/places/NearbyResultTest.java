package org.llm4people.hermes.places;

import android.content.Context;
import android.test.InstrumentationTestCase;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.llm4people.hermes.R;

@SuppressWarnings("deprecation")
public final class NearbyResultTest extends InstrumentationTestCase {
    private Context targetContext() { return getInstrumentation().getTargetContext(); }
    private static JSONObject response(JSONArray rows) throws JSONException {
        return new JSONObject().put("source", "OpenStreetMap").put("attribution", "© OpenStreetMap contributors")
                .put("source_date", "2026-11-22T13:05:00Z").put("radius_m", 1500).put("places", rows);
    }

    private static JSONObject venue(String type, long id, String name, int distance) throws JSONException {
        return new JSONObject().put("osm_type", type).put("osm_id", id).put("name", name)
                .put("distance_m", distance).put("latitude", 45.5018).put("longitude", -73.5674);
    }

    public void testAttributionStableDistanceOrderAndCanonicalVenueSources() throws Exception {
        JSONArray records = new JSONArray()
                .put(venue("node",17,"Doublon éloigné",1000))
                .put(venue("way",17,"Érable",205).put("cuisine","italian; Cuisine de l’archipel ;pizza;ITALIAN")
                        .put("address","34, rue du Parc").put("source_url","https://example.invalid/")
                        .put("website","https://example.invalid/"))
                .put(venue("node",17,"Café ☕",205))
                .put(venue("relation",5000000000L,"Bistro",22));
        Context context = targetContext();
        String italian = context.getString(R.string.places_cuisine_italian);
        String pizza = context.getString(R.string.places_cuisine_pizza);
        NearbyResult result = NearbyResult.parse(context, response(records));
        assertEquals("© OpenStreetMap contributors", result.attribution);
        assertEquals("2026-11-22T13:05:00Z", result.sourceDate);
        assertEquals(1500, result.radiusMeters);
        assertEquals(3, result.places.size());
        assertEquals("relation/5000000000", result.places.get(0).id);
        NearbyResult.Place place = result.places.get(1);
        assertEquals("Érable", place.name);
        assertEquals("way/17", place.id);
        assertEquals("https://www.openstreetmap.org/way/17", place.sourceUrl);
        assertEquals(italian + " · Cuisine de l’archipel · " + pizza, place.cuisine);
        assertEquals(java.util.Arrays.asList(italian, "Cuisine de l’archipel", pizza), place.cuisines);
        try { place.cuisines.remove(0); fail("Cuisine filters must be immutable"); }
        catch (UnsupportedOperationException expected) {}
        assertEquals("34, rue du Parc", place.address);
        assertEquals(205, place.distanceMeters);
        assertEquals("45.5018,-73.5674", place.coordinates());
        assertEquals("Café ☕", result.places.get(2).name);
        assertEquals("", result.places.get(2).address);
        try { result.places.clear(); fail("Result must be immutable"); }
        catch (UnsupportedOperationException expected) {}
    }

    public void testCustomOpenStreetMapBaseUrlFlowsIntoPlaceSourceUrl() throws Exception {
        JSONArray rows = new JSONArray().put(venue("way", 99, "Boulangerie", 80));
        NearbyResult custom = NearbyResult.parse(response(rows), null, "https://maps.example.org/");
        assertEquals("https://maps.example.org/way/99", custom.places.get(0).sourceUrl);

        NearbyResult fallback = NearbyResult.parse(response(rows), null, "   ");
        assertEquals("https://www.openstreetmap.org/way/99", fallback.places.get(0).sourceUrl);
    }

    public void testCuisineFilterKeepsAnUnknownSourceTagContainingAMiddleDot() throws Exception {
        Context context = targetContext();
        String italian = context.getString(R.string.places_cuisine_italian);
        JSONArray rows = new JSONArray().put(venue("node", 42, "Restaurant", 10)
                .put("cuisine", "du marché · local; italian;du marché · local"));
        NearbyResult.Place place = NearbyResult.parse(context, response(rows)).places.get(0);
        assertEquals(java.util.Arrays.asList("du marché · local", italian), place.cuisines);
        assertEquals("du marché · local · " + italian, place.cuisine);
    }

    public void testCuisineTagsFromTheMontrealSourceUseFrenchLabels() throws Exception {
        Context context = targetContext();
        JSONArray rows = new JSONArray().put(venue("node", 42, "Restaurant", 10)
                .put("cuisine", "italian_pizza;pasta;asian;korean"));
        String expected = context.getString(R.string.places_cuisine_italian_pizza) + " · "
                + context.getString(R.string.places_cuisine_pasta) + " · "
                + context.getString(R.string.places_cuisine_asian) + " · "
                + context.getString(R.string.places_cuisine_korean);
        assertEquals(expected,
                NearbyResult.parse(context, response(rows)).places.get(0).cuisine);
    }

    public void testMalformedNeighborsCannotSupplyAnIdentityOrReplaceGoodResults() throws Exception {
        JSONArray rows = new JSONArray().put(JSONObject.NULL)
                .put(venue("node",12,"X",42).put("osm_id","12"))
                .put(venue("way",12,"X",42).put("osm_id",1.5))
                .put(venue("relation",0,"X",42))
                .put(venue("node/../../",4,"X",42))
                .put(venue("node",4,"Titre\u202e",42))
                .put(venue("node",4,"X",42).put("longitude","-73.5"))
                .put(venue("node",4,"X",42).put("latitude",-90.01))
                .put(venue("node",4,"X",1501))
                .put(venue("node",4,"  \uFE0F",20))
                .put(venue("way",4,"Deux voisins 👩‍🍳",0)
                        .put("address", new JSONArray().put(42)).put("cuisine","texte\ncontaminé"));
        NearbyResult result = NearbyResult.parse(response(rows));
        assertEquals(1,result.places.size());
        assertEquals("Deux voisins 👩‍🍳", result.places.get(0).name);
        assertEquals("", result.places.get(0).address);
        assertEquals("", result.places.get(0).cuisine);
        assertTrue(result.places.get(0).cuisines.isEmpty());
        assertEquals(0, result.places.get(0).distanceMeters);
    }

    public void testAttributedEmptyDataDiffersFromAChangedOrDamagedEnvelope() throws Exception {
        assertTrue(NearbyResult.parse(response(new JSONArray())).places.isEmpty());
        JSONArray many = new JSONArray();
        for (int i = 0; i < 13; i++) many.put(venue("node", i + 1, "X", i));
        invalid(response(many));
        // Higher configured limit up to 24 is accepted, above 24 rejected.
        NearbyResult twenty = NearbyResult.parse(response(many).put("limit", 20).put("radius_m", 3000).put("category", "cafe"));
        assertEquals(NearbyCategory.CAFE, twenty.category);
        assertEquals(3000, twenty.radiusMeters);
        assertEquals(20, twenty.limit);
        assertEquals(13, twenty.places.size());
        invalid(response(new JSONArray()).put("limit", 25));
        invalid(response(new JSONArray()).put("limit", 5));
        invalid(response(new JSONArray()).put("category", "invalid_category"));
        invalid(response(new JSONArray().put(false)));
        invalid(response(new JSONArray()).put("source", "Other"));
        invalid(response(new JSONArray()).put("attribution", ""));
        invalid(response(new JSONArray()).put("source_date", "2026-02-30T12:00:00Z"));
        invalid(response(new JSONArray()).put("source_date", "2026-11-22T14:05:00+01:00"));
        invalid(response(new JSONArray()).put("radius_m", "1500"));
        invalid(response(new JSONArray()).put("radius_m", 499));
        invalid(response(new JSONArray()).put("radius_m", 5001));
        invalid(response(new JSONArray()).put("places", new JSONObject()));
    }

    private void invalid(JSONObject response) {
        try { NearbyResult.parse(response); fail("Expected an unusable reply"); }
        catch (JSONException expected) {}
    }
}
