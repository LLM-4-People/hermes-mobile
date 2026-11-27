package org.llm4people.hermes;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.LocaleManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;

import org.json.JSONException;
import org.json.JSONObject;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.os.HermesWidgetProvider;
import org.llm4people.hermes.panel.BrainCapabilities;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Single Source of Truth for bilingual French/English auto-detection, per-app override,
 * Accept-Language header formatting, voice/TTS locale selection, and server profile sync.
 */
public final class AppLanguage {
    public static final String TAG_FRENCH = "fr";
    public static final String TAG_ENGLISH = "en";
    private static final String PREFS = "hermes.app.locale.v1";
    private static final String KEY_MODE = "mode";
    private static final Object STATE_LOCK = new Object();
    private static final ExecutorService SYNC_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "hermes-locale-sync");
        thread.setDaemon(true);
        return thread;
    });
    private static final Set<HermesApi> PENDING_SYNCS = ConcurrentHashMap.newKeySet();
    private static volatile String activeAcceptLanguage = "";

    public enum Mode {
        AUTO("auto"),
        FRENCH(TAG_FRENCH),
        ENGLISH(TAG_ENGLISH);

        public final String wire;

        Mode(String wire) {
            this.wire = wire;
        }

        public static Mode fromWire(String raw) {
            if (raw == null) return AUTO;
            String normalized = raw.trim().toLowerCase(Locale.ROOT);
            if (TAG_FRENCH.equals(normalized) || normalized.startsWith("fr-") || normalized.startsWith("fr_")) {
                return FRENCH;
            }
            if (TAG_ENGLISH.equals(normalized) || normalized.startsWith("en-") || normalized.startsWith("en_")) {
                return ENGLISH;
            }
            return AUTO;
        }
    }

    private AppLanguage() {}

    /** Resolve the effective short language tag ("fr" or "en") for the given mode and device priority list. */
    public static String resolveTag(Mode mode, List<String> systemLanguageTags) {
        Mode safeMode = mode == null ? Mode.AUTO : mode;
        if (safeMode == Mode.FRENCH) return TAG_FRENCH;
        if (safeMode == Mode.ENGLISH) return TAG_ENGLISH;
        if (systemLanguageTags != null) {
            for (String raw : systemLanguageTags) {
                String base = baseLanguage(raw);
                if (TAG_FRENCH.equals(base)) return TAG_FRENCH;
                if (TAG_ENGLISH.equals(base)) return TAG_ENGLISH;
            }
        }
        return TAG_FRENCH;
    }

    /** Resolve a concrete Locale preserving the device's regional tag when it matches the active language. */
    public static Locale resolveLocale(Mode mode, List<String> systemLanguageTags) {
        String tag = resolveTag(mode, systemLanguageTags);
        if (systemLanguageTags != null) {
            for (String raw : systemLanguageTags) {
                if (tag.equals(baseLanguage(raw))) {
                    Locale candidate = Locale.forLanguageTag(raw.trim().replace('_', '-'));
                    if (tag.equals(candidate.getLanguage())) return candidate;
                }
            }
        }
        return TAG_ENGLISH.equals(tag) ? Locale.ENGLISH : Locale.FRENCH;
    }

    /** Resolve a BCP-47 tag suitable for Android speech recognition (e.g. "fr-CA", "fr-FR", "en-US", "en-GB"). */
    public static String resolvedVoiceTag(Mode mode, List<String> systemLanguageTags) {
        Locale locale = resolveLocale(mode, systemLanguageTags);
        if (!locale.getCountry().isEmpty()) {
            return locale.toLanguageTag();
        }
        return TAG_ENGLISH.equals(locale.getLanguage()) ? "en-US" : "fr-FR";
    }

    /** Format a clean RFC 9110 Accept-Language header value for the resolved locale. */
    public static String formatAcceptLanguage(Locale locale) {
        Locale target = locale == null ? Locale.FRENCH : locale;
        String base = TAG_ENGLISH.equals(target.getLanguage()) ? TAG_ENGLISH : TAG_FRENCH;
        String tag = target.toLanguageTag();
        if (tag == null || tag.isEmpty() || "und".equals(tag) || tag.equalsIgnoreCase(base)) {
            return base;
        }
        return tag + ", " + base + ";q=0.9";
    }

    private static String baseLanguage(String rawTag) {
        if (rawTag == null) return "";
        String trimmed = rawTag.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) return "";
        int sep = -1;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '-' || c == '_') {
                sep = i;
                break;
            }
        }
        return sep < 0 ? trimmed : trimmed.substring(0, sep);
    }

    /** Return the current Accept-Language header for HermesApi (safe on host JVM and Android). */
    public static String currentAcceptLanguage() {
        String cached = activeAcceptLanguage;
        if (cached != null && !cached.isEmpty()) return cached;
        Locale fallback = resolveLocale(Mode.AUTO, Collections.singletonList(Locale.getDefault().toLanguageTag()));
        return formatAcceptLanguage(fallback);
    }

    static String rawActiveAcceptLanguageForTest() {
        return activeAcceptLanguage;
    }

    public static void setActiveAcceptLanguageForTest(String header) {
        synchronized (STATE_LOCK) {
            activeAcceptLanguage = header == null ? "" : header.trim();
        }
    }

    /** Read the persisted or Android 13+ system per-app language mode. */
    public static Mode readMode(Context context) {
        if (context == null) return Mode.AUTO;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                LocaleManager manager = context.getSystemService(LocaleManager.class);
                if (manager != null) {
                    LocaleList appLocales = manager.getApplicationLocales();
                    if (appLocales != null && !appLocales.isEmpty()) {
                        return Mode.fromWire(appLocales.get(0).toLanguageTag());
                    }
                    return Mode.AUTO;
                }
            }
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return Mode.fromWire(prefs.getString(KEY_MODE, Mode.AUTO.wire));
        } catch (RuntimeException err) {
            Diagnostics.warning("locale", "read_mode_failed", err);
            return Mode.AUTO;
        }
    }

    /** Persist and apply the selected language mode across Android 13+ LocaleManager and older APIs. */
    public static void apply(Context context, Mode mode) {
        if (context == null) return;
        Mode target = mode == null ? Mode.AUTO : mode;
        applyWithWriter(target, () -> {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_MODE, target.wire)
                    .apply();
            if (Build.VERSION.SDK_INT >= 33) {
                try {
                    LocaleManager manager = context.getSystemService(LocaleManager.class);
                    if (manager != null) {
                        LocaleList next = target == Mode.AUTO
                                ? LocaleList.getEmptyLocaleList()
                                : LocaleList.forLanguageTags(target.wire);
                        manager.setApplicationLocales(next);
                    }
                } catch (RuntimeException err) {
                    Diagnostics.warning("locale", "platform_apply_failed", err);
                }
            }
        }, systemLanguageTags());
        HermesWidgetProvider.refresh(context, target);
        Diagnostics.event("locale", "mode_" + target.wire);
    }

    /** Serializes mode persistence + runtime cache update under {@link #STATE_LOCK}. */
    static void applyWithWriter(Mode target, Runnable persistMode, List<String> systemTags) {
        synchronized (STATE_LOCK) {
            persistMode.run();
            refreshRuntime(target, systemTags);
        }
    }

    /**
     * Build a configuration-localized Context for {@code mode} unconditionally (including API 33+
     * and {@link Mode#AUTO}), avoiding stale Activity configurations or asynchronous
     * {@link LocaleManager#setApplicationLocales} propagation when rendering widgets or shortcuts.
     */
    public static Context localizedContext(Context base, Mode mode) {
        if (base == null) return null;
        Mode target = mode == null ? readMode(base) : mode;
        Context app = base.getApplicationContext();
        Context source = app != null ? app : base;
        Locale resolved = resolveLocale(target, systemLanguageTags());
        Configuration config = new Configuration(source.getResources().getConfiguration());
        config.setLocale(resolved);
        Context created = source.createConfigurationContext(config);
        return created != null ? created : source;
    }

    /** Wrap an Activity base context on API 28-32 so explicit French/English overrides load matching resources. */
    public static Context wrapContext(Context base) {
        if (base == null) return null;
        Mode mode;
        synchronized (STATE_LOCK) {
            mode = readMode(base);
            refreshRuntime(mode, systemLanguageTags());
        }
        if (Build.VERSION.SDK_INT >= 33 || mode == Mode.AUTO) {
            return base;
        }
        Locale resolved = resolveLocale(mode, systemLanguageTags());
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.setLocale(resolved);
        return base.createConfigurationContext(config);
    }

    /**
     * Refresh cached Accept-Language from the active app/device configuration.
     *
     * @return {@code true} when the effective language tag ({@code "fr"} or {@code "en"}) changed
     * compared to the previously cached runtime tag.
     */
    public static boolean refreshRuntime(Context context) {
        return refreshFromSupplier(() -> readMode(context), systemLanguageTags());
    }

    /** Serializes mode read + runtime cache update under {@link #STATE_LOCK}. */
    static boolean refreshFromSupplier(Supplier<Mode> modeReader, List<String> systemTags) {
        synchronized (STATE_LOCK) {
            return refreshRuntime(modeReader.get(), systemTags);
        }
    }

    /** Host-JVM testable helper updating cached Accept-Language and reporting effective tag changes. */
    static boolean refreshRuntime(Mode mode, List<String> systemTags) {
        synchronized (STATE_LOCK) {
            String previousTag = effectiveTagFromHeader(activeAcceptLanguage);
            Locale resolved = resolveLocale(mode, systemTags);
            String nextHeader = formatAcceptLanguage(resolved);
            String nextTag = effectiveTagFromHeader(nextHeader);
            activeAcceptLanguage = nextHeader;
            return !previousTag.isEmpty() && !previousTag.equals(nextTag);
        }
    }

    private static String effectiveTagFromHeader(String header) {
        if (header == null || header.isEmpty()) return "";
        int comma = header.indexOf(',');
        String first = comma >= 0 ? header.substring(0, comma) : header;
        int semi = first.indexOf(';');
        if (semi >= 0) first = first.substring(0, semi);
        return baseLanguage(first);
    }

    public static String resolvedTag(Context context) {
        return resolveTag(readMode(context), systemLanguageTags());
    }

    public static Locale resolvedLocale(Context context) {
        return resolveLocale(readMode(context), systemLanguageTags());
    }

    public static String resolvedVoiceTag(Context context) {
        return resolvedVoiceTag(readMode(context), systemLanguageTags());
    }

    public static String acceptLanguageHeader(Context context) {
        synchronized (STATE_LOCK) {
            Locale resolved = resolvedLocale(context);
            String header = formatAcceptLanguage(resolved);
            activeAcceptLanguage = header;
            return header;
        }
    }

    private static List<String> systemLanguageTags() {
        List<String> tags = new ArrayList<>();
        try {
            LocaleList system = Resources.getSystem().getConfiguration().getLocales();
            for (int i = 0; i < system.size(); i++) {
                Locale locale = system.get(i);
                if (locale != null) tags.add(locale.toLanguageTag());
            }
        } catch (RuntimeException ignored) {
            tags.add(Locale.getDefault().toLanguageTag());
        }
        return tags;
    }

    /** Build the PATCH /api/profile/defaults payload aligned with Fork PR #14 (@fork-brain). */
    public static JSONObject profileDefaultsPatch(Mode mode) {
        Mode target = mode == null ? Mode.AUTO : mode;
        JSONObject body = new JSONObject();
        try {
            body.put("preferred_language", target.wire);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        return body;
    }

    /** Check whether the server advertises PATCH /api/profile/defaults support. */
    public static boolean supportsProfileLanguageSync(JSONObject capabilities) {
        if (capabilities == null) return false;
        JSONObject features = capabilities.optJSONObject("features");
        return features != null && features.optBoolean(BrainCapabilities.PROFILE_DEFAULTS);
    }

    /** Revoke all in-flight detached language sync clients (called on lock, forget, or final destroy). */
    public static void revokePendingSyncs() {
        for (HermesApi client : PENDING_SYNCS) {
            if (PENDING_SYNCS.remove(client)) {
                client.revoke();
            }
        }
    }

    /**
     * Submit capability-gated server profile language sync on an application-scoped daemon thread
     * so Activity recreation during locale switching never interrupts the HTTP request mid-flight.
     * Captures only the immutable HermesApi client and Mode (no Activity or Account reference).
     */
    public static Future<Boolean> syncToServerAsync(HermesApi api, Mode mode) {
        if (api == null) return CompletableFuture.completedFuture(false);
        PENDING_SYNCS.add(api);
        try {
            return SYNC_EXECUTOR.submit(() -> {
                try {
                    if (api.isRevoked()) return false;
                    return syncToServer(api, mode);
                } finally {
                    PENDING_SYNCS.remove(api);
                    api.revoke();
                }
            });
        } catch (RuntimeException | Error err) {
            PENDING_SYNCS.remove(api);
            api.revoke();
            throw err;
        }
    }

    /** Capability-gated background sync of preferred_language to the server profile defaults. */
    public static boolean syncToServer(HermesApi api, Mode mode) {
        if (api == null) return false;
        try {
            JSONObject capabilities = api.get("/v1/capabilities");
            if (!supportsProfileLanguageSync(capabilities)) return false;
            api.patch("/api/profile/defaults", profileDefaultsPatch(mode));
            Diagnostics.event("locale", "profile_synced");
            return true;
        } catch (IOException | RuntimeException skipped) {
            Diagnostics.debug("locale", "profile_sync_skipped", skipped);
            return false;
        }
    }

    /** Present a calm, bilingual-titled language selector without AI jargon. */
    public static AlertDialog showPicker(Activity activity, Consumer<Mode> onApplied) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return null;
        Mode current = readMode(activity);
        String previousTag = resolvedTag(activity);
        Mode[] choices = {Mode.AUTO, Mode.FRENCH, Mode.ENGLISH};
        CharSequence[] labels = {
                activity.getString(R.string.language_auto),
                activity.getString(R.string.language_french),
                activity.getString(R.string.language_english)
        };
        int checked = current == Mode.FRENCH ? 1 : current == Mode.ENGLISH ? 2 : 0;
        boolean[] handled = {false};
        return Ui.secureDialog(new AlertDialog.Builder(activity)
                .setTitle(R.string.language_picker_title)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    if (handled[0]) return;
                    handled[0] = true;
                    Mode selected = choices[which];
                    dialog.dismiss();
                    apply(activity, selected);
                    if (onApplied != null) onApplied.accept(selected);
                    String nextTag = resolvedTag(activity);
                    if (Build.VERSION.SDK_INT < 33 && !previousTag.equals(nextTag)) {
                        activity.recreate();
                    }
                })
                .setNegativeButton(R.string.language_close, null));
    }
}
