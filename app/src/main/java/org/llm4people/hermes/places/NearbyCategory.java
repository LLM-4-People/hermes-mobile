package org.llm4people.hermes.places;

import android.content.Context;

import org.llm4people.hermes.AppLanguage;
import org.llm4people.hermes.R;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Single source of truth for allowlisted OpenStreetMap categories, bounds, and route handoff modes. */
public enum NearbyCategory {
    RESTAURANT("restaurant",
            R.string.places_cat_restaurant_label, R.string.places_cat_restaurant_heading,
            R.string.places_cat_restaurant_loading, R.string.places_cat_restaurant_empty,
            R.string.places_cat_restaurant_unavailable, R.string.places_cat_restaurant_compass,
            R.string.places_cat_restaurant_next, R.string.places_cat_restaurant_prev,
            R.string.places_cat_restaurant_plural, true),
    CAFE("cafe",
            R.string.places_cat_cafe_label, R.string.places_cat_cafe_heading,
            R.string.places_cat_cafe_loading, R.string.places_cat_cafe_empty,
            R.string.places_cat_cafe_unavailable, R.string.places_cat_cafe_compass,
            R.string.places_cat_cafe_next, R.string.places_cat_cafe_prev,
            R.string.places_cat_cafe_plural, true),
    BAKERY("bakery",
            R.string.places_cat_bakery_label, R.string.places_cat_bakery_heading,
            R.string.places_cat_bakery_loading, R.string.places_cat_bakery_empty,
            R.string.places_cat_bakery_unavailable, R.string.places_cat_bakery_compass,
            R.string.places_cat_bakery_next, R.string.places_cat_bakery_prev,
            R.string.places_cat_bakery_plural, true),
    PHARMACY("pharmacy",
            R.string.places_cat_pharmacy_label, R.string.places_cat_pharmacy_heading,
            R.string.places_cat_pharmacy_loading, R.string.places_cat_pharmacy_empty,
            R.string.places_cat_pharmacy_unavailable, R.string.places_cat_pharmacy_compass,
            R.string.places_cat_pharmacy_next, R.string.places_cat_pharmacy_prev,
            R.string.places_cat_pharmacy_plural, false),
    TRANSIT("transit",
            R.string.places_cat_transit_label, R.string.places_cat_transit_heading,
            R.string.places_cat_transit_loading, R.string.places_cat_transit_empty,
            R.string.places_cat_transit_unavailable, R.string.places_cat_transit_compass,
            R.string.places_cat_transit_next, R.string.places_cat_transit_prev,
            R.string.places_cat_transit_plural, false),
    PARK("park",
            R.string.places_cat_park_label, R.string.places_cat_park_heading,
            R.string.places_cat_park_loading, R.string.places_cat_park_empty,
            R.string.places_cat_park_unavailable, R.string.places_cat_park_compass,
            R.string.places_cat_park_next, R.string.places_cat_park_prev,
            R.string.places_cat_park_plural, false),
    GROCERY("grocery",
            R.string.places_cat_grocery_label, R.string.places_cat_grocery_heading,
            R.string.places_cat_grocery_loading, R.string.places_cat_grocery_empty,
            R.string.places_cat_grocery_unavailable, R.string.places_cat_grocery_compass,
            R.string.places_cat_grocery_next, R.string.places_cat_grocery_prev,
            R.string.places_cat_grocery_plural, false),
    BOOKSHOP("bookshop",
            R.string.places_cat_bookshop_label, R.string.places_cat_bookshop_heading,
            R.string.places_cat_bookshop_loading, R.string.places_cat_bookshop_empty,
            R.string.places_cat_bookshop_unavailable, R.string.places_cat_bookshop_compass,
            R.string.places_cat_bookshop_next, R.string.places_cat_bookshop_prev,
            R.string.places_cat_bookshop_plural, false),
    COWORKING("coworking",
            R.string.places_cat_coworking_label, R.string.places_cat_coworking_heading,
            R.string.places_cat_coworking_loading, R.string.places_cat_coworking_empty,
            R.string.places_cat_coworking_unavailable, R.string.places_cat_coworking_compass,
            R.string.places_cat_coworking_next, R.string.places_cat_coworking_prev,
            R.string.places_cat_coworking_plural, false);

    public static final int MIN_RADIUS_M = 500;
    public static final int DEFAULT_RADIUS_M = 1500;
    public static final int MAX_RADIUS_M = 5000;
    public static final int MIN_LIMIT = 6;
    public static final int DEFAULT_LIMIT = 12;
    public static final int MAX_LIMIT = 24;
    public static final List<Integer> RADIUS_CHOICES = Collections.unmodifiableList(Arrays.asList(500, 1500, 3000, 5000));

