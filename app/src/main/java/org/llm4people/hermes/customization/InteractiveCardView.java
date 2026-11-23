package org.llm4people.hermes.customization;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Native Android 16 interactive inline card renderer matching Track 9A's {@code interactive_cards}
 * UI-patch contract. Supports bounded checklists, Editable-Draft-Rule quick picks, confirmation
 * toggles, and live {@link AppCustomization} preview cards with Apply/Undo.
 * Pure native Android views, strict &gt;= 48dp touch targets, and crisp Day/Night contrast.
 */
public final class InteractiveCardView extends LinearLayout {
    public static final int MAX_CHECKLIST_ITEMS = 8;
    public static final int MAX_QUICK_PICKS = 6;
    public static final int MAX_TOGGLES = 6;
    public static final int MAX_TITLE_CHARS = 72;
    public static final int MAX_SUBTITLE_CHARS = 140;
    public static final int MAX_LABEL_CHARS = 72;
    public static final int MAX_DRAFT_CHARS = 240;

    private static final Pattern ITEM_ID = Pattern.compile("[A-Za-z0-9._-]{1,48}");
    private static volatile Typeface mediumTypeface;

    private static Typeface mediumTypeface() {
        Typeface cached = mediumTypeface;
        if (cached == null) {
            cached = Typeface.create("sans-serif-medium", Typeface.NORMAL);
            mediumTypeface = cached;
        }
        return cached;
    }

    public enum Kind {
        CHECKLIST("checklist"),
        QUICK_PICKS("quick_pick"),
        TOGGLES("toggle_confirm"),
        CUSTOMIZATION_PREVIEW("customization_preview");

        public final String wire;

        Kind(String wire) { this.wire = wire; }

        public static Kind parse(String wire) {
            if (wire == null || wire.trim().isEmpty()) return null;
            return switch (wire.trim().toLowerCase(Locale.ROOT)) {
                case "checklist" -> CHECKLIST;
                case "quick_pick", "quick_picks" -> QUICK_PICKS;
                case "toggle_confirm", "toggles" -> TOGGLES;
                case "customization_preview" -> CUSTOMIZATION_PREVIEW;
                default -> null;
            };
        }
    }

    public record ChecklistItem(String id, String label, boolean checked) {
        public ChecklistItem {
            id = validateId(id);
            label = requireText(label, MAX_LABEL_CHARS, "Checklist label");
        }
    }

    public record QuickPick(String id, String label, String draftText) {
        public QuickPick {
            id = validateId(id);
            label = requireText(label, MAX_LABEL_CHARS, "Quick-pick label");
            draftText = AppCustomization.sanitizePlainText(draftText == null ? label : draftText, MAX_DRAFT_CHARS);
            if (draftText.isEmpty()) draftText = label;
        }
    }

    public record ToggleItem(String id, String label, String description, boolean checked) {
        public ToggleItem {
            id = validateId(id);
            label = requireText(label, MAX_LABEL_CHARS, "Toggle label");
            description = AppCustomization.sanitizePlainText(description, MAX_SUBTITLE_CHARS);
        }
    }

    /** Pure-Java validated specification for an inline interactive card. */
    public static final class Spec {
        public final String cardId;
        public final Kind kind;
        public final String title;
        public final String subtitle;
        public final List<ChecklistItem> checklist;
        public final List<QuickPick> quickPicks;
        public final List<ToggleItem> toggles;
        public final AppCustomization proposedCustomization;

