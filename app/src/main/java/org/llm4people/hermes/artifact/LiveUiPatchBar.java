package org.llm4people.hermes.artifact;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.IOException;
import java.util.List;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;

/**
 * Non-blocking native banner presenting a live UI improvement proposed by Hermes
 * ("Voir · Appliquer · Annuler") with 1-tap rollback and custom composer quick-action chips.
 */
public final class LiveUiPatchBar extends LinearLayout {
    public interface Listener {
        void onApply(LiveUiPatch patch);
        void onRollback(String revisionId);
        void onDismiss(LiveUiPatch patch);
    }

    private final Activity activity;
    private final Listener listener;
    private final TextView headline;
    private final TextView summary;
    private final Button viewButton;
    private final Button applyButton;
    private final Button pinButton;
    private final Button cancelButton;
    private LiveUiPatch pending;
    private LiveUiPatch.WidgetSpec pendingWidget;
    private String appliedRevisionId;
    private boolean appliedState;

    public LiveUiPatchBar(Activity activity, Listener listener) {
        super(activity);
        this.activity = activity;
        this.listener = listener;
        setOrientation(VERTICAL);
        setVisibility(GONE);
        setSaveFromParentEnabled(false);

        ThemePalette palette = ThemePalette.resolve(ThemePalette.OverrideSpec.defaults(), ThemePalette.isNight(activity));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(palette.accentSurface);
        bg.setCornerRadius(Ui.dp(activity, 10));
        bg.setStroke(Math.max(1, Ui.dp(activity, 1)), palette.line);
        setBackground(bg);
        Ui.pad(this, 14, 10);

        headline = Ui.text(activity, "Hermes propose une amélioration de votre écran", 15);
        headline.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        headline.setTextColor(palette.ink);
        headline.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        addView(headline);

        summary = Ui.text(activity, "", 14);
        summary.setTextColor(palette.muted);
        summary.setPadding(0, Ui.dp(activity, 2), 0, Ui.dp(activity, 6));
        addView(summary);

        LinearLayout actions = Ui.row(activity);
        actions.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        viewButton = actionButton("Voir", "live-ui-view", v -> openPreview());
        applyButton = actionButton("Appliquer", "live-ui-apply", v -> applyCurrent());
        pinButton = actionButton("Épingler sur l’écran d’accueil", "live-ui-pin-home", v -> {
            if (pendingWidget != null) HomePinBridge.requestPinWidget(activity, pendingWidget);
        });
        pinButton.setVisibility(GONE);
        cancelButton = actionButton("Annuler", "live-ui-cancel", v -> cancelOrRollback());
        applyButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        actions.addView(viewButton);
        actions.addView(applyButton);
        actions.addView(pinButton);
        actions.addView(cancelButton);
        addView(actions);
    }

    public void present(LiveUiPatch patch) {
        if (patch == null) return;
        this.pending = patch;
        this.pendingWidget = firstWidget(patch);
        this.appliedRevisionId = null;
        this.appliedState = false;
        headline.setText("Hermes propose une amélioration de votre écran");
        summary.setText(patch.summary);
        viewButton.setVisibility(VISIBLE);
        applyButton.setVisibility(VISIBLE);
        pinButton.setVisibility(GONE);
        cancelButton.setText("Annuler");
        cancelButton.setContentDescription("Ignorer cette proposition");
        setVisibility(VISIBLE);
    }

    public void showApplied(LiveUiPatch patch) {
        this.pending = patch;
        this.pendingWidget = firstWidget(patch);
        this.appliedRevisionId = patch.revisionId;
        this.appliedState = true;
        headline.setText("Amélioration appliquée");
        summary.setText(patch.summary + " · Vous pouvez revenir en arrière en un geste.");
        viewButton.setVisibility(GONE);
        applyButton.setVisibility(GONE);
        pinButton.setVisibility(pendingWidget != null ? VISIBLE : GONE);
        cancelButton.setText("Annuler");
        cancelButton.setContentDescription("Annuler la dernière modification d’écran");
        setVisibility(VISIBLE);
    }

    private static LiveUiPatch.WidgetSpec firstWidget(LiveUiPatch patch) {
        if (patch == null) return null;
        for (LiveUiPatch.Op op : patch.ops) {
            if (op instanceof LiveUiPatch.Op.UpsertWidget uw) return uw.widget();
        }
        return null;
    }

    public void showReverted() {
        this.pending = null;
        this.pendingWidget = null;
        this.appliedRevisionId = null;
        this.appliedState = false;
        pinButton.setVisibility(GONE);
        setVisibility(GONE);
    }

    public void clear() {
        showReverted();
    }

    public LiveUiPatch pendingPatch() { return pending; }

    private void applyCurrent() {
        if (pending == null || appliedState) return;
        LiveUiPatch current = pending;
        listener.onApply(current);
    }

