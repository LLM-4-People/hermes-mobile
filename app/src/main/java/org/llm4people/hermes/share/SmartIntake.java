package org.llm4people.hermes.share;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.camera.CapturedMedia;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Calm, everyday-French Smart Intake suggestions for inbound shares and captures.
 * Classifies content type, adapts chip ordering ONLY when a learned user habit has repeated >= 3 times,
 * caps display at 2-3 chips max, and strictly prepares an editable draft (never auto-sends).
 */
public final class SmartIntake {
    public static final int MAX_CHIPS = 3;
    public static final int HABIT_THRESHOLD = 3;

    private static final Pattern SINGLE_URL = Pattern.compile("^https?://\\S+$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CODE_OR_LOG_HINT = Pattern.compile(
            "(Traceback \\(most recent call last\\)|Exception:|Error:|\\bat [a-zA-Z0-9_.$]+\\([^)]*:\\d+\\)"
                    + "|\\b(def |class |function |import |public |private |SELECT |FROM |const |let |var )\\b"
                    + "|[{};]\\s*\\n)");

    public enum ContentType {
        PHOTO_OR_RECEIPT,
        URL,
        CODE_OR_LOG,
        DOCUMENT,
        MESSAGE
    }

    public record Suggestion(String id, String label, String draftText) {}

    private SmartIntake() {}

    public static ContentType classify(String sharedText, List<CapturedMedia.Item> items) {
        if (items != null && !items.isEmpty()) {
            boolean hasImage = false;
            boolean hasCodeFile = false;
            for (CapturedMedia.Item item : items) {
                if (item == null) continue;
                if (item.kind() == CapturedMedia.Kind.IMAGE) {
                    hasImage = true;
                } else if (isCodeOrLogFile(item.name(), item.mimeType())) {
                    hasCodeFile = true;
                }
            }
            if (hasImage) return ContentType.PHOTO_OR_RECEIPT;
            if (hasCodeFile) return ContentType.CODE_OR_LOG;
            return ContentType.DOCUMENT;
        }
        String trimmed = sharedText == null ? "" : sharedText.trim();
        if (trimmed.isEmpty()) return ContentType.MESSAGE;
        if (SINGLE_URL.matcher(trimmed).matches()) return ContentType.URL;
        if (CODE_OR_LOG_HINT.matcher(trimmed).find()) return ContentType.CODE_OR_LOG;
        return ContentType.MESSAGE;
    }

    public static List<Suggestion> suggest(
            String sharedText, List<CapturedMedia.Item> items, Map<String, Integer> learnedHabits) {
        ContentType type = classify(sharedText, items);
        List<Suggestion> base = new ArrayList<>(defaultsFor(type));
        if (learnedHabits != null && !learnedHabits.isEmpty()) {
            base.sort(Comparator.comparingInt((Suggestion s) -> habitWeight(learnedHabits, s.id())).reversed());
        }
        return Collections.unmodifiableList(base.subList(0, Math.min(MAX_CHIPS, base.size())));
    }

    private static int habitWeight(Map<String, Integer> habits, String actionId) {
        Integer count = habits.get(actionId);
        return count != null && count >= HABIT_THRESHOLD ? count : 0;
    }

    private static List<Suggestion> defaultsFor(ContentType type) {
        return switch (type) {
            case PHOTO_OR_RECEIPT -> List.of(
                    new Suggestion("explain_image", "Expliquer", "Explique cette image simplement."),
                    new Suggestion("extract_info", "Extraire les infos", "Extrais les informations importantes de cette image."),
                    new Suggestion("save_note", "Sauvegarder en note", "Enregistre les informations importantes de cette image dans une note."));
            case URL -> List.of(
                    new Suggestion("summarize_url", "Résumer", "Résume le contenu de ce lien."),
                    new Suggestion("save_note", "Sauvegarder en note", "Enregistre un résumé de ce lien dans une note."));
            case CODE_OR_LOG -> List.of(
                    new Suggestion("diagnose_code", "Diagnostiquer", "Diagnostique ce problème et propose une solution claire."),
                    new Suggestion("explain_code", "Expliquer", "Explique simplement ce que fait cet extrait."));
            case DOCUMENT -> List.of(
                    new Suggestion("summarize_doc", "Résumer", "Résume les points essentiels de ce document."),
                    new Suggestion("extract_info", "Extraire les infos", "Extrais les informations clés de ce document."),
                    new Suggestion("save_note", "Sauvegarder en note", "Enregistre un résumé de ce document dans une note."));
            case MESSAGE -> List.of(
                    new Suggestion("reply_message", "Répondre", "Aide-moi à rédiger une réponse claire à ce message."),
                    new Suggestion("add_calendar", "Ajouter au calendrier", "Vérifie s’il y a une date dans ce message pour l’ajouter à mon agenda."));
        };
    }

    private static boolean isCodeOrLogFile(String name, String mimeType) {
        String lowerName = name == null ? "" : name.toLowerCase(Locale.ROOT);
        String lowerMime = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        if (lowerMime.contains("json") || lowerMime.contains("yaml") || lowerMime.contains("xml")
                || lowerMime.contains("python") || lowerMime.contains("java")) {
            return true;
        }
        return lowerName.endsWith(".log") || lowerName.endsWith(".py") || lowerName.endsWith(".java")
                || lowerName.endsWith(".kt") || lowerName.endsWith(".js") || lowerName.endsWith(".ts")
                || lowerName.endsWith(".json") || lowerName.endsWith(".yaml") || lowerName.endsWith(".yml")
                || lowerName.endsWith(".sh") || lowerName.endsWith(".sql") || lowerName.endsWith(".diff");
    }

    /** Build a calm horizontal row of 2-3 tonal chips; tapping a chip ONLY stages a draft (never auto-sends). */
    public static View createChipRow(Context context, List<Suggestion> suggestions, Consumer<Suggestion> onPick) {
        HorizontalScrollView scroller = new HorizontalScrollView(context);
        scroller.setHorizontalScrollBarEnabled(false);
        LinearLayout row = Ui.row(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Ui.pad(row, 14, 4);
        int gap = Ui.dp(context, 8);
        int count = suggestions == null ? 0 : Math.min(MAX_CHIPS, suggestions.size());
        for (int i = 0; i < count; i++) {
            Suggestion suggestion = suggestions.get(i);
            Button chip = Ui.button(context, suggestion.label(), v -> {
                if (onPick != null) onPick.accept(suggestion);
            });
            chip.setContentDescription("Préparer : " + suggestion.label());
            chip.setTextColor(context.getColor(R.color.accent));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(Ui.dp(context, 16));
            bg.setColor(context.getColor(R.color.accent_surface));
            bg.setStroke(Ui.dp(context, 1), context.getColor(R.color.line));
            chip.setBackground(bg);
            Ui.pad(chip, 12, 6);
            chip.setMinimumHeight(Ui.dp(context, 48));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
            if (i > 0) params.setMarginStart(gap);
            row.addView(chip, params);
        }
        scroller.addView(row);
        scroller.setVisibility(count == 0 ? View.GONE : View.VISIBLE);
        return scroller;
    }
}
