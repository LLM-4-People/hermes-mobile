package org.llm4people.hermes.artifact;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
import org.llm4people.hermes.customization.InteractiveCardView;
import org.llm4people.hermes.places.NearbyCategory;
import org.llm4people.hermes.profile.Account;

/**
 * Validated, bounded, reversible Real-Time Live UI Editing v2 protocol aligned with Fork PR #15
 * (tools/mobile_ui_patch.py) and /api/mobile/ui/* REST endpoints. Every mutation is declarative,
 * sandboxed, WCAG-AA contrast-checked, fail-closed on storage damage, and atomically reversible.
 */
public final class LiveUiPatch {
    public static final int MAX_PATCH_BYTES = 65_536;
    public static final int MAX_OPS = 16;
    public static final int MAX_QUICK_ACTIONS = 12;
    public static final int MAX_CUSTOM_TABS = 6;
    public static final int MAX_WIDGETS = 16;
    public static final int MAX_WIDGET_ITEMS = 8;
    public static final int MAX_SUGGESTED_ACTIONS = 4;
    public static final int MAX_INTERACTIVE_CARDS = 4;
    public static final int MAX_HISTORY = 16;
    public static final int MAX_SUMMARY_CHARS = 240;
    public static final int MAX_DRAFT_CHARS = 1000;

    public static final List<String> WIDGET_TEMPLATES = Collections.unmodifiableList(
            Arrays.asList("metric", "checklist", "quick_actions", "status", "briefing"));
    public static final List<String> FORK_SURFACE_KINDS = Collections.unmodifiableList(Arrays.asList(
            "kpi_grid", "sparkline", "bar_chart", "table", "form", "toggle_group",
            "chip_filter", "slider", "action_bar", "markdown_card", "progress_tracker", "map_compass"));
    public static final List<String> FORK_DESTINATIONS = Collections.unmodifiableList(
            Arrays.asList("discussions", "atelier", "cerveau", "activite"));
    public static final List<String> FORK_QUICK_ACTION_KINDS = Collections.unmodifiableList(
            Arrays.asList("prompt", "open_destination", "toggle_surface"));
    public static final List<String> FORK_SUGGESTED_ACTION_KINDS = Collections.unmodifiableList(
            Arrays.asList("pin_widget", "save_memory", "create_reminder", "open_surface", "save_skill", "prompt"));

    private static final Pattern REVISION_ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final Pattern FORBIDDEN_PAYLOAD = Pattern.compile(
            "(?i)<\\s*/?\\s*(script|iframe|object|embed|svg)\\b|javascript\\s*:|vbscript\\s*:|data\\s*:\\s*text/html|intent\\s*:|file\\s*://");
    private static final Pattern WIDGET_SECRET = Pattern.compile(
            "(?i)(?:sk-[a-z0-9_-]{12,}|ghp_[a-z0-9]{20,}|bearer\\s+[a-z0-9._-]{16,}|-----begin\\s+[a-z ]*private key-----)");
    private static final List<String> SCOPES = Arrays.asList("thread", "atelier", "global");
    private static final String STATE_KEY = "workspace.live_ui.v2";

    public static final class CustomTab {
        public final String id;
        public final String label;
        public final String page;