    private void cancelOrRollback() {
        if (appliedState && appliedRevisionId != null) {
            String rev = appliedRevisionId;
            listener.onRollback(rev);
        } else if (pending != null) {
            LiveUiPatch dismissed = pending;
            clear();
            listener.onDismiss(dismissed);
        } else {
            clear();
        }
    }

    private void openPreview() {
        if (pending == null) return;
        LiveUiPatch patch = pending;
        Context c = activity;
        LinearLayout body = Ui.column(c);
        Ui.pad(body, 18, 10);
        TextView intro = Ui.text(c, patch.summary, 16);
        intro.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        body.addView(intro);

        for (LiveUiPatch.Op op : patch.ops) {
            TextView item = Ui.text(c, "• " + op.frenchDescription(), 15);
            item.setPadding(0, Ui.dp(c, 6), 0, Ui.dp(c, 4));
            body.addView(item);
            if (op instanceof LiveUiPatch.Op.UpsertSurface up) {
                try {
                    WorkspaceLayout layout = WorkspaceLayout.parse(up.layoutJson());
                    body.addView(SurfaceRenderer.preview(c, layout));
                } catch (IOException e) {
                    Diagnostics.warning("live_ui", "preview_failed");
                }
            } else if (op instanceof LiveUiPatch.Op.SetThemeOverride st && st.spec().accentHex != null) {
                addColorSwatch(body, st.spec());
            }
        }
        ScrollView scroll = new ScrollView(c);
        scroll.addView(body);
        Ui.secureDialog(new AlertDialog.Builder(activity)
                .setTitle("Aperçu de l’amélioration")
                .setView(scroll)
                .setNegativeButton("Fermer", null)
                .setPositiveButton("Appliquer", (d, w) -> applyCurrent()));
    }

    private void addColorSwatch(LinearLayout parent, ThemePalette.OverrideSpec spec) {
        Context c = parent.getContext();
        ThemePalette preview = ThemePalette.resolve(spec, ThemePalette.isNight(c));
        LinearLayout row = Ui.row(c);
        Ui.pad(row, 0, 6);
        View swatch = new View(c);
        GradientDrawable chip = new GradientDrawable();
        chip.setCornerRadius(Ui.dp(c, 6));
        chip.setColor(preview.accent);
        swatch.setBackground(chip);
        row.addView(swatch, new LinearLayout.LayoutParams(Ui.dp(c, 28), Ui.dp(c, 28)));
        double contrast = ThemePalette.contrastRatio(preview.accent, preview.paper);
        TextView info = Ui.text(c, "Contraste vérifié (" + String.format(java.util.Locale.FRANCE, "%.1f", contrast) + " : 1)", 14);
        info.setTextColor(preview.muted);
        info.setPaddingRelative(Ui.dp(c, 10), 0, 0, 0);
        row.addView(info);
        parent.addView(row);
    }

    private Button actionButton(String label, String tag, OnClickListener click) {
        Button b = Ui.button(activity, label, click);
        b.setTag(tag);
        b.setMinHeight(Ui.dp(activity, 48));
        Ui.pad(b, 10, 4);
        return b;
    }

    /**
     * Populates a horizontal quick-action chip strip above the composer.
     * Tapping a chip ONLY prepares an editable draft, opens a surface, or opens a destination — never auto-sends.
     */
    public static View quickActionStrip(Context context, List<LiveUiPatch.QuickAction> actions,
                                        ThemePalette palette,
                                        java.util.function.Consumer<String> stageDraft,
                                        java.util.function.Consumer<String> openSurface) {
        return quickActionStrip(context, actions, palette, stageDraft, openSurface, null);
    }

    public static View quickActionStrip(Context context, List<LiveUiPatch.QuickAction> actions,
                                        ThemePalette palette,
                                        java.util.function.Consumer<String> stageDraft,
                                        java.util.function.Consumer<String> openSurface,
                                        java.util.function.Consumer<String> openAction) {
        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = Ui.row(context);
        Ui.pad(row, 8, 4);
        ThemePalette colors = palette == null
                ? ThemePalette.resolve(ThemePalette.OverrideSpec.defaults(), ThemePalette.isNight(context))
                : palette;
        if (actions == null || actions.isEmpty()) {
            scroll.setVisibility(GONE);
            scroll.addView(row);
            return scroll;
        }
        for (LiveUiPatch.QuickAction action : actions) {
            Button chip = Ui.button(context, action.label, v -> {
                if (action.draft != null && stageDraft != null) stageDraft.accept(action.draft);
                else if (action.page != null && openSurface != null) openSurface.accept(action.page);
                else if (action.nativeActionId() != null && openAction != null) openAction.accept(action.nativeActionId());
            });
            chip.setTag("quick-action-" + action.id);
            chip.setTextSize(14);
            chip.setTextColor(colors.accent);
            chip.setMinHeight(Ui.dp(context, 44));
            Ui.pad(chip, 12, 4);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(Ui.dp(context, 18));
            bg.setColor(colors.accentSurface);
            bg.setStroke(Math.max(1, Ui.dp(context, 1)), colors.line);
            chip.setBackground(bg);
            chip.setContentDescription(action.draft != null
                    ? action.label + ", préparer un brouillon"
                    : action.label + ", ouvrir");
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMarginEnd(Ui.dp(context, 6));
            row.addView(chip, lp);
        }
        scroll.addView(row);
        return scroll;
    }

