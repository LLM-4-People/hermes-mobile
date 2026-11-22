package org.llm4people.hermes.places;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.llm4people.hermes.VisibleText;

import java.math.BigDecimal;
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
import java.util.regex.Pattern;

/** A snapshot of the gateway's bounded, attributed OpenStreetMap restaurant results. */
public final class NearbyResult {
    private static final int RADIUS_METERS = 1500;
    private static final int MAX_PLACES = 12;
    private static final String CREDIT = "© OpenStreetMap contributors";
    private static final Pattern UTC = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z");

    public final String attribution, sourceDate;
    public final int radiusMeters;
    public final List<Place> places;

    private NearbyResult(String date, List<Place> places) {
        this.attribution = CREDIT;
        this.sourceDate = date;
        this.radiusMeters = RADIUS_METERS;
        this.places = Collections.unmodifiableList(places);
    }

    public static final class Place {
        public final String id, name, cuisine, address, sourceUrl;
        public final List<String> cuisines;
        public final double latitude, longitude;
        public final int distanceMeters;

        private Place(String id, String name, List<String> cuisines, String address, double lat, double lon, int distance) {
            this.id = id;
            this.name = name;
            this.cuisines = cuisines;
            this.cuisine = String.join(" · ", cuisines);
            this.address = address;
            this.latitude = lat;
            this.longitude = lon;
            this.distanceMeters = distance;
            this.sourceUrl = "https://www.openstreetmap.org/" + id;
        }

        /** Coordinates of the public venue, as plain decimal degrees suitable for Android's geo query. */
        public String coordinates() {
            return BigDecimal.valueOf(latitude).stripTrailingZeros().toPlainString() + ","
                    + BigDecimal.valueOf(longitude).stripTrailingZeros().toPlainString();
        }
    }

    public static NearbyResult parse(JSONObject response) throws JSONException {
        if (response == null || !"OpenStreetMap".equals(response.opt("source"))
                || !CREDIT.equals(response.opt("attribution")) || integer(response.opt("radius_m")) != RADIUS_METERS
                || !(response.opt("source_date") instanceof String date) || !UTC.matcher(date).matches()
                || !(response.opt("places") instanceof JSONArray rows) || rows.length() > MAX_PLACES) {
            throw new JSONException("Invalid nearby restaurant response");
        }
        try {
            if (!Instant.parse(date).toString().equals(date)) throw new JSONException("Invalid source date");
        } catch (DateTimeException invalid) { throw new JSONException("Invalid source date"); }

        List<Place> places = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            Place place = readPlace(rows.optJSONObject(i));
            if (place != null) places.add(place);
        }
        if (rows.length() > 0 && places.isEmpty()) throw new JSONException("No usable restaurant records");
        places.sort(Comparator.comparingInt(place -> place.distanceMeters));
        Set<String> seen = new HashSet<>();
        places.removeIf(place -> !seen.add(place.id));
        return new NearbyResult(date, places);
    }

    private static Place readPlace(JSONObject row) {
        if (row == null || !(row.opt("osm_type") instanceof String type)
                || !("node".equals(type) || "way".equals(type) || "relation".equals(type))) return null;
        long id = integer(row.opt("osm_id"));
        long distance = integer(row.opt("distance_m"));
        String name = label(row.opt("name"), 256);
        if (id <= 0 || distance < 0 || distance > RADIUS_METERS || name.isEmpty()
                || !(row.opt("latitude") instanceof Number lat) || !(row.opt("longitude") instanceof Number lon)) return null;
        double latitude = lat.doubleValue(), longitude = lon.doubleValue();
        if (!Double.isFinite(latitude) || Math.abs(latitude) > 90 || !Double.isFinite(longitude)
                || Math.abs(longitude) > 180) return null;
        return new Place(type + "/" + id, name, cuisines(label(row.opt("cuisine"), 256)),
                label(row.opt("address"), 320), latitude, longitude, (int) distance);
    }

    private static long integer(Object value) {
        return value instanceof Integer || value instanceof Long ? ((Number) value).longValue() : -1;
    }

    private static String label(Object value, int maxCodePoints) {
        if (!(value instanceof String text) || text.codePointCount(0, text.length()) > maxCodePoints
                || VisibleText.isBlank(text)) return "";
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i), type = Character.getType(cp);
            if (Character.isISOControl(cp) || type == Character.SURROGATE || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR || (type == Character.FORMAT && cp != 0x200C && cp != 0x200D))
                return "";
            i += Character.charCount(cp);
        }
        return text.trim();
    }

    private static List<String> cuisines(String value) {
        Set<String> labels = new LinkedHashSet<>();
        for (String part : value.split(";")) {
            String name = part.trim();
            if (VisibleText.isBlank(name)) continue;
            labels.add(switch (name.toLowerCase(Locale.ROOT)) {
                case "italian" -> "Italienne";
                case "italian_pizza" -> "Pizza italienne";
                case "pasta" -> "Pâtes";
                case "asian" -> "Asiatique";
                case "korean" -> "Coréenne";
                case "french" -> "Française";
                case "japanese" -> "Japonaise";
                case "chinese" -> "Chinoise";
                case "indian" -> "Indienne";
                case "vietnamese" -> "Vietnamienne";
                case "thai" -> "Thaïlandaise";
                case "lebanese" -> "Libanaise";
                case "mexican" -> "Mexicaine";
                case "greek" -> "Grecque";
                case "mediterranean" -> "Méditerranéenne";
                case "vegan" -> "Végétalienne";
                case "vegetarian" -> "Végétarienne";
                case "seafood" -> "Fruits de mer";
                case "pizza" -> "Pizza";
                case "burger" -> "Burgers";
                case "sushi" -> "Sushi";
                default -> name;
            });
        }
        return Collections.unmodifiableList(new ArrayList<>(labels));
    }
}
