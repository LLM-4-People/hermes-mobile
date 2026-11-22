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

    public synchronized void upsertSpec(HermesWidgetSpec spec) {
        Objects.requireNonNull(spec);
        backing.put(SPEC_PREFIX + spec.widgetId, spec.encode());
        logEvent("spec_upserted");
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
            if (raw.startsWith("v1\n")) return HermesWidgetSpec.decode(raw);
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
        try { Diagnostics.event("widget", action); }
        catch (RuntimeException unmockedHostJvm) {}
    }

    private static void logWarning(String action) {
        try { Diagnostics.warning("widget", action); }
        catch (RuntimeException unmockedHostJvm) {}
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

    public synchronized String draftForAction(String widgetId, int itemIndex) {
        HermesWidgetSpec found = spec(widgetId);
        if (found == null || itemIndex < 0 || itemIndex >= found.items.size()) return null;
        String draft = found.items.get(itemIndex).actionDraft();
        return draft.isEmpty() ? null : draft;
    }

    private static final class SharedPrefsBacking implements Backing {
        private final SharedPreferences prefs;

        SharedPrefsBacking(SharedPreferences prefs) { this.prefs = prefs; }

        @Override public String get(String key) {
            synchronized (prefs) { return prefs.getString(key, null); }
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
