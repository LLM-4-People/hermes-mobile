package org.llm4people.hermes.presence;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.AlarmClock;
import android.service.quicksettings.TileService;

import org.json.JSONObject;
import org.llm4people.hermes.AppLanguage;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.JsonValues;
import org.llm4people.hermes.R;
import org.llm4people.hermes.WorkspaceNavigation;
import org.llm4people.hermes.ambient.AmbientAccessPanel;
import org.llm4people.hermes.artifact.ThemePalette;
import org.llm4people.hermes.customization.AppCustomization;
import org.llm4people.hermes.customization.AppCustomizationStore;
import org.llm4people.hermes.doctor.ConnectionDoctorPanel;
import org.llm4people.hermes.hub.AppsLauncherPanel;
import org.llm4people.hermes.hub.NotesTasksPanel;
import org.llm4people.hermes.os.HermesShortcuts;
import org.llm4people.hermes.os.HermesTileService;
import org.llm4people.hermes.os.HermesWidgetProvider;
import org.llm4people.hermes.os.HermesWidgetSpec;
import org.llm4people.hermes.os.HermesWidgetStore;
import org.llm4people.hermes.os.PermissionsPanel;
import org.llm4people.hermes.push.DeliveryItem;
import org.llm4people.hermes.push.PushPolicy;
import org.llm4people.hermes.settings.AppearanceSettingsModel;
import org.llm4people.hermes.settings.AppearanceSettingsPanel;
import org.llm4people.hermes.settings.SettingsHubPanel;
import org.llm4people.hermes.templates.TemplatesPanel;
import org.llm4people.hermes.thread.SafeLinks;
import org.llm4people.hermes.update.UpdatePanel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Fail-closed executor and validator for all 9 ultra-deep Android 16 actions matching Track 9A's
 * {@code DEVICE_ACTION_CATALOG}:
 * {@code app_navigate}, {@code app_customize}, {@code app_read_state}, {@code app_create_template},
 * {@code android_set_timer}, {@code android_share_sheet}, {@code android_open_app},
 * {@code android_sync_shortcuts}, and {@code android_proactive_nudge}.
 */
public final class DeepAndroidActions {
    public static final int MIN_TIMER_SECONDS = 1;
    public static final int MAX_TIMER_SECONDS = 86_400;
    public static final int MAX_TIMER_LABEL_CHARS = 64;
    public static final int MAX_SHARE_TEXT_CHARS = 4_000;
    public static final int MAX_SHARE_SUBJECT_CHARS = 120;
    public static final int MAX_NUDGE_TITLE_CHARS = 64;
    public static final int MAX_NUDGE_BODY_CHARS = 180;
    public static final String NUDGE_CHANNEL_ID = "hermes_proactive_nudges";

    /** Canonical Single Source of Truth destinations shared between {@link DeepAndroidActions} and {@code MainActivity}. */
    public enum NavigateDestination {
        DISCUSSIONS,
        APPS,
        NOTES_TASKS,
        SETTINGS,
        APPEARANCE,
        COMMAND_PALETTE,
        TEMPLATES,
        THREAD,
        AMBIENT,
        CONNECTION_DOCTOR,
        PERMISSIONS,
        PRESENCE,
        UPDATES,
        MEMORY,
        NOTES,
        SHARED_WITH_ME,
        SEARCH,
        SKILLS,
        WORKSPACE_FILES,
        PROFILE_DEFAULTS,
        TAB,
        PANEL
    }

    private static final Map<String, NavigateDestination> NAVIGATE_TARGET_MAP = buildNavigateTargetMap();
    public static final Set<String> NAVIGATE_TARGETS = Collections.unmodifiableSet(NAVIGATE_TARGET_MAP.keySet());

