package org.llm4people.hermes.settings;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.llm4people.hermes.AdaptiveLayout;
import org.llm4people.hermes.AppLanguage;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.RouteRegistry;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.artifact.ThemePalette;
import org.llm4people.hermes.customization.AppCustomization;
import org.llm4people.hermes.customization.AppCustomizationStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Native Material 3 Appearance &amp; Layout Studio backed by {@link AppCustomizationStore} and
 * {@link ThemePalette}. Features a live preview card, theme polarity chips, WCAG-AA verified
 * curated &amp; custom hex palettes, density &amp; text-size controls, startup-tab selector,
 * companion greeting editor, Editable-Draft-Rule quick shortcuts, and 1-tap Undo/Reset.
 */
public final class AppearanceSettingsPanel {
    public static final String ROUTE_ID = "appearance_settings";

    public interface Listener {
        void onCustomizationChanged(AppCustomization customization);
        void onThemeModeChanged(String themeMode);
    }

    private final Activity activity;
    private final AppCustomizationStore store;
    private final Listener listener;
    private String activeThemeMode;

    private LinearLayout previewSlot;
    private LinearLayout themeSlot;
    private LinearLayout paletteSwatchesSlot;
    private LinearLayout densitySlot;
    private Button textSizeMinusBtn;
    private TextView textSizeValuePill;
    private Button textSizePlusBtn;
    private LinearLayout startupSlot;
    private LinearLayout shortcutsSlot;
    private LinearLayout historySlot;
    private EditText hexInput;
    private TextView hexContrastPill;
    private TextView hexErrorText;
    private Button hexApplyBtn;
    private EditText greetingInput;
    private TextView greetingCounter;
    private TextView greetingError;
    private String lastSyncedCustomHex;
    private String lastSyncedGreeting;
    private AlertDialog activeDialog;

    public AppearanceSettingsPanel(
            Activity activity,
            AppCustomizationStore store,
            String initialThemeMode,
            Listener listener) {
        this.activity = Objects.requireNonNull(activity, "activity");
        this.store = Objects.requireNonNull(store, "store");
        this.activeThemeMode = AppThemeMode.normalize(initialThemeMode);
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /** Register Appearance Studio under {@link RouteRegistry.Section#SETTINGS}. */
    public static RouteRegistry registerRoute(RouteRegistry registry, Context context, Runnable open) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(context, "context");
        return registry.register(new RouteRegistry.Destination(
                ROUTE_ID,
                RouteRegistry.Section.SETTINGS,
                context.getString(R.string.appearance_route_label),
                context.getString(R.string.appearance_route_hint),
                0,
                false,
                null,
                open));
    }

    public View buildView() {
        LinearLayout list = Ui.column(activity);
        ScrollView scroll = Ui.centeredScroll(activity, list, AdaptiveLayout.READING_COLUMN_MAX_DP, 20, 14, 28);
        scroll.setTag("settings:appearance");

        previewSlot = Ui.column(activity);
        list.addView(previewSlot, new LinearLayout.LayoutParams(-1, -2));

        themeSlot = addSectionCard(list, R.string.appearance_theme_title);
        LinearLayout paletteCard = addSectionCard(list, R.string.appearance_palette_title);
        buildPaletteSection(paletteCard);
        densitySlot = addSectionCard(list, R.string.appearance_density_title);
        LinearLayout textSizeCard = addSectionCard(list, R.string.appearance_text_size_title);
        buildTextSizeSection(textSizeCard);
        startupSlot = addSectionCard(list, R.string.appearance_startup_title);
        LinearLayout greetingCard = addSectionCard(list, R.string.appearance_greeting_title);
        buildGreetingSection(greetingCard);
        shortcutsSlot = addSectionCard(list, R.string.appearance_shortcuts_title);
        historySlot = addSectionCard(list, R.string.appearance_history_title);

        refreshAll();
        Diagnostics.debug("appearance_panel", "opened");
        return scroll;
    }

    public void syncThemeMode(String themeMode) {
        activeThemeMode = AppThemeMode.normalize(themeMode);
    }

    public void dismissDialogs() {
        AlertDialog dialog = activeDialog;
        activeDialog = null;
        if (dialog != null) dialog.dismiss();
    }

