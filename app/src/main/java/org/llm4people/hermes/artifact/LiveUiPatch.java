package org.llm4people.hermes.artifact;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.profile.Account;

/**
 * Validated, bounded, reversible Real-Time Live UI Editing v2 protocol (Fork PR #15 ui.patch SSE
 * and /api/mobile/ui/* REST endpoints). Every mutation is declarative, sandboxed, contrast-checked,
 * and atomically reversible.
 */
public final class LiveUiPatch {
    public static final int MAX_PATCH_BYTES = 32 * 1024;
    public static final int MAX_OPS = 16;
    public static final int MAX_QUICK_ACTIONS = 8;
    public static final int MAX_CUSTOM_TABS = 6;
    public static final int MAX_WIDGETS = 8;
    public static final int MAX_WIDGET_ITEMS = 6;
    public static final int MAX_SUGGESTED_ACTIONS = 4;
    public static final int MAX_HISTORY = 16;
    public static final int MAX_SUMMARY_CHARS = 200;
    public static final int MAX_DRAFT_CHARS = 500;
    public static final List<String> WIDGET_TEMPLATES = Collections.unmodifiableList(
            Arrays.asList("metric", "checklist", "quick_actions", "status", "briefing"));
    private static final Pattern REVISION_ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    private static final List<String> SCOPES = Arrays.asList("thread", "atelier", "global");
    private static final String STATE_KEY = "workspace.live_ui.v2";

    public static final class CustomTab {
        public final String id;
        public final String label;
        public final String page;

        public CustomTab(String id, String label, String page) throws IOException {
            if (!DraftForm.validId(id)) throw new IOException("Invalid tab id");
            if (!validText(label, 32)) throw new IOException("Invalid tab label");
            if (!WorkspaceLayout.custom(page)) throw new IOException("Invalid tab page");
            this.id = id;
            this.label = label;
            this.page = page;
        }

        @Override public boolean equals(Object o) {
            return o instanceof CustomTab other && id.equals(other.id)
                    && label.equals(other.label) && page.equals(other.page);
        }

        @Override public int hashCode() { return Objects.hash(id, label, page); }
    }

    /** Custom composer/toolbar chip: prepares an editable draft or opens a surface — NEVER auto-sends. */
    public static final class QuickAction {
        public final String id;
        public final String label;
        public final String draft; // nullable
        public final String page;  // nullable

        public QuickAction(String id, String label, String draft, String page) throws IOException {
            if (!DraftForm.validId(id)) throw new IOException("Invalid quick action id");
            if (!validText(label, 36)) throw new IOException("Invalid quick action label");
            boolean hasDraft = draft != null && !draft.isEmpty();
            boolean hasPage = page != null && !page.isEmpty();
            if (hasDraft == hasPage) throw new IOException("Quick action must specify either draft or page");
            if (hasDraft && !validText(draft, MAX_DRAFT_CHARS)) throw new IOException("Invalid quick action draft");
            if (hasPage && !WorkspaceLayout.custom(page)) throw new IOException("Invalid quick action page");
            this.id = id;
            this.label = label;
            this.draft = hasDraft ? draft : null;
            this.page = hasPage ? page : null;
        }

        @Override public boolean equals(Object o) {
            return o instanceof QuickAction other && id.equals(other.id) && label.equals(other.label)
                    && Objects.equals(draft, other.draft) && Objects.equals(page, other.page);
        }

        @Override public int hashCode() { return Objects.hash(id, label, draft, page); }
    }

    /** Contextual 1-tap follow-up suggestion chip below a Hermes reply (always safe, never auto-sends). */
    public static final class SuggestedAction {
        public final String label;
        public final String draft;  // nullable
        public final String page;   // nullable
        public final String action; // nullable (allowlisted WorkspaceLayout.ACTIONS key)

        public SuggestedAction(String label, String draft, String page, String action) throws IOException {
            if (!validText(label, 40)) throw new IOException("Invalid suggested action label");
            boolean hasDraft = draft != null && !draft.isEmpty();
            boolean hasPage = page != null && !page.isEmpty();
            boolean hasAction = action != null && !action.isEmpty();
            int count = (hasDraft ? 1 : 0) + (hasPage ? 1 : 0) + (hasAction ? 1 : 0);
            if (count != 1) throw new IOException("Suggested action must declare exactly one target");
            if (hasDraft && !validText(draft, MAX_DRAFT_CHARS)) throw new IOException("Invalid suggested action draft");
            if (hasPage && !WorkspaceLayout.custom(page)) throw new IOException("Invalid suggested action page");
            if (hasAction && !WorkspaceLayout.ACTIONS.containsKey(action)) throw new IOException("Unknown suggested action");
            this.label = label;
            this.draft = hasDraft ? draft : null;
            this.page = hasPage ? page : null;
            this.action = hasAction ? action : null;
        }

        @Override public boolean equals(Object o) {
            return o instanceof SuggestedAction other && label.equals(other.label)
                    && Objects.equals(draft, other.draft) && Objects.equals(page, other.page)
                    && Objects.equals(action, other.action);
        }

        @Override public int hashCode() { return Objects.hash(label, draft, page, action); }
    }

    public static final class WidgetItem {
        public final String label;
        public final String value;
        public final String actionDraft; // nullable