        public Spec(
                String cardId,
                Kind kind,
                String title,
                String subtitle,
                List<ChecklistItem> checklist,
                List<QuickPick> quickPicks,
                List<ToggleItem> toggles,
                AppCustomization proposedCustomization) {
            this.cardId = validateId(cardId);
            this.kind = Objects.requireNonNull(kind, "kind");
            this.title = requireText(title, MAX_TITLE_CHARS, "Card title");
            this.subtitle = AppCustomization.sanitizePlainText(subtitle, MAX_SUBTITLE_CHARS);
            this.checklist = boundedCopy(checklist, MAX_CHECKLIST_ITEMS);
            this.quickPicks = boundedCopy(quickPicks, MAX_QUICK_PICKS);
            this.toggles = boundedCopy(toggles, MAX_TOGGLES);
            this.proposedCustomization = proposedCustomization;
            if (kind == Kind.CUSTOMIZATION_PREVIEW && proposedCustomization == null) {
                throw new IllegalArgumentException("Customization preview requires proposed customization");
            }
        }

        public static Spec checklist(String cardId, String title, String subtitle, List<ChecklistItem> items) {
            return new Spec(cardId, Kind.CHECKLIST, title, subtitle, items, null, null, null);
        }

        public static Spec quickPicks(String cardId, String title, String subtitle, List<QuickPick> picks) {
            return new Spec(cardId, Kind.QUICK_PICKS, title, subtitle, null, picks, null, null);
        }

        public static Spec toggles(String cardId, String title, String subtitle, List<ToggleItem> items) {
            return new Spec(cardId, Kind.TOGGLES, title, subtitle, null, null, items, null);
        }

        public static Spec customizationPreview(
                String cardId, String title, String subtitle, AppCustomization proposed) {
            return new Spec(cardId, Kind.CUSTOMIZATION_PREVIEW, title, subtitle, null, null, null, proposed);
        }

        /** Pure-Java fail-closed parser for host-JVM and map-based callers. */
        @SuppressWarnings("unchecked")
        public static Spec fromMap(Map<String, Object> raw) {
            if (raw == null) throw new IllegalArgumentException("Missing card object");
            String id = str(raw.get("card_id"));
            Kind kind = Kind.parse(str(raw.get("kind")));
            if (kind == null) throw new IllegalArgumentException("Unknown card kind");
            String title = str(raw.get("title"));
            String subtitle = str(raw.get("subtitle"));
            return switch (kind) {
                case CHECKLIST -> {
                    List<ChecklistItem> items = new ArrayList<>();
                    Object rawItems = raw.get("items");
                    if (rawItems instanceof List<?> list) {
                        for (Object entry : list) {
                            if (items.size() >= MAX_CHECKLIST_ITEMS) break;
                            if (!(entry instanceof Map<?, ?> m)) continue;
                            items.add(new ChecklistItem(
                                    str(m.get("id")), str(m.get("label")), bool(m.get("checked"))));
                        }
                    }
                    yield checklist(id, title, subtitle, items);
                }
                case QUICK_PICKS -> {
                    List<QuickPick> picks = new ArrayList<>();
                    Object rawPicks = raw.get("choices");
                    if (rawPicks instanceof List<?> list) {
                        for (Object entry : list) {
                            if (picks.size() >= MAX_QUICK_PICKS) break;
                            if (!(entry instanceof Map<?, ?> m)) continue;
                            picks.add(new QuickPick(
                                    str(m.get("id")), str(m.get("label")), str(m.get("draft_text"))));
                        }
                    }
                    yield quickPicks(id, title, subtitle, picks);
                }
                case TOGGLES -> {
                    List<ToggleItem> items = new ArrayList<>();
                    Object rawToggles = raw.get("toggles");
                    if (rawToggles instanceof List<?> list) {
                        for (Object entry : list) {
                            if (items.size() >= MAX_TOGGLES) break;
                            if (!(entry instanceof Map<?, ?> m)) continue;
                            items.add(new ToggleItem(
                                    str(m.get("id")), str(m.get("label")),
                                    str(m.get("description")), bool(m.get("checked"))));
                        }
                    }
                    yield toggles(id, title, subtitle, items);
                }
                case CUSTOMIZATION_PREVIEW -> {
                    Object rawCust = raw.get("customization");
                    if (!(rawCust instanceof Map<?, ?> m)) {
                        throw new IllegalArgumentException("Missing customization block");
                    }
                    AppCustomization proposed = parseCustomizationMap((Map<String, Object>) m);
                    yield customizationPreview(id, title, subtitle, proposed);
                }
            };
        }