    private static Map<String, NavigateDestination> buildNavigateTargetMap() {
        Map<String, NavigateDestination> m = new LinkedHashMap<>();
        m.put("discussions", NavigateDestination.DISCUSSIONS);
        m.put(WorkspaceNavigation.DISCUSSION, NavigateDestination.DISCUSSIONS);
        m.put(AppsLauncherPanel.ROUTE_ID, NavigateDestination.APPS);
        m.put("atelier", NavigateDestination.APPS);
        m.put("home", NavigateDestination.APPS);
        m.put("workspace", NavigateDestination.APPS);
        m.put(NotesTasksPanel.ROUTE_ID, NavigateDestination.NOTES_TASKS);
        m.put("tasks", NavigateDestination.NOTES_TASKS);
        m.put("activite", NavigateDestination.NOTES_TASKS);
        m.put("activity", NavigateDestination.NOTES_TASKS);
        m.put(SettingsHubPanel.ROUTE_ID, NavigateDestination.SETTINGS);
        m.put("cerveau", NavigateDestination.SETTINGS);
        m.put("brain", NavigateDestination.SETTINGS);
        m.put("reglages", NavigateDestination.SETTINGS);
        m.put("appearance", NavigateDestination.APPEARANCE);
        m.put("apparence", NavigateDestination.APPEARANCE);
        m.put(AppearanceSettingsPanel.ROUTE_ID, NavigateDestination.APPEARANCE);
        m.put("command_palette", NavigateDestination.COMMAND_PALETTE);
        m.put("template", NavigateDestination.TEMPLATES);
        m.put("templates", NavigateDestination.TEMPLATES);
        m.put(TemplatesPanel.ROUTE_ID, NavigateDestination.TEMPLATES);
        m.put("thread", NavigateDestination.THREAD);
        m.put("ambient", NavigateDestination.AMBIENT);
        m.put(AmbientAccessPanel.ROUTE_ID, NavigateDestination.AMBIENT);
        m.put("doctor", NavigateDestination.CONNECTION_DOCTOR);
        m.put(ConnectionDoctorPanel.ROUTE_ID, NavigateDestination.CONNECTION_DOCTOR);
        m.put("permissions", NavigateDestination.PERMISSIONS);
        m.put(PermissionsPanel.ROUTE_ID, NavigateDestination.PERMISSIONS);
        m.put("presence", NavigateDestination.PRESENCE);
        m.put(PresenceSettingsPanel.ROUTE_ID, NavigateDestination.PRESENCE);
        m.put("updates", NavigateDestination.UPDATES);
        m.put(UpdatePanel.ROUTE_ID, NavigateDestination.UPDATES);
        m.put("memory", NavigateDestination.MEMORY);
        m.put("notes", NavigateDestination.NOTES);
        m.put("shared_with_me", NavigateDestination.SHARED_WITH_ME);
        m.put("search", NavigateDestination.SEARCH);
        m.put("skills", NavigateDestination.SKILLS);
        m.put("workspace_files", NavigateDestination.WORKSPACE_FILES);
        m.put("profile_defaults", NavigateDestination.PROFILE_DEFAULTS);
        m.put("tab", NavigateDestination.TAB);
        m.put("panel", NavigateDestination.PANEL);
        return Collections.unmodifiableMap(m);
    }

    /** Resolve any supported navigation target string to its canonical destination, or null if unsupported. */
    public static NavigateDestination resolveNavigateDestination(String rawTarget) {
        if (rawTarget == null) return null;
        return NAVIGATE_TARGET_MAP.get(rawTarget.trim().toLowerCase(Locale.ROOT));
    }
    private static final Set<String> ALLOWED_DEEP_LINK_SCHEMES =
            Set.of("https", "geo", "mailto", "tel");
    private static final Pattern SAFE_PACKAGE =
            Pattern.compile("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+");
    private static final Pattern SAFE_TARGET_ID = Pattern.compile("[A-Za-z0-9._:/-]{0,96}");
    private static final Pattern SAFE_SHORTCUT_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");

    public enum ConsentTier {
        AUTOMATIC_SAFE,
        OPT_IN_ONLY,
        CONFIRM_REQUIRED
    }

    public enum Action {
        APP_NAVIGATE("app_navigate", ConsentTier.AUTOMATIC_SAFE),
        APP_CUSTOMIZE("app_customize", ConsentTier.CONFIRM_REQUIRED),
        APP_READ_STATE("app_read_state", ConsentTier.OPT_IN_ONLY),
        APP_CREATE_TEMPLATE("app_create_template", ConsentTier.CONFIRM_REQUIRED),
        ANDROID_SET_TIMER("android_set_timer", ConsentTier.CONFIRM_REQUIRED),
        ANDROID_SHARE_SHEET("android_share_sheet", ConsentTier.CONFIRM_REQUIRED),
        ANDROID_OPEN_APP("android_open_app", ConsentTier.CONFIRM_REQUIRED),
        ANDROID_SYNC_SHORTCUTS("android_sync_shortcuts", ConsentTier.CONFIRM_REQUIRED),
        ANDROID_PROACTIVE_NUDGE("android_proactive_nudge", ConsentTier.OPT_IN_ONLY);

        public final String wire;
        public final ConsentTier consentTier;

        Action(String wire, ConsentTier consentTier) {
            this.wire = wire;
            this.consentTier = consentTier;
        }

