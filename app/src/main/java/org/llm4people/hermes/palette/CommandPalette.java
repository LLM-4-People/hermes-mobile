package org.llm4people.hermes.palette;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Native searchable Quick-Jump Command Palette overlay (Ctrl+K / Cmd+K or 1-tap launch).
 * Styled with .impeccable/design.json paper/surface/deep-teal tokens, FLAG_SECURE, and >= 48dp rows.
 */
public final class CommandPalette {
    private final Activity activity;
    private final CommandRegistry registry;
    private final BooleanSupplier isOpen;
    private AlertDialog dialog;
    private LinearLayout resultsColumn;
    private TextView countAnnouncement;
    private List<CommandItem> visibleResults = Collections.emptyList();

    public CommandPalette(Activity activity, CommandRegistry registry, BooleanSupplier isOpen) {
        this.activity = Objects.requireNonNull(activity, "activity");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.isOpen = isOpen != null ? isOpen : () -> !activity.isFinishing() && !activity.isDestroyed();
    }

    public AlertDialog show() {
        return show(null);
    }

    public AlertDialog show(Runnable onDismissed) {
        dismiss();
        if (!canAct()) return null;

        LinearLayout container = Ui.column(activity);
        container.setBackgroundColor(activity.getColor(R.color.paper));
        Ui.pad(container, 16, 12);

        EditText search = new EditText(activity);
        search.setSaveEnabled(false);
        search.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_ACTION_GO
                | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        search.setSingleLine(true);
        search.setFilters(new InputFilter[]{new InputFilter.LengthFilter(CommandRegistry.MAX_QUERY_CHARS)});
        search.setHint(R.string.palette_search_hint);
        search.setTextColor(activity.getColor(R.color.ink));
        search.setHintTextColor(activity.getColor(R.color.muted));
        search.setTextSize(16);
        search.setMinHeight(Ui.dp(activity, 48));
        search.setMinimumHeight(Ui.dp(activity, 48));
        Ui.pad(search, 12, 10);
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setColor(activity.getColor(R.color.surface));
        inputBg.setCornerRadius(Ui.dp(activity, Ui.BUTTON_CORNER_DP));
        inputBg.setStroke(Math.max(1, Ui.dp(activity, 1)), activity.getColor(R.color.accent));
        search.setBackground(inputBg);
        container.addView(search);

        countAnnouncement = Ui.text(activity, "", 13);
        countAnnouncement.setTextColor(activity.getColor(R.color.muted));
        countAnnouncement.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        countAnnouncement.setPaddingRelative(Ui.dp(activity, 4), Ui.dp(activity, 6), 0, Ui.dp(activity, 6));
        container.addView(countAnnouncement);

        int maxListHeightPx = Math.min(
                Ui.dp(activity, 380),
                Math.max(Ui.dp(activity, 180),
                        Math.round(activity.getResources().getDisplayMetrics().heightPixels * 0.45f)));
        ScrollView scroll = new ScrollView(activity) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int constrained = MeasureSpec.makeMeasureSpec(maxListHeightPx, MeasureSpec.AT_MOST);
                super.onMeasure(widthSpec, constrained);
            }
        };
        resultsColumn = Ui.column(activity);
        scroll.addView(resultsColumn, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(-1, -2);
        container.addView(scroll, scrollLp);

        renderResults("");

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                renderResults(s == null ? "" : s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        search.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO && !visibleResults.isEmpty()) {
                executeItem(visibleResults.get(0));
                return true;
            }
            return false;
        });

        AlertDialog shown = Ui.secureDialog(new AlertDialog.Builder(activity)
                .setTitle(R.string.palette_title)
                .setView(container)
                .setNegativeButton(R.string.palette_close, null));
        dialog = shown;
        shown.setOnDismissListener(d -> {
            if (dialog == shown) dialog = null;
            if (onDismissed != null) onDismissed.run();
        });
        search.requestFocus();
        Diagnostics.event("palette", "opened");
        return shown;
    }

    private void renderResults(String query) {
        if (resultsColumn == null) return;
        visibleResults = registry.search(query);
        resultsColumn.removeAllViews();
        if (countAnnouncement != null) {
            countAnnouncement.setText(activity.getString(R.string.palette_results_count, visibleResults.size()));
        }
        if (visibleResults.isEmpty()) {
            TextView empty = Ui.text(activity, activity.getString(R.string.palette_empty), 15);
            empty.setTextColor(activity.getColor(R.color.muted));
            Ui.pad(empty, 8, 16);
            resultsColumn.addView(empty);
            return;
        }
        for (CommandItem item : visibleResults) {
            resultsColumn.addView(rowView(item), rowParams());
        }
    }

    private View rowView(CommandItem item) {
        LinearLayout row = Ui.column(activity);
        row.setClickable(true);
        row.setFocusable(true);
        row.setMinimumHeight(Ui.dp(activity, 48));
        Ui.pad(row, 14, 10);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(activity.getColor(R.color.surface));
        bg.setCornerRadius(Ui.dp(activity, Ui.CARD_CORNER_DP));
        bg.setStroke(Math.max(1, Ui.dp(activity, 1)), activity.getColor(R.color.line));
        row.setBackground(Ui.roundedRipple(activity, Ui.CARD_CORNER_DP, bg));

        LinearLayout header = Ui.row(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(activity, item.title, 16);
        title.setTypeface(Ui.mediumTypeface());
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView badge = Ui.text(activity, categoryLabel(item.category), 12);
        badge.setTextColor(activity.getColor(R.color.accent));
        badge.setTypeface(Ui.mediumTypeface());
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(activity.getColor(R.color.accent_surface));
        badgeBg.setCornerRadius(Ui.dp(activity, 8));
        badge.setBackground(badgeBg);
        Ui.pad(badge, 8, 3);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(-2, -2);
        badgeLp.setMarginStart(Ui.dp(activity, 8));
        header.addView(badge, badgeLp);
        row.addView(header);

        if (!item.subtitle.isEmpty()) {
            TextView sub = Ui.text(activity, item.subtitle, 13);
            sub.setTextColor(activity.getColor(R.color.muted));
            sub.setMaxLines(2);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            sub.setPaddingRelative(0, Ui.dp(activity, 2), 0, 0);
            row.addView(sub);
        }

        row.setContentDescription(item.title + ", " + categoryLabel(item.category)
                + (item.subtitle.isEmpty() ? "" : ", " + item.subtitle));
        row.setOnClickListener(v -> executeItem(item));
        return row;
    }

    private void executeItem(CommandItem item) {
        if (item == null || !canAct()) {
            dismiss();
            return;
        }
        dismiss();
        Diagnostics.event("palette", "selected_" + item.category.name().toLowerCase(java.util.Locale.ROOT));
        if (item.action != null) item.action.run();
    }

    private String categoryLabel(CommandItem.Category category) {
        return switch (category) {
            case ACTION -> activity.getString(R.string.palette_cat_action);
            case TEMPLATE -> activity.getString(R.string.palette_cat_template);
            case THREAD -> activity.getString(R.string.palette_cat_thread);
            case SCREEN -> activity.getString(R.string.palette_cat_screen);
        };
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = Ui.dp(activity, 8);
        return lp;
    }

    private boolean canAct() {
        return isOpen.getAsBoolean() && !activity.isFinishing() && !activity.isDestroyed();
    }

    public boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }

    public void dismiss() {
        if (dialog != null) {
            dialog.dismiss();
            dialog = null;
        }
    }
}
