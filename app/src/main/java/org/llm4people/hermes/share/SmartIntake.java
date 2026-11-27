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

    public record Suggestion(String id, int labelRes, int draftRes) {
        public String label(Context context) { return context.getString(labelRes); }
        public String draftText(Context context) { return context.getString(draftRes); }
    }

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
                    new Suggestion("explain_image", R.string.share_intake_explain, R.string.share_intake_draft_explain_image),
                    new Suggestion("extract_info", R.string.share_intake_extract, R.string.share_intake_draft_extract_image),
                    new Suggestion("save_note", R.string.share_intake_save_note, R.string.share_intake_draft_save_image));
            case URL -> List.of(
                    new Suggestion("summarize_url", R.string.share_intake_summarize, R.string.share_intake_draft_summarize_url),
                    new Suggestion("save_note", R.string.share_intake_save_note, R.string.share_intake_draft_save_url));
            case CODE_OR_LOG -> List.of(
                    new Suggestion("diagnose_code", R.string.share_intake_diagnose, R.string.share_intake_draft_diagnose_code),
                    new Suggestion("explain_code", R.string.share_intake_explain, R.string.share_intake_draft_explain_code));
            case DOCUMENT -> List.of(
                    new Suggestion("summarize_doc", R.string.share_intake_summarize, R.string.share_intake_draft_summarize_doc),
                    new Suggestion("extract_info", R.string.share_intake_extract, R.string.share_intake_draft_extract_doc),
                    new Suggestion("save_note", R.string.share_intake_save_note, R.string.share_intake_draft_save_doc));
            case MESSAGE -> List.of(
                    new Suggestion("reply_message", R.string.share_intake_reply, R.string.share_intake_draft_reply_msg),
                    new Suggestion("add_calendar", R.string.share_intake_add_calendar, R.string.share_intake_draft_calendar_msg));
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
            String chipLabel = suggestion.label(context);
            Button chip = Ui.button(context, chipLabel, v -> {
                if (onPick != null) onPick.accept(suggestion);
            });
            chip.setContentDescription(context.getString(R.string.share_intake_prepare_desc, chipLabel));
            chip.setTextColor(Ui.enabledDisabledInk(context, R.color.accent));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(Ui.dp(context, Ui.BUTTON_CORNER_DP));
            bg.setColor(context.getColor(R.color.accent_surface));
            bg.setStroke(Math.max(1, Ui.dp(context, 1)), context.getColor(R.color.muted));
            chip.setBackground(Ui.roundedRipple(context, Ui.BUTTON_CORNER_DP, bg));
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