        public static Action parse(String wire) {
            if (wire == null || wire.trim().isEmpty()) return null;
            String normalized = wire.trim().toLowerCase(Locale.ROOT);
            for (Action value : values()) {
                if (value.wire.equals(normalized)) return value;
            }
            return null;
        }
    }

    public enum Status {
        EXECUTED,
        DENIED_CONFIRMATION_REQUIRED,
        DENIED_NOT_OPTED_IN,
        SUPPRESSED_QUIET_HOURS,
        SUPPRESSED_RATE_LIMIT,
        REJECTED_UNKNOWN_ACTION,
        REJECTED_INVALID_PARAMS
    }

    public record Result(Status status, String reasonCode, String payloadJson) {
        public boolean ok() { return status == Status.EXECUTED; }
    }

    /** Rolling 60-minute rate limiter for {@code android_proactive_nudge}. */
    public static final class NudgeRateLimiter {
        private static final long WINDOW_MS = 3_600_000L;
        private final Deque<Long> timestampsMs = new ArrayDeque<>();

        public synchronized boolean tryAcquire(long nowMs, int maxPerHour) {
            int limit = Math.max(PresencePolicy.MIN_MAX_NUDGES_PER_HOUR,
                    Math.min(PresencePolicy.MAX_MAX_NUDGES_PER_HOUR, maxPerHour));
            while (!timestampsMs.isEmpty() && nowMs - timestampsMs.peekFirst() >= WINDOW_MS) {
                timestampsMs.removeFirst();
            }
            if (timestampsMs.size() >= limit) {
                return false;
            }
            timestampsMs.addLast(nowMs);
            return true;
        }

        public synchronized void reset() {
            timestampsMs.clear();
        }
    }

    /** Pure-Java bridge implemented by the Android host (or test doubles on host JVM). */
    public interface HostBridge {
        default boolean navigate(String targetKind, String targetId) { return true; }
        default void onCustomizationChanged(AppCustomization updated) {}
        default boolean applyThemeMode(String themeMode) { return true; }
        default boolean createTemplate(String title, String category, String draftText) { return true; }
        default boolean setTimer(int durationSeconds, String label) { return true; }
        default boolean openShareSheet(String text, String subject) { return true; }
        default boolean openAppOrLink(String packageName, String deepLinkUrl) { return true; }
        default boolean syncShortcutsAndWidgets(String widgetTitle, String widgetSubtitle, boolean homeScreenSafe) {
            return true;
        }
        default boolean postLocalNudge(String title, String body) { return true; }
    }

    private final AppPresenceTracker presenceTracker;
    private final ContinuousLocationTracker locationTracker;
    private final AppCustomizationStore customizationStore;
    private final NudgeRateLimiter nudgeRateLimiter;
    private final HostBridge hostBridge;

    public DeepAndroidActions(
            AppPresenceTracker presenceTracker,
            ContinuousLocationTracker locationTracker,
            AppCustomizationStore customizationStore,
            NudgeRateLimiter nudgeRateLimiter,
            HostBridge hostBridge) {
        this.presenceTracker = Objects.requireNonNull(presenceTracker, "presenceTracker");
        this.locationTracker = Objects.requireNonNull(locationTracker, "locationTracker");
        this.customizationStore = Objects.requireNonNull(customizationStore, "customizationStore");
        this.nudgeRateLimiter = nudgeRateLimiter == null ? new NudgeRateLimiter() : nudgeRateLimiter;
        this.hostBridge = hostBridge == null ? new HostBridge() {} : hostBridge;
    }

    /**
     * Pure-Java fail-closed action execution entry point (host-JVM and Android testable).
     */
    public Result execute(
            String actionWire,
            Map<String, Object> params,
            PresencePolicy presencePolicy,
            PushPolicy pushPolicy,
            boolean userConfirmed,
            long nowMs,
            int localMinuteOfDay) {
        Action action = Action.parse(actionWire);
        if (action == null) {
            Diagnostics.warning("deep_action", "unknown_action");
            return new Result(Status.REJECTED_UNKNOWN_ACTION, "unknown_action", null);
        }
        if (action.consentTier == ConsentTier.CONFIRM_REQUIRED && !userConfirmed) {
            Diagnostics.event("deep_action", "confirmation_required", action.wire);
            return new Result(Status.DENIED_CONFIRMATION_REQUIRED, "confirmation_required", null);
        }
        Map<String, Object> safeParams = params == null ? Map.of() : params;
        PresencePolicy safePresence = presencePolicy == null ? PresencePolicy.defaults() : presencePolicy;
        PushPolicy safePush = pushPolicy == null ? PushPolicy.defaults() : pushPolicy;

        return switch (action) {
            case APP_NAVIGATE -> handleNavigate(safeParams);
            case APP_CUSTOMIZE -> handleCustomize(safeParams);
            case APP_READ_STATE -> handleReadState(safePresence, nowMs);
            case APP_CREATE_TEMPLATE -> handleCreateTemplate(safeParams);
            case ANDROID_SET_TIMER -> handleSetTimer(safeParams);
            case ANDROID_SHARE_SHEET -> handleShareSheet(safeParams);
            case ANDROID_OPEN_APP -> handleOpenApp(safeParams);
            case ANDROID_SYNC_SHORTCUTS -> handleSyncShortcuts(safeParams);
            case ANDROID_PROACTIVE_NUDGE -> handleProactiveNudge(safeParams, safePresence, safePush, nowMs, localMinuteOfDay);
        };
    }

