package org.llm4people.hermes.onboarding;

import org.json.JSONObject;
import org.llm4people.hermes.JsonValues;
import org.llm4people.hermes.PlainTextSanitizer;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.profile.GatewayAddress;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Pure-Java typed data models and fail-closed wire parsers for Track 19 pairing,
 * multi-profile management, and first-run onboarding endpoints.
 * Both {@code fromMap} (host-JVM testable) and {@code fromJson} (Android runtime) share the
 * exact same validation logic.
 */
public final class OnboardingModels {
    private static final Pattern HEX_COLOR = Pattern.compile("#[0-9A-Fa-f]{6}");

    private OnboardingModels() {}

    public record PairingTicket(
            String ticketId,
            String code,
            long expiresAtEpochSec,
            int ttlSeconds,
            String deepLink,
            String profileSlug,
            String displayName,
            String mode) {
        public long remainingSeconds(long nowEpochSec) {
            return Math.max(0L, expiresAtEpochSec - nowEpochSec);
        }

        public boolean isExpired(long nowEpochSec) {
            return expiresAtEpochSec > 0L && nowEpochSec >= expiresAtEpochSec;
        }
    }

    public record StarterPrompt(String id, String title, String prompt, String category) {}

    public record OnboardingStatus(
            boolean ready,
            String activeModel,
            int activeCapabilities,
            int endpointCount,
            String language,
            String profileSlug,
            String displayName,
            boolean isAdmin,
            boolean profilesManageable,
            List<StarterPrompt> starters) {}

    public record AdminSettings(
            String defaultLanguage,
            String defaultModel,
            int maxConcurrentRuns,
            int perProfileConcurrentLimit,
            int pairingTtlSeconds,
            boolean allowSelfDevicePairing,
            int profileCount,
            int activeRunsCount,
            int activePairingTickets,
            int activeCapabilities,
            int endpointCount) {
        public static final int MIN_CONCURRENT_RUNS = 1;
        public static final int MAX_CONCURRENT_RUNS = 128;
        public static final int MIN_PER_PROFILE_RUNS = 1;
        public static final int MAX_PER_PROFILE_RUNS = 64;
        public static final int MIN_PAIRING_TTL_SEC = 60;
        public static final int MAX_PAIRING_TTL_SEC = 900;

        public AdminSettings {
            defaultLanguage = normalizeLanguage(defaultLanguage);
            defaultModel = cleanText(defaultModel, 128);
            maxConcurrentRuns = clamp(maxConcurrentRuns, MIN_CONCURRENT_RUNS, MAX_CONCURRENT_RUNS);
            perProfileConcurrentLimit = clamp(perProfileConcurrentLimit,
                    MIN_PER_PROFILE_RUNS, Math.min(MAX_PER_PROFILE_RUNS, maxConcurrentRuns));
            pairingTtlSeconds = clamp(pairingTtlSeconds, MIN_PAIRING_TTL_SEC, MAX_PAIRING_TTL_SEC);
            profileCount = Math.max(0, profileCount);
            activeRunsCount = Math.max(0, activeRunsCount);
            activePairingTickets = Math.max(0, activePairingTickets);
            activeCapabilities = Math.max(0, activeCapabilities);
            endpointCount = Math.max(0, endpointCount);
        }

        public Map<String, Object> toPatchMap() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("default_language", defaultLanguage);
            body.put("default_model", defaultModel);
            body.put("max_concurrent_runs", maxConcurrentRuns);
            body.put("per_profile_concurrent_limit", perProfileConcurrentLimit);
            body.put("pairing_ttl_seconds", pairingTtlSeconds);
            body.put("allow_self_device_pairing", allowSelfDevicePairing);
            return Collections.unmodifiableMap(body);
        }

