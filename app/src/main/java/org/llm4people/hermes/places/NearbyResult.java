package org.llm4people.hermes.places;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.llm4people.hermes.AppLanguage;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.HermesConfig;
import org.llm4people.hermes.R;
import org.llm4people.hermes.PlainTextSanitizer;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.regex.Pattern;

/** A snapshot of the gateway's bounded, attributed OpenStreetMap nearby results. */
public final class NearbyResult {
    private static final String CREDIT = "© OpenStreetMap contributors";
    private static final Pattern UTC = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z");

    public final String attribution, sourceDate;
    public final NearbyCategory category;
    public final int radiusMeters;
    public final int limit;
    public final List<Place> places;

    private NearbyResult(String date, NearbyCategory category, int radiusMeters, int limit, List<Place> places) {
        this.attribution = CREDIT;
        this.sourceDate = date;
        this.category = category;
        this.radiusMeters = radiusMeters;
        this.limit = limit;
        this.places = Collections.unmodifiableList(places);
    }

    public static final class Place {
        public final String id, name, cuisine, address, openingHours, sourceUrl;
        public final List<String> cuisines;
        public final List<String> tags;
        public final double latitude, longitude;
        public final int distanceMeters;

        private Place(String id, String name, List<String> cuisines, List<String> tags, String address,
                      String openingHours, double lat, double lon, int distance, String osmBaseUrl) {
            this.id = id;
            this.name = name;
            this.cuisines = cuisines;
            this.cuisine = String.join(" · ", cuisines);
            this.tags = tags;
            this.address = address;
            this.openingHours = openingHours;
            this.latitude = lat;
            this.longitude = lon;
            this.distanceMeters = distance;
            String base = osmBaseUrl == null || osmBaseUrl.trim().isEmpty()
                    ? HermesConfig.DEFAULT_OSM_BASE_URL
                    : osmBaseUrl.trim();
            if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            this.sourceUrl = base + "/" + id;
        }

        /** Coordinates of the public venue, as plain decimal degrees suitable for Android's geo query. */
        public String coordinates() {
            return NearbyCategory.plainCoords(latitude, longitude);
        }
    }

    public static NearbyResult parse(JSONObject response) throws JSONException {
        return parse(response, (IntFunction<String>) null, HermesConfig.DEFAULT_OSM_BASE_URL);
    }

    public static NearbyResult parse(Context context, JSONObject response) throws JSONException {
        Context localized = context == null ? null : AppLanguage.wrapContext(context);
        String osmBaseUrl = HermesConfig.load(context).openStreetMapBaseUrl;
        return parse(response, localized == null ? null : localized::getString, osmBaseUrl);
    }

    public static NearbyResult parse(JSONObject response, IntFunction<String> strings) throws JSONException {
        return parse(response, strings, HermesConfig.DEFAULT_OSM_BASE_URL);
    }