    /** Android JSONObject convenience wrapper over {@link #execute}. */
    public Result executeJson(
            String actionWire,
            JSONObject params,
            PresencePolicy presencePolicy,
            PushPolicy pushPolicy,
            boolean userConfirmed,
            long nowMs,
            int localMinuteOfDay) {
        Map<String, Object> map = JsonValues.toMap(params);
        return execute(actionWire, map, presencePolicy, pushPolicy, userConfirmed, nowMs, localMinuteOfDay);
    }

    /** Validates a deep-link URL against the strict allowlist (blocks file://, content://, javascript:, intent:). */
    public static boolean isAllowedDeepLink(String rawUrl) {
        if (rawUrl == null) return false;
        String trimmed = rawUrl.trim();
        if (trimmed.isEmpty() || trimmed.length() > 512) return false;
        for (int i = 0; i < trimmed.length(); i++) {
            if (Character.isISOControl(trimmed.charAt(i))) return false;
        }
        int colon = trimmed.indexOf(':');
        if (colon <= 0) return false;
        String scheme = trimmed.substring(0, colon).toLowerCase(Locale.ROOT);
        if (!ALLOWED_DEEP_LINK_SCHEMES.contains(scheme)) return false;
        if ("https".equals(scheme)) {
            return SafeLinks.validate(trimmed) != null;
        }
        return trimmed.length() > colon + 1;
    }

    public static boolean isValidPackageName(String rawPkg) {
        return rawPkg != null && rawPkg.length() <= 128 && SAFE_PACKAGE.matcher(rawPkg.trim()).matches();
    }

    private Result handleNavigate(Map<String, Object> params) {
        String target = str(params.get("target")).trim().toLowerCase(Locale.ROOT);
        if (!NAVIGATE_TARGETS.contains(target)) {
            return reject("invalid_navigate_target");
        }
        String sessionId = str(params.get("session_id")).trim();
        String templateId = str(params.get("template_id")).trim();
        String routeId = str(params.get("route_id")).trim();
        if (!SAFE_TARGET_ID.matcher(sessionId).matches()
                || !SAFE_TARGET_ID.matcher(templateId).matches()
                || !SAFE_TARGET_ID.matcher(routeId).matches()) {
            return reject("invalid_navigate_target");
        }
        String targetId;
        if ("thread".equals(target)) {
            if (sessionId.isEmpty()) return reject("missing_navigate_id");
            targetId = sessionId;
        } else if ("template".equals(target)) {
            if (templateId.isEmpty()) return reject("missing_navigate_id");
            targetId = templateId;
        } else if ("panel".equals(target) || "tab".equals(target)) {
            if (routeId.isEmpty()) return reject("missing_navigate_id");
            targetId = routeId;
        } else {
            targetId = routeId;
        }
        boolean ok = hostBridge.navigate(target, targetId);
        if (!ok) return reject("navigate_unavailable");
        Diagnostics.event("deep_action", "app_navigate");
        return new Result(Status.EXECUTED, "ok", "{\"navigated\":true}");
    }

