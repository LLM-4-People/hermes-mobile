package org.llm4people.hermes.thread;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
        GradientDrawable outline = new GradientDrawable();
        outline.setCornerRadius(Ui.dp(ui.activity, 12));
        outline.setColor(ui.activity.getColor(R.color.surface));
        outline.setStroke(Ui.dp(ui.activity, 1), ui.activity.getColor(R.color.line));
        panel.setBackground(outline);
        addView(panel);
        boolean question = RunPrompts.question(prompt);
        String heading = question ? ThreadData.string(prompt, "question") : "Autoriser cette action ?";
        TextView title = Ui.text(ui.activity, heading.trim().isEmpty() ? "Question de Hermes" : heading, 18);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setAccessibilityHeading(true);
        panel.addView(title);
        actions = question ? Ui.column(ui.activity) : new ApprovalChoices(ui.activity);
        if (question) {
            Button listen = PromptButtons.add(panel, "", null, PromptButtons.Role.UTILITY);
            PromptButtons.icon(listen, R.drawable.ic_prompt_listen);
            panel.addView(actions);
            this.question = new PromptQuestion(this, actions, prompt, listen, answer);
        } else {
            this.question = null;
            approval(panel, prompt, answer);
        }
        status = Ui.text(ui.activity, "", 14);
        status.setTextColor(ui.activity.getColor(R.color.muted));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        spaced(panel, status);
        verify = PromptButtons.add(panel, "Vérifier la demande", check, PromptButtons.Role.UTILITY);
        state(State.READY);
    }

    private void approval(LinearLayout panel, JSONObject prompt, Consumer<String> answer) {
        String description = ThreadData.string(prompt, "description").trim();
        String command = ThreadData.string(prompt, "command").trim();
        DiffRenderer.DiffModel diff = DiffRenderer.parse(prompt);
        String path = diff != null ? diff.path() : ThreadData.string(prompt.optJSONObject("edit"), "path");
        boolean described = !description.isEmpty() || !command.isEmpty() || !path.isEmpty()
                || (diff != null && (diff.hasLines() || !diff.summary().isEmpty()));
        String summary;
        if (diff != null && !diff.summary().isEmpty()) summary = diff.summary();
        else if (!path.isEmpty()) summary = "Hermes propose de modifier " + path + ".";
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
        if (diff != null && (!diff.path().isEmpty() || !DiffRenderer.statsText(diff).isEmpty() || !diff.risk().isEmpty())) {
            spaced(panel, DiffRenderer.summaryHeader(getContext(), diff));
        }
        if (description.isEmpty() && !command.isEmpty() && diff == null) {
            TextView preview = Ui.text(getContext(), command, 15);
            preview.setTypeface(Typeface.MONOSPACE);
            preview.setMaxLines(3);
            preview.setEllipsize(android.text.TextUtils.TruncateAt.END);
            spaced(panel, preview);
        }
        if (described) {
            String textDetails = approvalTextDetails(prompt);
            LinearLayout expanded = Ui.column(getContext());
            Button more = PromptButtons.add(panel, "Voir les détails", null, PromptButtons.Role.UTILITY);
            PromptButtons.icon(more, R.drawable.ic_prompt_disclosure);
            more.setOnClickListener(v -> {
                boolean opening = expanded.getVisibility() == GONE;
                if (opening && expanded.getChildCount() == 0) {
                    populateDetails(expanded, diff, textDetails);
                }
                expanded.setVisibility(opening ? VISIBLE : GONE);
                more.setActivated(opening);
                more.setText(opening ? "Masquer les détails" : "Voir les détails");
            });
            expanded.setVisibility(GONE);
            panel.addView(expanded);
        }
        panel.addView(actions);
        if (described && RunPrompts.permits(prompt, "once")) {
            Button allow = PromptButtons.add(actions, "Autoriser une fois", () -> answer.accept("once"), PromptButtons.Role.DECISION);
            PromptButtons.icon(allow, R.drawable.ic_prompt_allow);
        }
        if (RunPrompts.permits(prompt, "deny")) {
            Button deny = PromptButtons.add(actions, "Refuser", () -> answer.accept("deny"), PromptButtons.Role.DECISION);
            PromptButtons.icon(deny, R.drawable.ic_prompt_deny);
        }
    }

    private void populateDetails(LinearLayout expanded, DiffRenderer.DiffModel diff, String textDetails) {
        if (diff != null) {
            spaced(expanded, DiffRenderer.diffView(getContext(), diff, ui::show));
        }
        if (!textDetails.isEmpty()) {
            boolean over = textDetails.length() > TranscriptPage.PAGE_CHARS;
            TextView exact = Ui.text(getContext(), over
                    ? textDetails.substring(0, TranscriptPage.PAGE_CHARS) + "…" : textDetails, 15);
            exact.setTextIsSelectable(true);
            spaced(expanded, exact);
            if (over) PromptButtons.add(expanded, "Lire tous les détails", () ->
                    ui.show(new AlertDialog.Builder(getContext()).setTitle("Détails de la demande")
                            .setView(MessageText.reader(getContext(), textDetails, false, true))
                            .setPositiveButton("Fermer", null)), PromptButtons.Role.UTILITY);
        }
    }

    private static String approvalTextDetails(JSONObject prompt) {
        List<String> details = new ArrayList<>();
        String reason = ThreadData.string(prompt, "description");
        String command = ThreadData.string(prompt, "command");
        if (!reason.trim().isEmpty()) details.add(reason);
        if (!command.trim().isEmpty()) details.add("Commande\n" + command);
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
        // Disabling an Android text editor moves its selection and dismisses the keyboard.
        // The answer travels only through the separate, fenced submission action.
        if (view instanceof android.widget.EditText) return;
        view.setEnabled(enabled);
        if (view instanceof android.view.ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) enabled(group.getChildAt(i), enabled);
        }
    }

    boolean ready() { return state == State.READY; }

    void spaced(LinearLayout parent, View child) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = Ui.dp(getContext(), 8);
        parent.addView(child, params);
    }
}
