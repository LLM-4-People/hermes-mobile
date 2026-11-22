package org.llm4people.hermes.places;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Single source of truth for allowlisted OpenStreetMap categories, bounds, and route handoff modes. */
public enum NearbyCategory {
    RESTAURANT("restaurant", "Restaurants", "Restaurants dans la zone",
            "Recherche des restaurants dans la zone partagée…",
            "Aucun restaurant répertorié dans ce rayon.",
            "Le guide des restaurants est indisponible. Vous pouvez choisir à nouveau une zone.",
            "Repères des restaurants, nord en haut.",
            "Restaurant suivant", "Restaurant précédent", "des restaurants", true),
    CAFE("cafe", "Cafés", "Cafés dans la zone",
            "Recherche des cafés dans la zone partagée…",
            "Aucun café répertorié dans ce rayon.",
            "Le guide des cafés est indisponible. Vous pouvez choisir à nouveau une zone.",
            "Repères des cafés, nord en haut.",
            "Café suivant", "Café précédent", "des cafés", true),
    BAKERY("bakery", "Boulangeries", "Boulangeries dans la zone",
            "Recherche des boulangeries dans la zone partagée…",
            "Aucune boulangerie répertoriée dans ce rayon.",
            "Le guide des boulangeries est indisponible. Vous pouvez choisir à nouveau une zone.",
            "Repères des boulangeries, nord en haut.",
            "Boulangerie suivante", "Boulangerie précédente", "des boulangeries", true),
    PHARMACY("pharmacy", "Pharmacies", "Pharmacies dans la zone",
            "Recherche des pharmacies dans la zone partagée…",
            "Aucune pharmacie répertoriée dans ce rayon.",
            "Le guide des pharmacies est indisponible. Vous pouvez choisir à nouveau une zone.",
            "Repères des pharmacies, nord en haut.",
            "Pharmacie suivante", "Pharmacie précédente", "des pharmacies", false),
    TRANSIT("transit", "Transports", "Transports dans la zone",
            "Recherche des arrêts et stations dans la zone partagée…",
            "Aucun arrêt ou station répertorié dans ce rayon.",
            "Le guide des transports est indisponible. Vous pouvez choisir à nouveau une zone.",
            "Repères des transports, nord en haut.",
            "Arrêt suivant", "Arrêt précédent", "des transports", false),
    PARK("park", "Parcs", "Parcs dans la zone",
            "Recherche des parcs dans la zone partagée…",
            "Aucun parc répertorié dans ce rayon.",
            "Le guide des parcs est indisponible. Vous pouvez choisir à nouveau une zone.",
            "Repères des parcs, nord en haut.",
            "Parc suivant", "Parc précédent", "des parcs", false),
    GROCERY("grocery", "Épiceries", "Épiceries dans la zone",
            "Recherche des épiceries dans la zone partagée…",
            "Aucune épicerie répertoriée dans ce rayon.",
            "Le guide des épiceries est indisponible. Vous pouvez choisir à nouveau une zone.",
            "Repères des épiceries, nord en haut.",
            "Épicerie suivante", "Épicerie précédente", "des épiceries", false),
    BOOKSHOP("bookshop", "Librairies", "Librairies dans la zone",
            "Recherche des librairies dans la zone partagée…",
            "Aucune librairie répertoriée dans ce rayon.",
            "Le guide des librairies est indisponible. Vous pouvez choisir à nouveau une zone.",
            "Repères des librairies, nord en haut.",
            "Librairie suivante", "Librairie précédente", "des librairies", false),
    COWORKING("coworking", "Coworking", "Espaces de coworking dans la zone",
            "Recherche des espaces de coworking dans la zone partagée…",
            "Aucun espace de coworking répertorié dans ce rayon.",
            "Le guide des espaces de coworking est indisponible. Vous pouvez choisir à nouveau une zone.",
            "Repères des espaces de coworking, nord en haut.",
            "Espace suivant", "Espace précédent", "des espaces de coworking", false);

    public static final int MIN_RADIUS_M = 500;
    public static final int DEFAULT_RADIUS_M = 1500;
    public static final int MAX_RADIUS_M = 5000;
    public static final int MIN_LIMIT = 6;
    public static final int DEFAULT_LIMIT = 12;
    public static final int MAX_LIMIT = 24;
    public static final List<Integer> RADIUS_CHOICES = Collections.unmodifiableList(Arrays.asList(500, 1500, 3000, 5000));

    public enum RouteMode {
        WALKING("w", "À pied", "Itinéraire à pied", "Ouvrir l’itinéraire à pied"),
        DRIVING("d", "En voiture", "Itinéraire en voiture", "Ouvrir l’itinéraire en voiture");

        public final String navParam, chipLabel, title, openLabel;

        RouteMode(String navParam, String chipLabel, String title, String openLabel) {
            this.navParam = navParam;
            this.chipLabel = chipLabel;
            this.title = title;
            this.openLabel = openLabel;
        }

        public String navigationUri(double latitude, double longitude) {
            return "google.navigation:q=" + plainCoords(latitude, longitude) + "&mode=" + navParam;
        }

        public String fallbackGeoUri(double latitude, double longitude) {
            return "geo:0,0?q=" + plainCoords(latitude, longitude);
        }
    }

    public final String id, label, heading, loadingMessage, emptyMessage, unavailableMessage;
    public final String compassDescription, nextItemLabel, previousItemLabel, draftPlural;
    public final boolean supportsCuisine;

    private static final Map<String, NearbyCategory> BY_ID;
    static {
        Map<String, NearbyCategory> map = new LinkedHashMap<>();
        for (NearbyCategory category : values()) map.put(category.id, category);
        BY_ID = Collections.unmodifiableMap(map);
    }

    NearbyCategory(String id, String label, String heading, String loadingMessage, String emptyMessage,
                   String unavailableMessage, String compassDescription, String nextItemLabel,
                   String previousItemLabel, String draftPlural, boolean supportsCuisine) {
        this.id = id;
        this.label = label;
        this.heading = heading;
        this.loadingMessage = loadingMessage;
        this.emptyMessage = emptyMessage;
        this.unavailableMessage = unavailableMessage;
        this.compassDescription = compassDescription;
        this.nextItemLabel = nextItemLabel;
        this.previousItemLabel = previousItemLabel;
        this.draftPlural = draftPlural;
        this.supportsCuisine = supportsCuisine;
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

    public static String tagLabel(String rawTag) {
        if (rawTag == null) return "";
        return switch (rawTag.trim().toLowerCase(Locale.ROOT)) {
            case "wheelchair" -> "Accès PMR";
            case "takeaway" -> "À emporter";
            case "wifi" -> "Wi-Fi";
            case "24/7" -> "Ouvert 24h/24";
            case "outdoor_seating" -> "Terrasse";
            default -> "";
        };
    }

    static String plainCoords(double latitude, double longitude) {
        return BigDecimal.valueOf(latitude).stripTrailingZeros().toPlainString() + ","
                + BigDecimal.valueOf(longitude).stripTrailingZeros().toPlainString();
    }
}