    public static NearbyResult parse(JSONObject response, IntFunction<String> strings, String osmBaseUrl) throws JSONException {
        if (response == null || !"OpenStreetMap".equals(response.opt("source"))
                || !CREDIT.equals(response.opt("attribution"))
                || !(response.opt("source_date") instanceof String date) || !UTC.matcher(date).matches()
                || !(response.opt("places") instanceof JSONArray rows)) {
            Diagnostics.warning("places", "invalid_response");
            throw new JSONException("Invalid nearby places response");
        }
        long rawRadius = integer(response.opt("radius_m"));
        if (rawRadius < NearbyCategory.MIN_RADIUS_M || rawRadius > NearbyCategory.MAX_RADIUS_M) {
            Diagnostics.warning("places", "invalid_radius");
            throw new JSONException("Invalid nearby radius");
        }
        int radius = (int) rawRadius;
        int limit = NearbyCategory.DEFAULT_LIMIT;
        if (response.has("limit")) {
            long rawLimit = integer(response.opt("limit"));
            if (rawLimit < NearbyCategory.MIN_LIMIT || rawLimit > NearbyCategory.MAX_LIMIT) {
                Diagnostics.warning("places", "invalid_limit");
                throw new JSONException("Invalid nearby limit");
            }
            limit = (int) rawLimit;
        }
        if (rows.length() > limit) {
            Diagnostics.warning("places", "limit_exceeded");
            throw new JSONException("Nearby places exceeded limit");
        }
        NearbyCategory category = NearbyCategory.RESTAURANT;
        if (response.has("category")) {
            Object rawCategory = response.opt("category");
            if (!(rawCategory instanceof String catId) || (category = NearbyCategory.fromId(catId)) == null) {
                Diagnostics.warning("places", "invalid_category");
                throw new JSONException("Invalid nearby category");
            }
        }
        try {
            if (!Instant.parse(date).toString().equals(date)) {
                Diagnostics.warning("places", "invalid_source_date");
                throw new JSONException("Invalid source date");
            }
        } catch (DateTimeException invalid) {
            Diagnostics.warning("places", "invalid_source_date", invalid);
            throw new JSONException("Invalid source date");
        }

        List<Place> places = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            Place place = readPlace(rows.optJSONObject(i), radius, strings, osmBaseUrl);
            if (place != null) places.add(place);
        }
        if (rows.length() > 0 && places.isEmpty()) {
            Diagnostics.warning("places", "no_usable_records");
            throw new JSONException("No usable place records");
        }
        places.sort(Comparator.comparingInt(place -> place.distanceMeters));
        Set<String> seen = new HashSet<>();
        places.removeIf(place -> !seen.add(place.id));
        return new NearbyResult(date, category, radius, limit, places);
    }

    private static Place readPlace(JSONObject row, int radiusMeters, IntFunction<String> strings, String osmBaseUrl) {
        if (row == null || !(row.opt("osm_type") instanceof String type)
                || !("node".equals(type) || "way".equals(type) || "relation".equals(type))) return null;
        long id = integer(row.opt("osm_id"));
        long distance = integer(row.opt("distance_m"));
        String name = label(row.opt("name"), 256);
        if (id <= 0 || distance < 0 || distance > radiusMeters || name.isEmpty()
                || !(row.opt("latitude") instanceof Number lat) || !(row.opt("longitude") instanceof Number lon)) return null;
        double latitude = lat.doubleValue(), longitude = lon.doubleValue();
        if (!Double.isFinite(latitude) || Math.abs(latitude) > 90 || !Double.isFinite(longitude)
                || Math.abs(longitude) > 180) return null;
        return new Place(type + "/" + id, name, cuisines(label(row.opt("cuisine"), 256), strings),
                readTags(row.opt("tags"), strings), label(row.opt("address"), 320),
                label(row.opt("opening_hours"), 160), latitude, longitude, (int) distance, osmBaseUrl);
    }

    private static List<String> readTags(Object raw, IntFunction<String> strings) {
        if (!(raw instanceof JSONArray array)) return Collections.emptyList();
        Set<String> labels = new LinkedHashSet<>();
        int cap = Math.min(array.length(), 10);
        for (int i = 0; i < cap; i++) {
            Object item = array.opt(i);
            if (item instanceof String text) {
                int res = NearbyCategory.tagLabelRes(text);
                if (res != 0) {
                    String mapped = strings == null ? text.trim().toLowerCase(Locale.ROOT) : strings.apply(res);
                    if (mapped != null && !mapped.isEmpty()) labels.add(mapped);
                }
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(labels));
    }

    private static long integer(Object value) {
        return value instanceof Integer || value instanceof Long ? ((Number) value).longValue() : -1;
    }

    private static String label(Object value, int maxCodePoints) {
        if (!(value instanceof String text) || text.codePointCount(0, text.length()) > maxCodePoints
                || !PlainTextSanitizer.hasVisibleText(text)) return "";
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i), type = Character.getType(cp);
            if (Character.isISOControl(cp) || type == Character.SURROGATE || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR || (type == Character.FORMAT && cp != 0x200C && cp != 0x200D))
                return "";
            i += Character.charCount(cp);
        }
        return text.trim();
    }

    public static int cuisineLabelRes(String rawCuisine) {
        if (rawCuisine == null) return 0;
        return switch (rawCuisine.trim().toLowerCase(Locale.ROOT)) {
            case "italian" -> R.string.places_cuisine_italian;
            case "italian_pizza" -> R.string.places_cuisine_italian_pizza;
            case "pasta" -> R.string.places_cuisine_pasta;
            case "asian" -> R.string.places_cuisine_asian;
            case "korean" -> R.string.places_cuisine_korean;
            case "french" -> R.string.places_cuisine_french;
            case "japanese" -> R.string.places_cuisine_japanese;
            case "chinese" -> R.string.places_cuisine_chinese;
            case "indian" -> R.string.places_cuisine_indian;
            case "vietnamese" -> R.string.places_cuisine_vietnamese;
            case "thai" -> R.string.places_cuisine_thai;
            case "lebanese" -> R.string.places_cuisine_lebanese;
            case "mexican" -> R.string.places_cuisine_mexican;
            case "greek" -> R.string.places_cuisine_greek;
            case "mediterranean" -> R.string.places_cuisine_mediterranean;
            case "vegan" -> R.string.places_cuisine_vegan;
            case "vegetarian" -> R.string.places_cuisine_vegetarian;
            case "seafood" -> R.string.places_cuisine_seafood;
            case "pizza" -> R.string.places_cuisine_pizza;
            case "burger" -> R.string.places_cuisine_burger;
            case "sushi" -> R.string.places_cuisine_sushi;
            default -> 0;
        };
    }

    private static List<String> cuisines(String value, IntFunction<String> strings) {
        Set<String> labels = new LinkedHashSet<>();
        for (String part : value.split(";")) {
            String name = part.trim();
            if (!PlainTextSanitizer.hasVisibleText(name)) continue;
            int res = cuisineLabelRes(name);
            String resolved = res != 0 && strings != null ? strings.apply(res) : name;
            labels.add(resolved == null || resolved.isEmpty() ? name : resolved);
        }
        return Collections.unmodifiableList(new ArrayList<>(labels));
    }
}
