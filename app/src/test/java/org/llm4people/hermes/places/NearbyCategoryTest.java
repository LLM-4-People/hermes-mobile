package org.llm4people.hermes.places;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.HashSet;
import org.junit.Test;

public final class NearbyCategoryTest {
    @Test public void allowlistMatchesAllNineOsmCategoriesAndBounds() {
        assertEquals(
                new HashSet<>(Arrays.asList(
                        "restaurant", "cafe", "bakery", "pharmacy", "transit",
                        "park", "grocery", "bookshop", "coworking")),
                new HashSet<>(NearbyCategory.ids()));
        assertEquals(NearbyCategory.RESTAURANT, NearbyCategory.fromId("restaurant"));
        assertEquals(NearbyCategory.PHARMACY, NearbyCategory.fromId("pharmacy"));
        assertNull(NearbyCategory.fromId("nuclear_plant"));
        assertNull(NearbyCategory.fromId(""));
        assertNull(NearbyCategory.fromId(null));

        assertTrue(NearbyCategory.validRadius(500));
        assertTrue(NearbyCategory.validRadius(1500));
        assertTrue(NearbyCategory.validRadius(5000));
        assertFalse(NearbyCategory.validRadius(499));
        assertFalse(NearbyCategory.validRadius(5001));

        assertTrue(NearbyCategory.validLimit(6));
        assertTrue(NearbyCategory.validLimit(12));
        assertTrue(NearbyCategory.validLimit(24));
        assertFalse(NearbyCategory.validLimit(5));
        assertFalse(NearbyCategory.validLimit(25));
    }

    @Test public void routeModesAndTagLabelsProduceExpectedValues() {
        assertEquals("google.navigation:q=45.51,-73.57&mode=w",
                NearbyCategory.RouteMode.WALKING.navigationUri(45.51, -73.57));
        assertEquals("google.navigation:q=45.51,-73.57&mode=d",
                NearbyCategory.RouteMode.DRIVING.navigationUri(45.51, -73.57));
        assertEquals("geo:0,0?q=45.51,-73.57",
                NearbyCategory.RouteMode.WALKING.fallbackGeoUri(45.51, -73.57));

        assertEquals("Accès PMR", NearbyCategory.tagLabel("wheelchair"));
        assertEquals("Wi-Fi", NearbyCategory.tagLabel("wifi"));
        assertEquals("À emporter", NearbyCategory.tagLabel("takeaway"));
        assertEquals("Ouvert 24h/24", NearbyCategory.tagLabel("24/7"));
        assertEquals("Terrasse", NearbyCategory.tagLabel("outdoor_seating"));
        assertEquals("", NearbyCategory.tagLabel("unknown_tag"));
    }
}