        public CustomTab(String id, String label, String page) throws IOException {
            if (!validSafeId(id)) throw new IOException("Invalid tab id");
            if (!validText(label, 48)) throw new IOException("Invalid tab label");
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

    /** Custom composer/toolbar chip: prepares an editable draft, opens a surface, or navigates — NEVER auto-sends. */
    public static final class QuickAction {
        public final String id;
        public final String label;
        public final String draft;       // nullable
        public final String page;        // nullable (page/<slug>)
        public final String destination; // nullable (discussions | atelier | cerveau | activite)
        public final String icon;        // nullable

        public QuickAction(String id, String label, String draft, String page) throws IOException {
            this(id, label, draft, page, null, null);
        }

        public QuickAction(String id, String label, String draft, String page,
                           String destination, String icon) throws IOException {
            if (!validSafeId(id)) throw new IOException("Invalid quick action id");
            if (!validText(label, 64)) throw new IOException("Invalid quick action label");
            boolean hasDraft = draft != null && !draft.isEmpty();
            boolean hasPage = page != null && !page.isEmpty();
            boolean hasDest = destination != null && !destination.isEmpty();
            int targets = (hasDraft ? 1 : 0) + (hasPage ? 1 : 0) + (hasDest ? 1 : 0);
            if (targets != 1) throw new IOException("Quick action must specify exactly one target");
            if (hasDraft && !validText(draft, MAX_DRAFT_CHARS)) throw new IOException("Invalid quick action draft");
            if (hasPage && !WorkspaceLayout.custom(page)) throw new IOException("Invalid quick action page");
            if (hasDest && !FORK_DESTINATIONS.contains(destination)) throw new IOException("Invalid quick action destination");
            if (icon != null && !icon.isEmpty() && !validSafeId(icon)) throw new IOException("Invalid quick action icon");
            this.id = id;
            this.label = label;
            this.draft = hasDraft ? draft : null;
            this.page = hasPage ? page : null;
            this.destination = hasDest ? destination : null;
            this.icon = icon == null || icon.isEmpty() ? null : icon;
        }

        /** Maps Fork PR #15 destination names to top-level native tab navigation actions. */
        public String nativeActionId() {
            if (destination == null) return null;
            return switch (destination) {
                case "discussions" -> "nav_discussions";
                case "atelier" -> "nav_apps";
                case "cerveau" -> "nav_settings";
                case "activite" -> "nav_tasks";
                default -> null;
            };
        }

        @Override public boolean equals(Object o) {
            return o instanceof QuickAction other && id.equals(other.id) && label.equals(other.label)
                    && Objects.equals(draft, other.draft) && Objects.equals(page, other.page)
                    && Objects.equals(destination, other.destination) && Objects.equals(icon, other.icon);
        }

        @Override public int hashCode() { return Objects.hash(id, label, draft, page, destination, icon); }
    }

    /** Contextual 1-tap follow-up suggestion chip below a Hermes reply (aligned with Fork PR #15, never auto-sends). */
    public static final class SuggestedAction {
        public final String id;       // optional safe slug
        public final String label;
        public final String kind;     // prompt | open_surface | pin_widget | create_reminder | save_memory | save_skill
        public final String draft;    // nullable
        public final String page;     // nullable
        public final String action;   // nullable (allowlisted WorkspaceLayout.ACTIONS key)
        public final String widgetId; // nullable

        public SuggestedAction(String label, String draft, String page, String action) throws IOException {
            this("", label,
                    page != null && !page.isEmpty() ? "open_surface" : "prompt",
                    draft, page, action, null);
        }

        public SuggestedAction(String id, String label, String kind, String draft,
                               String page, String action, String widgetId) throws IOException {
            if (id != null && !id.isEmpty() && !validSafeId(id)) throw new IOException("Invalid suggested action id");
            if (!validText(label, 48)) throw new IOException("Invalid suggested action label");
            String safeKind = kind == null || kind.isEmpty() ? "prompt" : kind;
            if (!FORK_SUGGESTED_ACTION_KINDS.contains(safeKind)) throw new IOException("Invalid suggested action kind");
            boolean hasDraft = draft != null && !draft.isEmpty();
            boolean hasPage = page != null && !page.isEmpty();
            boolean hasAction = action != null && !action.isEmpty();
            boolean hasWidget = widgetId != null && !widgetId.isEmpty();
            int count = (hasDraft ? 1 : 0) + (hasPage ? 1 : 0) + (hasAction ? 1 : 0) + (hasWidget ? 1 : 0);
            if (count != 1) throw new IOException("Suggested action must declare exactly one target");
            if (hasDraft && !validText(draft, MAX_DRAFT_CHARS)) throw new IOException("Invalid suggested action draft");
            if (hasPage && !WorkspaceLayout.custom(page)) throw new IOException("Invalid suggested action page");
            if (hasAction && !WorkspaceLayout.ACTIONS.containsKey(action)) throw new IOException("Unknown suggested action");
            if (hasWidget && !validSafeId(widgetId)) throw new IOException("Invalid suggested widget_id");
            this.id = id == null ? "" : id;
            this.label = label;
            this.kind = safeKind;
            this.draft = hasDraft ? draft : null;
            this.page = hasPage ? page : null;
            this.action = hasAction ? action : null;
            this.widgetId = hasWidget ? widgetId : null;
        }

        @Override public boolean equals(Object o) {
            return o instanceof SuggestedAction other && id.equals(other.id) && label.equals(other.label)
                    && kind.equals(other.kind) && Objects.equals(draft, other.draft)
                    && Objects.equals(page, other.page) && Objects.equals(action, other.action)
                    && Objects.equals(widgetId, other.widgetId);
        }

        @Override public int hashCode() { return Objects.hash(id, label, kind, draft, page, action, widgetId); }
    }

    public static final class WidgetItem {
        public final String label;
        public final String value;
        public final Boolean checked;     // nullable
        public final String actionDraft;  // nullable

        public WidgetItem(String label, String value, String actionDraft) throws IOException {
            this(label, value, null, actionDraft);
        }

        public WidgetItem(String label, String value, Boolean checked, String actionDraft) throws IOException {
            if (!validText(label, 80)) throw new IOException("Invalid widget item label");
            String safeVal = value == null ? "" : value;
            if (!safeVal.isEmpty() && !validText(safeVal, 80)) throw new IOException("Invalid widget item value");
            String safeDraft = actionDraft == null || actionDraft.isEmpty() ? null : actionDraft;
            if (safeDraft != null && !validText(safeDraft, 240)) throw new IOException("Invalid widget action draft");
            if (containsSecret(label) || containsSecret(safeVal) || (safeDraft != null && containsSecret(safeDraft))) {
                throw new IOException("Widget item contains secret-like material");
            }
            this.label = label;
            this.value = safeVal;
            this.checked = checked;
            this.actionDraft = safeDraft;
        }

        @Override public boolean equals(Object o) {
            return o instanceof WidgetItem other && label.equals(other.label)
                    && value.equals(other.value) && Objects.equals(checked, other.checked)
                    && Objects.equals(actionDraft, other.actionDraft);
        }

        @Override public int hashCode() { return Objects.hash(label, value, checked, actionDraft); }
    }

    /** Declarative home-screen-safe widget specification aligned with Fork PR #15 (strictly home_screen_safe: true). */
    public static final class WidgetSpec {
        public final String widgetId;
        public final String title;
        public final String subtitle;
        public final String template;
        public final List<WidgetItem> items;
        public final String accentHex;    // nullable, WCAG-AA verified
        public final String accentInkHex; // nullable, WCAG-AA verified
        public final boolean homeScreenSafe;

        public WidgetSpec(String widgetId, String title, String subtitle, String template,
                          List<WidgetItem> items, String accentHex, boolean homeScreenSafe, boolean nightMode) throws IOException {
            this(widgetId, title, subtitle, template, items, accentHex, null, homeScreenSafe, nightMode);
        }

        public WidgetSpec(String widgetId, String title, String subtitle, String template,
                          List<WidgetItem> items, String accentHex, String accentInkHex,
                          boolean homeScreenSafe, boolean nightMode) throws IOException {
            if (!homeScreenSafe) throw new IOException("Widget must explicitly opt in with home_screen_safe: true");
            if (!validSafeId(widgetId)) throw new IOException("Invalid widget_id");
            if (!validText(title, 80)) throw new IOException("Invalid widget title");
            String safeSub = subtitle == null ? "" : subtitle;
            if (!safeSub.isEmpty() && !validText(safeSub, 120)) throw new IOException("Invalid widget subtitle");
            if (containsSecret(title) || containsSecret(safeSub)) throw new IOException("Widget text contains secret-like material");
            if (template == null || !WIDGET_TEMPLATES.contains(template)) throw new IOException("Invalid widget template");
            if (items == null || items.size() > MAX_WIDGET_ITEMS) throw new IOException("Invalid widget items");
            String normalizedAccent = null;
            String normalizedAccentInk = null;
            if (accentInkHex != null && !accentInkHex.isEmpty() && (accentHex == null || accentHex.isEmpty())) {
                throw new IOException("accent_ink_hex requires accent_hex");
            }
            if (accentHex != null && !accentHex.isEmpty()) {
                int rgb = ThemePalette.parseHexColor(accentHex);
                if (accentInkHex != null && !accentInkHex.isEmpty()) {
                    int inkRgb = ThemePalette.parseHexColor(accentInkHex);
                    if (ThemePalette.contrastRatio(inkRgb, rgb) < ThemePalette.WCAG_AA_NORMAL) {
                        throw new ThemePalette.ContrastException("Widget accent_ink_hex contrast below WCAG AA",
                                ThemePalette.contrastRatio(inkRgb, rgb));
                    }
                    normalizedAccentInk = ThemePalette.formatHex(inkRgb);
                } else {
                    ThemePalette.validateSystemAccentContrast(rgb);
                }
                normalizedAccent = ThemePalette.formatHex(rgb);
            }
            this.widgetId = widgetId;
            this.title = title;
            this.subtitle = safeSub;
            this.template = template;
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
            this.accentHex = normalizedAccent;
            this.accentInkHex = normalizedAccentInk;
            this.homeScreenSafe = true;
        }

        @Override public boolean equals(Object o) {
            return o instanceof WidgetSpec other && homeScreenSafe == other.homeScreenSafe
                    && widgetId.equals(other.widgetId) && title.equals(other.title)
                    && subtitle.equals(other.subtitle) && template.equals(other.template)
                    && items.equals(other.items) && Objects.equals(accentHex, other.accentHex)
                    && Objects.equals(accentInkHex, other.accentInkHex);
        }

        @Override public int hashCode() {
            return Objects.hash(widgetId, title, subtitle, template, items, accentHex, accentInkHex, homeScreenSafe);
        }
    }

    public sealed interface Op {
        String kind();
        String describe(android.content.Context context);
        String targetLabel();

        record UpsertSurface(String surface, String layoutJson, String title) implements Op {
            @Override public String kind() { return "upsert_surface"; }
            @Override public String targetLabel() { return title; }
            @Override public String describe(android.content.Context c) {
                return c.getString(org.llm4people.hermes.R.string.ws_liveui_op_upsert_surface, title);
            }
        }

        record RemoveSurface(String surface) implements Op {
            @Override public String kind() { return "remove_surface"; }
            @Override public String targetLabel() { return surface; }
            @Override public String describe(android.content.Context c) {
                String name = WorkspaceLayout.screenName(c, surface);
                return c.getString(org.llm4people.hermes.R.string.ws_liveui_op_remove_surface,
                        name == null ? surface : name);
            }
        }

        record UpsertTab(CustomTab tab) implements Op {
            @Override public String kind() { return "upsert_tab"; }
            @Override public String targetLabel() { return tab.label; }
            @Override public String describe(android.content.Context c) {
                return c.getString(org.llm4people.hermes.R.string.ws_liveui_op_upsert_tab, tab.label);
            }
        }

        record RemoveTab(String id) implements Op {
            @Override public String kind() { return "remove_tab"; }
            @Override public String targetLabel() { return id; }
            @Override public String describe(android.content.Context c) {
                return c.getString(org.llm4people.hermes.R.string.ws_liveui_op_remove_tab, id);
            }
        }

        record UpsertQuickAction(QuickAction action) implements Op {
            @Override public String kind() { return "upsert_quick_action"; }
            @Override public String targetLabel() { return action.label; }
            @Override public String describe(android.content.Context c) {
                return c.getString(org.llm4people.hermes.R.string.ws_liveui_op_upsert_qa, action.label);
            }
        }

        record RemoveQuickAction(String id) implements Op {
            @Override public String kind() { return "remove_quick_action"; }
            @Override public String targetLabel() { return id; }
            @Override public String describe(android.content.Context c) {
                return c.getString(org.llm4people.hermes.R.string.ws_liveui_op_remove_qa, id);
            }
        }

        record SetThemeOverride(ThemePalette.OverrideSpec spec) implements Op {
            @Override public String kind() { return "set_theme_override"; }
            @Override public String targetLabel() { return spec.theme; }
            @Override public String describe(android.content.Context c) {
                return c.getString(org.llm4people.hermes.R.string.ws_liveui_op_theme, spec.describe(c));
            }
        }

        record ResetTheme() implements Op {
            @Override public String kind() { return "clear_theme_override"; }
            @Override public String targetLabel() { return "default"; }
            @Override public String describe(android.content.Context c) {
                return c.getString(org.llm4people.hermes.R.string.ws_liveui_op_reset_theme);
            }
        }

        record UpsertWidget(WidgetSpec widget) implements Op {
            @Override public String kind() { return "upsert_widget"; }
            @Override public String targetLabel() { return widget.title; }
            @Override public String describe(android.content.Context c) {
                return c.getString(org.llm4people.hermes.R.string.ws_liveui_op_upsert_widget, widget.title);
            }
        }

        record RemoveWidget(String widgetId) implements Op {
            @Override public String kind() { return "remove_widget"; }
            @Override public String targetLabel() { return widgetId; }
            @Override public String describe(android.content.Context c) {
                return c.getString(org.llm4people.hermes.R.string.ws_liveui_op_remove_widget, widgetId);
            }
        }
    }

    public final String revisionId;
    public final String parentRevisionId;
    public final String scope;
    public final String summary;
    public final List<Op> ops;
    public final List<SuggestedAction> suggestedActions;
    public final List<InteractiveCardView.Spec> interactiveCards;
    public final boolean requiresApply;

    public LiveUiPatch(String revisionId, String parentRevisionId, String scope, String summary,
                       List<Op> ops, boolean requiresApply) throws IOException {
        this(revisionId, parentRevisionId, scope, summary, ops, Collections.emptyList(), requiresApply);
    }

    public LiveUiPatch(String revisionId, String parentRevisionId, String scope, String summary,
                       List<Op> ops, List<SuggestedAction> suggestedActions, boolean requiresApply) throws IOException {
        this(revisionId, parentRevisionId, scope, summary, ops, suggestedActions,
                Collections.emptyList(), requiresApply, MAX_OPS);
    }

    private LiveUiPatch(String revisionId, String parentRevisionId, String scope, String summary,
                        List<Op> ops, List<SuggestedAction> suggestedActions,
                        List<InteractiveCardView.Spec> interactiveCards, boolean requiresApply,
                        int maxOps) throws IOException {
        if (!validRevisionId(revisionId)) throw new IOException("Invalid revision id");
        if (parentRevisionId != null && !parentRevisionId.isEmpty() && !validRevisionId(parentRevisionId)) {
            throw new IOException("Invalid parent revision id");
        }
        String safeScope = scope == null || scope.isEmpty() ? "thread" : scope;
        if (!SCOPES.contains(safeScope)) throw new IOException("Invalid patch scope");
        List<InteractiveCardView.Spec> safeCards = interactiveCards == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(interactiveCards));
        if (safeCards.size() > MAX_INTERACTIVE_CARDS) {
            throw new IOException("Too many interactive cards");
        }
        boolean hasOps = ops != null && !ops.isEmpty();
        if ((!hasOps && safeCards.isEmpty()) || (ops != null && ops.size() > maxOps)) {
            throw new IOException("Invalid op count");
        }
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
        this.ops = ops == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(ops));
        this.interactiveCards = safeCards;
        this.summary = cleanSummary;
        this.suggestedActions = suggestedActions == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(suggestedActions));
        this.requiresApply = requiresApply;
    }

    public static boolean validRevisionId(String id) {
        return id != null && REVISION_ID.matcher(id).matches();
    }

    public static boolean validSafeId(String id) {
        return id != null && SAFE_ID.matcher(id).matches();
    }

    public static boolean exceedsByteLimit(String serializedJson) {
        return serializedJson != null && serializedJson.getBytes(StandardCharsets.UTF_8).length > MAX_PATCH_BYTES;
    }

    public String displaySummary(android.content.Context context) {
        if (!summary.isEmpty()) return summary;
        if (ops.size() == 1) return ops.get(0).describe(context);
        if (!ops.isEmpty()) {
            return context.getString(org.llm4people.hermes.R.string.ws_liveui_summary_ops, ops.size());
        }
        return interactiveCards.size() == 1
                ? interactiveCards.get(0).title
                : context.getString(org.llm4people.hermes.R.string.ws_liveui_summary_cards, interactiveCards.size());
    }

    public List<String> highlights(android.content.Context context) {
        List<String> lines = new ArrayList<>(ops.size() + interactiveCards.size());
        for (Op op : ops) lines.add(op.describe(context));
        for (InteractiveCardView.Spec card : interactiveCards) lines.add(card.title);
        return Collections.unmodifiableList(lines);
    }

    /** Legacy zero-context highlight labels for external callers; prefer {@link #highlights(android.content.Context)}. */
    public List<String> frenchHighlights() {
        List<String> lines = new ArrayList<>(ops.size() + interactiveCards.size());
        for (Op op : ops) lines.add(op.targetLabel());
        for (InteractiveCardView.Spec card : interactiveCards) lines.add(card.title);
        return Collections.unmodifiableList(lines);
    }

    /** Parses and validates a Fork PR #15 / Track 9A ui.patch SSE event or REST revision object. */
    public static LiveUiPatch parse(JSONObject raw, boolean nightMode) throws IOException {
        if (raw == null) throw new IOException("Missing patch object");
        String serialized = raw.toString();
        if (exceedsByteLimit(serialized)) {
            warnRejected();
            throw new IOException("Patch exceeded UTF-8 byte limit");
        }
        try {
            JSONObject payload = raw.has("patch") && raw.opt("patch") instanceof JSONObject nested ? nested : raw;
            if (payload.has("reversible") && !payload.optBoolean("reversible", true)) {
                throw new IOException("Non-reversible patches are rejected");
            }
            String revId = payload.optString("revision_id", "");
            String parentId = payload.isNull("parent_revision_id") ? "" : payload.optString("parent_revision_id", "");
            String scope = payload.optString("scope", "thread");
            String summary = payload.optString("summary", "");
            boolean requiresApply = payload.optBoolean("requires_apply", true);
            JSONArray rawOps = payload.optJSONArray("ops");
            List<InteractiveCardView.Spec> cards = parseInteractiveCards(payload.opt("interactive_cards"));
            if ((rawOps == null || rawOps.length() == 0) && cards.isEmpty()) {
                throw new IOException("Missing or oversized ops");
            }
            if (rawOps != null && rawOps.length() > MAX_OPS) {
                throw new IOException("Missing or oversized ops");
            }
            List<Op> parsed = new ArrayList<>(rawOps == null ? 0 : rawOps.length());
            if (rawOps != null) {
                for (int i = 0; i < rawOps.length(); i++) {
                    JSONObject opObj = rawOps.optJSONObject(i);
                    if (opObj == null) throw new IOException("Malformed op entry");
                    parseOp(opObj, nightMode, parsed);
                }
            }
            List<SuggestedAction> suggestions = parseSuggestedActions(payload.opt("suggested_actions"), parsed);
            return new LiveUiPatch(revId, parentId, scope, summary, parsed, suggestions, cards, requiresApply, MAX_OPS * 2);
        } catch (IOException | JSONException | IllegalArgumentException e) {
            warnRejected();
            throw e instanceof IOException io ? io : new IOException("Malformed ui.patch", e);
        }
    }

    public static List<InteractiveCardView.Spec> parseInteractiveCards(Object raw) throws IOException {
        if (raw == null || JSONObject.NULL.equals(raw)) return Collections.emptyList();
        if (!(raw instanceof JSONArray arr) || arr.length() > MAX_INTERACTIVE_CARDS) {
            throw new IOException("Invalid interactive_cards");
        }
        List<InteractiveCardView.Spec> out = new ArrayList<>(arr.length());
        Set<String> seenIds = new HashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.optJSONObject(i);
            if (obj == null) throw new IOException("Malformed interactive card");
            InteractiveCardView.Spec spec;
            try {
                spec = InteractiveCardView.Spec.fromJson(obj);
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Invalid interactive card", invalid);
            }
            if (!seenIds.add(spec.cardId)) throw new IOException("Duplicate interactive card id");
            out.add(spec);
        }
        return Collections.unmodifiableList(out);
    }

    public static List<SuggestedAction> parseSuggestedActions(Object raw) throws IOException {
        return parseSuggestedActions(raw, Collections.emptyList());
    }

    public static List<SuggestedAction> parseSuggestedActions(Object raw, List<Op> ops) throws IOException {
        if (raw == null || JSONObject.NULL.equals(raw)) return Collections.emptyList();
        if (!(raw instanceof JSONArray arr) || arr.length() > MAX_SUGGESTED_ACTIONS) {
            throw new IOException("Invalid suggested_actions");
        }
        String defaultWidgetId = firstWidgetId(ops);
        String defaultSurfacePage = firstCustomSurface(ops);
        List<SuggestedAction> out = new ArrayList<>(arr.length());
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.optJSONObject(i);
            if (obj == null) throw new IOException("Malformed suggested action");
            Set<String> keys = keySet(obj);
            allowOnly(keys, "id", "label", "kind", "prompt", "draft", "action_draft", "page", "target", "action", "widget_id");
            String id = obj.optString("id", "");
            String label = obj.optString("label", "");
            String kind = obj.has("kind") ? obj.optString("kind", "prompt") : null;
            String prompt = obj.has("prompt") ? obj.optString("prompt", "")
                    : obj.has("draft") ? obj.optString("draft", "")
                    : obj.has("action_draft") ? obj.optString("action_draft", "") : null;
            String page = obj.has("page") ? obj.optString("page", "") : null;
            String target = obj.has("target") ? obj.optString("target", "") : null;
            String action = obj.has("action") ? obj.optString("action", "") : null;
            String widgetId = obj.has("widget_id") ? obj.optString("widget_id", "") : null;

            if (kind != null) {
                if (!FORK_SUGGESTED_ACTION_KINDS.contains(kind)) throw new IOException("Unknown suggested action kind");
                switch (kind) {
                    case "pin_widget" -> {
                        String wid = widgetId != null && !widgetId.isEmpty() ? widgetId
                                : (target != null && !target.isEmpty() ? target : defaultWidgetId);
                        if (wid == null || wid.isEmpty()) continue;
                        out.add(new SuggestedAction(id, label, kind, null, null, null, wid));
                    }
                    case "open_surface" -> {
                        String pageId = page != null && !page.isEmpty() ? page
                                : (target != null && !target.isEmpty() ? normalizePageId(target) : defaultSurfacePage);
                        if (pageId == null || pageId.isEmpty()) continue;
                        out.add(new SuggestedAction(id, label, kind, null, pageId, null, null));
                    }
                    case "create_reminder" -> {
                        if (prompt != null && !prompt.isEmpty()) {
                            out.add(new SuggestedAction(id, label, kind, prompt, null, null, null));
                        } else {
                            out.add(new SuggestedAction(id, label, kind, null, null, "reminders", null));
                        }
                    }
                    case "save_memory", "save_skill", "prompt" -> {
                        String effectiveDraft = prompt != null && !prompt.isEmpty() ? prompt : label;
                        out.add(new SuggestedAction(id, label, kind, effectiveDraft, null, null, null));
                    }
                    default -> throw new IOException("Unsupported suggested action kind");
                }
            } else {
                out.add(new SuggestedAction(label, prompt, page, action));
            }
        }
        return out;
    }

    private static String firstWidgetId(List<Op> ops) {
        if (ops == null) return null;
        for (Op op : ops) {
            if (op instanceof Op.UpsertWidget uw) return uw.widget().widgetId;
        }
        return null;
    }

    private static String firstCustomSurface(List<Op> ops) {
        if (ops == null) return null;
        for (Op op : ops) {
            if (op instanceof Op.UpsertSurface up && WorkspaceLayout.custom(up.surface())) return up.surface();
        }
        return null;
    }

    private static void parseOp(JSONObject obj, boolean nightMode, List<Op> out) throws IOException, JSONException {
        String op = obj.optString("op", "");
        Set<String> keys = keySet(obj);
        switch (op) {
            case "upsert_surface" -> {
                if (obj.has("layout")) {
                    allowOnly(keys, "op", "surface", "layout");
                    Object layoutVal = obj.opt("layout");
                    String layoutJson;
                    if (layoutVal instanceof JSONObject lo) layoutJson = lo.toString();
                    else if (layoutVal instanceof String ls) layoutJson = ls;
                    else throw new IOException("Missing layout document");
                    WorkspaceLayout layout = WorkspaceLayout.parse(layoutJson);
                    String surface = obj.optString("surface", layout.surface);
                    if (!surface.equals(layout.surface)) throw new IOException("Surface mismatch");
                    out.add(new Op.UpsertSurface(surface, layoutJson, layout.title));
                } else {
                    // Fork PR #15 canonical upsert_surface payload
                    allowOnly(keys, "op", "surface_id", "scope", "kind", "title", "spec", "order", "tab");
                    String surfaceId = obj.optString("surface_id", "");
                    if (!validSafeId(surfaceId)) throw new IOException("Invalid surface_id");
                    String scope = obj.optString("scope", "atelier");
                    if (!"atelier".equals(scope) && !"thread".equals(scope)) throw new IOException("Invalid surface scope");
                    String kind = obj.optString("kind", "");
                    if (!FORK_SURFACE_KINDS.contains(kind)) throw new IOException("Unsupported surface kind");
                    String title = obj.optString("title", "");
                    if (!validText(title, 160)) throw new IOException("Invalid surface title");
                    String boundedTitle = title.length() > 64 ? title.substring(0, 64).trim() : title;
                    JSONObject spec = obj.optJSONObject("spec");
                    if (spec == null) spec = new JSONObject();
                    String pageId = normalizePageId(surfaceId);
                    String layoutJson = translateForkSurfaceToLayoutJson(pageId, boundedTitle, kind, spec);
                    WorkspaceLayout validated = WorkspaceLayout.parse(layoutJson);
                    out.add(new Op.UpsertSurface(validated.surface, layoutJson, validated.title));
                    if (obj.has("tab") && !obj.isNull("tab")) {
                        String tabId = obj.optString("tab", "");
                        if (!validSafeId(tabId)) throw new IOException("Invalid surface tab");
                        String tabLabel = boundedTitle.length() > 48 ? boundedTitle.substring(0, 48).trim() : boundedTitle;
                        out.add(new Op.UpsertTab(new CustomTab(tabId, tabLabel, validated.surface)));
                    }
                }
            }
            case "remove_surface" -> {
                allowOnly(keys, "op", "surface", "surface_id", "scope");
                String rawSurface = obj.has("surface") ? obj.optString("surface", "") : normalizePageId(obj.optString("surface_id", ""));
                if (!WorkspaceLayout.SURFACES.containsKey(rawSurface) && !WorkspaceLayout.custom(rawSurface)) {
                    throw new IOException("Unknown surface");
                }
                out.add(new Op.RemoveSurface(rawSurface));
            }
            case "upsert_tab" -> {
                allowOnly(keys, "op", "id", "label", "page");
                out.add(new Op.UpsertTab(new CustomTab(
                        obj.optString("id", ""), obj.optString("label", ""), obj.optString("page", ""))));
            }
            case "remove_tab" -> {
                allowOnly(keys, "op", "id");
                String id = obj.optString("id", "");
                if (!validSafeId(id)) throw new IOException("Invalid tab id");
                out.add(new Op.RemoveTab(id));
            }
            case "upsert_quick_action" -> {
                // Accepts both Fork PR #15 (action_id, action_kind, icon, prompt/destination/surface_id) and direct format.
                // Rejects any auto-send / script / url keys strictly.
                allowOnly(keys, "op", "id", "action_id", "label", "action_kind", "icon",
                        "draft", "prompt", "page", "surface_id", "destination");
                String id = obj.has("action_id") ? obj.optString("action_id", "") : obj.optString("id", "");
                String label = obj.optString("label", "");
                String icon = obj.has("icon") && !obj.isNull("icon") ? obj.optString("icon", "") : null;
                String actionKind = obj.has("action_kind") ? obj.optString("action_kind", "") : null;
                if (actionKind != null && !FORK_QUICK_ACTION_KINDS.contains(actionKind)) {
                    throw new IOException("Invalid action_kind");
                }
                String draft = obj.has("draft") ? obj.optString("draft", "")
                        : obj.has("prompt") ? obj.optString("prompt", "") : null;
                String page = obj.has("page") ? obj.optString("page", "")
                        : obj.has("surface_id") ? normalizePageId(obj.optString("surface_id", "")) : null;
                String dest = obj.has("destination") ? obj.optString("destination", "") : null;
                if ("prompt".equals(actionKind)) {
                    if (draft == null || page != null || dest != null) throw new IOException("prompt quick action requires prompt only");
                } else if ("open_destination".equals(actionKind)) {
                    if (dest == null || draft != null || page != null) throw new IOException("open_destination requires destination only");
                } else if ("toggle_surface".equals(actionKind)) {
                    if (page == null || draft != null || dest != null) throw new IOException("toggle_surface requires surface_id only");
                }
                out.add(new Op.UpsertQuickAction(new QuickAction(id, label, draft, page, dest, icon)));
            }
            case "remove_quick_action" -> {
                allowOnly(keys, "op", "id", "action_id");
                String id = obj.has("action_id") ? obj.optString("action_id", "") : obj.optString("id", "");
                if (!validSafeId(id)) throw new IOException("Invalid quick action id");
                out.add(new Op.RemoveQuickAction(id));
            }
            case "set_theme_override" -> {
                JSONObject themeObj = obj.opt("theme") instanceof JSONObject nested ? nested : obj;
                if (themeObj == obj) {
                    allowOnly(keys, "op", "theme", "accent_hex", "accent_ink_hex", "surface_hex", "ink_hex",
                            "density", "font_scale_step", "surface_style");
                } else {
                    allowOnly(keys, "op", "theme");
                    allowOnly(keySet(themeObj), "theme", "accent_hex", "accent_ink_hex", "surface_hex", "ink_hex",
                            "density", "font_scale_step", "surface_style");
                }
                int step = 0;
                if (themeObj.has("font_scale_step")) {
                    Object rawStep = themeObj.opt("font_scale_step");
                    if (!(rawStep instanceof Integer)) throw new IOException("font_scale_step must be an integer");
                    step = (Integer) rawStep;
                }
                String mode = themeObj.opt("theme") instanceof String s ? s : "system";
                ThemePalette.OverrideSpec spec = ThemePalette.OverrideSpec.validated(
                        mode,
                        themeObj.has("accent_hex") ? themeObj.optString("accent_hex", "") : null,
                        themeObj.has("accent_ink_hex") ? themeObj.optString("accent_ink_hex", "") : null,
                        themeObj.has("surface_hex") ? themeObj.optString("surface_hex", "") : null,
                        themeObj.has("ink_hex") ? themeObj.optString("ink_hex", "") : null,
                        themeObj.has("density") ? themeObj.optString("density", "") : "comfortable",
                        step,
                        themeObj.has("surface_style") ? themeObj.optString("surface_style", "") : "paper",
                        nightMode);
                out.add(new Op.SetThemeOverride(spec));
            }
            case "reset_theme", "clear_theme_override" -> {
                allowOnly(keys, "op");
                out.add(new Op.ResetTheme());
            }
            case "upsert_widget" -> {
                allowOnly(keys, "op", "widget_id", "title", "subtitle", "template", "items",
                        "accent_hex", "accent_ink_hex", "home_screen_safe");
                if (!(obj.opt("home_screen_safe") instanceof Boolean safe) || !safe) {
                    throw new IOException("Widget requires home_screen_safe=true");
                }
                JSONArray rawItems = obj.optJSONArray("items");
                if (rawItems == null || rawItems.length() > MAX_WIDGET_ITEMS) {
                    throw new IOException("Invalid widget items");
                }
                List<WidgetItem> items = new ArrayList<>(rawItems.length());
                for (int i = 0; i < rawItems.length(); i++) {
                    JSONObject itemObj = rawItems.optJSONObject(i);
                    if (itemObj == null) throw new IOException("Malformed widget item");
                    allowOnly(keySet(itemObj), "label", "value", "checked", "action_draft");
                    Boolean checked = null;
                    if (itemObj.has("checked") && !itemObj.isNull("checked")) {
                        if (!(itemObj.opt("checked") instanceof Boolean b)) throw new IOException("checked must be boolean");
                        checked = b;
                    }
                    items.add(new WidgetItem(
                            itemObj.optString("label", ""),
                            itemObj.has("value") && !itemObj.isNull("value") ? itemObj.optString("value", "") : "",
                            checked,
                            itemObj.has("action_draft") && !itemObj.isNull("action_draft") ? itemObj.optString("action_draft", "") : null));
                }
                out.add(new Op.UpsertWidget(new WidgetSpec(
                        obj.optString("widget_id", ""),
                        obj.optString("title", ""),
                        obj.has("subtitle") && !obj.isNull("subtitle") ? obj.optString("subtitle", "") : "",
                        obj.optString("template", ""),
                        items,
                        obj.has("accent_hex") && !obj.isNull("accent_hex") ? obj.optString("accent_hex", "") : null,
                        obj.has("accent_ink_hex") && !obj.isNull("accent_ink_hex") ? obj.optString("accent_ink_hex", "") : null,
                        true,
                        nightMode)));
            }
            case "remove_widget" -> {
                allowOnly(keys, "op", "widget_id");
                String id = obj.optString("widget_id", "");
                if (!validSafeId(id)) throw new IOException("Invalid widget_id");
                out.add(new Op.RemoveWidget(id));
            }
            default -> throw new IOException("Unsupported ui.patch op");
        }
    }

    /** Translates a validated Fork PR #15 declarative surface op into a bounded WorkspaceLayout v2 JSON document. */
    private static String translateForkSurfaceToLayoutJson(String pageId, String title, String kind, JSONObject spec)
            throws IOException, JSONException {
        JSONObject block = new JSONObject();
        block.put("title", title);
        switch (kind) {
            case "kpi_grid" -> {
                block.put("type", "metric_grid");
                JSONArray srcItems = spec.optJSONArray("items");
                if (srcItems == null || srcItems.length() == 0) srcItems = spec.optJSONArray("metrics");
                JSONArray items = new JSONArray();
                if (srcItems != null) {
                    for (int i = 0; i < Math.min(srcItems.length(), MetricGridBlock.MAX_ITEMS); i++) {
                        JSONObject m = srcItems.optJSONObject(i);
                        if (m == null) continue;
                        JSONObject item = new JSONObject();
                        item.put("label", trimTo(m.optString("label", title), 48));
                        if (m.has("source") && WorkspaceLayout.SOURCES.contains(m.optString("source"))) {
                            item.put("source", m.optString("source"));
                        } else {
                            item.put("value", trimTo(m.optString("value", "—"), 48));
                        }
                        if (m.has("delta") && !m.optString("delta").isEmpty()) item.put("delta", trimTo(m.optString("delta"), 24));
                        if (m.has("unit") && !m.optString("unit").isEmpty()) item.put("unit", trimTo(m.optString("unit"), 16));
                        if (m.has("tone") && MetricGridBlock.TONES.contains(m.optString("tone"))) item.put("tone", m.optString("tone"));
                        if (m.has("provenance") && !m.optString("provenance").isEmpty()) item.put("provenance", trimTo(m.optString("provenance"), 80));
                        items.put(item);
                    }
                }
                if (items.length() == 0) items.put(new JSONObject().put("label", title).put("value", "—"));
                block.put("items", items);
            }
            case "sparkline", "bar_chart" -> {
                block.put("type", "sparkline");
                block.put("style", "bar_chart".equals(kind) ? "bar" : spec.optString("style", "line"));
                JSONArray vals = spec.optJSONArray("values");
                if (vals == null) vals = spec.optJSONArray("series");
                if (vals == null || vals.length() < SparklineView.MIN_POINTS) {
                    throw new IOException("sparkline/bar_chart spec requires at least 2 numeric values");
                }
                block.put("values", vals);
                if (spec.has("unit") && !spec.optString("unit").isEmpty()) block.put("unit", trimTo(spec.optString("unit"), 16));
                if (spec.has("caption") && !spec.optString("caption").isEmpty()) block.put("caption", trimTo(spec.optString("caption"), 120));
                if (spec.has("target") && spec.opt("target") instanceof Number) block.put("target", spec.getDouble("target"));
            }
            case "table" -> {
                block.put("type", "table");
                JSONArray cols = spec.optJSONArray("columns");
                JSONArray rows = spec.optJSONArray("rows");
                if (cols == null || rows == null) throw new IOException("table spec requires columns and rows");
                block.put("columns", cols);
                block.put("rows", rows);
                if (spec.has("sortable")) block.put("sortable", spec.optBoolean("sortable", true));
                if (spec.has("filterable")) block.put("filterable", spec.optBoolean("filterable", false));
                if (spec.has("prompt") && !spec.optString("prompt").isEmpty()) block.put("prompt", trimTo(spec.optString("prompt"), 240));
            }
            case "form" -> {
                block.put("type", "form");
                block.put("prompt", trimTo(spec.optString("prompt", title), 240));
                JSONArray fields = spec.optJSONArray("fields");
                if (fields == null) throw new IOException("form spec requires fields");
                block.put("fields", fields);
            }
            case "toggle_group", "chip_filter", "slider", "progress_tracker" -> {
                block.put("type", "controls");
                if (spec.has("state_key")) block.put("state_key", spec.optString("state_key"));
                if (spec.has("prompt") && !spec.optString("prompt").isEmpty()) block.put("prompt", trimTo(spec.optString("prompt"), 240));
                JSONArray items = spec.optJSONArray("items");
                if (items == null) items = spec.optJSONArray("controls");
                if (items == null || items.length() == 0) {
                    if ("slider".equals(kind)) {
                        JSONObject slider = new JSONObject()
                                .put("id", "valeur")
                                .put("label", trimTo(spec.optString("label", title), 64))
                                .put("kind", "slider")
                                .put("min", spec.optDouble("min", 0))
                                .put("max", spec.optDouble("max", 100));
                        if (spec.has("step")) slider.put("step", spec.optDouble("step", 1));
                        if (spec.has("default")) slider.put("default", spec.optDouble("default", 0));
                        if (spec.has("unit") && !spec.optString("unit").isEmpty()) slider.put("unit", trimTo(spec.optString("unit"), 16));
                        items = new JSONArray().put(slider);
                    } else if ("chip_filter".equals(kind) && spec.optJSONArray("options") != null) {
                        JSONObject chips = new JSONObject()
                                .put("id", "filtre")
                                .put("label", trimTo(spec.optString("label", title), 64))
                                .put("kind", "chips")
                                .put("options", spec.getJSONArray("options"))
                                .put("multi", spec.optBoolean("multi", false));
                        items = new JSONArray().put(chips);
                    } else {
                        throw new IOException(kind + " spec requires items");
                    }
                }
                block.put("items", items);
            }
            case "map_compass" -> {
                block.remove("title");
                block.put("type", "nearby_places");
                String cat = spec.optString("category", NearbyCategory.RESTAURANT.id);
                block.put("category", NearbyCategory.valid(cat) ? cat : NearbyCategory.RESTAURANT.id);
                if (spec.has("radius_m")) block.put("radius_m", spec.optInt("radius_m", NearbyCategory.DEFAULT_RADIUS_M));
                if (spec.has("limit")) block.put("limit", spec.optInt("limit", NearbyCategory.DEFAULT_LIMIT));
            }
            case "markdown_card", "action_bar" -> {
                block.put("type", "section");
                String note = spec.optString("body", spec.optString("note", spec.optString("text", "")));
                if (!note.isEmpty()) block.put("note", trimTo(note, 240));
                JSONArray acts = spec.optJSONArray("actions");
                if (acts != null && acts.length() > 0) block.put("actions", acts);
                else if (note.isEmpty()) block.put("note", title);
            }
            default -> throw new IOException("Unsupported surface kind");
        }
        return new JSONObject()
                .put("version", 2)
                .put("surface", pageId)
                .put("mode", "replace")
                .put("title", title)
                .put("blocks", new JSONArray().put(block))
                .toString();
    }

    static String normalizePageId(String raw) throws IOException {
        if (raw == null || raw.isEmpty()) throw new IOException("Missing surface_id");
        if (WorkspaceLayout.SURFACES.containsKey(raw) || WorkspaceLayout.custom(raw)) return raw;
        String slug = raw.startsWith("page/") ? raw.substring(5) : raw;
        if (slug.length() > 36) slug = slug.substring(0, 36);
        if (!slug.isEmpty() && Character.isDigit(slug.charAt(0))) slug = "s_" + (slug.length() > 34 ? slug.substring(0, 34) : slug);
        if (!DraftForm.validId(slug)) throw new IOException("Invalid surface slug");
        return "page/" + slug;
    }

    private static String trimTo(String raw, int max) {
        String s = raw == null ? "" : raw.trim().replace('\n', ' ').replace('\r', ' ');
        return s.length() > max ? s.substring(0, max).trim() : s;
    }

    private static Set<String> keySet(JSONObject obj) {
        Set<String> keys = new HashSet<>();
        java.util.Iterator<String> it = obj.keys();
        while (it.hasNext()) keys.add(it.next());
        return keys;
    }

    private static void allowOnly(Set<String> keys, String... allowed) throws IOException {
        if (!Arrays.asList(allowed).containsAll(keys)) {
            throw new IOException("Unexpected field in ui.patch op");
        }
    }

    static boolean containsSecret(String s) {
        return s != null && WIDGET_SECRET.matcher(s).find();
    }

    static boolean validText(String s, int max) {
        if (s == null || s.isEmpty() || s.length() > max) return false;
        if (FORBIDDEN_PAYLOAD.matcher(s).find()) return false;
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
        Diagnostics.warning("live_ui", "patch_rejected");
    }

    private static void warnStateUnreadable() {
        Diagnostics.warning("live_ui", "state_unreadable");
    }

    /**
     * Profile-scoped persistent state for live UI edits: custom Atelier tabs, custom composer quick-action chips,
     * widgets, live theme override, and atomic rollback history. Fails closed on storage corruption.
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
        private boolean corrupted;

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
                // Propagate LayoutHistory.Unreadable / PageLayouts.Damaged so damaged records are never overwritten silently.
                @Override public String readCurrent(String surface) {
                    return new LayoutHistory(account, surface).current();
                }
                @Override public void replace(String surface, String revisionId, String summary, String nextRaw) {
                    new LayoutHistory(account, surface).replaceWithRevision(revisionId, summary, nextRaw);
                }
            };
            return new StateManager(backing, adapter, nightMode);
        }

        public boolean isCorrupted() { return corrupted; }
        public List<CustomTab> tabs() { return Collections.unmodifiableList(new ArrayList<>(tabs)); }
        public List<QuickAction> quickActions() { return Collections.unmodifiableList(new ArrayList<>(quickActions)); }
        public List<WidgetSpec> widgets() { return Collections.unmodifiableList(new ArrayList<>(widgets)); }
        public ThemePalette.OverrideSpec theme() { return theme; }
        public List<AppliedRevision> history() { return Collections.unmodifiableList(new ArrayList<>(history)); }
        public boolean canRollback() { return !corrupted && !history.isEmpty(); }

        /** Explicit user-confirmed recovery of a corrupted workspace.live_ui.v2 record. */
        public synchronized void resetCorrupted() {
            store.remove(STATE_KEY);
            store.remove("workspace.theme.v2");
            tabs.clear();
            quickActions.clear();
            widgets.clear();
            history.clear();
            theme = ThemePalette.OverrideSpec.defaults();
            corrupted = false;
        }

        /** Atomically applies all validated ops and records a full rollback snapshot. Refuses if state is corrupted. */
        public synchronized void apply(LiveUiPatch patch) throws IOException {
            Objects.requireNonNull(patch);
            if (corrupted) throw new IOException("Live UI state is corrupted; explicit reset required");
            List<CustomTab> nextTabs = new ArrayList<>(tabs);
            List<QuickAction> nextActions = new ArrayList<>(quickActions);
            List<WidgetSpec> nextWidgets = new ArrayList<>(widgets);
            ThemePalette.OverrideSpec nextTheme = theme;
            Map<String, String> surfaceWrites = new LinkedHashMap<>();
            Map<String, String> priorSurfaces = new LinkedHashMap<>();

            // Stage & validate all bounds (and verify existing surface records are readable) before mutating anything.
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

            List<String> writtenSurfaces = new ArrayList<>(surfaceWrites.size());
            try {
                for (Map.Entry<String, String> write : surfaceWrites.entrySet()) {
                    surfaces.replace(write.getKey(), patch.revisionId, patch.summary, write.getValue());
                    writtenSurfaces.add(write.getKey());
                }
            } catch (RuntimeException failure) {
                for (int i = writtenSurfaces.size() - 1; i >= 0; i--) {
                    String surface = writtenSurfaces.get(i);
                    try {
                        surfaces.replace(surface, "rollback:" + patch.revisionId,
                                patch.summary, priorSurfaces.get(surface));
                    } catch (RuntimeException ignored) {
                        warnStateUnreadable();
                    }
                }
                throw failure;
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
            if (corrupted) throw new IOException("Live UI state is corrupted; explicit reset required");
            if (history.isEmpty()) return null;
            AppliedRevision last = history.remove(history.size() - 1);
            restoreSnapshot(last);
            persist();
            return last;
        }

        /** Atomic rollback to the state right before the specified revisionId, returning all popped revisions newest-first. */
        public synchronized List<AppliedRevision> rollbackAllTo(String revisionId) throws IOException {
            if (corrupted) throw new IOException("Live UI state is corrupted; explicit reset required");
            int target = -1;
            for (int i = history.size() - 1; i >= 0; i--) {
                if (history.get(i).revisionId.equals(revisionId)) { target = i; break; }
            }
            if (target < 0) return Collections.emptyList();
            List<AppliedRevision> popped = new ArrayList<>(history.size() - target);
            while (history.size() > target) {
                AppliedRevision reverted = history.remove(history.size() - 1);
                restoreSnapshot(reverted);
                popped.add(reverted);
            }
            persist();
            return Collections.unmodifiableList(popped);
        }

        /** Atomic rollback to the state right before the specified revisionId (reverting it and any later patches). */
        public synchronized AppliedRevision rollbackTo(String revisionId) throws IOException {
            List<AppliedRevision> popped = rollbackAllTo(revisionId);
            return popped.isEmpty() ? null : popped.get(popped.size() - 1);
        }

        /** Collects every widget ID present in current state (helper for broadcasting HomePinBridge refreshes). */
        public synchronized Set<String> widgetIds() {
            Set<String> ids = new HashSet<>();
            for (WidgetSpec w : widgets) ids.add(w.widgetId);
            return ids;
        }

        private void restoreSnapshot(AppliedRevision snapshot) {
            for (Map.Entry<String, String> entry : snapshot.previousSurfaces.entrySet()) {
                surfaces.replace(entry.getKey(), "revert:" + snapshot.revisionId,
                        snapshot.summary, entry.getValue());
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
            corrupted = false;
            String raw;
            try { raw = store.read(STATE_KEY); }
            catch (RuntimeException unreadable) {
                corrupted = true;
                warnStateUnreadable();
                return;
            }
            if (raw == null || raw.isEmpty()) return;
            try {
                decodeState(raw);
            } catch (IOException e) {
                tabs.clear();
                quickActions.clear();
                widgets.clear();
                history.clear();
                theme = ThemePalette.OverrideSpec.defaults();
                corrupted = true;
                warnStateUnreadable();
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
                        .append('\t').append(esc(a.page == null ? "" : a.page))
                        .append('\t').append(esc(a.destination == null ? "" : a.destination))
                        .append('\t').append(esc(a.icon == null ? "" : a.icon)).append('\n');
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
                        if ((parts.length != 5 && parts.length != 7) || quickActions.size() >= MAX_QUICK_ACTIONS) {
                            throw new IOException("Bad action row");
                        }
                        String draft = unesc(parts[3]);
                        String page = unesc(parts[4]);
                        String dest = parts.length == 7 ? unesc(parts[5]) : "";
                        String icon = parts.length == 7 ? unesc(parts[6]) : "";
                        quickActions.add(new QuickAction(unesc(parts[1]), unesc(parts[2]),
                                draft.isEmpty() ? null : draft, page.isEmpty() ? null : page,
                                dest.isEmpty() ? null : dest, icon.isEmpty() ? null : icon));
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
                String checkedStr = item.checked == null ? "" : (item.checked ? "1" : "0");
                items.append(esc(item.label)).append(':').append(esc(item.value)).append(':')
                        .append(checkedStr).append(':').append(esc(item.actionDraft == null ? "" : item.actionDraft));
            }
            return esc(w.widgetId) + "|" + esc(w.title) + "|" + esc(w.subtitle) + "|" + esc(w.template)
                    + "|" + esc(w.accentHex == null ? "" : w.accentHex)
                    + "|" + esc(w.accentInkHex == null ? "" : w.accentInkHex)
                    + "|" + esc(items.toString());
        }

        private static WidgetSpec decodeWidget(String raw, boolean nightMode) throws IOException {
            String[] p = raw.split("\\|", -1);
            if (p.length != 6 && p.length != 7) throw new IOException("Bad widget encoding");
            boolean hasInk = p.length == 7;
            String accent = unesc(p[4]);
            String accentInk = hasInk ? unesc(p[5]) : "";
            String itemsRaw = unesc(p[hasInk ? 6 : 5]);
            List<WidgetItem> items = new ArrayList<>();
            if (!itemsRaw.isEmpty()) {
                for (String itemPart : itemsRaw.split(",", -1)) {
                    String[] f = itemPart.split(":", -1);
                    if (f.length == 3) {
                        String draft = unesc(f[2]);
                        items.add(new WidgetItem(unesc(f[0]), unesc(f[1]), null, draft.isEmpty() ? null : draft));
                    } else if (f.length == 4) {
                        Boolean checked = f[2].isEmpty() ? null : "1".equals(f[2]);
                        String draft = unesc(f[3]);
                        items.add(new WidgetItem(unesc(f[0]), unesc(f[1]), checked, draft.isEmpty() ? null : draft));
                    } else {
                        throw new IOException("Bad widget item encoding");
                    }
                }
            }
            return new WidgetSpec(unesc(p[0]), unesc(p[1]), unesc(p[2]), unesc(p[3]),
                    items, accent.isEmpty() ? null : accent, accentInk.isEmpty() ? null : accentInk, true, nightMode);
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
            for (String part : raw.split(";", -1)) list.add(decodeWidget(unesc(part), nightMode));
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
                        .append(esc(a.page == null ? "" : a.page)).append('|')
                        .append(esc(a.destination == null ? "" : a.destination)).append('|')
                        .append(esc(a.icon == null ? "" : a.icon));
            }
            return sb.toString();
        }

        private static List<QuickAction> decodeActions(String raw) throws IOException {
            List<QuickAction> list = new ArrayList<>();
            if (raw.isEmpty()) return list;
            for (String item : raw.split(";", -1)) {
                String[] p = item.split("\\|", -1);
                if (p.length != 4 && p.length != 6) throw new IOException("Bad quick action snapshot");
                String draft = unesc(p[2]);
                String page = unesc(p[3]);
                String dest = p.length == 6 ? unesc(p[4]) : "";
                String icon = p.length == 6 ? unesc(p[5]) : "";
                list.add(new QuickAction(unesc(p[0]), unesc(p[1]),
                        draft.isEmpty() ? null : draft, page.isEmpty() ? null : page,
                        dest.isEmpty() ? null : dest, icon.isEmpty() ? null : icon));
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
                try { sb.append((char) Integer.parseInt(hex, 16)); }
                catch (NumberFormatException e) { throw new IOException("Invalid escape", e); }
                i += 2;
            }
            return sb.toString();
        }
    }
}