        /** Android JSONObject fail-closed parser matching Track 9A wire payloads. */
        public static Spec fromJson(JSONObject obj) {
            if (obj == null) throw new IllegalArgumentException("Missing card JSON");
            String id = obj.optString("card_id", "");
            Kind kind = Kind.parse(obj.optString("kind", ""));
            if (kind == null) throw new IllegalArgumentException("Unknown card kind");
            String title = obj.optString("title", "");
            String subtitle = obj.optString("subtitle", "");
            return switch (kind) {
                case CHECKLIST -> {
                    List<ChecklistItem> items = new ArrayList<>();
                    JSONArray arr = obj.optJSONArray("items");
                    if (arr != null) {
                        for (int i = 0; i < arr.length() && items.size() < MAX_CHECKLIST_ITEMS; i++) {
                            JSONObject item = arr.optJSONObject(i);
                            if (item == null) continue;
                            items.add(new ChecklistItem(
                                    item.optString("id", ""),
                                    item.optString("label", ""),
                                    item.optBoolean("checked", false)));
                        }
                    }
                    yield checklist(id, title, subtitle, items);
                }
                case QUICK_PICKS -> {
                    List<QuickPick> picks = new ArrayList<>();
                    JSONArray arr = obj.optJSONArray("choices");
                    if (arr != null) {
                        for (int i = 0; i < arr.length() && picks.size() < MAX_QUICK_PICKS; i++) {
                            JSONObject item = arr.optJSONObject(i);
                            if (item == null) continue;
                            picks.add(new QuickPick(
                                    item.optString("id", ""),
                                    item.optString("label", ""),
                                    item.optString("draft_text", "")));
                        }
                    }
                    yield quickPicks(id, title, subtitle, picks);
                }
                case TOGGLES -> {
                    List<ToggleItem> items = new ArrayList<>();
                    JSONArray arr = obj.optJSONArray("toggles");
                    if (arr != null) {
                        for (int i = 0; i < arr.length() && items.size() < MAX_TOGGLES; i++) {
                            JSONObject item = arr.optJSONObject(i);
                            if (item == null) continue;
                            items.add(new ToggleItem(
                                    item.optString("id", ""),
                                    item.optString("label", ""),
                                    item.optString("description", ""),
                                    item.optBoolean("checked", false)));
                        }
                    }
                    yield toggles(id, title, subtitle, items);
                }
                case CUSTOMIZATION_PREVIEW -> {
                    JSONObject cust = obj.optJSONObject("customization");
                    if (cust == null) throw new IllegalArgumentException("Missing customization JSON");
                    AppCustomization base = AppCustomization.defaults();
                    String paletteRaw = cust.optString("palette", base.palette.wire);
                    AppCustomization.PalettePreset preset = AppCustomization.PalettePreset.parse(paletteRaw);
                    if (preset == null) throw new IllegalArgumentException("Invalid palette");
                    String customHex = cust.optString("custom_accent_hex", "");
                    AppCustomization.Density density = AppCustomization.Density.parse(
                            cust.optString("density", base.density.wire));
                    if (density == null) throw new IllegalArgumentException("Invalid density");
                    float delta = (float) cust.optDouble("font_scale_delta", base.fontScaleDelta);
                    AppCustomization.StartupTab tab = AppCustomization.StartupTab.parse(
                            cust.optString("startup_tab", base.startupTab.wire));
                    if (tab == null) throw new IllegalArgumentException("Invalid startup tab");
                    String greeting = cust.optString("companion_greeting", "");
                    yield customizationPreview(id, title, subtitle, new AppCustomization(
                            preset, customHex.isEmpty() ? null : customHex, density, delta, tab, greeting, null));
                }
            };
        }