        public WidgetItem(String label, String value, String actionDraft) throws IOException {
            if (!validText(label, 48)) throw new IOException("Invalid widget item label");
            String safeVal = value == null ? "" : value;
            if (!safeVal.isEmpty() && !validText(safeVal, 48)) throw new IOException("Invalid widget item value");
            String safeDraft = actionDraft == null || actionDraft.isEmpty() ? null : actionDraft;
            if (safeDraft != null && !validText(safeDraft, MAX_DRAFT_CHARS)) throw new IOException("Invalid widget action draft");
            this.label = label;
            this.value = safeVal;
            this.actionDraft = safeDraft;
        }

        @Override public boolean equals(Object o) {
            return o instanceof WidgetItem other && label.equals(other.label)
                    && value.equals(other.value) && Objects.equals(actionDraft, other.actionDraft);
        }

        @Override public int hashCode() { return Objects.hash(label, value, actionDraft); }
    }

    /** Declarative home-screen-safe widget specification (strictly requires home_screen_safe: true). */
    public static final class WidgetSpec {
        public final String widgetId;
        public final String title;
        public final String subtitle;
        public final String template;
        public final List<WidgetItem> items;
        public final String accentHex; // nullable, WCAG-AA verified
        public final boolean homeScreenSafe;

        public WidgetSpec(String widgetId, String title, String subtitle, String template,
                          List<WidgetItem> items, String accentHex, boolean homeScreenSafe, boolean nightMode) throws IOException {
            if (!homeScreenSafe) throw new IOException("Widget must explicitly opt in with home_screen_safe: true");
            if (!DraftForm.validId(widgetId)) throw new IOException("Invalid widget_id");
            if (!validText(title, 48)) throw new IOException("Invalid widget title");
            String safeSub = subtitle == null ? "" : subtitle;
            if (!safeSub.isEmpty() && !validText(safeSub, 80)) throw new IOException("Invalid widget subtitle");
            if (template == null || !WIDGET_TEMPLATES.contains(template)) throw new IOException("Invalid widget template");
            if (items == null || items.isEmpty() || items.size() > MAX_WIDGET_ITEMS) throw new IOException("Invalid widget items");
            String normalizedAccent = null;
            if (accentHex != null && !accentHex.isEmpty()) {
                int rgb = ThemePalette.parseHexColor(accentHex);
                ThemePalette.validateAccentContrast(rgb, nightMode);
                normalizedAccent = ThemePalette.formatHex(rgb);
            }
            this.widgetId = widgetId;
            this.title = title;
            this.subtitle = safeSub;
            this.template = template;
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
            this.accentHex = normalizedAccent;
            this.homeScreenSafe = true;
        }

        @Override public boolean equals(Object o) {
            return o instanceof WidgetSpec other && homeScreenSafe == other.homeScreenSafe
                    && widgetId.equals(other.widgetId) && title.equals(other.title)
                    && subtitle.equals(other.subtitle) && template.equals(other.template)
                    && items.equals(other.items) && Objects.equals(accentHex, other.accentHex);
        }

        @Override public int hashCode() {
            return Objects.hash(widgetId, title, subtitle, template, items, accentHex, homeScreenSafe);
        }
    }

    public sealed interface Op {
        String kind();
        String frenchDescription();

        record UpsertSurface(String surface, String layoutJson, String title) implements Op {
            @Override public String kind() { return "upsert_surface"; }
            @Override public String frenchDescription() {
                return "Mettre à jour l’écran « " + title + " »";
            }
        }

        record RemoveSurface(String surface) implements Op {
            @Override public String kind() { return "remove_surface"; }
            @Override public String frenchDescription() {
                String name = WorkspaceLayout.screenName(surface);
                return "Rétablir l’écran « " + (name == null ? surface : name) + " »";
            }
        }

        record UpsertTab(CustomTab tab) implements Op {
            @Override public String kind() { return "upsert_tab"; }
            @Override public String frenchDescription() {
                return "Ajouter l’onglet « " + tab.label + " » dans l’atelier";
            }
        }

        record RemoveTab(String id) implements Op {
            @Override public String kind() { return "remove_tab"; }
            @Override public String frenchDescription() {
                return "Retirer l’onglet « " + id + " » de l’atelier";
            }
        }

        record UpsertQuickAction(QuickAction action) implements Op {
            @Override public String kind() { return "upsert_quick_action"; }
            @Override public String frenchDescription() {
                return "Ajouter le raccourci « " + action.label + " »";
            }
        }

        record RemoveQuickAction(String id) implements Op {
            @Override public String kind() { return "remove_quick_action"; }
            @Override public String frenchDescription() {
                return "Retirer le raccourci « " + id + " »";
            }
        }

        record SetThemeOverride(ThemePalette.OverrideSpec spec) implements Op {
            @Override public String kind() { return "set_theme_override"; }
            @Override public String frenchDescription() {
                return "Apparence : " + spec.frenchSummary();
            }
        }

        record ResetTheme() implements Op {
            @Override public String kind() { return "reset_theme"; }
            @Override public String frenchDescription() {
                return "Rétablir l’apparence par défaut";
            }
        }

        record UpsertWidget(WidgetSpec widget) implements Op {
            @Override public String kind() { return "upsert_widget"; }
            @Override public String frenchDescription() {
                return "Préparer le widget d’accueil « " + widget.title + " »";
            }
        }

