package org.llm4people.hermes.thread;

import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** A native, scrollable transcript item. Editing a question never changes the message draft. */
final class PromptCard extends LinearLayout {
    enum State { READY, SENDING, VERIFY, CHECKING, OFFLINE }
    final ThreadViews ui;
    private final LinearLayout actions;
    private final TextView status;
    private final Button verify;
    private State state;
    private final PromptQuestion question;

    PromptCard(ThreadViews ui, JSONObject prompt, Consumer<String> answer, Runnable check) {
        super(ui.activity);
        this.ui = ui;
        setOrientation(VERTICAL);
        Ui.pad(this, 18, 8);
        LinearLayout panel = Ui.column(ui.activity);
        Ui.pad(panel, 14, 12);
        panel.setBackground(shape(R.color.surface, true));
        addView(panel);
        boolean question = RunPrompts.question(prompt);
        String heading = question ? ThreadData.string(prompt, "question") : "Autoriser cette action ?";
        TextView title = Ui.text(ui.activity, heading.trim().isEmpty() ? "Question de Hermes" : heading, 18);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setAccessibilityHeading(true);
        panel.addView(title);
        actions = Ui.column(ui.activity);
        if (question) {
            panel.addView(actions);
            this.question = new PromptQuestion(this, actions, prompt, answer);
        } else {
            this.question = null;
            approval(panel, prompt, answer);
        }
        status = Ui.text(ui.activity, "", 14);
        status.setTextColor(ui.activity.getColor(R.color.muted));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        spaced(panel, status);
        verify = button(panel, "Vérifier la demande", check, false);
        state(State.READY);
    }

    private void approval(LinearLayout panel, JSONObject prompt, Consumer<String> answer) {
        String description = ThreadData.string(prompt, "description").trim();
        String command = ThreadData.string(prompt, "command").trim();
        JSONObject edit = prompt.optJSONObject("edit");
        String path = ThreadData.string(edit, "path");
        boolean described = !description.isEmpty() || !command.isEmpty() || !path.isEmpty();
        String summary;
        if (!path.isEmpty()) summary = "Hermes propose de modifier " + path + ".";
        else if (!description.isEmpty()) summary = switch (description) {
            case "world/other-writable permissions" -> "Hermes demande à accorder un droit d’écriture à d’autres utilisateurs.";
            case "recursive world/other-writable (long flag)" ->
                    "Hermes demande à accorder un droit d’écriture à d’autres utilisateurs sur un dossier et son contenu.";
            case "recursive delete", "recursive delete (long flag)", "recursive delete (flags after operands)" ->
                    "Hermes demande à supprimer des fichiers ou des dossiers avec leur contenu.";
            default -> description;
        };
        else if (!command.isEmpty()) summary = "Hermes souhaite exécuter cette commande.";
        else summary = "Hermes n’a pas précisé l’action demandée.";
        TextView reason = Ui.text(getContext(), summary, 16);
        spaced(panel, reason);
        if (description.isEmpty() && !command.isEmpty() && edit == null) {
            TextView preview = Ui.text(getContext(), command, 15);
            preview.setTypeface(Typeface.MONOSPACE);
            preview.setMaxLines(3);
            preview.setEllipsize(android.text.TextUtils.TruncateAt.END);
            spaced(panel, preview);
        }
        if (described) {
            String details = approvalDetails(prompt);
            LinearLayout expanded = Ui.column(getContext());
            Button more = button(panel, "Voir les détails", () -> {}, false);
            more.setOnClickListener(v -> {
                boolean opening = expanded.getVisibility() == GONE;
                if (opening && expanded.getChildCount() == 0) {
                    boolean over = details.length() > TranscriptPage.PAGE_CHARS;
                    TextView exact = Ui.text(getContext(), over
                            ? details.substring(0, TranscriptPage.PAGE_CHARS) + "…" : details, 15);
                    exact.setTextIsSelectable(true);
                    expanded.addView(exact);
                    if (over) button(expanded, "Lire tous les détails", () ->
                            ui.show(new AlertDialog.Builder(getContext()).setTitle("Détails de la demande")
                                    .setView(MessageText.reader(getContext(), details, false, true))
                                    .setPositiveButton("Fermer", null)), false);
                }
                expanded.setVisibility(opening ? VISIBLE : GONE);
                more.setText(opening ? "Masquer les détails" : "Voir les détails");
            });
            expanded.setVisibility(GONE);
            panel.addView(expanded);
        }
        panel.addView(actions);
        if (described && RunPrompts.permits(prompt, "once"))
            button(actions, "Autoriser une fois", () -> answer.accept("once"), true);
        if (RunPrompts.permits(prompt, "deny")) button(actions, "Refuser", () -> answer.accept("deny"), true);
    }

    private static String approvalDetails(JSONObject prompt) {
        List<String> details = new ArrayList<>();
        String reason = ThreadData.string(prompt, "description");
        String command = ThreadData.string(prompt, "command");
        if (!reason.trim().isEmpty()) details.add(reason);
        if (!command.trim().isEmpty()) details.add("Commande\n" + command);
        JSONObject edit = prompt.optJSONObject("edit");
        if (edit != null) {
            try { details.add("Modification proposée\n" + edit.toString(2)); }
            catch (org.json.JSONException invalid) { throw new IllegalArgumentException(invalid); }
        }
        return String.join("\n\n", details);
    }

    void state(State next) {
        if (next == state) return;
        state = next;
        enabled(actions, next == State.READY);
        if (question != null) question.stateChanged();
        String message = switch (next) {
            case SENDING -> "Envoi de votre réponse…";
            case VERIFY -> "Hermes n’a pas confirmé la réponse.";
            case CHECKING -> "Vérification de la demande…";
            case OFFLINE -> "En attente de la reconnexion à Hermes.";
            default -> "";
        };
        status.setText(message);
        status.setVisibility(message.isEmpty() ? GONE : VISIBLE);
        verify.setVisibility(next == State.VERIFY ? VISIBLE : GONE);
    }

    private static void enabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof android.view.ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) enabled(group.getChildAt(i), enabled);
        }
    }

    private GradientDrawable shape(int fill, boolean border) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(Ui.dp(getContext(), 12));
        shape.setColor(getContext().getColor(fill));
        if (border) shape.setStroke(Ui.dp(getContext(), 1), getContext().getColor(R.color.line));
        return shape;
    }

    boolean ready() { return state == State.READY; }

    Button button(LinearLayout parent, String label, Runnable click, boolean tonal) {
        Button button = Ui.button(getContext(), label, v -> click.run());
        button.setSingleLine(false);
        button.setEllipsize(null);
        button.setMinWidth(0);
        Ui.pad(button, 12, 8);
        button.setTextColor(new ColorStateList(new int[][]{
                {-android.R.attr.state_enabled}, {}}, new int[]{
                getContext().getColor(R.color.muted), getContext().getColor(R.color.accent)}));
        if (tonal) {
            button.setBackground(new RippleDrawable(ColorStateList.valueOf(getContext().getColor(R.color.line)),
                    shape(R.color.accent_surface, false), null));
        }
        spaced(parent, button);
        return button;
    }

    void spaced(LinearLayout parent, View child) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = Ui.dp(getContext(), 8);
        parent.addView(child, params);
    }
}