    public enum RouteMode {
        WALKING("w", R.string.places_route_walk_chip, R.string.places_route_walk_title, R.string.places_route_walk_open),
        DRIVING("d", R.string.places_route_drive_chip, R.string.places_route_drive_title, R.string.places_route_drive_open);

        public final String navParam;
        public final int chipLabelRes, titleRes, openLabelRes;

        RouteMode(String navParam, int chipLabelRes, int titleRes, int openLabelRes) {
            this.navParam = navParam;
            this.chipLabelRes = chipLabelRes;
            this.titleRes = titleRes;
            this.openLabelRes = openLabelRes;
        }

        public String chipLabel(Context context) { return localized(context, chipLabelRes); }
        public String title(Context context) { return localized(context, titleRes); }
        public String openLabel(Context context) { return localized(context, openLabelRes); }

        public String navigationUri(double latitude, double longitude) {
            return "google.navigation:q=" + plainCoords(latitude, longitude) + "&mode=" + navParam;
        }

        public String fallbackGeoUri(double latitude, double longitude) {
            return "geo:0,0?q=" + plainCoords(latitude, longitude);
        }
    }

    public final String id;
    /** Opaque fallback key retained for callers without Context; use {@link #heading(Context)} for UI text. */
    public final String heading;
    public final int labelRes, headingRes, loadingMessageRes, emptyMessageRes, unavailableMessageRes;
    public final int compassDescriptionRes, nextItemLabelRes, previousItemLabelRes, draftPluralRes;
    public final boolean supportsCuisine;

    private static final Map<String, NearbyCategory> BY_ID;
    static {
        Map<String, NearbyCategory> map = new LinkedHashMap<>();
        for (NearbyCategory category : values()) map.put(category.id, category);
        BY_ID = Collections.unmodifiableMap(map);
    }

    NearbyCategory(String id, int labelRes, int headingRes, int loadingMessageRes, int emptyMessageRes,
                   int unavailableMessageRes, int compassDescriptionRes, int nextItemLabelRes,
                   int previousItemLabelRes, int draftPluralRes, boolean supportsCuisine) {
        this.id = id;
        this.heading = id;
        this.labelRes = labelRes;
        this.headingRes = headingRes;
        this.loadingMessageRes = loadingMessageRes;
        this.emptyMessageRes = emptyMessageRes;
        this.unavailableMessageRes = unavailableMessageRes;
        this.compassDescriptionRes = compassDescriptionRes;
        this.nextItemLabelRes = nextItemLabelRes;
        this.previousItemLabelRes = previousItemLabelRes;
        this.draftPluralRes = draftPluralRes;
        this.supportsCuisine = supportsCuisine;
    }

    public String label(Context context) { return localized(context, labelRes); }
    public String heading(Context context) { return localized(context, headingRes); }
    public String loadingMessage(Context context) { return localized(context, loadingMessageRes); }
    public String emptyMessage(Context context) { return localized(context, emptyMessageRes); }
    public String unavailableMessage(Context context) { return localized(context, unavailableMessageRes); }
    public String compassDescription(Context context) { return localized(context, compassDescriptionRes); }
    public String nextItemLabel(Context context) { return localized(context, nextItemLabelRes); }
    public String previousItemLabel(Context context) { return localized(context, previousItemLabelRes); }
    public String draftPlural(Context context) { return localized(context, draftPluralRes); }

    private static String localized(Context context, int resId) {
        return AppLanguage.wrapContext(context).getString(resId);
    }

    public static NearbyCategory fromId(String id) {
        return id == null ? null : BY_ID.get(id);
    }

    public static boolean valid(String id) {
        return fromId(id) != null;
    }

    public static List<String> ids() {
        return Collections.unmodifiableList(Arrays.asList(BY_ID.keySet().toArray(new String[0])));
    }

    public static boolean validRadius(int meters) {
        return meters >= MIN_RADIUS_M && meters <= MAX_RADIUS_M;
    }

    public static boolean validLimit(int limit) {
        return limit >= MIN_LIMIT && limit <= MAX_LIMIT;
    }

    public static int tagLabelRes(String rawTag) {
        if (rawTag == null) return 0;
        return switch (rawTag.trim().toLowerCase(Locale.ROOT)) {
            case "wheelchair" -> R.string.places_tag_wheelchair;
            case "takeaway" -> R.string.places_tag_takeaway;
            case "wifi" -> R.string.places_tag_wifi;
            case "24/7" -> R.string.places_tag_24_7;
            case "outdoor_seating" -> R.string.places_tag_outdoor_seating;
            default -> 0;
        };
    }

    static String plainCoords(double latitude, double longitude) {
        return BigDecimal.valueOf(latitude).stripTrailingZeros().toPlainString() + ","
                + BigDecimal.valueOf(longitude).stripTrailingZeros().toPlainString();
    }
}