    private Result handleCustomize(Map<String, Object> params) {
        String operation = str(params.get("operation")).trim().toLowerCase(Locale.ROOT);
        if (operation.isEmpty()) return reject("missing_customize_operation");
        if ("undo".equals(operation)) {
            AppCustomization restored = customizationStore.undo();
            hostBridge.onCustomizationChanged(restored);
            return new Result(Status.EXECUTED, "undone",
                    "{\"accent\":" + JsonValues.quote(restored.palette.wire) + "}");
        }
        if ("reset".equals(operation)) {
            if (!hostBridge.applyThemeMode("system")) return reject("theme_mode_apply_failed");
            AppCustomization reset = customizationStore.resetToDefault();
            hostBridge.onCustomizationChanged(reset);
            return new Result(Status.EXECUTED, "reset",
                    "{\"accent\":" + JsonValues.quote(reset.palette.wire) + "}");
        }
        if (!"apply".equals(operation)) return reject("invalid_customize_operation");

        try {
            String themeMode = null;
            if (params.containsKey("theme") && params.get("theme") != null) {
                String normalizedTheme = str(params.get("theme")).trim().toLowerCase(Locale.ROOT);
                if (!ThemePalette.THEMES.contains(normalizedTheme)) return reject("invalid_theme_mode");
                themeMode = normalizedTheme;
            }
            AppCustomization current = customizationStore.current();
            AppCustomization.PalettePreset preset = current.palette;
            String customHex = current.customAccentHex;
            boolean changed = false;

            Object rawAccent = params.containsKey("accent") ? params.get("accent") : params.get("accent_preset");
            if (rawAccent != null) {
                preset = AppCustomization.PalettePreset.parse(str(rawAccent));
                if (preset == null) return reject("invalid_palette");
                if (preset != AppCustomization.PalettePreset.CUSTOM) customHex = null;
                changed = true;
            }
            if (params.containsKey("accent_hex") && params.get("accent_hex") != null) {
                String rawHex = str(params.get("accent_hex"));
                if (rawHex.isEmpty()) return reject("invalid_accent_hex");
                preset = AppCustomization.PalettePreset.CUSTOM;
                customHex = AppCustomization.validateCustomAccentHex(rawHex);
                changed = true;
            }
            AppCustomization.Density density = current.density;
            if (params.containsKey("density") && params.get("density") != null) {
                density = AppCustomization.Density.parse(str(params.get("density")));
                if (density == null) return reject("invalid_density");
                changed = true;
            }
            float fontDelta = current.fontScaleDelta;
            if (params.containsKey("font_scale_step") && params.get("font_scale_step") != null) {
                Integer step = AppearanceSettingsModel.parseFontScaleStep(params.get("font_scale_step"));
                if (step == null) return reject("invalid_font_scale");
                fontDelta = AppearanceSettingsModel.fontScaleDeltaForStep(step);
                changed = true;
            }
            AppCustomization.StartupTab startupTab = current.startupTab;
            if (params.containsKey("startup_tab") && params.get("startup_tab") != null) {
                startupTab = AppCustomization.StartupTab.parse(str(params.get("startup_tab")));
                if (startupTab == null) return reject("invalid_startup_tab");
                changed = true;
            }
            String greeting = current.companionGreeting;
            if (params.containsKey("greeting") && params.get("greeting") != null) {
                greeting = AppCustomization.sanitizePlainText(
                        str(params.get("greeting")), AppCustomization.MAX_GREETING_CHARS);
                changed = true;
            }
            List<AppCustomization.ComposerShortcut> shortcuts = current.composerShortcuts;
            if (params.containsKey("quick_shortcuts") && params.get("quick_shortcuts") != null) {
                Object rawList = params.get("quick_shortcuts");
                if (!(rawList instanceof List<?> list) || list.size() > AppCustomization.MAX_COMPOSER_SHORTCUTS) {
                    return reject("invalid_shortcuts");
                }
                List<AppCustomization.ComposerShortcut> parsed = new ArrayList<>(list.size());
                for (int i = 0; i < list.size(); i++) {
                    Object entry = list.get(i);
                    if (!(entry instanceof Map<?, ?> m)) return reject("invalid_shortcut_entry");
                    String rawId = str(m.get("id")).trim();
                    String id = rawId.isEmpty() ? ("sc_" + (i + 1)) : rawId;
                    parsed.add(new AppCustomization.ComposerShortcut(
                            id, str(m.get("label")), str(m.get("draft"))));
                }
                shortcuts = parsed;
                changed = true;
            }
            if (!changed && themeMode == null) return reject("empty_customization");
            AppCustomization updated = changed
                    ? new AppCustomization(preset, customHex, density, fontDelta, startupTab, greeting, shortcuts)
                    : current;
            if (themeMode != null && !hostBridge.applyThemeMode(themeMode)) {
                return reject("theme_mode_apply_failed");
            }
            if (changed) {
                customizationStore.apply(updated);
                hostBridge.onCustomizationChanged(updated);
            }
            Diagnostics.event("deep_action", "app_customize");
            StringBuilder payload = new StringBuilder();
            payload.append("{\"accent\":").append(JsonValues.quote(updated.palette.wire))
                    .append(",\"density\":").append(JsonValues.quote(updated.density.wire));
            if (themeMode != null) {
                payload.append(",\"theme\":").append(JsonValues.quote(themeMode));
            }
            payload.append('}');
            return new Result(Status.EXECUTED, "applied", payload.toString());
        } catch (IllegalArgumentException invalid) {
            return reject("invalid_customization");
        }
    }