    public void refreshAll() {
        if (previewSlot == null) return;
        renderPreview();
        renderThemeSection();
        renderPaletteSection();
        renderDensitySection();
        renderTextSizeSection();
        renderStartupSection();
        syncGreetingFromStore();
        renderShortcutsSection();
        renderHistorySection();
    }

    private LinearLayout addSectionCard(LinearLayout parent, int titleRes) {
        TextView heading = Ui.text(activity, activity.getString(titleRes), 17);
        heading.setTypeface(Ui.mediumTypeface());
        heading.setLetterSpacing(-0.01f);
        heading.setPaddingRelative(Ui.dp(activity, 4), Ui.dp(activity, 20), 0, Ui.dp(activity, 8));
        heading.setAccessibilityHeading(true);
        parent.addView(heading);

        LinearLayout card = Ui.card(activity);
        parent.addView(card, new LinearLayout.LayoutParams(-1, -2));
        return card;
    }

    private void renderPreview() {
        previewSlot.removeAllViews();
        AppCustomization custom = store.current();
        boolean previewNight = AppThemeMode.resolveNight(activeThemeMode, AppThemeMode.isSystemNight());

        int bgColor = previewNight ? ThemePalette.NIGHT_SURFACE : ThemePalette.DAY_SURFACE;
        int borderColor = previewNight ? ThemePalette.NIGHT_LINE : ThemePalette.DAY_LINE;
        int inkColor = previewNight ? ThemePalette.NIGHT_INK : ThemePalette.DAY_INK;
        int mutedColor = previewNight ? ThemePalette.NIGHT_MUTED : ThemePalette.DAY_MUTED;
        int rawAccent = previewNight ? custom.darkAccentArgb() : custom.lightAccentArgb();
        int accentColor = (custom.palette == AppCustomization.PalettePreset.FOREST && custom.customAccentHex == null)
                ? (previewNight ? ThemePalette.NIGHT_ACCENT : ThemePalette.DAY_ACCENT)
                : ThemePalette.adaptAccentForMode(rawAccent, previewNight);
        int onAccentColor = ThemePalette.bestOnAccent(accentColor);

        int padH = Math.round(16 * custom.density.spacingMultiplier);
        int padV = Math.round(14 * custom.density.spacingMultiplier);
        float scale = 1.0f + custom.fontScaleDelta;

        LinearLayout preview = Ui.column(activity);
        preview.setTag("appearance:preview_card");
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setCornerRadius(Ui.dp(activity, Ui.CARD_CORNER_DP));
        cardBg.setColor(bgColor);
        cardBg.setStroke(Math.max(1, Ui.dp(activity, 1)), borderColor);
        preview.setBackground(cardBg);
        Ui.pad(preview, padH, padV);

        LinearLayout topRow = Ui.row(activity);
        TextView title = Ui.text(activity, activity.getString(R.string.appearance_preview_title), 13 * scale);
        title.setTypeface(Ui.mediumTypeface());
        title.setTextColor(accentColor);
        topRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView scalePill = new TextView(activity);
        scalePill.setText(AppearanceSettingsModel.formatFontScalePercent(custom.fontScaleDelta));
        scalePill.setTextSize(12);
        scalePill.setTypeface(Ui.mediumTypeface());
        scalePill.setTextColor(onAccentColor);
        scalePill.setPaddingRelative(Ui.dp(activity, 10), Ui.dp(activity, 4), Ui.dp(activity, 10), Ui.dp(activity, 4));
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setCornerRadius(Ui.dp(activity, 999));
        pillBg.setColor(accentColor);
        scalePill.setBackground(pillBg);
        topRow.addView(scalePill);
        preview.addView(topRow);

        String greeting = custom.companionGreeting.isEmpty()
                ? activity.getString(R.string.appearance_preview_default_greeting)
                : custom.companionGreeting;
        TextView greetingView = Ui.text(activity, greeting, 16 * scale);
        greetingView.setTypeface(Ui.mediumTypeface());
        greetingView.setTextColor(inkColor);
        greetingView.setPaddingRelative(0, Ui.dp(activity, 8), 0, Ui.dp(activity, 4));
        preview.addView(greetingView);

        String meta = activity.getString(R.string.appearance_preview_meta,
                activity.getString(AppearanceSettingsModel.themeModeLabelRes(activeThemeMode)),
                activity.getString(AppearanceSettingsModel.paletteLabelRes(custom.palette)),
                activity.getString(AppearanceSettingsModel.densityLabelRes(custom.density)));
        TextView metaView = Ui.text(activity, meta, 13 * scale);
        metaView.setTextColor(mutedColor);
        preview.addView(metaView);

        TextView sampleCta = new TextView(activity);
        sampleCta.setText(R.string.appearance_preview_sample_cta);
        sampleCta.setTextSize(13 * scale);
        sampleCta.setTypeface(Ui.mediumTypeface());
        sampleCta.setTextColor(onAccentColor);
        sampleCta.setGravity(Gravity.CENTER);
        sampleCta.setPaddingRelative(Ui.dp(activity, 14), Ui.dp(activity, 8), Ui.dp(activity, 14), Ui.dp(activity, 8));
        GradientDrawable ctaBg = new GradientDrawable();
        ctaBg.setCornerRadius(Ui.dp(activity, Ui.BUTTON_CORNER_DP));
        ctaBg.setColor(accentColor);
        sampleCta.setBackground(ctaBg);
        LinearLayout.LayoutParams ctaLp = new LinearLayout.LayoutParams(-2, -2);
        ctaLp.topMargin = Ui.dp(activity, 10);
        preview.addView(sampleCta, ctaLp);

        previewSlot.addView(preview, new LinearLayout.LayoutParams(-1, -2));
    }