    /**
     * Renders optional contextual "Actions rapides suggérées" chips below a Hermes reply.
     * Every chip is 1-tap safe (stages an editable draft, opens a surface, opens a native panel, or pins a widget — never auto-sends).
     */
    public static View suggestedActionsStrip(Context context, List<LiveUiPatch.SuggestedAction> suggestions,
                                             ThemePalette palette,
                                             java.util.function.Consumer<String> stageDraft,
                                             java.util.function.Consumer<String> openSurface,
                                             java.util.function.Consumer<String> openAction) {
        return suggestedActionsStrip(context, suggestions, palette, stageDraft, openSurface, openAction, null);
    }

    public static View suggestedActionsStrip(Context context, List<LiveUiPatch.SuggestedAction> suggestions,
                                             ThemePalette palette,
                                             java.util.function.Consumer<String> stageDraft,
                                             java.util.function.Consumer<String> openSurface,
                                             java.util.function.Consumer<String> openAction,
                                             java.util.function.Consumer<String> pinWidget) {
        LinearLayout container = Ui.column(context);
        if (suggestions == null || suggestions.isEmpty()) {
            container.setVisibility(GONE);
            return container;
        }
        ThemePalette colors = palette == null
                ? ThemePalette.resolve(ThemePalette.OverrideSpec.defaults(), ThemePalette.isNight(context))
                : palette;
        Ui.pad(container, 18, 6);
        TextView heading = Ui.text(context, "Actions rapides suggérées", colors.scaledSp(13));
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setTextColor(colors.muted);
        heading.setPadding(0, 0, 0, Ui.dp(context, 4));
        container.addView(heading);

        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = Ui.row(context);
        for (int i = 0; i < suggestions.size(); i++) {
            LiveUiPatch.SuggestedAction item = suggestions.get(i);
            Button chip = Ui.button(context, item.label, v -> {
                if (item.draft != null && stageDraft != null) stageDraft.accept(item.draft);
                else if (item.page != null && openSurface != null) openSurface.accept(item.page);
                else if (item.action != null && openAction != null) openAction.accept(item.action);
                else if (item.widgetId != null && pinWidget != null) pinWidget.accept(item.widgetId);
            });
            chip.setTag("suggested-action-" + i);
            chip.setTextSize(colors.scaledSp(14));
            chip.setTextColor(colors.accent);
            chip.setMinHeight(Ui.dp(context, 44));
            Ui.pad(chip, 12, 4);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(Ui.dp(context, 18));
            bg.setColor(colors.surface);
            bg.setStroke(Math.max(1, Ui.dp(context, 1)), colors.line);
            chip.setBackground(bg);
            chip.setContentDescription(item.draft != null
                    ? item.label + ", préparer un brouillon"
                    : item.label + ", ouvrir");
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMarginEnd(Ui.dp(context, 6));
            row.addView(chip, lp);
        }
        scroll.addView(row);
        container.addView(scroll);
        return container;
    }

    /** Shows revision history dialog with 1-tap rollback to any prior live UI state. */
    public static AlertDialog showHistoryDialog(Activity activity, LiveUiPatch.StateManager manager,
                                                java.util.function.Consumer<String> onRevert) {
        List<LiveUiPatch.StateManager.AppliedRevision> revs = manager.history();
        if (revs.isEmpty()) {
            return Ui.secureDialog(new AlertDialog.Builder(activity)
                    .setTitle("Historique des modifications")
                    .setMessage("Aucune modification d’écran en cours dans ce profil.")
                    .setPositiveButton("Fermer", null));
        }
        String[] labels = new String[revs.size()];
        String[] ids = new String[revs.size()];
        for (int i = 0; i < revs.size(); i++) {
            LiveUiPatch.StateManager.AppliedRevision rev = revs.get(revs.size() - 1 - i);
            labels[i] = rev.summary;
            ids[i] = rev.revisionId;
        }
        return Ui.secureDialog(new AlertDialog.Builder(activity)
                .setTitle("Revenir avant une modification")
                .setItems(labels, (d, which) -> onRevert.accept(ids[which]))
                .setNegativeButton("Fermer", null));
    }
}