    private Result handleReadState(PresencePolicy policy, long nowMs) {
        ContinuousLocationTracker.Snapshot loc = locationTracker.currentSnapshot(policy, nowMs);
        String wire = presenceTracker.toWireContextJson(policy, loc, nowMs);
        if (wire == null) {
            Diagnostics.event("deep_action", "read_state_denied_opt_out");
            return new Result(Status.DENIED_NOT_OPTED_IN, "presence_not_opted_in", null);
        }
        Diagnostics.event("deep_action", "app_read_state");
        return new Result(Status.EXECUTED, "ok", wire);
    }

    private Result handleCreateTemplate(Map<String, Object> params) {
        try {
            String title = AppCustomization.sanitizePlainText(str(params.get("title")), 80);
            String category = AppCustomization.sanitizePlainText(str(params.get("category")), 40);
            String draft = AppCustomization.sanitizePlainText(str(params.get("draft_prompt")), 1000);
            if (title.isEmpty() || draft.isEmpty()) return reject("missing_template_fields");
            boolean ok = hostBridge.createTemplate(title, category.isEmpty() ? "custom" : category, draft);
            if (!ok) return reject("template_create_failed");
            Diagnostics.event("deep_action", "app_create_template");
            return new Result(Status.EXECUTED, "created", "{\"saved\":true}");
        } catch (IllegalArgumentException invalid) {
            return reject("invalid_template_content");
        }
    }

    private Result handleSetTimer(Map<String, Object> params) {
        Object rawSeconds = params.get("duration_seconds");
        if (!(rawSeconds instanceof Number)) return reject("invalid_timer_duration");
        Integer parsedSeconds = JsonValues.parseIntOrNull(rawSeconds);
        if (parsedSeconds == null) return reject("invalid_timer_duration");
        int seconds = parsedSeconds;
        if (seconds < MIN_TIMER_SECONDS || seconds > MAX_TIMER_SECONDS) {
            return reject("timer_duration_out_of_range");
        }
        String label;
        try {
            label = AppCustomization.sanitizePlainText(str(params.get("label")), MAX_TIMER_LABEL_CHARS);
        } catch (IllegalArgumentException secretOrInvalid) {
            return reject("invalid_timer_label");
        }
        boolean ok = hostBridge.setTimer(seconds, label);
        if (!ok) return reject("timer_launch_failed");
        Diagnostics.event("deep_action", "android_set_timer");
        return new Result(Status.EXECUTED, "timer_opened", "{\"duration_seconds\":" + seconds + "}");
    }

    private Result handleShareSheet(Map<String, Object> params) {
        String text;
        String subject;
        try {
            text = AppCustomization.sanitizePlainText(str(params.get("text")), MAX_SHARE_TEXT_CHARS);
            subject = AppCustomization.sanitizePlainText(str(params.get("subject")), MAX_SHARE_SUBJECT_CHARS);
        } catch (IllegalArgumentException invalid) {
            return reject("invalid_share_content");
        }
        if (text.isEmpty()) return reject("missing_share_text");
        boolean ok = hostBridge.openShareSheet(text, subject);
        if (!ok) return reject("share_launch_failed");
        Diagnostics.event("deep_action", "android_share_sheet");
        return new Result(Status.EXECUTED, "share_opened", "{\"shared\":true}");
    }

    private Result handleOpenApp(Map<String, Object> params) {
        String pkg = str(params.get("package_name")).trim();
        String url = str(params.get("deep_link")).trim();
        if (pkg.isEmpty() && url.isEmpty()) return reject("missing_app_target");
        if (!pkg.isEmpty() && !isValidPackageName(pkg)) return reject("invalid_package_name");
        if (!url.isEmpty() && !isAllowedDeepLink(url)) return reject("blocked_deep_link_scheme");
        boolean ok = hostBridge.openAppOrLink(pkg, url);
        if (!ok) return reject("open_app_failed");
        Diagnostics.event("deep_action", "android_open_app");
        return new Result(Status.EXECUTED, "opened", "{\"opened\":true}");
    }

