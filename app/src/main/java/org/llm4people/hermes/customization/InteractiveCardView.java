package org.llm4people.hermes.customization;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONObject;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.JsonValues;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.onboarding.PairingLinkParser;
import org.llm4people.hermes.onboarding.PairingShareSheet;
import org.llm4people.hermes.onboarding.QrCodeView;

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

    public enum Kind {
        CHECKLIST("checklist"),
        QUICK_PICKS("quick_pick"),
        TOGGLES("toggle_confirm"),
        CUSTOMIZATION_PREVIEW("customization_preview"),
        PAIRING_INVITE("pairing_invite");

        public final String wire;

        Kind(String wire) { this.wire = wire; }

        public static Kind parse(String wire) {
            if (wire == null || wire.trim().isEmpty()) return null;
            return switch (wire.trim().toLowerCase(Locale.ROOT)) {
                case "checklist" -> CHECKLIST;
                case "quick_pick", "quick_picks" -> QUICK_PICKS;
                case "toggle_confirm", "toggles" -> TOGGLES;
                case "customization_preview" -> CUSTOMIZATION_PREVIEW;
                case "pairing_invite", "pairing" -> PAIRING_INVITE;
                default -> null;
            };
        }
    }

    /** Validated fail-closed payload for an inline LLM pairing / invite card with QR &amp; Share actions. */
    public record PairingInvite(
            String mode,
            String deepLink,
            String profileSlug,
            String displayName,
            long expiresAtEpochSec,
            int ttlSeconds) {
        public PairingInvite {
            String normalizedMode = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
            mode = ("invite".equals(normalizedMode) || "device".equals(normalizedMode)) ? normalizedMode : "device";
            deepLink = deepLink == null ? "" : deepLink.trim();
            if (deepLink.isEmpty() || PairingLinkParser.parseOrNull(deepLink) == null) {
                throw new IllegalArgumentException("Invalid pairing_invite deep_link");
            }
            profileSlug = AppCustomization.sanitizePlainText(profileSlug, 64);
            displayName = AppCustomization.sanitizePlainText(displayName, 64);
            expiresAtEpochSec = Math.max(0L, expiresAtEpochSec);
            ttlSeconds = Math.max(0, Math.min(900, ttlSeconds));
        }

        public long remainingSeconds(long nowEpochSec) {
            if (expiresAtEpochSec > 0L) return Math.max(0L, expiresAtEpochSec - nowEpochSec);
            return ttlSeconds;
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

    /** Partial customization delta preserving omitted user settings when applied onto current state. */
    public record CustomizationDelta(
            AppCustomization.PalettePreset palette,
            String customAccentHex,
            AppCustomization.Density density,
            Float fontScaleDelta,
            AppCustomization.StartupTab startupTab,
            String companionGreeting,
            String themeMode) {
        public AppCustomization mergeOnto(AppCustomization current) {
            AppCustomization base = current == null ? AppCustomization.defaults() : current;
            AppCustomization.PalettePreset nextPalette = palette != null ? palette : base.palette;
            String nextHex = palette != null
                    ? (palette == AppCustomization.PalettePreset.CUSTOM ? customAccentHex : null)
                    : base.customAccentHex;
            AppCustomization.Density nextDensity = density != null ? density : base.density;
            float nextFontDelta = fontScaleDelta != null ? fontScaleDelta : base.fontScaleDelta;
            AppCustomization.StartupTab nextTab = startupTab != null ? startupTab : base.startupTab;
            String nextGreeting = companionGreeting != null ? companionGreeting : base.companionGreeting;
            return new AppCustomization(
                    nextPalette, nextHex, nextDensity, nextFontDelta, nextTab, nextGreeting, base.composerShortcuts);
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
        public final CustomizationDelta customizationDelta;
        public final PairingInvite pairingInvite;

        public Spec(
                String cardId,
                Kind kind,
                String title,
                String subtitle,
                List<ChecklistItem> checklist,
                List<QuickPick> quickPicks,
                List<ToggleItem> toggles,
                AppCustomization proposedCustomization,
                CustomizationDelta customizationDelta) {
            this(cardId, kind, title, subtitle, checklist, quickPicks, toggles,
                    proposedCustomization, customizationDelta, null);
        }

        public Spec(
                String cardId,
                Kind kind,
                String title,
                String subtitle,
                List<ChecklistItem> checklist,
                List<QuickPick> quickPicks,
                List<ToggleItem> toggles,
                AppCustomization proposedCustomization,
                CustomizationDelta customizationDelta,
                PairingInvite pairingInvite) {
            this.cardId = validateId(cardId);
            this.kind = Objects.requireNonNull(kind, "kind");
            this.title = requireText(title, MAX_TITLE_CHARS, "Card title");
            this.subtitle = AppCustomization.sanitizePlainText(subtitle, MAX_SUBTITLE_CHARS);
            this.checklist = boundedCopy(checklist, MAX_CHECKLIST_ITEMS);
            this.quickPicks = boundedCopy(quickPicks, MAX_QUICK_PICKS);
            this.toggles = boundedCopy(toggles, MAX_TOGGLES);
            this.customizationDelta = customizationDelta;
            this.proposedCustomization = proposedCustomization != null
                    ? proposedCustomization
                    : (customizationDelta != null ? customizationDelta.mergeOnto(AppCustomization.defaults()) : null);
            this.pairingInvite = pairingInvite;
            if (kind == Kind.CUSTOMIZATION_PREVIEW && this.proposedCustomization == null) {
                throw new IllegalArgumentException("Customization preview requires proposed customization");
            }
            if (kind == Kind.PAIRING_INVITE && this.pairingInvite == null) {
                throw new IllegalArgumentException("Pairing invite card requires pairingInvite payload");
            }
        }

        /** Merges a partial customization preview cleanly onto the profile's live customization state. */
        public AppCustomization mergeOnto(AppCustomization current) {
            if (customizationDelta != null) return customizationDelta.mergeOnto(current);
            return proposedCustomization != null ? proposedCustomization
                    : (current != null ? current : AppCustomization.defaults());
        }

        public static Spec checklist(String cardId, String title, String subtitle, List<ChecklistItem> items) {
            return new Spec(cardId, Kind.CHECKLIST, title, subtitle, items, null, null, null, null);
        }

        public static Spec quickPicks(String cardId, String title, String subtitle, List<QuickPick> picks) {
            return new Spec(cardId, Kind.QUICK_PICKS, title, subtitle, null, picks, null, null, null);
        }

        public static Spec toggles(String cardId, String title, String subtitle, List<ToggleItem> items) {
            return new Spec(cardId, Kind.TOGGLES, title, subtitle, null, null, items, null, null);
        }

        public static Spec customizationPreview(
                String cardId, String title, String subtitle, AppCustomization proposed) {
            return new Spec(cardId, Kind.CUSTOMIZATION_PREVIEW, title, subtitle, null, null, null, proposed, null);
        }

        public static Spec customizationPreview(
                String cardId, String title, String subtitle, CustomizationDelta delta) {
            Objects.requireNonNull(delta, "delta");
            return new Spec(cardId, Kind.CUSTOMIZATION_PREVIEW, title, subtitle, null, null, null,
                    delta.mergeOnto(AppCustomization.defaults()), delta, null);
        }

        public static Spec pairingInvite(
                String cardId, String title, String subtitle, PairingInvite invite) {
            Objects.requireNonNull(invite, "invite");
            return new Spec(cardId, Kind.PAIRING_INVITE, title, subtitle, null, null, null, null, null, invite);
        }

        /** Pure-Java fail-closed parser matching canonical Track 9A {@code interactive_cards} schema. */
        @SuppressWarnings("unchecked")
        public static Spec fromMap(Map<String, Object> raw) {
            if (raw == null) throw new IllegalArgumentException("Missing card object");
            String id = str(raw.get("id"));
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
                    Object rawPicks = raw.get("items");
                    if (rawPicks instanceof List<?> list) {
                        for (Object entry : list) {
                            if (picks.size() >= MAX_QUICK_PICKS) break;
                            if (!(entry instanceof Map<?, ?> m)) continue;
                            picks.add(new QuickPick(
                                    str(m.get("id")), str(m.get("label")), str(m.get("draft"))));
                        }
                    }
                    yield quickPicks(id, title, subtitle, picks);
                }
                case TOGGLES -> {
                    List<ToggleItem> items = new ArrayList<>();
                    Object rawToggles = raw.get("items");
                    if (rawToggles instanceof List<?> list) {
                        for (Object entry : list) {
                            if (items.size() >= MAX_TOGGLES) break;
                            if (!(entry instanceof Map<?, ?> m)) continue;
                            items.add(new ToggleItem(
                                    str(m.get("id")), str(m.get("label")),
                                    str(m.get("description")), bool(m.get("enabled"))));
                        }
                    }
                    yield toggles(id, title, subtitle, items);
                }
                case CUSTOMIZATION_PREVIEW -> {
                    Object rawCust = raw.get("customization");
                    if (!(rawCust instanceof Map<?, ?> m)) {
                        throw new IllegalArgumentException("Missing customization block");
                    }
                    CustomizationDelta delta = parseCustomizationDeltaMap((Map<String, Object>) m);
                    yield customizationPreview(id, title, subtitle, delta);
                }
                case PAIRING_INVITE -> {
                    Object nested = raw.get("pairing");
                    Map<String, Object> src = nested instanceof Map<?, ?> m ? (Map<String, Object>) m : raw;
                    String rawMode = str(src.get("mode"));
                    String mode = rawMode.isEmpty() ? "device" : rawMode;
                    Object rawLink = src.get("deep_link") != null ? src.get("deep_link") : src.get("link");
                    String link = str(rawLink);
                    String slug = str(src.get("profile_slug"));
                    String displayName = str(src.get("display_name"));
                    long expiresAt = parseLongVal(src.get("expires_at"));
                    int ttl = JsonValues.parseInt(src.get("ttl_seconds"), 300);
                    yield pairingInvite(id, title, subtitle,
                            new PairingInvite(mode, link, slug, displayName, expiresAt, ttl));
                }
            };
        }

        /** Android JSONObject fail-closed parser delegating directly to {@link #fromMap(Map)} via {@link JsonValues}. */
        public static Spec fromJson(JSONObject obj) {
            if (obj == null) throw new IllegalArgumentException("Missing card JSON");
            return fromMap(JsonValues.toMap(obj));
        }

        private static CustomizationDelta parseCustomizationDeltaMap(Map<String, Object> m) {
            AppCustomization.PalettePreset preset = null;
            String customHex = null;
            Object rawAccentHex = m.get("accent_hex");
            Object rawAccent = m.get("accent") != null ? m.get("accent") : m.get("accent_preset");
            if (rawAccentHex != null) {
                preset = AppCustomization.PalettePreset.CUSTOM;
                customHex = AppCustomization.validateCustomAccentHex(str(rawAccentHex));
            } else if (rawAccent != null) {
                preset = AppCustomization.PalettePreset.parse(str(rawAccent));
                if (preset == null) throw new IllegalArgumentException("Invalid accent");
            }
            AppCustomization.Density density = null;
            if (m.get("density") != null) {
                density = AppCustomization.Density.parse(str(m.get("density")));
                if (density == null) throw new IllegalArgumentException("Invalid density");
            }
            Float fontDelta = null;
            if (m.containsKey("font_scale_step") && m.get("font_scale_step") != null) {
                fontDelta = parseFontScaleStep(m.get("font_scale_step"));
            }
            AppCustomization.StartupTab tab = null;
            if (m.get("startup_tab") != null) {
                tab = AppCustomization.StartupTab.parse(str(m.get("startup_tab")));
                if (tab == null) throw new IllegalArgumentException("Invalid startup tab");
            }
            String greeting = null;
            if (m.containsKey("greeting") && m.get("greeting") != null) {
                greeting = AppCustomization.sanitizePlainText(str(m.get("greeting")), AppCustomization.MAX_GREETING_CHARS);
            }
            String themeMode = null;
            if (m.containsKey("theme") && m.get("theme") != null) {
                themeMode = parseThemeMode(str(m.get("theme")));
            }
            return new CustomizationDelta(preset, customHex, density, fontDelta, tab, greeting, themeMode);
        }

        private static float parseFontScaleStep(Object raw) {
            if (!(raw instanceof Number n)) {
                throw new IllegalArgumentException("Invalid font_scale_step");
            }
            double value = n.doubleValue();
            if (!Double.isFinite(value) || value != Math.rint(value)) {
                throw new IllegalArgumentException("font_scale_step must be an integer");
            }
            int step = (int) value;
            if (step < -3 || step > 6) {
                throw new IllegalArgumentException("font_scale_step out of range [-3, 6]");
            }
            return step * 0.05f;
        }

        private static String parseThemeMode(String raw) {
            String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            if (!"system".equals(normalized) && !"light".equals(normalized) && !"dark".equals(normalized)) {
                throw new IllegalArgumentException("Invalid theme mode");
            }
            return normalized;
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
        default void onSharePairingLink(String cardId, PairingInvite invite) {}
        default void onCopyPairingLink(String cardId, PairingInvite invite) {}
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
        bg.setCornerRadius(Ui.dp(context, Ui.CARD_CORNER_DP));
        bg.setStroke(Math.max(1, Ui.dp(context, 1)), context.getColor(R.color.line));
        setBackground(bg);
    }

    private void renderCard(Context context) {
        TextView titleView = Ui.text(context, spec.title, 16);
        titleView.setTypeface(Ui.mediumTypeface());
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
            case PAIRING_INVITE -> renderPairingInvite(context);
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
            chip.setTypeface(Ui.mediumTypeface());
            chip.setTextColor(Ui.enabledDisabledInk(context, R.color.accent));
            chip.setMinHeight(touchMin);
            chip.setMinimumHeight(touchMin);
            chip.setMinWidth(touchMin);
            chip.setMinimumWidth(touchMin);
            chip.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            Ui.pad(chip, 14, 8);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(context.getColor(R.color.accent_surface));
            bg.setCornerRadius(Ui.dp(context, Ui.BUTTON_CORNER_DP));
            chip.setBackground(Ui.roundedRipple(context, Ui.BUTTON_CORNER_DP, bg));
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
        boolean night = org.llm4people.hermes.artifact.ThemePalette.isNight(context);
        int rawAccent = night ? proposed.darkAccentArgb() : proposed.lightAccentArgb();
        int accentArgb = org.llm4people.hermes.artifact.ThemePalette.adaptAccentForMode(rawAccent, night);
        int onAccentArgb = AppCustomization.onAccentArgb(accentArgb);

        LinearLayout preview = Ui.row(context);
        preview.setPaddingRelative(0, Ui.dp(context, 10), 0, Ui.dp(context, 4));

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

        View previewDivider = Ui.divider(context);
        LayoutParams divLp = (LayoutParams) previewDivider.getLayoutParams();
        divLp.topMargin = Ui.dp(context, 10);
        addView(previewDivider, divLp);
        addView(preview, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        LinearLayout actions = Ui.row(context);
        int touchMin = Ui.dp(context, 48);

        Button apply = new Button(context, null, android.R.attr.borderlessButtonStyle);
        apply.setText(context.getString(R.string.card_apply_appearance));
        apply.setAllCaps(false);
        apply.setTypeface(Ui.mediumTypeface());
        apply.setTextColor(Ui.enabledDisabledInk(onAccentArgb, context.getColor(R.color.disabled_ink)));
        apply.setMinHeight(touchMin);
        apply.setMinimumHeight(touchMin);
        apply.setMinWidth(touchMin);
        apply.setMinimumWidth(touchMin);
        Ui.pad(apply, 16, 8);
        StateListDrawable applyStates = new StateListDrawable();
        GradientDrawable disabledApplyBg = new GradientDrawable();
        disabledApplyBg.setColor(context.getColor(R.color.disabled_surface));
        disabledApplyBg.setCornerRadius(Ui.dp(context, Ui.BUTTON_CORNER_DP));
        GradientDrawable enabledApplyBg = new GradientDrawable();
        enabledApplyBg.setColor(accentArgb);
        enabledApplyBg.setCornerRadius(Ui.dp(context, Ui.BUTTON_CORNER_DP));
        applyStates.addState(new int[]{-android.R.attr.state_enabled}, disabledApplyBg);
        applyStates.addState(new int[]{}, enabledApplyBg);
        apply.setBackground(Ui.roundedRipple(context, Ui.BUTTON_CORNER_DP, applyStates));
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
        undo.setMinimumWidth(touchMin);
        undo.setTag("card:undo");

        LayoutParams applyLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        applyLp.setMarginEnd(Ui.dp(context, 10));
        actions.addView(apply, applyLp);
        actions.addView(undo);

        HorizontalScrollView actionsScroll = new HorizontalScrollView(context);
        actionsScroll.setHorizontalScrollBarEnabled(false);
        actionsScroll.addView(actions, new HorizontalScrollView.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        LayoutParams actionsLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        actionsLp.topMargin = Ui.dp(context, 12);
        addView(actionsScroll, actionsLp);
    }

    private void renderPairingInvite(Context context) {
        PairingInvite invite = spec.pairingInvite;
        if (invite == null) return;

        TextView modePill = Ui.text(context,
                context.getString("invite".equals(invite.mode())
                        ? R.string.card_pairing_mode_invite : R.string.card_pairing_mode_device), 12);
        modePill.setTypeface(Ui.mediumTypeface());
        modePill.setTextColor(context.getColor(R.color.accent));
        modePill.setPaddingRelative(Ui.dp(context, 10), Ui.dp(context, 4),
                Ui.dp(context, 10), Ui.dp(context, 4));
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setCornerRadius(Ui.dp(context, 999));
        pillBg.setColor(context.getColor(R.color.accent_surface));
        modePill.setBackground(pillBg);
        LayoutParams modeLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        modeLp.topMargin = Ui.dp(context, 10);
        addView(modePill, modeLp);

        QrCodeView qr = new QrCodeView(context);
        qr.setTag("card:pairing_qr");
        qr.setContentDescription(spec.title);
        if (qr.setPayload(invite.deepLink())) {
            int qrSide = Ui.dp(context, 196);
            LayoutParams qrLp = new LayoutParams(qrSide, qrSide);
            qrLp.gravity = Gravity.CENTER_HORIZONTAL;
            qrLp.topMargin = Ui.dp(context, 12);
            qrLp.bottomMargin = Ui.dp(context, 8);
            addView(qr, qrLp);
        }

        long remaining = invite.remainingSeconds(System.currentTimeMillis() / 1000L);
        String expLabel = remaining > 0L
                ? context.getString(R.string.card_pairing_expires, PairingShareSheet.formatCountdown(remaining))
                : context.getString(R.string.card_pairing_expired);
        TextView expView = Ui.text(context, expLabel, 12);
        expView.setTextColor(context.getColor(remaining > 0L ? R.color.muted : R.color.danger));
        expView.setGravity(Gravity.CENTER_HORIZONTAL);
        LayoutParams expLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        expLp.topMargin = Ui.dp(context, 2);
        addView(expView, expLp);

        LinearLayout actions = Ui.row(context);
        Button share = Ui.filledButton(context, context.getString(R.string.card_pairing_share), v -> {
            Diagnostics.event("card", "pairing_share");
            PairingShareSheet.shareLink(context, invite.deepLink());
            callbacks.onSharePairingLink(spec.cardId, invite);
        });
        share.setTag("card:pairing_share");
        share.setEnabled(remaining > 0L);

        Button copy = Ui.outlinedButton(context, context.getString(R.string.card_pairing_copy), v -> {
            Diagnostics.event("card", "pairing_copy");
            if (PairingShareSheet.copyLink(context, invite.deepLink())) {
                ((Button) v).setText(context.getString(R.string.card_pairing_copied));
            }
            callbacks.onCopyPairingLink(spec.cardId, invite);
        });
        copy.setTag("card:pairing_copy");
        copy.setEnabled(remaining > 0L);

        LayoutParams shareLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        shareLp.setMarginEnd(Ui.dp(context, 10));
        actions.addView(share, shareLp);
        actions.addView(copy);

        HorizontalScrollView actionsScroll = new HorizontalScrollView(context);
        actionsScroll.setHorizontalScrollBarEnabled(false);
        actionsScroll.addView(actions, new HorizontalScrollView.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        LayoutParams actionsLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        actionsLp.topMargin = Ui.dp(context, 12);
        addView(actionsScroll, actionsLp);
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

    private static long parseLongVal(Object value) {
        return org.llm4people.hermes.EpochTimestamps.parseEpochSeconds(value);
    }
}