    private void renderThemeSection() {
        themeSlot.removeAllViews();
        SettingsWrapLayout chips = new SettingsWrapLayout(activity, 8, 8);
        String[] modes = {
                AppearanceSettingsModel.THEME_SYSTEM,
                AppearanceSettingsModel.THEME_LIGHT,
                AppearanceSettingsModel.THEME_DARK
        };
        for (String mode : modes) {
            boolean selected = mode.equals(activeThemeMode);
            chips.addView(choiceChip(
                    activity.getString(AppearanceSettingsModel.themeModeLabelRes(mode)),
                    selected,
                    0,
                    () -> {
                        if (mode.equals(activeThemeMode)) return;
                        listener.onThemeModeChanged(mode);
                        activeThemeMode = AppThemeMode.readMode(activity);
                        refreshAll();
                    }));
        }
        themeSlot.addView(chips, new LinearLayout.LayoutParams(-1, -2));
    }

    private void buildPaletteSection(LinearLayout card) {
        paletteSwatchesSlot = Ui.column(activity);
        card.addView(paletteSwatchesSlot, new LinearLayout.LayoutParams(-1, -2));

        View divider = Ui.divider(activity);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(-1, Math.max(1, Ui.dp(activity, 1)));
        divLp.topMargin = Ui.dp(activity, 14);
        divLp.bottomMargin = Ui.dp(activity, 12);
        card.addView(divider, divLp);

        hexInput = new EditText(activity);
        hexInput.setSingleLine(true);
        hexInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        hexInput.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        hexInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(7)});
        hexInput.setSaveEnabled(false);
        hexInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        hexInput.setHint(R.string.appearance_custom_hex_hint);
        Ui.styleDialogInput(activity, hexInput);
        Ui.pad(hexInput, 12, 9);
        card.addView(hexInput, new LinearLayout.LayoutParams(-1, -2));

        hexContrastPill = Ui.pill(activity, "", R.color.surface_container, R.color.muted);
        hexContrastPill.setVisibility(View.GONE);
        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(-2, -2);
        pillLp.topMargin = Ui.dp(activity, 8);
        card.addView(hexContrastPill, pillLp);

        hexErrorText = Ui.text(activity, "", 13);
        hexErrorText.setTextColor(activity.getColor(R.color.danger));
        hexErrorText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        hexErrorText.setVisibility(View.GONE);
        hexErrorText.setPaddingRelative(0, Ui.dp(activity, 6), 0, 0);
        card.addView(hexErrorText);

        hexApplyBtn = Ui.tonalButton(activity, activity.getString(R.string.appearance_custom_hex_apply), v -> {
            AppearanceSettingsModel.HexEvaluation eval =
                    AppearanceSettingsModel.evaluateCustomHex(hexInput.getText().toString());
            if (!eval.accessible()) {
                updateHexFeedback(hexInput.getText().toString(), true);
                return;
            }
            applyCustomization(store.current().withPalette(
                    AppCustomization.PalettePreset.CUSTOM, eval.normalizedHex()));
            Toast.makeText(activity, R.string.appearance_applied_toast, Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams applyLp = new LinearLayout.LayoutParams(-2, -2);
        applyLp.topMargin = Ui.dp(activity, 10);
        card.addView(hexApplyBtn, applyLp);

        hexInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateHexFeedback(s == null ? "" : s.toString(), false);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void renderPaletteSection() {
        if (paletteSwatchesSlot == null) return;
        paletteSwatchesSlot.removeAllViews();
        AppCustomization current = store.current();
        boolean previewNight = AppThemeMode.resolveNight(activeThemeMode, AppThemeMode.isSystemNight());
        SettingsWrapLayout swatches = new SettingsWrapLayout(activity, 8, 8);
        for (AppCustomization.PalettePreset preset : AppearanceSettingsModel.CURATED_PRESETS) {
            boolean selected = current.palette == preset;
            int rawSwatch = 0xFF000000 | AppCustomization.parseRgb(
                    previewNight ? preset.darkAccentHex : preset.lightAccentHex);
            int swatchColor = preset == AppCustomization.PalettePreset.FOREST
                    ? (previewNight ? ThemePalette.NIGHT_ACCENT : ThemePalette.DAY_ACCENT)
                    : ThemePalette.adaptAccentForMode(rawSwatch, previewNight);
            swatches.addView(choiceChip(
                    activity.getString(AppearanceSettingsModel.paletteLabelRes(preset)),
                    selected,
                    swatchColor,
                    () -> applyCustomization(store.current().withPalette(preset, null))));
        }
        paletteSwatchesSlot.addView(swatches, new LinearLayout.LayoutParams(-1, -2));

        String storedCustom = current.palette == AppCustomization.PalettePreset.CUSTOM && current.customAccentHex != null
                ? current.customAccentHex : "";
        if (lastSyncedCustomHex == null || !storedCustom.equals(lastSyncedCustomHex)) {
            lastSyncedCustomHex = storedCustom;
            if (hexInput != null && !storedCustom.equals(hexInput.getText().toString())) {
                hexInput.setText(storedCustom);
            }
        }
        if (hexInput != null) {
            updateHexFeedback(hexInput.getText().toString(), false);
        }
    }

    private void updateHexFeedback(String raw, boolean showInvalidSyntax) {
        if (hexContrastPill == null || hexErrorText == null || hexApplyBtn == null) return;
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            hexContrastPill.setVisibility(View.GONE);
            hexErrorText.setVisibility(View.GONE);
            hexApplyBtn.setEnabled(false);
            return;
        }
        AppearanceSettingsModel.HexEvaluation eval = AppearanceSettingsModel.evaluateCustomHex(trimmed);
        Locale locale = AppLanguage.resolvedLocale(activity);
        if (!eval.validSyntax()) {
            hexContrastPill.setVisibility(View.GONE);
            if (showInvalidSyntax || trimmed.length() >= 7) {
                showHexError(R.string.appearance_hex_invalid, showInvalidSyntax);
            } else {
                hexErrorText.setVisibility(View.GONE);
            }
            hexApplyBtn.setEnabled(false);
            return;
        }
        String ratio = AppearanceSettingsModel.formatContrastRatio(eval.contrastRatio(), locale);
        hexContrastPill.setVisibility(View.VISIBLE);
        if (eval.accessible()) {
            hexContrastPill.setText(activity.getString(R.string.appearance_contrast_pass, ratio));
            hexContrastPill.setTextColor(activity.getColor(R.color.accent));
            hexContrastPill.setBackground(Ui.shape(activity, 999, R.color.accent_surface, 0, 0));
            hexErrorText.setVisibility(View.GONE);
            hexApplyBtn.setEnabled(true);
        } else {
            hexContrastPill.setText(activity.getString(R.string.appearance_contrast_fail, ratio));
            hexContrastPill.setTextColor(activity.getColor(R.color.danger));
            hexContrastPill.setBackground(Ui.shape(activity, 999, R.color.danger_surface, 0, 0));
            showHexError(R.string.appearance_hex_low_contrast, showInvalidSyntax);
            hexApplyBtn.setEnabled(false);
        }
    }

    private void showHexError(int msgRes, boolean forceReannounce) {
        String next = activity.getString(msgRes);
        if (forceReannounce || hexErrorText.getVisibility() != View.VISIBLE
                || !next.contentEquals(hexErrorText.getText())) {
            Ui.showInlineError(hexErrorText, next);
        }
    }

    private void renderDensitySection() {
        densitySlot.removeAllViews();
        AppCustomization current = store.current();
        SettingsWrapLayout chips = new SettingsWrapLayout(activity, 8, 8);
        for (AppCustomization.Density density : AppCustomization.Density.values()) {
            chips.addView(choiceChip(
                    activity.getString(AppearanceSettingsModel.densityLabelRes(density)),
                    current.density == density,
                    0,
                    () -> applyCustomization(store.current().withDensity(density))));
        }
        densitySlot.addView(chips, new LinearLayout.LayoutParams(-1, -2));
    }

    private void buildTextSizeSection(LinearLayout card) {
        TextView hint = Ui.text(activity, activity.getString(R.string.appearance_text_size_hint), 14);
        hint.setTextColor(activity.getColor(R.color.muted));
        hint.setPaddingRelative(0, 0, 0, Ui.dp(activity, 10));
        card.addView(hint);

        LinearLayout row = Ui.row(activity);
        textSizeMinusBtn = textSizeStepButton(
                R.drawable.ic_text_size_minus, R.string.appearance_text_decrease, v -> {
                    float next = AppearanceSettingsModel.stepFontScale(store.current().fontScaleDelta, -1);
                    applyCustomization(store.current().withFontScaleDelta(next));
                });
        row.addView(textSizeMinusBtn);

        textSizeValuePill = Ui.pill(activity,
                AppearanceSettingsModel.formatFontScalePercent(store.current().fontScaleDelta),
                R.color.accent_surface, R.color.accent);
        textSizeValuePill.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(-2, -2);
        pillLp.setMarginStart(Ui.dp(activity, 12));
        pillLp.setMarginEnd(Ui.dp(activity, 12));
        row.addView(textSizeValuePill, pillLp);

        textSizePlusBtn = textSizeStepButton(
                R.drawable.ic_text_size_plus, R.string.appearance_text_increase, v -> {
                    float next = AppearanceSettingsModel.stepFontScale(store.current().fontScaleDelta, 1);
                    applyCustomization(store.current().withFontScaleDelta(next));
                });
        row.addView(textSizePlusBtn);

        card.addView(row);
    }

    private Button textSizeStepButton(int iconRes, int contentDescRes, View.OnClickListener action) {
        Button btn = Ui.outlinedButton(activity, "", action);
        int minTouch = Ui.dp(activity, 48);
        int iconSize = Ui.dp(activity, 20);
        int padH = Ui.dp(activity, 14);
        btn.setMinWidth(minTouch);
        btn.setMinimumWidth(minTouch);
        btn.setMinHeight(minTouch);
        btn.setMinimumHeight(minTouch);
        btn.setPaddingRelative(padH, 0, padH, 0);
        android.graphics.drawable.Drawable icon = activity.getDrawable(iconRes);
        if (icon != null) {
            icon = icon.mutate();
            icon.setTintList(Ui.enabledDisabledInk(activity, R.color.accent));
            icon.setBounds(0, 0, iconSize, iconSize);
            btn.setCompoundDrawablesRelative(icon, null, null, null);
        }
        btn.setContentDescription(activity.getString(contentDescRes));
        return btn;
    }

    private void renderTextSizeSection() {
        if (textSizeMinusBtn == null || textSizeValuePill == null || textSizePlusBtn == null) return;
        float delta = store.current().fontScaleDelta;
        textSizeMinusBtn.setEnabled(AppearanceSettingsModel.canDecreaseFontScale(delta));
        String formatted = AppearanceSettingsModel.formatFontScalePercent(delta);
        if (!formatted.contentEquals(textSizeValuePill.getText())) {
            textSizeValuePill.setText(formatted);
        }
        textSizePlusBtn.setEnabled(AppearanceSettingsModel.canIncreaseFontScale(delta));
    }

    private void renderStartupSection() {
        startupSlot.removeAllViews();
        TextView hint = Ui.text(activity, activity.getString(R.string.appearance_startup_hint), 14);
        hint.setTextColor(activity.getColor(R.color.muted));
        hint.setPaddingRelative(0, 0, 0, Ui.dp(activity, 10));
        startupSlot.addView(hint);

        AppCustomization.StartupTab current = store.current().startupTab;
        SettingsWrapLayout chips = new SettingsWrapLayout(activity, 8, 8);
        for (AppCustomization.StartupTab tab : AppCustomization.StartupTab.values()) {
            chips.addView(choiceChip(
                    activity.getString(AppearanceSettingsModel.startupTabLabelRes(tab)),
                    current == tab,
                    0,
                    () -> applyCustomization(store.current().withStartupTab(tab))));
        }
        startupSlot.addView(chips, new LinearLayout.LayoutParams(-1, -2));
    }

    private void buildGreetingSection(LinearLayout card) {
        greetingInput = new EditText(activity);
        greetingInput.setSingleLine(true);
        greetingInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        greetingInput.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        greetingInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(AppCustomization.MAX_GREETING_CHARS)});
        greetingInput.setSaveEnabled(false);
        greetingInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        greetingInput.setHint(R.string.appearance_greeting_hint);
        Ui.styleDialogInput(activity, greetingInput);
        Ui.pad(greetingInput, 12, 9);
        card.addView(greetingInput, new LinearLayout.LayoutParams(-1, -2));

        greetingCounter = Ui.text(activity, "", 12);
        greetingCounter.setTextColor(activity.getColor(R.color.muted));
        greetingCounter.setPaddingRelative(0, Ui.dp(activity, 6), 0, 0);
        card.addView(greetingCounter);

        greetingError = Ui.text(activity, "", 13);
        greetingError.setTextColor(activity.getColor(R.color.danger));
        greetingError.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        greetingError.setVisibility(View.GONE);
        greetingError.setPaddingRelative(0, Ui.dp(activity, 4), 0, 0);
        card.addView(greetingError);

        greetingInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateGreetingCounter(s == null ? 0 : s.length());
                greetingError.setVisibility(View.GONE);
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        Button applyBtn = Ui.tonalButton(activity, activity.getString(R.string.appearance_greeting_apply), v -> {
            try {
                AppCustomization next = store.current().withCompanionGreeting(greetingInput.getText().toString());
                applyCustomization(next);
                Toast.makeText(activity, R.string.appearance_greeting_saved, Toast.LENGTH_SHORT).show();
            } catch (IllegalArgumentException rejected) {
                greetingError.setText(R.string.appearance_secret_rejected);
                greetingError.setVisibility(View.VISIBLE);
            }
        });
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(-2, -2);
        btnLp.topMargin = Ui.dp(activity, 10);
        card.addView(applyBtn, btnLp);
    }

    private void syncGreetingFromStore() {
        if (greetingInput == null) return;
        String stored = store.current().companionGreeting;
        if (lastSyncedGreeting == null || !stored.equals(lastSyncedGreeting)) {
            lastSyncedGreeting = stored;
            if (!stored.equals(greetingInput.getText().toString())) {
                greetingInput.setText(stored);
            }
        }
        updateGreetingCounter(greetingInput.length());
    }

    private void updateGreetingCounter(int length) {
        if (greetingCounter == null) return;
        greetingCounter.setText(activity.getString(
                R.string.appearance_greeting_counter, length, AppCustomization.MAX_GREETING_CHARS));
    }

    private void renderShortcutsSection() {
        shortcutsSlot.removeAllViews();
        TextView hint = Ui.text(activity, activity.getString(R.string.appearance_shortcuts_hint), 14);
        hint.setTextColor(activity.getColor(R.color.muted));
        hint.setPaddingRelative(0, 0, 0, Ui.dp(activity, 10));
        shortcutsSlot.addView(hint);

        List<AppCustomization.ComposerShortcut> shortcuts = store.current().composerShortcuts;
        if (shortcuts.isEmpty()) {
            TextView empty = Ui.text(activity,
                    activity.getString(R.string.appearance_shortcuts_empty, AppCustomization.MAX_COMPOSER_SHORTCUTS), 14);
            empty.setTextColor(activity.getColor(R.color.muted));
            shortcutsSlot.addView(empty);
        } else {
            for (int i = 0; i < shortcuts.size(); i++) {
                AppCustomization.ComposerShortcut item = shortcuts.get(i);
                if (i > 0) shortcutsSlot.addView(Ui.insetDivider(activity, 0));
                LinearLayout row = Ui.row(activity);
                row.setMinimumHeight(Ui.dp(activity, 52));
                row.setPaddingRelative(0, Ui.dp(activity, 8), 0, Ui.dp(activity, 8));

                LinearLayout copy = Ui.column(activity);
                TextView lbl = Ui.text(activity, item.label(), 15);
                lbl.setTypeface(Ui.mediumTypeface());
                copy.addView(lbl);
                TextView draft = Ui.text(activity, item.draftText(), 13);
                draft.setTextColor(activity.getColor(R.color.muted));
                draft.setMaxLines(2);
                copy.addView(draft);
                row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));

                Button remove = Ui.button(activity, activity.getString(R.string.ws_delete), v -> {
                    List<AppCustomization.ComposerShortcut> next =
                            AppearanceSettingsModel.removeShortcut(store.current().composerShortcuts, item.id());
                    applyCustomization(store.current().withComposerShortcuts(next));
                });
                remove.setTextColor(Ui.dangerInk(activity));
                remove.setContentDescription(activity.getString(R.string.appearance_shortcut_remove, item.label()));
                row.addView(remove);
                shortcutsSlot.addView(row);
            }
        }

        boolean canAdd = shortcuts.size() < AppCustomization.MAX_COMPOSER_SHORTCUTS;
        Button addBtn = Ui.tonalButton(activity,
                canAdd
                        ? activity.getString(R.string.appearance_shortcuts_add)
                        : activity.getString(R.string.appearance_shortcuts_limit_reached,
                                AppCustomization.MAX_COMPOSER_SHORTCUTS),
                v -> showAddShortcutDialog());
        addBtn.setEnabled(canAdd);
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(-2, -2);
        addLp.topMargin = Ui.dp(activity, 10);
        shortcutsSlot.addView(addBtn, addLp);
    }

    private void showAddShortcutDialog() {
        dismissDialogs();
        LinearLayout form = Ui.column(activity);
        Ui.pad(form, 18, 12);

        EditText labelInput = new EditText(activity);
        labelInput.setSingleLine(true);
        labelInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        labelInput.setImeOptions(EditorInfo.IME_ACTION_NEXT
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        labelInput.setHint(R.string.appearance_shortcut_label_hint);
        labelInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(AppCustomization.MAX_SHORTCUT_LABEL_CHARS)});
        labelInput.setSaveEnabled(false);
        labelInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        Ui.styleDialogInput(activity, labelInput);
        Ui.pad(labelInput, 12, 9);
        form.addView(labelInput, new LinearLayout.LayoutParams(-1, -2));

        EditText draftInput = new EditText(activity);
        draftInput.setMinLines(2);
        draftInput.setMaxLines(4);
        draftInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        draftInput.setImeOptions(EditorInfo.IME_ACTION_DONE
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        draftInput.setHint(R.string.appearance_shortcut_draft_hint);
        draftInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(AppCustomization.MAX_SHORTCUT_DRAFT_CHARS)});
        draftInput.setSaveEnabled(false);
        draftInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        Ui.styleDialogInput(activity, draftInput);
        draftInput.setMinHeight(Ui.dp(activity, 64));
        Ui.pad(draftInput, 12, 9);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(-1, -2);
        dLp.topMargin = Ui.dp(activity, 10);
        form.addView(draftInput, dLp);

        TextView errorView = Ui.text(activity, "", 13);
        errorView.setTextColor(activity.getColor(R.color.danger));
        errorView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        errorView.setVisibility(View.GONE);
        errorView.setPaddingRelative(0, Ui.dp(activity, 8), 0, 0);
        form.addView(errorView);

        TextWatcher clearError = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                errorView.setVisibility(View.GONE);
            }
            @Override public void afterTextChanged(Editable s) {}
        };
        labelInput.addTextChangedListener(clearError);
        draftInput.addTextChangedListener(clearError);

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(form, new ScrollView.LayoutParams(-1, -2));

        AlertDialog dialog = Ui.secureDialog(new AlertDialog.Builder(activity)
                .setTitle(R.string.appearance_shortcut_dialog_title)
                .setView(scroll)
                .setNegativeButton(R.string.ws_cancel, null)
                .setPositiveButton(R.string.ws_apply, null));
        activeDialog = dialog;
        Button applyBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (applyBtn != null) {
            applyBtn.setOnClickListener(v -> {
                try {
                    List<AppCustomization.ComposerShortcut> existing = store.current().composerShortcuts;
                    AppCustomization.ComposerShortcut created = AppearanceSettingsModel.createShortcut(
                            existing, labelInput.getText().toString(), draftInput.getText().toString());
                    List<AppCustomization.ComposerShortcut> updated = new ArrayList<>(existing);
                    updated.add(created);
                    applyCustomization(store.current().withComposerShortcuts(updated));
                    dialog.dismiss();
                } catch (IllegalArgumentException | IllegalStateException invalid) {
                    errorView.setText(R.string.appearance_shortcut_invalid);
                    errorView.setVisibility(View.VISIBLE);
                }
            });
        }
        dialog.setOnDismissListener(d -> {
            if (activeDialog == d) activeDialog = null;
        });
    }

    private void renderHistorySection() {
        historySlot.removeAllViews();
        SettingsWrapLayout row = new SettingsWrapLayout(activity, 10, 8);

        int depth = store.undoDepth();
        String undoLabel = depth > 0
                ? activity.getString(R.string.appearance_undo_count, depth)
                : activity.getString(R.string.appearance_undo);
        Button undoBtn = Ui.outlinedButton(activity, undoLabel, v -> {
            AppCustomization restored = store.undo();
            listener.onCustomizationChanged(restored);
            refreshAll();
            Toast.makeText(activity, R.string.appearance_undone_toast, Toast.LENGTH_SHORT).show();
        });
        undoBtn.setEnabled(store.canUndo());
        row.addView(undoBtn);

        boolean canReset = !store.current().equals(AppCustomization.defaults())
                || !AppearanceSettingsModel.THEME_SYSTEM.equals(activeThemeMode);
        Button resetBtn = Ui.outlinedButton(activity, activity.getString(R.string.appearance_reset), v -> {
            AppCustomization defaults = store.resetToDefault();
            listener.onCustomizationChanged(defaults);
            listener.onThemeModeChanged(AppearanceSettingsModel.THEME_SYSTEM);
            activeThemeMode = AppThemeMode.readMode(activity);
            refreshAll();
            Toast.makeText(activity, R.string.appearance_reset_toast, Toast.LENGTH_SHORT).show();
        });
        resetBtn.setEnabled(canReset);
        row.addView(resetBtn);

        historySlot.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void applyCustomization(AppCustomization next) {
        AppCustomization applied = store.apply(next);
        listener.onCustomizationChanged(applied);
        refreshAll();
    }

    private View choiceChip(String label, boolean selected, int swatchColorArgb, Runnable onClick) {
        LinearLayout chip = Ui.row(activity);
        chip.setGravity(Gravity.CENTER);
        chip.setMinimumHeight(Ui.dp(activity, 48));
        chip.setFocusable(true);
        chip.setClickable(true);
        chip.setSelected(selected);
        Ui.pad(chip, 14, 10);
        GradientDrawable bg = selected
                ? Ui.shape(activity, Ui.BUTTON_CORNER_DP, R.color.accent, 0, 0)
                : Ui.shape(activity, Ui.BUTTON_CORNER_DP, R.color.surface, R.color.line, 1);
        chip.setBackground(Ui.roundedRipple(activity, Ui.BUTTON_CORNER_DP, bg));
        chip.setOnClickListener(v -> onClick.run());
        chip.setContentDescription(label);
        Ui.setSelectableButtonRole(chip);

        if (swatchColorArgb != 0) {
            View dot = new View(activity);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(swatchColorArgb);
            circle.setStroke(Math.max(1, Ui.dp(activity, 1)),
                    selected ? activity.getColor(R.color.on_accent) : Color.TRANSPARENT);
            dot.setBackground(circle);
            dot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            int size = Ui.dp(activity, 14);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(size, size);
            dotLp.setMarginEnd(Ui.dp(activity, 8));
            chip.addView(dot, dotLp);
        }

        TextView text = Ui.text(activity, label, 14);
        text.setTypeface(Ui.mediumTypeface());
        text.setTextColor(activity.getColor(selected ? R.color.on_accent : R.color.ink));
        text.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        chip.addView(text);
        return chip;
    }
}