    private Result handleSyncShortcuts(Map<String, Object> params) {
        String tileLabel;
        String widgetSummary;
        int shortcutCount = 0;
        try {
            tileLabel = AppCustomization.sanitizePlainText(str(params.get("tile_label")), 32);
            widgetSummary = AppCustomization.sanitizePlainText(
                    str(params.get("widget_summary")), HermesWidgetSpec.MAX_SUBTITLE_CHARS);
            if (params.containsKey("shortcuts") && params.get("shortcuts") != null) {
                Object rawSc = params.get("shortcuts");
                if (!(rawSc instanceof List<?> list) || list.size() > 4) return reject("invalid_shortcuts");
                for (Object entry : list) {
                    if (!(entry instanceof Map<?, ?> m)) return reject("invalid_shortcut_entry");
                    String sid = str(m.get("id")).trim();
                    String lbl = AppCustomization.sanitizePlainText(str(m.get("label")), 40);
                    String target = str(m.get("target")).trim().toLowerCase(Locale.ROOT);
                    if (target.isEmpty()) target = "discussions";
                    if (!SAFE_SHORTCUT_ID.matcher(sid).matches() || lbl.isEmpty()
                            || !NAVIGATE_TARGETS.contains(target)) {
                        return reject("invalid_shortcut_entry");
                    }
                    shortcutCount++;
                }
            }
        } catch (IllegalArgumentException invalid) {
            return reject("invalid_widget_summary");
        }
        if (tileLabel.isEmpty() && widgetSummary.isEmpty() && shortcutCount == 0) {
            return reject("missing_sync_payload");
        }
        boolean hasWidgetSummary = !widgetSummary.isEmpty();
        String widgetTitle = !tileLabel.isEmpty() ? tileLabel : (hasWidgetSummary ? "Hermes" : "");
        boolean ok = hostBridge.syncShortcutsAndWidgets(widgetTitle, widgetSummary, hasWidgetSummary);
        if (!ok) return reject("sync_failed");
        Diagnostics.event("deep_action", "android_sync_shortcuts");
        return new Result(Status.EXECUTED, "synced", "{\"synced\":true}");
    }

    private Result handleProactiveNudge(
            Map<String, Object> params,
            PresencePolicy presencePolicy,
            PushPolicy pushPolicy,
            long nowMs,
            int localMinuteOfDay) {
        if (!presencePolicy.proactiveNudgesEnabled) {
            Diagnostics.event("deep_action", "nudge_denied_opt_out");
            return new Result(Status.DENIED_NOT_OPTED_IN, "nudges_not_opted_in", null);
        }
        if (localMinuteOfDay < 0 || localMinuteOfDay >= 1440) {
            return reject("invalid_local_minute");
        }
        PushPolicy.Decision decision = pushPolicy.evaluate("notice", DeliveryItem.Urgency.NORMAL, localMinuteOfDay);
        if (decision == PushPolicy.Decision.SUPPRESSED_QUIET_HOURS
                || decision == PushPolicy.Decision.SUPPRESSED_CATEGORY) {
            Diagnostics.event("deep_action", "nudge_suppressed_quiet_hours");
            return new Result(Status.SUPPRESSED_QUIET_HOURS, decision.reasonCode, null);
        }
        String title;
        String body;
        try {
            title = AppCustomization.sanitizePlainText(str(params.get("title")), MAX_NUDGE_TITLE_CHARS);
            body = AppCustomization.sanitizePlainText(str(params.get("body")), MAX_NUDGE_BODY_CHARS);
        } catch (IllegalArgumentException invalid) {
            return reject("invalid_nudge_text");
        }
        if (title.isEmpty() || body.isEmpty()) return reject("missing_nudge_text");
        if (!nudgeRateLimiter.tryAcquire(nowMs, presencePolicy.maxNudgesPerHour)) {
            Diagnostics.event("deep_action", "nudge_rate_limited");
            return new Result(Status.SUPPRESSED_RATE_LIMIT, "hourly_nudge_cap", null);
        }
        boolean ok = hostBridge.postLocalNudge(title, body);
        if (!ok) return reject("nudge_post_failed");
        Diagnostics.event("deep_action", "android_proactive_nudge");
        return new Result(Status.EXECUTED, "notified", "{\"posted\":true}");
    }

    private static Result reject(String reason) {
        Diagnostics.warning("deep_action", "rejected", reason);
        return new Result(Status.REJECTED_INVALID_PARAMS, reason, null);
    }

    private static String str(Object value) {
        return value instanceof String s ? s : "";
    }

