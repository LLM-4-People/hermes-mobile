package org.llm4people.hermes.templates;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.RouteRegistry;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.profile.Account;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Native bilingual Everyday Workflows &amp; Reusable Templates screen mounted under Atelier ({@link #ROUTE_ID}).
 * Follows .impeccable/design.json tokens and strictly obeys The Editable Draft Rule (never auto-sends).
 */
public final class TemplatesPanel {
    public static final String ROUTE_ID = "workflow_templates";
    public static final int MAX_STARTER_CHIPS = 4;
    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private final Activity activity;
    private final TemplateStore store;
    private final Consumer<String> stageDraft;
    private final BooleanSupplier isOpen;
    private LinearLayout customContainer;
    private TextView customStatus;
    private AlertDialog activeDialog;
    private boolean closed;

    public TemplatesPanel(Activity activity, Account account, Consumer<String> stageDraft, BooleanSupplier isOpen) {
        this(activity, TemplateStore.forAccount(account), stageDraft, isOpen);
    }

    public TemplatesPanel(Activity activity, TemplateStore store, Consumer<String> stageDraft, BooleanSupplier isOpen) {
        this.activity = Objects.requireNonNull(activity, "activity");
        this.store = Objects.requireNonNull(store, "store");
        this.stageDraft = Objects.requireNonNull(stageDraft, "stageDraft");
        this.isOpen = isOpen != null ? isOpen : () -> !activity.isFinishing() && !activity.isDestroyed();
    }

    /** Register this panel in {@link RouteRegistry} under {@link RouteRegistry.Section#ATELIER}. */
    public static RouteRegistry registerRoute(RouteRegistry registry, Context context, Runnable open) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(context, "context");
        return registry.register(new RouteRegistry.Destination(
                ROUTE_ID,
                RouteRegistry.Section.ATELIER,
                context.getString(R.string.templates_route_label),
                context.getString(R.string.templates_route_hint),
                0,
                false,
                null,
                open));
    }

    /**
     * Compact horizontal starter chip strip for empty discussions.
     * Tapping a chip strictly stages an editable draft — never sends automatically.
     */
    public static View starterStrip(Context context, List<WorkflowTemplate> templates, Consumer<String> stageDraft) {
        Objects.requireNonNull(context, "context");
        HorizontalScrollView scroller = new HorizontalScrollView(context);
        scroller.setHorizontalScrollBarEnabled(false);
        LinearLayout row = Ui.row(context);
        Ui.pad(row, 14, 6);
        int count = templates == null ? 0 : Math.min(MAX_STARTER_CHIPS, templates.size());
        int gap = Ui.dp(context, 8);
        for (int i = 0; i < count; i++) {
            WorkflowTemplate tpl = templates.get(i);
            if (tpl == null) continue;
            Button chip = Ui.button(context, tpl.title, v -> {
                if (stageDraft != null) {
                    Diagnostics.event("templates", "starter_staged");
                    stageDraft.accept(tpl.draft);
                }
            });
            chip.setContentDescription(context.getString(R.string.templates_chip_cd, tpl.title));
            chip.setTextColor(context.getColor(R.color.accent));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(Ui.dp(context, 12));
            bg.setColor(context.getColor(R.color.accent_surface));
            bg.setStroke(Math.max(1, Ui.dp(context, 1)), context.getColor(R.color.line));
            chip.setBackground(bg);
            Ui.pad(chip, 14, 8);
            chip.setMinimumHeight(Ui.dp(context, 48));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (i > 0) lp.setMarginStart(gap);
            row.addView(chip, lp);
        }
        scroller.addView(row);
        scroller.setVisibility(count == 0 ? View.GONE : View.VISIBLE);
        return scroller;
    }

    public View buildView() {
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(activity.getColor(R.color.paper));

        LinearLayout body = Ui.column(activity);
        Ui.pad(body, 20, 18);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));

        TextView title = Ui.text(activity, activity.getString(R.string.templates_panel_title), 24);
        title.setTypeface(MEDIUM);
        title.setAccessibilityHeading(true);
        body.addView(title);

        TextView subtitle = Ui.text(activity, activity.getString(R.string.templates_panel_subtitle), 15);
        subtitle.setTextColor(activity.getColor(R.color.muted));
        subtitle.setPaddingRelative(0, Ui.dp(activity, 4), 0, Ui.dp(activity, 14));
        body.addView(subtitle);

        Button createBtn = filledPrimaryButton(activity.getString(R.string.templates_new_button),
                v -> openEditorDialog(null));
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(-1, -2);
        btnLp.bottomMargin = Ui.dp(activity, 18);
        body.addView(createBtn, btnLp);

        body.addView(sectionHeading(activity.getString(R.string.templates_section_everyday)));
        for (WorkflowTemplate builtIn : WorkflowTemplate.builtIn(activity)) {
            body.addView(templateCard(builtIn), cardLayoutParams());
        }

        TextView customHeading = sectionHeading(activity.getString(R.string.templates_section_custom));
        customHeading.setPaddingRelative(0, Ui.dp(activity, 16), 0, Ui.dp(activity, 8));
        body.addView(customHeading);

        customStatus = Ui.text(activity, "", 14);
        customStatus.setTextColor(activity.getColor(R.color.muted));
        customStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        customStatus.setVisibility(View.GONE);
        body.addView(customStatus);

        customContainer = Ui.column(activity);
        body.addView(customContainer);
        refreshCustomSection();

        return scroll;
    }

    public void refreshCustomSection() {
        if (closed || customContainer == null) return;
        customContainer.removeAllViews();
        List<WorkflowTemplate> custom;
        try {
            custom = store.listCustom();
        } catch (IOException corrupted) {
            showStatus(activity.getString(R.string.templates_error_storage), true);
            custom = Collections.emptyList();
        }
        if (custom.isEmpty()) {
            TextView empty = Ui.text(activity, activity.getString(R.string.templates_custom_empty), 15);
            empty.setTextColor(activity.getColor(R.color.muted));
            Ui.pad(empty, 0, 8);
            customContainer.addView(empty);
            return;
        }
        hideStatus();
        for (WorkflowTemplate tpl : custom) {
            customContainer.addView(templateCard(tpl), cardLayoutParams());
        }
    }

    private LinearLayout templateCard(WorkflowTemplate tpl) {
        LinearLayout card = Ui.column(activity);
        Ui.pad(card, 16, 14);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(activity.getColor(R.color.surface));
        bg.setCornerRadius(Ui.dp(activity, 12));
        bg.setStroke(Math.max(1, Ui.dp(activity, 1)), activity.getColor(R.color.line));
        card.setBackground(bg);

        TextView heading = Ui.text(activity, tpl.title, 17);
        heading.setTypeface(MEDIUM);
        card.addView(heading);

        if (!tpl.subtitle.isEmpty()) {
            TextView sub = Ui.text(activity, tpl.subtitle, 14);
            sub.setTextColor(activity.getColor(R.color.muted));
            sub.setPaddingRelative(0, Ui.dp(activity, 3), 0, Ui.dp(activity, 8));
            card.addView(sub);
        }

        HorizontalScrollView actionsScroll = new HorizontalScrollView(activity);
        actionsScroll.setHorizontalScrollBarEnabled(false);
        actionsScroll.setPaddingRelative(0, Ui.dp(activity, 4), 0, 0);

        LinearLayout actions = Ui.row(activity);

        Button useBtn = Ui.button(activity, activity.getString(R.string.templates_use_button), v -> {
            if (!canAct()) return;
            Diagnostics.event("templates", tpl.builtIn ? "builtin_staged" : "custom_staged");
            stageDraft.accept(tpl.draft);
        });
        useBtn.setTypeface(MEDIUM);
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(activity.getColor(R.color.accent_surface));
        pillBg.setCornerRadius(Ui.dp(activity, 12));
        useBtn.setBackground(pillBg);
        Ui.pad(useBtn, 14, 8);
        actions.addView(useBtn);

        if (!tpl.builtIn) {
            Button editBtn = Ui.button(activity, activity.getString(R.string.templates_edit_button),
                    v -> openEditorDialog(tpl));
            Ui.pad(editBtn, 12, 8);
            actions.addView(editBtn);

            Button deleteBtn = Ui.button(activity, activity.getString(R.string.templates_delete_button),
                    v -> confirmDelete(tpl));
            deleteBtn.setTextColor(activity.getColor(R.color.danger));
            Ui.pad(deleteBtn, 12, 8);
            actions.addView(deleteBtn);
        }

        actionsScroll.addView(actions);
        card.addView(actionsScroll, new LinearLayout.LayoutParams(-1, -2));
        return card;
    }

    private void openEditorDialog(WorkflowTemplate existing) {
        if (!canAct()) return;
        dismissDialogs();

        LinearLayout content = Ui.column(activity);
        Ui.pad(content, 20, 12);

        TextView titleLbl = fieldLabel(activity.getString(R.string.templates_field_title));
        EditText titleInput = privateField(
                activity.getString(R.string.templates_field_title_hint),
                WorkflowTemplate.MAX_TITLE_CHARS,
                false);
        if (existing != null) titleInput.setText(existing.title);

        TextView subLbl = fieldLabel(activity.getString(R.string.templates_field_subtitle));
        EditText subInput = privateField(
                activity.getString(R.string.templates_field_subtitle_hint),
                WorkflowTemplate.MAX_SUBTITLE_CHARS,
                false);
        if (existing != null) subInput.setText(existing.subtitle);

        TextView draftLbl = fieldLabel(activity.getString(R.string.templates_field_draft));
        EditText draftInput = privateField(
                activity.getString(R.string.templates_field_draft_hint),
                WorkflowTemplate.MAX_DRAFT_CHARS,
                true);
        if (existing != null) draftInput.setText(existing.draft);

        TextView errorView = Ui.text(activity, "", 14);
        errorView.setTextColor(activity.getColor(R.color.danger));
        errorView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        errorView.setVisibility(View.GONE);
        errorView.setPaddingRelative(0, Ui.dp(activity, 8), 0, 0);

        content.addView(titleLbl);
        content.addView(titleInput);
        content.addView(subLbl);
        content.addView(subInput);
        content.addView(draftLbl);
        content.addView(draftInput);
        content.addView(errorView);

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        AlertDialog dialog = Ui.secureDialog(new AlertDialog.Builder(activity)
                .setTitle(existing == null ? R.string.templates_dialog_new_title : R.string.templates_dialog_edit_title)
                .setView(scroll)
                .setNegativeButton(R.string.templates_cancel_button, null)
                .setPositiveButton(R.string.templates_save_button, null));
        activeDialog = dialog;
        dialog.setOnDismissListener(d -> { if (activeDialog == dialog) activeDialog = null; });
        Button saveBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (saveBtn != null) {
            saveBtn.setOnClickListener(v -> {
                if (!canAct()) {
                    dialog.dismiss();
                    return;
                }
                try {
                    store.saveCustom(
                            existing == null ? null : existing.id,
                            titleInput.getText().toString(),
                            subInput.getText().toString(),
                            draftInput.getText().toString());
                    dialog.dismiss();
                    refreshCustomSection();
                } catch (WorkflowTemplate.SecretRejectedException secret) {
                    errorView.setText(R.string.templates_error_secret);
                    errorView.setVisibility(View.VISIBLE);
                } catch (TemplateStore.LimitExceededException limit) {
                    errorView.setText(R.string.templates_error_limit);
                    errorView.setVisibility(View.VISIBLE);
                } catch (IOException invalid) {
                    errorView.setText(R.string.templates_error_invalid);
                    errorView.setVisibility(View.VISIBLE);
                } catch (RuntimeException storageFailed) {
                    Diagnostics.warning("templates", "save_failed");
                    errorView.setText(R.string.templates_error_storage);
                    errorView.setVisibility(View.VISIBLE);
                }
            });
        }
    }

    private void confirmDelete(WorkflowTemplate tpl) {
        if (!canAct()) return;
        dismissDialogs();
        AlertDialog dialog = Ui.secureDialog(new AlertDialog.Builder(activity)
                .setTitle(R.string.templates_delete_confirm_title)
                .setMessage(activity.getString(R.string.templates_delete_confirm_body, tpl.title))
                .setNegativeButton(R.string.templates_cancel_button, null)
                .setPositiveButton(R.string.templates_delete_button, (d, w) -> {
                    if (!canAct()) return;
                    try {
                        store.deleteCustom(tpl.id);
                        refreshCustomSection();
                    } catch (IOException | RuntimeException error) {
                        Diagnostics.warning("templates", "delete_failed");
                        showStatus(activity.getString(R.string.templates_error_storage), true);
                    }
                }));
        activeDialog = dialog;
        dialog.setOnDismissListener(d -> { if (activeDialog == dialog) activeDialog = null; });
    }

    private boolean canAct() {
        return !closed && isOpen.getAsBoolean() && !activity.isFinishing() && !activity.isDestroyed();
    }

    private TextView sectionHeading(String text) {
        TextView view = Ui.text(activity, text, 15);
        view.setTypeface(MEDIUM);
        view.setTextColor(activity.getColor(R.color.muted));
        view.setAccessibilityHeading(true);
        view.setPaddingRelative(0, Ui.dp(activity, 4), 0, Ui.dp(activity, 8));
        return view;
    }

    private TextView fieldLabel(String text) {
        TextView view = Ui.text(activity, text, 14);
        view.setTypeface(MEDIUM);
        view.setPaddingRelative(0, Ui.dp(activity, 10), 0, Ui.dp(activity, 4));
        return view;
    }

    private EditText privateField(String hint, int maxChars, boolean multiline) {
        EditText field = new EditText(activity);
        field.setSaveEnabled(false);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        field.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxChars)});
        field.setHint(hint);
        field.setTextColor(activity.getColor(R.color.ink));
        field.setHintTextColor(activity.getColor(R.color.muted));
        field.setTextSize(16);
        int minTouch = Ui.dp(activity, 48);
        field.setMinHeight(minTouch);
        field.setMinimumHeight(minTouch);
        Ui.pad(field, 12, 10);
        GradientDrawable stroke = new GradientDrawable();
        stroke.setColor(activity.getColor(R.color.surface));
        stroke.setCornerRadius(Ui.dp(activity, 8));
        stroke.setStroke(Math.max(1, Ui.dp(activity, 1)), activity.getColor(R.color.muted));
        field.setBackground(stroke);
        if (multiline) {
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            field.setSingleLine(false);
            field.setMinLines(3);
            field.setMaxLines(6);
            field.setGravity(Gravity.START | Gravity.TOP);
        } else {
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            field.setSingleLine(true);
        }
        return field;
    }

    private Button filledPrimaryButton(String label, View.OnClickListener click) {
        Button button = Ui.button(activity, label, click);
        button.setTextColor(activity.getColor(R.color.on_accent));
        button.setTypeface(MEDIUM);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(activity.getColor(R.color.accent));
        bg.setCornerRadius(Ui.dp(activity, 12));
        button.setBackground(bg);
        Ui.pad(button, 16, 10);
        return button;
    }

    private LinearLayout.LayoutParams cardLayoutParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = Ui.dp(activity, 10);
        return lp;
    }

    private void showStatus(String msg, boolean danger) {
        if (customStatus == null) return;
        customStatus.setText(msg);
        customStatus.setTextColor(activity.getColor(danger ? R.color.danger : R.color.muted));
        customStatus.setVisibility(View.VISIBLE);
    }

    private void hideStatus() {
        if (customStatus != null) customStatus.setVisibility(View.GONE);
    }

    public void dismissDialogs() {
        if (activeDialog != null) {
            activeDialog.dismiss();
            activeDialog = null;
        }
    }

    public void close() {
        closed = true;
        dismissDialogs();
    }
}