        record RemoveWidget(String widgetId) implements Op {
            @Override public String kind() { return "remove_widget"; }
            @Override public String frenchDescription() {
                return "Retirer le widget d’accueil « " + widgetId + " »";
            }
        }
    }

    public final String revisionId;
    public final String parentRevisionId;
    public final String scope;
    public final String summary;
    public final List<Op> ops;
    public final List<SuggestedAction> suggestedActions;
    public final boolean requiresApply;

    public LiveUiPatch(String revisionId, String parentRevisionId, String scope, String summary,
                       List<Op> ops, boolean requiresApply) throws IOException {
        this(revisionId, parentRevisionId, scope, summary, ops, Collections.emptyList(), requiresApply);
    }

    public LiveUiPatch(String revisionId, String parentRevisionId, String scope, String summary,
                       List<Op> ops, List<SuggestedAction> suggestedActions, boolean requiresApply) throws IOException {
        if (!validRevisionId(revisionId)) throw new IOException("Invalid revision id");
        if (parentRevisionId != null && !parentRevisionId.isEmpty() && !validRevisionId(parentRevisionId)) {
            throw new IOException("Invalid parent revision id");
        }
        String safeScope = scope == null || scope.isEmpty() ? "thread" : scope;
        if (!SCOPES.contains(safeScope)) throw new IOException("Invalid patch scope");
        if (ops == null || ops.isEmpty() || ops.size() > MAX_OPS) throw new IOException("Invalid op count");
        if (suggestedActions != null && suggestedActions.size() > MAX_SUGGESTED_ACTIONS) {
            throw new IOException("Too many suggested actions");
        }
        String cleanSummary = summary == null ? "" : summary.trim();
        if (!cleanSummary.isEmpty() && !validText(cleanSummary, MAX_SUMMARY_CHARS)) {
            throw new IOException("Invalid patch summary");
        }
        this.revisionId = revisionId;
        this.parentRevisionId = parentRevisionId == null ? "" : parentRevisionId;
        this.scope = safeScope;
        this.summary = cleanSummary.isEmpty() ? defaultSummary(ops) : cleanSummary;
        this.ops = Collections.unmodifiableList(new ArrayList<>(ops));
        this.suggestedActions = suggestedActions == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(suggestedActions));
        this.requiresApply = requiresApply;
    }

    public static boolean validRevisionId(String id) {
        return id != null && REVISION_ID.matcher(id).matches();
    }

    private static String defaultSummary(List<Op> ops) {
        if (ops.size() == 1) return ops.get(0).frenchDescription();
        return ops.size() + " ajustements proposés pour votre espace";
    }

    public List<String> frenchHighlights() {
        List<String> lines = new ArrayList<>(ops.size());
        for (Op op : ops) lines.add(op.frenchDescription());
        return Collections.unmodifiableList(lines);
    }

    /** Parses and validates a Fork PR #15 ui.patch SSE event or REST revision object. */
    public static LiveUiPatch parse(JSONObject raw, boolean nightMode) throws IOException {
        if (raw == null) throw new IOException("Missing patch object");
        String serialized = raw.toString();
        if (serialized.length() > MAX_PATCH_BYTES) {
            warnRejected();
            throw new IOException("Patch exceeded byte limit");
        }
        try {
            JSONObject payload = raw.has("patch") && raw.opt("patch") instanceof JSONObject nested ? nested : raw;
            if (payload.has("reversible") && !payload.optBoolean("reversible", true)) {
                throw new IOException("Non-reversible patches are rejected");
            }
            String revId = payload.optString("revision_id", "");
            String parentId = payload.optString("parent_revision_id", "");
            String scope = payload.optString("scope", "thread");
            String summary = payload.optString("summary", "");
            boolean requiresApply = payload.optBoolean("requires_apply", true);
            JSONArray rawOps = payload.optJSONArray("ops");
            if (rawOps == null || rawOps.length() == 0 || rawOps.length() > MAX_OPS) {
                throw new IOException("Missing or oversized ops");
            }
            List<Op> parsed = new ArrayList<>(rawOps.length());
            for (int i = 0; i < rawOps.length(); i++) {
                JSONObject opObj = rawOps.optJSONObject(i);
                if (opObj == null) throw new IOException("Malformed op entry");
                parsed.add(parseOp(opObj, nightMode));
            }
            List<SuggestedAction> suggestions = parseSuggestedActions(payload.opt("suggested_actions"));
            return new LiveUiPatch(revId, parentId, scope, summary, parsed, suggestions, requiresApply);
        } catch (IOException | JSONException e) {
            warnRejected();
            throw e instanceof IOException io ? io : new IOException("Malformed ui.patch", e);
        }
    }

    public static List<SuggestedAction> parseSuggestedActions(Object raw) throws IOException {
        if (raw == null || JSONObject.NULL.equals(raw)) return Collections.emptyList();
        if (!(raw instanceof JSONArray arr) || arr.length() > MAX_SUGGESTED_ACTIONS) {
            throw new IOException("Invalid suggested_actions");
        }
        List<SuggestedAction> out = new ArrayList<>(arr.length());
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.optJSONObject(i);
            if (obj == null) throw new IOException("Malformed suggested action");
            Set<String> keys = new HashSet<>();
            java.util.Iterator<String> it = obj.keys();
            while (it.hasNext()) keys.add(it.next());
            allowOnly(keys, "label", "draft", "action_draft", "page", "action");
            String draft = obj.has("draft") ? obj.optString("draft", "")
                    : obj.has("action_draft") ? obj.optString("action_draft", "") : null;
            out.add(new SuggestedAction(
                    obj.optString("label", ""),
                    draft,
                    obj.has("page") ? obj.optString("page", "") : null,
                    obj.has("action") ? obj.optString("action", "") : null));
        }
        return out;
    }

    private static Op parseOp(JSONObject obj, boolean nightMode) throws IOException, JSONException {
        String op = obj.optString("op", "");
        Set<String> keys = new HashSet<>();
        java.util.Iterator<String> it = obj.keys();
        while (it.hasNext()) keys.add(it.next());
        return switch (op) {
            case "upsert_surface" -> {
                allowOnly(keys, "op", "surface", "layout");
                Object layoutVal = obj.opt("layout");
                String layoutJson;
                if (layoutVal instanceof JSONObject lo) layoutJson = lo.toString();
                else if (layoutVal instanceof String ls) layoutJson = ls;
                else throw new IOException("Missing layout document");
                WorkspaceLayout layout = WorkspaceLayout.parse(layoutJson);
                String surface = obj.optString("surface", layout.surface);
                if (!surface.equals(layout.surface)) throw new IOException("Surface mismatch");
                yield new Op.UpsertSurface(surface, layoutJson, layout.title);
            }
            case "remove_surface" -> {
                allowOnly(keys, "op", "surface");
                String surface = obj.optString("surface", "");
                if (!WorkspaceLayout.SURFACES.containsKey(surface) && !WorkspaceLayout.custom(surface)) {
                    throw new IOException("Unknown surface");
                }
                yield new Op.RemoveSurface(surface);
            }
            case "upsert_tab" -> {
                allowOnly(keys, "op", "id", "label", "page");
                yield new Op.UpsertTab(new CustomTab(
                        obj.optString("id", ""), obj.optString("label", ""), obj.optString("page", "")));
            }
            case "remove_tab" -> {
                allowOnly(keys, "op", "id");
                String id = obj.optString("id", "");
                if (!DraftForm.validId(id)) throw new IOException("Invalid tab id");
                yield new Op.RemoveTab(id);
            }
            case "upsert_quick_action" -> {
                // Reject any auto-send / script / url keys strictly.
                allowOnly(keys, "op", "id", "label", "draft", "prompt", "page");
                String draft = obj.has("draft") ? obj.optString("draft", "")
                        : obj.has("prompt") ? obj.optString("prompt", "") : null;
                yield new Op.UpsertQuickAction(new QuickAction(
                        obj.optString("id", ""), obj.optString("label", ""),
                        draft,
                        obj.has("page") ? obj.optString("page", "") : null));
            }
            case "remove_quick_action" -> {
                allowOnly(keys, "op", "id");
                String id = obj.optString("id", "");
                if (!DraftForm.validId(id)) throw new IOException("Invalid quick action id");
                yield new Op.RemoveQuickAction(id);
            }
            case "set_theme_override" -> {
                allowOnly(keys, "op", "theme", "accent_hex", "density", "font_scale_step", "surface_style");
                int step = 0;
                if (obj.has("font_scale_step")) {
                    Object rawStep = obj.opt("font_scale_step");
                    if (!(rawStep instanceof Integer)) throw new IOException("font_scale_step must be an integer");
                    step = (Integer) rawStep;
                }
                ThemePalette.OverrideSpec spec = ThemePalette.OverrideSpec.validated(
                        obj.has("theme") ? obj.optString("theme", "") : "system",
                        obj.has("accent_hex") ? obj.optString("accent_hex", "") : null,
                        obj.has("density") ? obj.optString("density", "") : "comfortable",
                        step,
                        obj.has("surface_style") ? obj.optString("surface_style", "") : "paper",
                        nightMode);
                yield new Op.SetThemeOverride(spec);
            }
            case "reset_theme", "clear_theme_override" -> {
                allowOnly(keys, "op");
                yield new Op.ResetTheme();
            }
            case "upsert_widget" -> {
                allowOnly(keys, "op", "widget_id", "title", "subtitle", "template", "items", "accent_hex", "home_screen_safe");
                if (!(obj.opt("home_screen_safe") instanceof Boolean safe) || !safe) {
                    throw new IOException("Widget requires home_screen_safe=true");
                }
                JSONArray rawItems = obj.optJSONArray("items");
                if (rawItems == null || rawItems.length() == 0 || rawItems.length() > MAX_WIDGET_ITEMS) {
                    throw new IOException("Invalid widget items");
                }
                List<WidgetItem> items = new ArrayList<>(rawItems.length());
                for (int i = 0; i < rawItems.length(); i++) {
                    JSONObject itemObj = rawItems.optJSONObject(i);
                    if (itemObj == null) throw new IOException("Malformed widget item");
                    Set<String> itemKeys = new HashSet<>();
                    java.util.Iterator<String> itemIt = itemObj.keys();
                    while (itemIt.hasNext()) itemKeys.add(itemIt.next());
                    allowOnly(itemKeys, "label", "value", "action_draft");
                    items.add(new WidgetItem(
                            itemObj.optString("label", ""),
                            itemObj.optString("value", ""),
                            itemObj.has("action_draft") ? itemObj.optString("action_draft", "") : null));
                }
                yield new Op.UpsertWidget(new WidgetSpec(
                        obj.optString("widget_id", ""),
                        obj.optString("title", ""),
                        obj.optString("subtitle", ""),
                        obj.optString("template", ""),
                        items,
                        obj.has("accent_hex") ? obj.optString("accent_hex", "") : null,
                        true,
                        nightMode));
            }
            case "remove_widget" -> {
                allowOnly(keys, "op", "widget_id");
                String id = obj.optString("widget_id", "");
                if (!DraftForm.validId(id)) throw new IOException("Invalid widget_id");
                yield new Op.RemoveWidget(id);
            }
            default -> throw new IOException("Unsupported ui.patch op");
        };
    }

    private static void allowOnly(Set<String> keys, String... allowed) throws IOException {
        if (!Arrays.asList(allowed).containsAll(keys)) {
            throw new IOException("Unexpected field in ui.patch op");
        }
    }

    static boolean validText(String s, int max) {
        if (s == null || s.isEmpty() || s.length() > max) return false;
        boolean hasVisible = false;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int kind = Character.getType(cp);
            if (Character.isISOControl(cp) || kind == Character.FORMAT || kind == Character.SURROGATE
                    || kind == Character.LINE_SEPARATOR || kind == Character.PARAGRAPH_SEPARATOR) {
                return false;
            }
            if (!Character.isWhitespace(cp) && !Character.isSpaceChar(cp) && !isVariationSelector(cp)) {
                hasVisible = true;
            }
            i += Character.charCount(cp);
        }
        return hasVisible;
    }

    private static boolean isVariationSelector(int cp) {
        return cp == 0x180F || (cp >= 0x180B && cp <= 0x180D)
                || (cp >= 0xFE00 && cp <= 0xFE0F)
                || (cp >= 0xE0100 && cp <= 0xE01EF);
    }

    private static void warnRejected() {
        try { Diagnostics.warning("live_ui", "patch_rejected"); }
        catch (RuntimeException ignoredInHostJvmTests) {}
    }

    /**
     * Profile-scoped persistent state for live UI edits: custom Atelier tabs, custom composer quick-action chips,
     * live theme override, and atomic rollback history. Works with Account in Android and in-memory BackingStore in JVM tests.
     */
    public static final class StateManager {
        public interface SurfaceAdapter {
            String readCurrent(String surface);
            void replace(String surface, String revisionId, String summary, String nextRaw);
        }

        public static final class AppliedRevision {
            public final String revisionId;
            public final String summary;
            public final Map<String, String> previousSurfaces;
            public final List<CustomTab> previousTabs;
            public final List<QuickAction> previousQuickActions;
            public final List<WidgetSpec> previousWidgets;
            public final ThemePalette.OverrideSpec previousTheme;

            AppliedRevision(String revisionId, String summary, Map<String, String> previousSurfaces,
                            List<CustomTab> previousTabs, List<QuickAction> previousQuickActions,
                            List<WidgetSpec> previousWidgets, ThemePalette.OverrideSpec previousTheme) {
                this.revisionId = revisionId;
                this.summary = summary;
                this.previousSurfaces = Collections.unmodifiableMap(previousSurfaces);
                this.previousTabs = Collections.unmodifiableList(previousTabs);
                this.previousQuickActions = Collections.unmodifiableList(previousQuickActions);
                this.previousWidgets = Collections.unmodifiableList(previousWidgets);
                this.previousTheme = previousTheme;
            }
        }

        private final InstrumentStateStore.BackingStore store;
        private final SurfaceAdapter surfaces;
        private final boolean nightMode;
        private final List<CustomTab> tabs = new ArrayList<>();
        private final List<QuickAction> quickActions = new ArrayList<>();
        private final List<WidgetSpec> widgets = new ArrayList<>();
        private ThemePalette.OverrideSpec theme = ThemePalette.OverrideSpec.defaults();
        private final List<AppliedRevision> history = new ArrayList<>();

        public StateManager(InstrumentStateStore.BackingStore store, SurfaceAdapter surfaces, boolean nightMode) {
            this.store = Objects.requireNonNull(store);
            this.surfaces = Objects.requireNonNull(surfaces);
            this.nightMode = nightMode;
            reload();
        }

        public static StateManager forAccount(Account account, boolean nightMode) {
            Objects.requireNonNull(account);
            InstrumentStateStore.BackingStore backing = new InstrumentStateStore.BackingStore() {
                @Override public String read(String key) { return account.read(key); }
                @Override public void write(String key, String value) { account.write(key, value); }
                @Override public void remove(String key) { account.remove(key); }
            };
            SurfaceAdapter adapter = new SurfaceAdapter() {
                @Override public String readCurrent(String surface) {
                    try { return new LayoutHistory(account, surface).current(); }
                    catch (RuntimeException e) { return ""; }
                }
                @Override public void replace(String surface, String revisionId, String summary, String nextRaw) {
                    new LayoutHistory(account, surface).replaceWithRevision(revisionId, summary, nextRaw);
                }
            };
            return new StateManager(backing, adapter, nightMode);
        }

        public List<CustomTab> tabs() { return Collections.unmodifiableList(new ArrayList<>(tabs)); }
        public List<QuickAction> quickActions() { return Collections.unmodifiableList(new ArrayList<>(quickActions)); }
        public List<WidgetSpec> widgets() { return Collections.unmodifiableList(new ArrayList<>(widgets)); }
        public ThemePalette.OverrideSpec theme() { return theme; }
        public List<AppliedRevision> history() { return Collections.unmodifiableList(new ArrayList<>(history)); }
        public boolean canRollback() { return !history.isEmpty(); }

        /** Atomically applies all validated ops and records a full rollback snapshot. */
        public synchronized void apply(LiveUiPatch patch) throws IOException {
            Objects.requireNonNull(patch);
            List<CustomTab> nextTabs = new ArrayList<>(tabs);
            List<QuickAction> nextActions = new ArrayList<>(quickActions);
            List<WidgetSpec> nextWidgets = new ArrayList<>(widgets);
            ThemePalette.OverrideSpec nextTheme = theme;
            Map<String, String> surfaceWrites = new LinkedHashMap<>();
            Map<String, String> priorSurfaces = new LinkedHashMap<>();

            // Stage & validate all bounds before mutating anything.
            for (Op op : patch.ops) {
                if (op instanceof Op.UpsertSurface up) {
                    priorSurfaces.putIfAbsent(up.surface(), surfaces.readCurrent(up.surface()));
                    surfaceWrites.put(up.surface(), up.layoutJson());
                } else if (op instanceof Op.RemoveSurface rm) {
                    priorSurfaces.putIfAbsent(rm.surface(), surfaces.readCurrent(rm.surface()));
                    surfaceWrites.put(rm.surface(), "");
                } else if (op instanceof Op.UpsertTab up) {
                    nextTabs.removeIf(t -> t.id.equals(up.tab().id));
                    if (nextTabs.size() >= MAX_CUSTOM_TABS) throw new IOException("Too many custom tabs");
                    nextTabs.add(up.tab());
                } else if (op instanceof Op.RemoveTab rm) {
                    nextTabs.removeIf(t -> t.id.equals(rm.id()));
                } else if (op instanceof Op.UpsertQuickAction up) {
                    nextActions.removeIf(a -> a.id.equals(up.action().id));
                    if (nextActions.size() >= MAX_QUICK_ACTIONS) throw new IOException("Too many quick actions");
                    nextActions.add(up.action());
                } else if (op instanceof Op.RemoveQuickAction rm) {
                    nextActions.removeIf(a -> a.id.equals(rm.id()));
                } else if (op instanceof Op.UpsertWidget uw) {
                    nextWidgets.removeIf(w -> w.widgetId.equals(uw.widget().widgetId));
                    if (nextWidgets.size() >= MAX_WIDGETS) throw new IOException("Too many widgets");
                    nextWidgets.add(uw.widget());
                } else if (op instanceof Op.RemoveWidget rw) {
                    nextWidgets.removeIf(w -> w.widgetId.equals(rw.widgetId()));
                } else if (op instanceof Op.SetThemeOverride st) {
                    nextTheme = st.spec();
                } else if (op instanceof Op.ResetTheme) {
                    nextTheme = ThemePalette.OverrideSpec.defaults();
                }
            }

            AppliedRevision snapshot = new AppliedRevision(
                    patch.revisionId, patch.summary, priorSurfaces,
                    new ArrayList<>(tabs), new ArrayList<>(quickActions), new ArrayList<>(widgets), theme);

            for (Map.Entry<String, String> write : surfaceWrites.entrySet()) {
                surfaces.replace(write.getKey(), patch.revisionId, patch.summary, write.getValue());
            }
            tabs.clear();
            tabs.addAll(nextTabs);
            quickActions.clear();
            quickActions.addAll(nextActions);
            widgets.clear();
            widgets.addAll(nextWidgets);
            theme = nextTheme;
            history.add(snapshot);
            while (history.size() > MAX_HISTORY) history.remove(0);
            persist();
        }

        /** 1-tap atomic rollback of the most recent applied patch. */
        public synchronized AppliedRevision rollbackLast() throws IOException {
            if (history.isEmpty()) return null;
            AppliedRevision last = history.remove(history.size() - 1);
            restoreSnapshot(last);
            persist();
            return last;
        }

        /** Atomic rollback to the state right before the specified revisionId (reverting it and any later patches). */
        public synchronized AppliedRevision rollbackTo(String revisionId) throws IOException {
            int target = -1;
            for (int i = history.size() - 1; i >= 0; i--) {
                if (history.get(i).revisionId.equals(revisionId)) { target = i; break; }
            }
            if (target < 0) return null;
            AppliedRevision reverted = null;
            while (history.size() > target) {
                reverted = history.remove(history.size() - 1);
                restoreSnapshot(reverted);
            }
            persist();
            return reverted;
        }

        private void restoreSnapshot(AppliedRevision snapshot) {
            for (Map.Entry<String, String> entry : snapshot.previousSurfaces.entrySet()) {
                surfaces.replace(entry.getKey(), "revert:" + snapshot.revisionId,
                        "Annulation · " + snapshot.summary, entry.getValue());
            }
            tabs.clear();
            tabs.addAll(snapshot.previousTabs);
            quickActions.clear();
            quickActions.addAll(snapshot.previousQuickActions);
            widgets.clear();
            widgets.addAll(snapshot.previousWidgets);
            theme = snapshot.previousTheme;
        }

        private void reload() {
            tabs.clear();
            quickActions.clear();
            widgets.clear();
            history.clear();
            theme = ThemePalette.OverrideSpec.defaults();
            String raw;
            try { raw = store.read(STATE_KEY); }
            catch (RuntimeException e) { return; }
            if (raw == null || raw.isEmpty()) return;
            try {
                decodeState(raw);
            } catch (IOException e) {
                tabs.clear();
                quickActions.clear();
                widgets.clear();
                history.clear();
                theme = ThemePalette.OverrideSpec.defaults();
                warnRejected();
            }
        }

        private void persist() {
            if (tabs.isEmpty() && quickActions.isEmpty() && widgets.isEmpty() && theme.isDefault() && history.isEmpty()) {
                store.remove(STATE_KEY);
                store.remove("workspace.theme.v2");
                return;
            }
            store.write(STATE_KEY, encodeState());
            if (theme.isDefault()) store.remove("workspace.theme.v2");
            else store.write("workspace.theme.v2", theme.encode());
        }

        /** Pure-Java deterministic encoding (zero org.json dependency so JVM unit tests run natively). */
        private String encodeState() {
            StringBuilder out = new StringBuilder();
            out.append("THEME\t").append(esc(theme.encode())).append('\n');
            for (CustomTab t : tabs) {
                out.append("TAB\t").append(esc(t.id)).append('\t').append(esc(t.label))
                        .append('\t').append(esc(t.page)).append('\n');
            }
            for (QuickAction a : quickActions) {
                out.append("ACT\t").append(esc(a.id)).append('\t').append(esc(a.label))
                        .append('\t').append(esc(a.draft == null ? "" : a.draft))
                        .append('\t').append(esc(a.page == null ? "" : a.page)).append('\n');
            }
            for (WidgetSpec w : widgets) {
                out.append("WDG\t").append(esc(encodeWidget(w))).append('\n');
            }
            for (AppliedRevision rev : history) {
                out.append("REV\t").append(esc(rev.revisionId)).append('\t').append(esc(rev.summary))
                        .append('\t').append(esc(rev.previousTheme.encode()))
                        .append('\t').append(esc(encodeMap(rev.previousSurfaces)))
                        .append('\t').append(esc(encodeTabs(rev.previousTabs)))
                        .append('\t').append(esc(encodeActions(rev.previousQuickActions)))
                        .append('\t').append(esc(encodeWidgets(rev.previousWidgets))).append('\n');
            }
            return out.toString();
        }

        private void decodeState(String raw) throws IOException {
            for (String line : raw.split("\n")) {
                if (line.isEmpty()) continue;
                String[] parts = line.split("\t", -1);
                switch (parts[0]) {
                    case "THEME" -> {
                        if (parts.length != 2) throw new IOException("Bad theme row");
                        theme = ThemePalette.OverrideSpec.decode(unesc(parts[1]), nightMode);
                    }
                    case "TAB" -> {
                        if (parts.length != 4 || tabs.size() >= MAX_CUSTOM_TABS) throw new IOException("Bad tab row");
                        tabs.add(new CustomTab(unesc(parts[1]), unesc(parts[2]), unesc(parts[3])));
                    }
                    case "ACT" -> {
                        if (parts.length != 5 || quickActions.size() >= MAX_QUICK_ACTIONS) throw new IOException("Bad action row");
                        String draft = unesc(parts[3]);
                        String page = unesc(parts[4]);
                        quickActions.add(new QuickAction(unesc(parts[1]), unesc(parts[2]),
                                draft.isEmpty() ? null : draft, page.isEmpty() ? null : page));
                    }
                    case "WDG" -> {
                        if (parts.length != 2 || widgets.size() >= MAX_WIDGETS) throw new IOException("Bad widget row");
                        widgets.add(decodeWidget(unesc(parts[1]), nightMode));
                    }
                    case "REV" -> {
                        if ((parts.length != 7 && parts.length != 8) || history.size() >= MAX_HISTORY) {
                            throw new IOException("Bad revision row");
                        }
                        List<WidgetSpec> prevWidgets = parts.length == 8
                                ? decodeWidgets(unesc(parts[7]), nightMode) : Collections.emptyList();
                        history.add(new AppliedRevision(
                                unesc(parts[1]), unesc(parts[2]),
                                decodeMap(unesc(parts[4])),
                                decodeTabs(unesc(parts[5])),
                                decodeActions(unesc(parts[6])),
                                prevWidgets,
                                ThemePalette.OverrideSpec.decode(unesc(parts[3]), nightMode)));
                    }
                    default -> throw new IOException("Unknown state row");
                }
            }
        }

        private static String encodeWidget(WidgetSpec w) {
            StringBuilder items = new StringBuilder();
            for (WidgetItem item : w.items) {
                if (items.length() > 0) items.append(',');
                items.append(esc(item.label)).append(':').append(esc(item.value)).append(':')
                        .append(esc(item.actionDraft == null ? "" : item.actionDraft));
            }
            return esc(w.widgetId) + "|" + esc(w.title) + "|" + esc(w.subtitle) + "|" + esc(w.template)
                    + "|" + esc(w.accentHex == null ? "" : w.accentHex) + "|" + esc(items.toString());
        }

        private static WidgetSpec decodeWidget(String raw, boolean nightMode) throws IOException {
            String[] p = raw.split("\\|", -1);
            if (p.length != 6) throw new IOException("Bad widget encoding");
            List<WidgetItem> items = new ArrayList<>();
            String itemsRaw = unesc(p[5]);
            if (!itemsRaw.isEmpty()) {
                for (String itemPart : itemsRaw.split(",", -1)) {
                    String[] f = itemPart.split(":", -1);
                    if (f.length != 3) throw new IOException("Bad widget item encoding");
                    String draft = unesc(f[2]);
                    items.add(new WidgetItem(unesc(f[0]), unesc(f[1]), draft.isEmpty() ? null : draft));
                }
            }
            String accent = unesc(p[4]);
            return new WidgetSpec(unesc(p[0]), unesc(p[1]), unesc(p[2]), unesc(p[3]),
                    items, accent.isEmpty() ? null : accent, true, nightMode);
        }

        private static String encodeWidgets(List<WidgetSpec> list) {
            StringBuilder sb = new StringBuilder();
            for (WidgetSpec w : list) {
                if (sb.length() > 0) sb.append(';');
                sb.append(esc(encodeWidget(w)));
            }
            return sb.toString();
        }

        private static List<WidgetSpec> decodeWidgets(String raw, boolean nightMode) throws IOException {
            List<WidgetSpec> list = new ArrayList<>();
            if (raw.isEmpty()) return list;
            for (String part : raw.split(";", -1)) {
                list.add(decodeWidget(unesc(part), nightMode));
            }
            return list;
        }

        private static String encodeMap(Map<String, String> map) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : map.entrySet()) {
                if (sb.length() > 0) sb.append(';');
                sb.append(esc(e.getKey())).append('=').append(esc(e.getValue() == null ? "" : e.getValue()));
            }
            return sb.toString();
        }

        private static Map<String, String> decodeMap(String raw) throws IOException {
            Map<String, String> map = new LinkedHashMap<>();
            if (raw.isEmpty()) return map;
            for (String item : raw.split(";", -1)) {
                int eq = item.indexOf('=');
                if (eq <= 0) throw new IOException("Bad surface map entry");
                map.put(unesc(item.substring(0, eq)), unesc(item.substring(eq + 1)));
            }
            return map;
        }

        private static String encodeTabs(List<CustomTab> list) {
            StringBuilder sb = new StringBuilder();
            for (CustomTab t : list) {
                if (sb.length() > 0) sb.append(';');
                sb.append(esc(t.id)).append('|').append(esc(t.label)).append('|').append(esc(t.page));
            }
            return sb.toString();
        }

        private static List<CustomTab> decodeTabs(String raw) throws IOException {
            List<CustomTab> list = new ArrayList<>();
            if (raw.isEmpty()) return list;
            for (String item : raw.split(";", -1)) {
                String[] p = item.split("\\|", -1);
                if (p.length != 3) throw new IOException("Bad tab snapshot");
                list.add(new CustomTab(unesc(p[0]), unesc(p[1]), unesc(p[2])));
            }
            return list;
        }

        private static String encodeActions(List<QuickAction> list) {
            StringBuilder sb = new StringBuilder();
            for (QuickAction a : list) {
                if (sb.length() > 0) sb.append(';');
                sb.append(esc(a.id)).append('|').append(esc(a.label)).append('|')
                        .append(esc(a.draft == null ? "" : a.draft)).append('|')
                        .append(esc(a.page == null ? "" : a.page));
            }
            return sb.toString();
        }

        private static List<QuickAction> decodeActions(String raw) throws IOException {
            List<QuickAction> list = new ArrayList<>();
            if (raw.isEmpty()) return list;
            for (String item : raw.split(";", -1)) {
                String[] p = item.split("\\|", -1);
                if (p.length != 4) throw new IOException("Bad quick action snapshot");
                String draft = unesc(p[2]);
                String page = unesc(p[3]);
                list.add(new QuickAction(unesc(p[0]), unesc(p[1]),
                        draft.isEmpty() ? null : draft, page.isEmpty() ? null : page));
            }
            return list;
        }

        private static String esc(String s) {
            StringBuilder sb = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '%' -> sb.append("%25");
                    case '\t' -> sb.append("%09");
                    case '\n' -> sb.append("%0A");
                    case '\r' -> sb.append("%0D");
                    case ';' -> sb.append("%3B");
                    case '|' -> sb.append("%7C");
                    case '=' -> sb.append("%3D");
                    case ',' -> sb.append("%2C");
                    case ':' -> sb.append("%3A");
                    default -> sb.append(c);
                }
            }
            return sb.toString();
        }

        private static String unesc(String s) throws IOException {
            StringBuilder sb = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c != '%') { sb.append(c); continue; }
                if (i + 2 >= s.length()) throw new IOException("Truncated escape");
                String hex = s.substring(i + 1, i + 3);
                try {
                    sb.append((char) Integer.parseInt(hex, 16));
                } catch (NumberFormatException e) {
                    throw new IOException("Invalid escape", e);
                }
                i += 2;
            }
            return sb.toString();
        }
    }
}