        public JSONObject toPatchJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("default_language", defaultLanguage);
                obj.put("default_model", defaultModel);
                obj.put("max_concurrent_runs", maxConcurrentRuns);
                obj.put("per_profile_concurrent_limit", perProfileConcurrentLimit);
                obj.put("pairing_ttl_seconds", pairingTtlSeconds);
                obj.put("allow_self_device_pairing", allowSelfDevicePairing);
            } catch (org.json.JSONException impossible) {
                throw new IllegalStateException(impossible);
            }
            return obj;
        }
    }

    /** Non-consuming metadata returned by {@code POST /api/mobile/pairing/inspect} (never contains an API key). */
    public record PairingInspect(
            boolean valid,
            String serverUrl,
            String baseHost,
            String profileSlug,
            String displayName,
            String mode,
            long expiresAtEpochSec,
            int ttlRemainingSeconds) {
        /** Canonical endpoint combining {@link #serverUrl} and optional non-default {@link #profileSlug}. */
        public String resolvedEndpoint() {
            return profileSlug == null || profileSlug.isEmpty() || "default".equalsIgnoreCase(profileSlug)
                    ? GatewayAddress.normalize(serverUrl)
                    : GatewayAddress.normalize(serverUrl, profileSlug);
        }
    }

    public record PairingClaim(
            String serverUrl,
            String profileSlug,
            String displayName,
            String apiKey,
            String preferredLanguage,
            String defaultModel,
            int activeCapabilities,
            OnboardingStatus onboarding) {
        /** Canonical endpoint combining {@link #serverUrl} and optional non-default {@link #profileSlug}. */
        public String resolvedEndpoint() {
            return profileSlug == null || profileSlug.isEmpty() || "default".equalsIgnoreCase(profileSlug)
                    ? GatewayAddress.normalize(serverUrl)
                    : GatewayAddress.normalize(serverUrl, profileSlug);
        }
    }

    public record RemoteProfile(
            String slug,
            String displayName,
            String preferredLanguage,
            String defaultModel,
            String badgeColor,
            int activeRuns,
            int threadCount,
            long lastActiveEpochSec,
            boolean isAdmin,
            boolean isolatedDbOk) {
        /** Crisp 1-2 character avatar initials derived from display name or slug. */
        public String initials() {
            return Ui.initials(displayName, slug);
        }
    }

    public record RotateKeyResult(String profileSlug, String apiKey, PairingTicket pairingTicket) {}

    public static PairingTicket parsePairingTicket(JSONObject obj) {
        return parsePairingTicketMap(JsonValues.toMap(obj));
    }

    public static PairingTicket parsePairingTicketMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) throw new IllegalArgumentException("Missing pairing ticket");
        String ticketId = cleanText(str(map.get("ticket_id")), 96);
        String code = str(map.get("code")).trim();
        long expiresAt = parseEpochSeconds(map.get("expires_at"));
        Object rawTtl = map.containsKey("ttl_seconds") ? map.get("ttl_seconds") : map.get("ttl_remaining_seconds");
        int ttl = JsonValues.parseNonNegativeInt(rawTtl, 0);
        String deepLink = str(map.get("deep_link")).trim();
        String slug = safeSlug(str(map.get("profile_slug")));
        String displayName = cleanText(str(map.get("display_name")), 64);
        String mode = normalizeMode(str(map.get("mode")));
        if (ticketId.isEmpty() && code.isEmpty()) {
            throw new IllegalArgumentException("Pairing ticket missing identifier");
        }
        if (!code.isEmpty() && !PairingLinkParser.isValidPairingCode(code)) {
            throw new IllegalArgumentException("Malformed pairing code");
        }
        if (!deepLink.isEmpty() && PairingLinkParser.parseOrNull(deepLink) == null) {
            throw new IllegalArgumentException("Invalid pairing deep link");
        }
        return new PairingTicket(ticketId, code, expiresAt, ttl, deepLink, slug, displayName, mode);
    }

    public static List<PairingTicket> parseActivePairingTickets(JSONObject obj) {
        return parseActivePairingTicketsMap(JsonValues.toMap(obj));
    }

    @SuppressWarnings("unchecked")
    public static List<PairingTicket> parseActivePairingTicketsMap(Map<String, Object> map) {
        if (map == null) return Collections.emptyList();
        Object raw = map.containsKey("data") ? map.get("data")
                : (map.containsKey("tickets") ? map.get("tickets") : map.get("items"));
        if (!(raw instanceof List<?> list)) return Collections.emptyList();
        List<PairingTicket> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                try {
                    out.add(parsePairingTicketMap((Map<String, Object>) m));
                } catch (IllegalArgumentException ignored) {
                    // skip malformed individual row
                }
            }
        }
        return Collections.unmodifiableList(out);
    }

    public static PairingInspect parsePairingInspect(JSONObject obj) {
        return parsePairingInspectMap(JsonValues.toMap(obj));
    }

    public static PairingInspect parsePairingInspectMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            throw new IllegalArgumentException("Missing pairing inspect response");
        }
        boolean valid = bool(map.get("valid"), false);
        if (!valid) {
            throw new IllegalArgumentException("Pairing ticket is not valid");
        }
        String rawServer = str(map.get("server_url")).trim();
        String rawBaseHost = str(map.get("base_host")).trim();
        String chosenUrl = !rawServer.isEmpty() ? rawServer : rawBaseHost;
        String serverUrl = GatewayAddress.normalize(chosenUrl);
        String baseHost = rawBaseHost.isEmpty() ? serverUrl : GatewayAddress.normalize(rawBaseHost);
        String slug = safeSlug(str(map.get("profile_slug")));
        String displayName = cleanText(str(map.get("display_name")), 64);
        String mode = normalizeMode(str(map.get("mode")));
        long expiresAt = parseEpochSeconds(map.get("expires_at"));
        Object rawTtl = map.containsKey("ttl_remaining_seconds")
                ? map.get("ttl_remaining_seconds") : map.get("ttl_seconds");
        int ttl = JsonValues.parseNonNegativeInt(rawTtl, 0);
        return new PairingInspect(true, serverUrl, baseHost, slug, displayName, mode, expiresAt, ttl);
    }

    public static PairingClaim parsePairingClaim(JSONObject obj) {
        return parsePairingClaimMap(JsonValues.toMap(obj));
    }

    @SuppressWarnings("unchecked")
    public static PairingClaim parsePairingClaimMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) throw new IllegalArgumentException("Missing pairing claim response");
        String serverUrl = GatewayAddress.normalize(str(map.get("server_url")));
        String slug = safeSlug(str(map.get("profile_slug")));
        String displayName = cleanText(str(map.get("display_name")), 64);
        String apiKey = str(map.get("api_key")).trim();
        if (!PairingLinkParser.isValidApiKey(apiKey)) {
            throw new IllegalArgumentException("Invalid API key in claim response");
        }
        String language = normalizeLanguage(str(map.get("preferred_language")));
        String defaultModel = cleanText(str(map.get("default_model")), 128);
        int caps = extractCapabilityCount(map.get("capabilities_summary"));
        OnboardingStatus onboarding = null;
        Object rawOnboarding = map.get("onboarding");
        if (rawOnboarding instanceof Map<?, ?> om) {
            onboarding = parseOnboardingStatusMap((Map<String, Object>) om);
            if (caps == 0) caps = onboarding.activeCapabilities();
        }
        return new PairingClaim(serverUrl, slug, displayName, apiKey, language, defaultModel, caps, onboarding);
    }

    public static OnboardingStatus parseOnboardingStatus(JSONObject obj) {
        return parseOnboardingStatusMap(JsonValues.toMap(obj));
    }

    @SuppressWarnings("unchecked")
    public static OnboardingStatus parseOnboardingStatusMap(Map<String, Object> map) {
        if (map == null) {
            return new OnboardingStatus(false, "", 0, 0, "auto", "", "", false, false, Collections.emptyList());
        }
        boolean ready = bool(map.get("ready"), false);
        String activeModel = cleanText(str(map.get("active_model")), 128);
        int activeCapabilities = JsonValues.parseNonNegativeInt(map.get("active_capabilities"), 0);
        int endpointCount = JsonValues.parseNonNegativeInt(map.get("endpoint_count"), 0);
        String language = normalizeLanguage(str(map.get("language")));
        boolean manageable = bool(map.get("profiles_manageable"), false);

        String slug = "";
        String displayName = "";
        Boolean profileIsAdmin = null;
        Object rawProfile = map.get("profile");
        if (rawProfile instanceof Map<?, ?> pm) {
            slug = safeSlug(str(pm.get("slug")));
            displayName = cleanText(str(pm.get("display_name")), 64);
            if (pm.get("is_admin") instanceof Boolean b) profileIsAdmin = b;
            else if ("admin".equalsIgnoreCase(str(pm.get("role")))) profileIsAdmin = Boolean.TRUE;
        } else if (rawProfile instanceof String s) {
            slug = safeSlug(s);
        }
        if (slug.isEmpty()) slug = safeSlug(str(map.get("profile_slug")));
        if (displayName.isEmpty()) displayName = cleanText(str(map.get("display_name")), 64);
        boolean isAdmin;
        if (map.get("is_admin") instanceof Boolean b) {
            isAdmin = b;
        } else if ("admin".equalsIgnoreCase(str(map.get("role")))) {
            isAdmin = true;
        } else if (profileIsAdmin != null) {
            isAdmin = profileIsAdmin;
        } else {
            isAdmin = manageable;
        }

        List<StarterPrompt> starters = new ArrayList<>();
        Object rawStarters = map.containsKey("starters") ? map.get("starters") : map.get("starter_suggestions");
        if (rawStarters instanceof List<?> list) {
            int idx = 0;
            for (Object entry : list) {
                if (starters.size() >= 8) break;
                idx++;
                if (entry instanceof Map<?, ?> sm) {
                    String sid = cleanText(str(sm.get("id")), 48);
                    String title = cleanText(str(sm.get("title")), 80);
                    String prompt = cleanText(str(sm.containsKey("prompt") ? sm.get("prompt") : sm.get("draft")), 400);
                    String category = cleanText(str(sm.get("category")), 32);
                    if (title.isEmpty()) title = prompt;
                    if (prompt.isEmpty()) prompt = title;
                    if (!prompt.isEmpty()) {
                        starters.add(new StarterPrompt(sid.isEmpty() ? "starter_" + idx : sid, title, prompt, category));
                    }
                } else if (entry instanceof String text) {
                    String clean = cleanText(text, 400);
                    if (!clean.isEmpty()) {
                        starters.add(new StarterPrompt("starter_" + idx, clean, clean, ""));
                    }
                }
            }
        }
        return new OnboardingStatus(ready, activeModel, activeCapabilities, endpointCount, language,
                slug, displayName, isAdmin, manageable || isAdmin, Collections.unmodifiableList(starters));
    }

    public static AdminSettings parseAdminSettings(JSONObject obj) {
        return parseAdminSettingsMap(JsonValues.toMap(obj));
    }

    @SuppressWarnings("unchecked")
    public static AdminSettings parseAdminSettingsMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            throw new IllegalArgumentException("Missing admin settings payload");
        }
        Map<String, Object> settings = map.get("settings") instanceof Map<?, ?> nested
                ? (Map<String, Object>) nested : map;
        Map<String, Object> summary = map.get("summary") instanceof Map<?, ?> nestedSummary
                ? (Map<String, Object>) nestedSummary : map;
        String defaultLanguage = normalizeLanguage(str(settings.get("default_language")));
        String defaultModel = cleanText(str(settings.get("default_model")), 128);
        int maxConcurrent = JsonValues.parseInt(settings.get("max_concurrent_runs"), 32);
        int perProfile = JsonValues.parseInt(settings.get("per_profile_concurrent_limit"), 12);
        int pairingTtl = JsonValues.parseInt(settings.get("pairing_ttl_seconds"), 300);
        boolean allowSelfPairing = bool(settings.get("allow_self_device_pairing"), true);
        int profileCount = JsonValues.parseNonNegativeInt(summary.get("profile_count"), 0);
        int activeRunsCount = JsonValues.parseNonNegativeInt(summary.get("active_runs_count"), 0);
        int activeTickets = JsonValues.parseNonNegativeInt(summary.get("active_pairing_tickets"), 0);
        int activeCaps = JsonValues.parseNonNegativeInt(summary.get("active_capabilities"), 0);
        int endpoints = JsonValues.parseNonNegativeInt(summary.get("endpoint_count"), 0);
        return new AdminSettings(defaultLanguage, defaultModel, maxConcurrent, perProfile, pairingTtl,
                allowSelfPairing, profileCount, activeRunsCount, activeTickets, activeCaps, endpoints);
    }

    public static List<RemoteProfile> parseProfiles(JSONObject obj) {
        return parseProfilesMap(JsonValues.toMap(obj));
    }

    @SuppressWarnings("unchecked")
    public static List<RemoteProfile> parseProfilesMap(Map<String, Object> map) {
        if (map == null) return Collections.emptyList();
        Object raw = map.containsKey("data") ? map.get("data")
                : (map.containsKey("profiles") ? map.get("profiles") : map.get("items"));
        if (!(raw instanceof List<?> list)) return Collections.emptyList();
        List<RemoteProfile> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> pm) {
                RemoteProfile parsed = parseRemoteProfileMap((Map<String, Object>) pm);
                if (parsed != null) out.add(parsed);
            }
        }
        return Collections.unmodifiableList(out);
    }

    @SuppressWarnings("unchecked")
    public static RemoteProfile parseRemoteProfileMap(Map<String, Object> map) {
        if (map == null) return null;
        Map<String, Object> src = map.get("profile") instanceof Map<?, ?> nested
                ? (Map<String, Object>) nested : map;
        String slug = safeSlug(str(src.get("slug")));
        if (slug.isEmpty()) slug = safeSlug(str(src.get("profile_slug")));
        if (slug.isEmpty()) return null;
        String displayName = cleanText(str(src.get("display_name")), 64);
        if (displayName.isEmpty()) displayName = slug;
        String lang = normalizeLanguage(str(src.containsKey("preferred_language")
                ? src.get("preferred_language") : src.get("language")));
        String model = cleanText(str(src.get("default_model")), 128);
        String badgeColor = safeHexColor(str(src.get("badge_color")));
        int activeRuns = JsonValues.parseNonNegativeInt(src.get("active_runs"), 0);
        int threadCount = JsonValues.parseNonNegativeInt(src.get("thread_count"), 0);
        long lastActive = parseEpochSeconds(src.get("last_active"));
        boolean isAdmin = src.get("is_admin") instanceof Boolean b
                ? b : "admin".equalsIgnoreCase(str(src.get("role")));
        boolean isolatedDbOk = bool(src.get("isolated_db_ok"), true);
        return new RemoteProfile(slug, displayName, lang, model, badgeColor,
                activeRuns, threadCount, lastActive, isAdmin, isolatedDbOk);
    }

    public static RotateKeyResult parseRotateKey(JSONObject obj) {
        return parseRotateKeyMap(JsonValues.toMap(obj));
    }

    @SuppressWarnings("unchecked")
    public static RotateKeyResult parseRotateKeyMap(Map<String, Object> map) {
        if (map == null) throw new IllegalArgumentException("Missing rotate-key response");
        String slug = safeSlug(str(map.containsKey("profile_slug") ? map.get("profile_slug") : map.get("slug")));
        String apiKey = str(map.get("api_key")).trim();
        if (!PairingLinkParser.isValidApiKey(apiKey)) {
            throw new IllegalArgumentException("Missing or invalid rotated API key");
        }
        PairingTicket ticket = null;
        Object rawTicket = map.containsKey("pairing_ticket") ? map.get("pairing_ticket") : map.get("ticket");
        if (rawTicket instanceof Map<?, ?> tm) {
            ticket = parsePairingTicketMap((Map<String, Object>) tm);
        }
        return new RotateKeyResult(slug, apiKey, ticket);
    }

    public static String normalizeLanguage(String raw) {
        if (raw == null) return "auto";
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        if (lower.equals("fr") || lower.startsWith("fr-") || lower.startsWith("fr_")) return "fr";
        if (lower.equals("en") || lower.startsWith("en-") || lower.startsWith("en_")) return "en";
        return "auto";
    }

    public static String safeHexColor(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        return HEX_COLOR.matcher(trimmed).matches() ? trimmed.toUpperCase(Locale.ROOT) : "";
    }

    private static String safeSlug(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        try {
            return GatewayAddress.normalizeProfileSlug(raw);
        } catch (IllegalArgumentException invalid) {
            return "";
        }
    }

    private static String normalizeMode(String raw) {
        String lower = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return ("device".equals(lower) || "invite".equals(lower)) ? lower : "device";
    }

    private static int extractCapabilityCount(Object summary) {
        if (summary instanceof Number) return JsonValues.parseNonNegativeInt(summary, 0);
        if (summary instanceof Map<?, ?> m) {
            int count = JsonValues.parseInt(m.get("active_capabilities"), -1);
            if (count >= 0) return count;
            return JsonValues.parseNonNegativeInt(m.get("count"), 0);
        }
        return 0;
    }

    private static long parseEpochSeconds(Object value) {
        return org.llm4people.hermes.EpochTimestamps.parseEpochSeconds(value);
    }

    private static String cleanText(String raw, int maxChars) {
        return PlainTextSanitizer.collapseBounded(raw, maxChars);
    }

    private static String str(Object value) {
        return value instanceof String s ? s : "";
    }

    private static boolean bool(Object value, boolean fallback) {
        return value instanceof Boolean b ? b : fallback;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