        private static AppCustomization parseCustomizationMap(Map<String, Object> m) {
            AppCustomization base = AppCustomization.defaults();
            String paletteWire = str(m.get("palette"));
            AppCustomization.PalettePreset preset = paletteWire.isEmpty()
                    ? base.palette : AppCustomization.PalettePreset.parse(paletteWire);
            if (preset == null) throw new IllegalArgumentException("Invalid palette");
            String customHex = str(m.get("custom_accent_hex"));
            String densityWire = str(m.get("density"));
            AppCustomization.Density density = densityWire.isEmpty()
                    ? base.density : AppCustomization.Density.parse(densityWire);
            if (density == null) throw new IllegalArgumentException("Invalid density");
            float delta = m.get("font_scale_delta") instanceof Number n ? n.floatValue() : base.fontScaleDelta;
            String tabWire = str(m.get("startup_tab"));
            AppCustomization.StartupTab tab = tabWire.isEmpty()
                    ? base.startupTab : AppCustomization.StartupTab.parse(tabWire);
            if (tab == null) throw new IllegalArgumentException("Invalid startup tab");
            String greeting = str(m.get("companion_greeting"));
            return new AppCustomization(preset, customHex.isEmpty() ? null : customHex,
                    density, delta, tab, greeting, null);
        }

        private static <T> List<T> boundedCopy(List<T> items, int max) {
            if (items == null || items.isEmpty()) return Collections.emptyList();
            List<T> out = new ArrayList<>(Math.min(items.size(), max));
            for (T item : items) {
                if (item == null || out.size() >= max) break;
                out.add(item);
            }
            return Collections.unmodifiableList(out);
        }
    }

    public interface Callbacks {
        default void onChecklistChanged(String cardId, String itemId, boolean checked) {}
        /** Strictly Editable Draft Rule: stages the draft into the composer without auto-sending. */
        default void onQuickPickDraft(String cardId, String choiceId, String draftText) {}
        default void onToggleChanged(String cardId, String toggleId, boolean checked) {}
        default void onApplyCustomization(String cardId, AppCustomization proposed) {}
        default void onUndoCustomization(String cardId) {}
    }

    private final Spec spec;
    private final Callbacks callbacks;

    public InteractiveCardView(Context context, Spec spec, Callbacks callbacks) {
        super(Objects.requireNonNull(context, "context"));
        this.spec = Objects.requireNonNull(spec, "spec");
        this.callbacks = callbacks == null ? new Callbacks() {} : callbacks;
        setOrientation(VERTICAL);
        applyCardSurface(context);
        Ui.pad(this, 16, 14);
        renderCard(context);
    }

    public Spec spec() {
        return spec;
    }