    /**
     * Standard Android OS bridge for timer, share sheet, safe app/deep-link launch,
     * shortcuts/QS tile/widget sync, and local opt-in nudges.
     */
    public static HostBridge androidOsBridge(
            Context context,
            String ownerProfileId,
            Navigator navigator,
            TemplateCreator templateCreator) {
        Context app = Objects.requireNonNull(context, "context").getApplicationContext();
        return new HostBridge() {
            @Override public boolean navigate(String targetKind, String targetId) {
                return navigator != null && navigator.navigate(targetKind, targetId);
            }

            @Override public boolean createTemplate(String title, String category, String draftText) {
                return templateCreator != null && templateCreator.create(title, category, draftText);
            }

            @Override public boolean setTimer(int durationSeconds, String label) {
                Intent intent = new Intent(AlarmClock.ACTION_SET_TIMER)
                        .putExtra(AlarmClock.EXTRA_LENGTH, durationSeconds)
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (label != null && !label.isEmpty()) {
                    intent.putExtra(AlarmClock.EXTRA_MESSAGE, label);
                }
                return startSafely(app, intent);
            }

            @Override public boolean openShareSheet(String text, String subject) {
                Intent send = new Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, text);
                if (subject != null && !subject.isEmpty()) {
                    send.putExtra(Intent.EXTRA_SUBJECT, subject);
                }
                Context localized = AppLanguage.wrapContext(app);
                Intent chooser = Intent.createChooser(send, localized.getString(R.string.deep_action_share_chooser))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                return startSafely(app, chooser);
            }

            @Override public boolean openAppOrLink(String packageName, String deepLinkUrl) {
                Intent intent;
                if (deepLinkUrl != null && !deepLinkUrl.isEmpty()) {
                    intent = new Intent(Intent.ACTION_VIEW, Uri.parse(deepLinkUrl))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    if (packageName != null && !packageName.isEmpty()) {
                        intent.setPackage(packageName);
                    }
                } else {
                    PackageManager pm = app.getPackageManager();
                    intent = pm.getLaunchIntentForPackage(packageName);
                    if (intent == null) return false;
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                }
                return startSafely(app, intent);
            }

            @Override public boolean syncShortcutsAndWidgets(
                    String widgetTitle, String widgetSubtitle, boolean homeScreenSafe) {
                HermesShortcuts.publish(app);
                try {
                    TileService.requestListeningState(app, new ComponentName(app, HermesTileService.class));
                } catch (RuntimeException ignored) {
                    Diagnostics.debug("deep_action", "tile_sync_skipped");
                }
                if (widgetTitle != null && !widgetTitle.isEmpty()) {
                    String wid = HermesWidgetStore.profileScopedId("status_summary", ownerProfileId);
                    HermesWidgetSpec spec = new HermesWidgetSpec(
                            wid, widgetTitle, widgetSubtitle == null ? "" : widgetSubtitle,
                            HermesWidgetSpec.Template.STATUS, List.of(), null, homeScreenSafe, ownerProfileId);
                    new HermesWidgetStore(app).upsertSpec(spec);
                }
                HermesWidgetProvider.refresh(app, false);
                return true;
            }

            @Override public boolean postLocalNudge(String title, String body) {
                if (Build.VERSION.SDK_INT >= 33
                        && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    return false;
                }
                NotificationManager nm = app.getSystemService(NotificationManager.class);
                if (nm == null) return false;
                Context localized = AppLanguage.wrapContext(app);
                NotificationChannel channel = new NotificationChannel(
                        NUDGE_CHANNEL_ID,
                        localized.getString(R.string.deep_action_nudge_channel),
                        NotificationManager.IMPORTANCE_DEFAULT);
                channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
                nm.createNotificationChannel(channel);
                Notification publicVersion = new Notification.Builder(app, NUDGE_CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_push_notification)
                        .setContentTitle(localized.getString(R.string.app_name))
                        .setContentText(localized.getString(R.string.deep_action_nudge_channel))
                        .build();
                Notification notification = new Notification.Builder(app, NUDGE_CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_push_notification)
                        .setContentTitle(title)
                        .setContentText(body)
                        .setVisibility(Notification.VISIBILITY_PRIVATE)
                        .setPublicVersion(publicVersion)
                        .setAutoCancel(true)
                        .build();
                nm.notify((int) (System.currentTimeMillis() & 0x7FFF) + 5100, notification);
                return true;
            }
        };
    }

    private static boolean startSafely(Context app, Intent intent) {
        try {
            app.startActivity(intent);
            return true;
        } catch (RuntimeException failed) {
            Diagnostics.warning("deep_action", "intent_launch_failed");
            return false;
        }
    }

    public interface Navigator {
        boolean navigate(String targetKind, String targetId);
    }

    public interface TemplateCreator {
        boolean create(String title, String category, String draftText);
    }
}
