package org.llm4people.hermes.os;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;
import org.llm4people.hermes.Diagnostics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Multi-instance widget store mapping Android {@code appWidgetId -> widgetId} and persisting
 * home-screen-safe declarative widget specs. Testable on the host JVM via {@link Backing}.
 */
public final class HermesWidgetStore {
    private static final String PREFS = "hermes.os.widgets";
    private static final String SPEC_PREFIX = "spec.";
    private static final String BIND_PREFIX = "bind.";
    private static final String PIN_PREFIX = "pin.";

    public interface Backing {
        String get(String key);
        void put(String key, String value);
        void remove(String key);
        Map<String, String> all();
    }

    private final Backing backing;

    public HermesWidgetStore(Context context) {
        this(new SharedPrefsBacking(context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)));
    }

    public HermesWidgetStore(Backing backing) {
        this.backing = Objects.requireNonNull(backing);
    }

    /**
     * Stores a spec unless another profile already owns this widget ID. An unowned write never
     * strips an existing owner, so ownership can only be claimed, never silently erased or stolen.
     */
    public synchronized boolean upsertSpec(HermesWidgetSpec spec) {
        Objects.requireNonNull(spec);
        if (!ownerAllows(spec.widgetId, spec.ownerProfileId)) {
            logWarning("spec_owner_conflict");
            return false;
        }
        backing.put(SPEC_PREFIX + spec.widgetId, spec.encode());
        logEvent("spec_upserted");
        return true;
    }

    public synchronized boolean upsertSpec(HermesWidgetSpec spec, String ownerProfileId) {
        Objects.requireNonNull(spec);
        return upsertSpec(spec.withOwner(ownerProfileId));
    }

    /** Removes a spec only when it is unowned or owned by {@code ownerProfileId}. */
    public synchronized boolean removeSpec(String widgetId, String ownerProfileId) {
        if (!ownerAllows(widgetId, ownerProfileId)) {
            logWarning("spec_owner_conflict");
            return false;
        }
        return removeSpec(widgetId);
    }

    private boolean ownerAllows(String widgetId, String requester) {
        HermesWidgetSpec existing = spec(widgetId);
        return existing == null || existing.ownerProfileId == null || existing.ownerProfileId.equals(requester);
    }

    /** Derives a stable per-profile widget ID without exposing the local profile ID in launcher intents. */
    public static String profileScopedId(String baseId, String ownerProfileId) {
        if (!HermesWidgetSpec.validWidgetId(baseId)) throw new IllegalArgumentException("Invalid widget id");
        if (ownerProfileId == null || ownerProfileId.isEmpty()) return baseId;
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(ownerProfileId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder suffix = new StringBuilder(16);
            for (int i = 0; i < 8; i++) suffix.append(String.format(java.util.Locale.ROOT, "%02x", digest[i]));
            String scoped = baseId + "_" + suffix;
            if (!HermesWidgetSpec.validWidgetId(scoped)) throw new IllegalArgumentException("Widget id too long");
            return scoped;
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    public synchronized String ownerForWidget(String widgetId) {
        HermesWidgetSpec found = spec(widgetId);
        return found == null ? null : found.ownerProfileId;
    }

    /** Purges all widget specs, bound instances, and pending pins belonging to a forgotten profile. */
    public synchronized int purgeProfile(String profileId) {
        if (profileId == null || profileId.trim().isEmpty()) return 0;
        String target = profileId.trim();
        List<String> ownedIds = new ArrayList<>();
        for (HermesWidgetSpec spec : allSpecs()) {
            if (target.equals(spec.ownerProfileId)) ownedIds.add(spec.widgetId);
        }
        for (String wid : ownedIds) {
            removeSpec(wid);
            for (Map.Entry<String, String> entry : backing.all().entrySet()) {
                if (entry.getKey().startsWith(PIN_PREFIX) && wid.equals(entry.getValue())) {
                    backing.remove(entry.getKey());
                }
            }
        }
        if (!ownedIds.isEmpty()) logEvent("profile_purged");
        return ownedIds.size();
    }

    /** Convenience helper for AccountStore.forget / ProfileGate.onForget: purges widgets and refreshes Home Screen. */
    public static void forgetProfile(Context context, String profileId) {
        if (context == null || profileId == null || profileId.trim().isEmpty()) return;
        try {
            Context app = context.getApplicationContext();
            int removed = new HermesWidgetStore(app).purgeProfile(profileId);
            if (removed > 0) HermesWidgetProvider.refresh(app, false);
        } catch (RuntimeException failed) {
            Diagnostics.warning("widget", "profile_forget_failed", failed);
        }
    }

    public synchronized boolean removeSpec(String widgetId) {
        if (!HermesWidgetSpec.validWidgetId(widgetId)) return false;
        boolean existed = backing.get(SPEC_PREFIX + widgetId) != null;
        backing.remove(SPEC_PREFIX + widgetId);
        for (int instance : instancesForWidget(widgetId)) {
            backing.remove(BIND_PREFIX + instance);
        }
        if (existed) logEvent("spec_removed");
        return existed;
    }

    public synchronized HermesWidgetSpec spec(String widgetId) {
        if (!HermesWidgetSpec.validWidgetId(widgetId)) return null;
        String raw = backing.get(SPEC_PREFIX + widgetId);
        if (raw == null || raw.isEmpty()) return null;
        return parseStoredSpec(raw);
    }

    public synchronized List<HermesWidgetSpec> allSpecs() {
        List<HermesWidgetSpec> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : backing.all().entrySet()) {
            if (!entry.getKey().startsWith(SPEC_PREFIX)) continue;
            HermesWidgetSpec parsed = parseStoredSpec(entry.getValue());
            if (parsed != null) result.add(parsed);
        }
        return result;
    }

    private static HermesWidgetSpec parseStoredSpec(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        try {
            if (raw.startsWith("v1\n") || raw.startsWith("v2\n")) return HermesWidgetSpec.decode(raw);
            return HermesWidgetSpec.fromJson(new JSONObject(raw));
        } catch (JSONException | RuntimeException corrupt) {
            logWarning("spec_corrupt");
            return null;
        }
    }

    public synchronized void bindInstance(int appWidgetId, String widgetId) {
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID || appWidgetId < 0
                || !HermesWidgetSpec.validWidgetId(widgetId)) {
            throw new IllegalArgumentException("Invalid widget binding");
        }
        backing.put(BIND_PREFIX + appWidgetId, widgetId);
        logEvent("instance_bound");
    }

    public synchronized void unbindInstance(int appWidgetId) {
        if (appWidgetId < 0) return;
        backing.remove(BIND_PREFIX + appWidgetId);
    }

    public synchronized String widgetIdForInstance(int appWidgetId) {
        if (appWidgetId < 0) return null;
        String id = backing.get(BIND_PREFIX + appWidgetId);
        return HermesWidgetSpec.validWidgetId(id) ? id : null;
    }

    public synchronized HermesWidgetSpec specForInstance(int appWidgetId) {
        String id = widgetIdForInstance(appWidgetId);
        return id == null ? null : spec(id);
    }

    public synchronized List<Integer> instancesForWidget(String widgetId) {
        List<Integer> ids = new ArrayList<>();
        if (!HermesWidgetSpec.validWidgetId(widgetId)) return ids;
        for (Map.Entry<String, String> entry : backing.all().entrySet()) {
            if (!entry.getKey().startsWith(BIND_PREFIX) || !widgetId.equals(entry.getValue())) continue;
            try {
                ids.add(Integer.parseInt(entry.getKey().substring(BIND_PREFIX.length())));
            } catch (NumberFormatException ignored) {
                logWarning("binding_key_corrupt");
            }
        }
        return ids;
    }

    private static void logEvent(String action) {
        Diagnostics.event("widget", action);
    }

    private static void logWarning(String action) {
        Diagnostics.warning("widget", action);
    }

    public synchronized void savePendingPin(String token, String widgetId) {
        if (token == null || token.isEmpty() || !HermesWidgetSpec.validWidgetId(widgetId)) return;
        backing.put(PIN_PREFIX + token, widgetId);
    }

    public synchronized String consumePendingPin(String token) {
        if (token == null || token.isEmpty()) return null;
        String key = PIN_PREFIX + token;
        String widgetId = backing.get(key);
        backing.remove(key);
        return HermesWidgetSpec.validWidgetId(widgetId) ? widgetId : null;
    }

    /** Fail-closed profile-scoped draft lookup: blocks cross-profile draft injection. */
    public synchronized String draftForAction(String widgetId, int itemIndex, String activeProfileId) {
        HermesWidgetSpec found = spec(widgetId);
        if (found == null || itemIndex < 0 || itemIndex >= found.items.size()) return null;
        if (found.ownerProfileId != null && !found.ownerProfileId.equals(activeProfileId)) {
            logWarning("widget_cross_profile_blocked");
            return null;
        }
        String draft = found.items.get(itemIndex).actionDraft();
        return draft.isEmpty() ? null : draft;
    }

    static final class SharedPrefsBacking implements Backing {
        private final SharedPreferences prefs;

        SharedPrefsBacking(SharedPreferences prefs) { this.prefs = prefs; }

        @Override public String get(String key) {
            synchronized (prefs) {
                try {
                    return prefs.getString(key, null);
                } catch (ClassCastException corrupt) {
                    Diagnostics.warning("widget", "pref_read_corrupt");
                    return null;
                }
            }
        }

        @Override public void put(String key, String value) {
            synchronized (prefs) { prefs.edit().putString(key, value).apply(); }
        }

        @Override public void remove(String key) {
            synchronized (prefs) { prefs.edit().remove(key).apply(); }
        }

        @Override public Map<String, String> all() {
            Map<String, String> copy = new LinkedHashMap<>();
            synchronized (prefs) {
                for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                    if (entry.getValue() instanceof String value) copy.put(entry.getKey(), value);
                }
            }
            return copy;
        }
    }
}