    private void applyCardSurface(Context context) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(context.getColor(R.color.surface));
        bg.setCornerRadius(Ui.dp(context, 14));
        bg.setStroke(Math.max(1, Ui.dp(context, 1)), context.getColor(R.color.line));
        setBackground(bg);
    }

    private void renderCard(Context context) {
        TextView titleView = Ui.text(context, spec.title, 16);
        titleView.setTypeface(mediumTypeface());
        titleView.setAccessibilityHeading(true);
        addView(titleView);

        if (!spec.subtitle.isEmpty()) {
            TextView sub = Ui.text(context, spec.subtitle, 13);
            sub.setTextColor(context.getColor(R.color.muted));
            LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(context, 4);
            addView(sub, lp);
        }

        switch (spec.kind) {
            case CHECKLIST -> renderChecklist(context);
            case QUICK_PICKS -> renderQuickPicks(context);
            case TOGGLES -> renderToggles(context);
            case CUSTOMIZATION_PREVIEW -> renderCustomizationPreview(context);
        }
    }

    private void renderChecklist(Context context) {
        int touchMin = Ui.dp(context, 48);
        for (ChecklistItem item : spec.checklist) {
            CheckBox box = new CheckBox(context);
            box.setText(item.label());
            box.setTextSize(15);
            box.setTextColor(context.getColor(R.color.ink));
            box.setChecked(item.checked());
            box.setMinHeight(touchMin);
            box.setMinimumHeight(touchMin);
            box.setMinWidth(touchMin);
            box.setMinimumWidth(touchMin);
            box.setTag("checklist:" + item.id());
            box.setOnCheckedChangeListener((btn, checked) -> {
                Diagnostics.debug("card", "checklist_toggled");
                callbacks.onChecklistChanged(spec.cardId, item.id(), checked);
            });
            LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(context, 4);
            addView(box, lp);
        }
    }

    private void renderQuickPicks(Context context) {
        int touchMin = Ui.dp(context, 48);
        for (QuickPick pick : spec.quickPicks) {
            Button chip = new Button(context, null, android.R.attr.borderlessButtonStyle);
            chip.setText(pick.label());
            chip.setAllCaps(false);
            chip.setTextSize(14);
            chip.setTypeface(mediumTypeface());
            chip.setTextColor(context.getColor(R.color.accent));
            chip.setMinHeight(touchMin);
            chip.setMinimumHeight(touchMin);
            chip.setMinWidth(touchMin);
            chip.setMinimumWidth(touchMin);
            chip.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            Ui.pad(chip, 14, 8);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(context.getColor(R.color.accent_surface));
            bg.setCornerRadius(Ui.dp(context, 10));
            chip.setBackground(bg);
            chip.setTag("pick:" + pick.id());
            chip.setOnClickListener(v -> {
                Diagnostics.event("card", "quick_pick_draft");
                callbacks.onQuickPickDraft(spec.cardId, pick.id(), pick.draftText());
            });
            LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(context, 8);
            addView(chip, lp);
        }
    }

    private void renderToggles(Context context) {
        int touchMin = Ui.dp(context, 48);
        for (ToggleItem item : spec.toggles) {
            LinearLayout row = Ui.column(context);
            Switch sw = new Switch(context);
            sw.setText(item.label());
            sw.setTextSize(15);
            sw.setTextColor(context.getColor(R.color.ink));
            sw.setChecked(item.checked());
            sw.setMinHeight(touchMin);
            sw.setMinimumHeight(touchMin);
            sw.setMinWidth(touchMin);
            sw.setMinimumWidth(touchMin);
            sw.setTag("toggle:" + item.id());
            sw.setOnCheckedChangeListener((btn, checked) -> {
                Diagnostics.debug("card", "toggle_changed");
                callbacks.onToggleChanged(spec.cardId, item.id(), checked);
            });
            row.addView(sw, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            if (!item.description().isEmpty()) {
                TextView desc = Ui.text(context, item.description(), 13);
                desc.setTextColor(context.getColor(R.color.muted));
                row.addView(desc);
            }
            LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(context, 6);
            addView(row, lp);
        }
    }

    private void renderCustomizationPreview(Context context) {
        AppCustomization proposed = spec.proposedCustomization;
        boolean night = (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int accentArgb = night ? proposed.darkAccentArgb() : proposed.lightAccentArgb();
        int onAccentArgb = AppCustomization.onAccentArgb(accentArgb);

        LinearLayout preview = Ui.row(context);
        Ui.pad(preview, 12, 10);
        GradientDrawable swatchBg = new GradientDrawable();
        swatchBg.setColor(context.getColor(R.color.paper));
        swatchBg.setCornerRadius(Ui.dp(context, 10));
        swatchBg.setStroke(Math.max(1, Ui.dp(context, 1)), context.getColor(R.color.line));
        preview.setBackground(swatchBg);

        View swatch = new View(context);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(accentArgb);
        swatch.setBackground(dot);
        int dotSize = Ui.dp(context, 28);
        LayoutParams dotLp = new LayoutParams(dotSize, dotSize);
        dotLp.setMarginEnd(Ui.dp(context, 12));
        swatch.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        preview.addView(swatch, dotLp);

        LinearLayout details = Ui.column(context);
        details.addView(Ui.text(context,
                context.getString(R.string.card_preview_palette, paletteLabel(context, proposed.palette)), 14));
        TextView densityLine = Ui.text(context,
                context.getString(R.string.card_preview_density, densityLabel(context, proposed.density)), 13);
        densityLine.setTextColor(context.getColor(R.color.muted));
        details.addView(densityLine);
        if (!proposed.companionGreeting.isEmpty()) {
            TextView greetingLine = Ui.text(context,
                    context.getString(R.string.card_preview_greeting, proposed.companionGreeting), 13);
            greetingLine.setTextColor(context.getColor(R.color.muted));
            details.addView(greetingLine);
        }
        preview.addView(details, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        LayoutParams previewLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        previewLp.topMargin = Ui.dp(context, 10);
        addView(preview, previewLp);

        LinearLayout actions = Ui.row(context);
        int touchMin = Ui.dp(context, 48);

        Button apply = new Button(context, null, android.R.attr.borderlessButtonStyle);
        apply.setText(context.getString(R.string.card_apply_appearance));
        apply.setAllCaps(false);
        apply.setTypeface(mediumTypeface());
        apply.setTextColor(onAccentArgb);
        apply.setMinHeight(touchMin);
        apply.setMinimumHeight(touchMin);
        apply.setMinWidth(touchMin);
        apply.setMinimumWidth(touchMin);
        Ui.pad(apply, 16, 8);
        GradientDrawable applyBg = new GradientDrawable();
        applyBg.setColor(accentArgb);
        applyBg.setCornerRadius(Ui.dp(context, 10));
        apply.setBackground(applyBg);
        apply.setTag("card:apply");
        apply.setOnClickListener(v -> {
            Diagnostics.event("card", "customization_apply");
            callbacks.onApplyCustomization(spec.cardId, proposed);
        });

        Button undo = Ui.button(context, context.getString(R.string.card_undo_appearance), v -> {
            Diagnostics.event("card", "customization_undo");
            callbacks.onUndoCustomization(spec.cardId);
        });
        undo.setMinWidth(touchMin);
        undo.setTag("card:undo");

        LayoutParams applyLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        applyLp.setMarginEnd(Ui.dp(context, 10));
        actions.addView(apply, applyLp);
        actions.addView(undo);

        LayoutParams actionsLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        actionsLp.topMargin = Ui.dp(context, 12);
        addView(actions, actionsLp);
    }

    static String paletteLabel(Context context, AppCustomization.PalettePreset preset) {
        return switch (preset) {
            case FOREST -> context.getString(R.string.custom_palette_forest);
            case SLATE -> context.getString(R.string.custom_palette_slate);
            case TERRACOTTA -> context.getString(R.string.custom_palette_terracotta);
            case INDIGO -> context.getString(R.string.custom_palette_indigo);
            case PLUM -> context.getString(R.string.custom_palette_plum);
            case CUSTOM -> context.getString(R.string.custom_palette_custom);
        };
    }

    static String densityLabel(Context context, AppCustomization.Density density) {
        return switch (density) {
            case COMPACT -> context.getString(R.string.custom_density_compact);
            case COMFORTABLE -> context.getString(R.string.custom_density_comfortable);
            case SPACIOUS -> context.getString(R.string.custom_density_spacious);
        };
    }

    private static String validateId(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (!ITEM_ID.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Invalid card or item id");
        }
        return trimmed;
    }

    private static String requireText(String raw, int maxChars, String field) {
        String clean = AppCustomization.sanitizePlainText(raw, maxChars);
        if (clean.isEmpty()) throw new IllegalArgumentException(field + " required");
        return clean;
    }

    private static String str(Object value) {
        return value instanceof String s ? s : "";
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean b && b;
    }
}
