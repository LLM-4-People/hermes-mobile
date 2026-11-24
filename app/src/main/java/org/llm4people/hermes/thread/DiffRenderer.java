package org.llm4people.hermes.thread;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.LineBackgroundSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.JsonValues;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.artifact.CodeReader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Single Source of Truth for parsing and rendering coding approval diffs.
 * Supports both Fork PR #12 structured {@code preview.diff} and legacy gateway {@code edit} payloads.
 */
public final class DiffRenderer {
    static final int MAX_PREVIEW_LINES = 80;
    static final int MAX_PREVIEW_CHARS = 12_000;
    static final int MAX_FULL_CHARS = 120_000;

    public enum LineKind { HUNK, ADD, REMOVE, CONTEXT }

    public record DiffLine(LineKind kind, String text) {}

    public record DiffModel(
            String path,
            String language,
            String title,
            String summary,
            String risk,
            int additions,
            int deletions,
            boolean truncated,
            List<DiffLine> lines,
            String fullUnified
    ) {
        public boolean hasLines() { return !lines.isEmpty(); }
    }

    private DiffRenderer() {}

    /** Parse structured {@code preview.diff}, top-level {@code diff}, or legacy {@code edit} from an approval payload. */
    public static DiffModel parse(JSONObject prompt) {
        if (prompt == null) return null;
        try {
            JSONObject preview = prompt.optJSONObject("preview");
            String title = firstNonEmpty(stringOf(preview, "title"), stringOf(prompt, "title"));
            String summary = firstNonEmpty(stringOf(preview, "summary"), stringOf(prompt, "summary"));
            String risk = firstNonEmpty(stringOf(preview, "risk"), stringOf(prompt, "risk"));

            JSONObject structured = preview != null ? preview.optJSONObject("diff") : null;
            if (structured == null) structured = prompt.optJSONObject("diff");
            if (structured != null) {
                DiffModel model = parseStructured(structured, title, summary, risk);
                if (model != null) return model;
            }

            JSONObject edit = prompt.optJSONObject("edit");
            if (edit != null) {
                return fromEditStrings(
                        stringOf(edit, "path"),
                        firstNonEmpty(stringOf(edit, "old_text"), stringOf(edit, "old_string")),
                        firstNonEmpty(stringOf(edit, "new_text"), stringOf(edit, "new_string"), stringOf(edit, "content")),
                        firstNonEmpty(stringOf(edit, "patch"), stringOf(edit, "diff")),
                        title, summary, risk
                );
            }
            return null;
        } catch (RuntimeException malformed) {
            Diagnostics.warning("approval", "diff_parse_failed");
            return null;
        }
    }

    private static DiffModel parseStructured(JSONObject diff, String title, String summary, String risk) {
        String path = stringOf(diff, "path");
        String language = stringOf(diff, "language");
        boolean remoteTruncated = diff.optBoolean("truncated", false);
        JSONArray hunks = diff.optJSONArray("hunks");
        List<HunkInput> parsedHunks = new ArrayList<>();
        if (hunks != null) {
            for (int i = 0; i < hunks.length(); i++) {
                JSONObject hunk = hunks.optJSONObject(i);
                if (hunk == null) continue;
                int oldStart = JsonValues.parseInt(hunk.opt("old_start"), 1);
                int newStart = JsonValues.parseInt(hunk.opt("new_start"), 1);
                JSONArray rawLines = hunk.optJSONArray("lines");
                List<String> lines = new ArrayList<>();
                if (rawLines != null) {
                    for (int j = 0; j < rawLines.length(); j++) {
                        Object item = rawLines.opt(j);
                        if (item instanceof String s) lines.add(s);
                    }
                }
                parsedHunks.add(new HunkInput(oldStart, newStart, lines));
            }
        }
        Integer parsedAdditions = diff.has("additions") && diff.opt("additions") instanceof Number
                ? JsonValues.parseIntOrNull(diff.opt("additions")) : null;
        Integer parsedDeletions = diff.has("deletions") && diff.opt("deletions") instanceof Number
                ? JsonValues.parseIntOrNull(diff.opt("deletions")) : null;
        int additions = parsedAdditions != null ? Math.max(0, parsedAdditions) : -1;
        int deletions = parsedDeletions != null ? Math.max(0, parsedDeletions) : -1;
        String rawPatch = stringOf(diff, "patch");
        if (parsedHunks.isEmpty() && !rawPatch.isEmpty()) {
            return parseUnified(path, language, rawPatch, title, summary, risk, remoteTruncated);
        }
        return fromHunks(path, language, parsedHunks, additions, deletions, remoteTruncated, title, summary, risk);
    }

    public record HunkInput(int oldStart, int newStart, List<String> lines) {}

    /** Pure Java builder for Fork PR #12 structured hunks (host-JVM testable). */
    public static DiffModel fromHunks(String path, String language, List<HunkInput> hunks,
                                      int declaredAdditions, int declaredDeletions, boolean remoteTruncated,
                                      String title, String summary, String risk) {
        String cleanPath = safe(path);
        String cleanLang = safe(language);
        List<DiffLine> previewLines = new ArrayList<>();
        StringBuilder full = new StringBuilder();
        if (!cleanPath.isEmpty()) {
            full.append("--- a/").append(cleanPath).append('\n')
                .append("+++ b/").append(cleanPath).append('\n');
        }
        int countedAdd = 0, countedDel = 0, previewChars = 0;
        boolean truncated = remoteTruncated;
        if (hunks != null) {
            for (HunkInput hunk : hunks) {
                if (hunk == null || hunk.lines == null || hunk.lines.isEmpty()) continue;
                String header = "@@ -" + Math.max(0, hunk.oldStart) + " +" + Math.max(0, hunk.newStart) + " @@";
                appendFull(full, header);
                if (previewLines.size() < MAX_PREVIEW_LINES && previewChars + header.length() <= MAX_PREVIEW_CHARS) {
                    previewLines.add(new DiffLine(LineKind.HUNK, header));
                    previewChars += header.length() + 1;
                } else {
                    truncated = true;
                }
                for (String raw : hunk.lines) {
                    if (raw == null) continue;
                    String line = sanitizeLine(raw);
                    LineKind kind;
                    String formatted;
                    if (line.startsWith("+")) {
                        kind = LineKind.ADD;
                        formatted = line;
                        countedAdd++;
                    } else if (line.startsWith("-")) {
                        kind = LineKind.REMOVE;
                        formatted = line;
                        countedDel++;
                    } else if (line.startsWith("@@")) {
                        kind = LineKind.HUNK;
                        formatted = line;
                    } else {
                        kind = LineKind.CONTEXT;
                        formatted = line.startsWith(" ") ? line : " " + line;
                    }
                    appendFull(full, formatted);
                    if (previewLines.size() < MAX_PREVIEW_LINES && previewChars + formatted.length() <= MAX_PREVIEW_CHARS) {
                        previewLines.add(new DiffLine(kind, formatted));
                        previewChars += formatted.length() + 1;
                    } else {
                        truncated = true;
                    }
                }
            }
        }
        int additions = declaredAdditions >= 0 ? declaredAdditions : countedAdd;
        int deletions = declaredDeletions >= 0 ? declaredDeletions : countedDel;
        if (cleanPath.isEmpty() && previewLines.isEmpty() && additions == 0 && deletions == 0) return null;
        return new DiffModel(cleanPath, cleanLang, safe(title), safe(summary), normalizeRisk(risk),
                additions, deletions, truncated, Collections.unmodifiableList(previewLines), full.toString().trim());
    }

    /** Pure Java builder for legacy gateway {@code edit} payloads (host-JVM testable). */
    public static DiffModel fromEditStrings(String path, String oldText, String newText, String patch,
                                            String title, String summary, String risk) {
        String cleanPath = safe(path);
        String cleanPatch = safe(patch);
        if (!cleanPatch.isEmpty()) {
            return parseUnified(cleanPath, "", cleanPatch, title, summary, risk, false);
        }
        String cleanOld = oldText == null ? "" : oldText;
        String cleanNew = newText == null ? "" : newText;
        if (cleanPath.isEmpty() && cleanOld.isEmpty() && cleanNew.isEmpty()) return null;

        List<String> lines = new ArrayList<>();
        if (!cleanOld.isEmpty()) {
            for (String line : splitLines(cleanOld)) lines.add("-" + line);
        }
        if (!cleanNew.isEmpty()) {
            for (String line : splitLines(cleanNew)) lines.add("+" + line);
        }
        if (lines.isEmpty()) {
            return new DiffModel(cleanPath, "", safe(title), safe(summary), normalizeRisk(risk),
                    0, 0, false, Collections.emptyList(), cleanPath.isEmpty() ? "" : "--- a/" + cleanPath);
        }
        return fromHunks(cleanPath, "", Collections.singletonList(new HunkInput(1, 1, lines)),
                -1, -1, false, title, summary, risk);
    }

    /** Parse a raw unified diff string into a bounded DiffModel (host-JVM testable). */
    public static DiffModel parseUnified(String path, String language, String unified,
                                         String title, String summary, String risk, boolean remoteTruncated) {
        String cleanPath = safe(path);
        String body = unified == null ? "" : unified;
        List<DiffLine> previewLines = new ArrayList<>();
        StringBuilder full = new StringBuilder();
        int additions = 0, deletions = 0, previewChars = 0;
        boolean truncated = remoteTruncated;
        for (String raw : splitLines(body)) {
            if (cleanPath.isEmpty() && raw.startsWith("+++ ")) {
                String candidate = raw.substring(4).trim();
                if (candidate.startsWith("b/")) candidate = candidate.substring(2);
                if (!"/dev/null".equals(candidate)) cleanPath = candidate;
            }
            if (raw.startsWith("--- ") || raw.startsWith("+++ ") || raw.startsWith("diff --git ")) {
                appendFull(full, raw);
                continue;
            }
            LineKind kind;
            if (raw.startsWith("@@")) kind = LineKind.HUNK;
            else if (raw.startsWith("+")) { kind = LineKind.ADD; additions++; }
            else if (raw.startsWith("-")) { kind = LineKind.REMOVE; deletions++; }
            else kind = LineKind.CONTEXT;
            appendFull(full, raw);
            if (previewLines.size() < MAX_PREVIEW_LINES && previewChars + raw.length() <= MAX_PREVIEW_CHARS) {
                previewLines.add(new DiffLine(kind, raw));
                previewChars += raw.length() + 1;
            } else {
                truncated = true;
            }
        }
        if (cleanPath.isEmpty() && previewLines.isEmpty()) return null;
        return new DiffModel(cleanPath, safe(language), safe(title), safe(summary), normalizeRisk(risk),
                additions, deletions, truncated, Collections.unmodifiableList(previewLines), full.toString().trim());
    }

    public static String statsText(DiffModel model) {
        if (model == null) return "";
        if (model.additions == 0 && model.deletions == 0) return "";
        if (model.additions > 0 && model.deletions > 0) return "+" + model.additions + " / -" + model.deletions;
        return model.additions > 0 ? "+" + model.additions : "-" + model.deletions;
    }

    public static String statsAccessibleDescription(Context context, DiffModel model) {
        if (model == null || context == null) return "";
        List<String> parts = new ArrayList<>();
        if (!model.path.isEmpty()) parts.add(model.path);
        if (model.additions > 0) {
            parts.add(context.getResources().getQuantityString(R.plurals.diff_lines_added, model.additions, model.additions));
        }
        if (model.deletions > 0) {
            parts.add(context.getResources().getQuantityString(R.plurals.diff_lines_removed, model.deletions, model.deletions));
        }
        return String.join(", ", parts);
    }

    public static int riskLabelRes(String risk) {
        return switch (normalizeRisk(risk)) {
            case "low" -> R.string.diff_risk_low;
            case "medium" -> R.string.diff_risk_medium;
            case "high" -> R.string.diff_risk_high;
            default -> 0;
        };
    }

    public static String riskLabel(Context context, String risk) {
        int res = riskLabelRes(risk);
        return res == 0 || context == null ? "" : context.getString(res);
    }

    public static String toUnifiedText(DiffModel model) {
        if (model == null) return "";
        if (!model.fullUnified.isEmpty()) return model.fullUnified;
        StringBuilder sb = new StringBuilder();
        if (!model.path.isEmpty()) {
            sb.append("--- a/").append(model.path).append('\n')
              .append("+++ b/").append(model.path).append('\n');
        }
        for (DiffLine line : model.lines) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(line.text);
        }
        return sb.toString();
    }

    /** Compact bordered file-path + additions/deletions header mounted on the approval card. */
    public static View summaryHeader(Context context, DiffModel model) {
        LinearLayout box = Ui.row(context);
        box.setGravity(Gravity.CENTER_VERTICAL);
        Ui.pad(box, 10, 8);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Ui.dp(context, 8));
        bg.setColor(context.getColor(R.color.paper));
        bg.setStroke(Ui.dp(context, 1), context.getColor(R.color.line));
        box.setBackground(bg);

        LinearLayout info = Ui.column(context);
        if (!model.path.isEmpty()) {
            TextView pathView = Ui.text(context, model.path, 14);
            pathView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            pathView.setMaxLines(2);
            pathView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            info.addView(pathView);
        }
        String risk = riskLabel(context, model.risk);
        if (!risk.isEmpty()) {
            TextView riskView = Ui.text(context, risk, 13);
            riskView.setTextColor(context.getColor("high".equals(model.risk) ? R.color.danger : R.color.muted));
            info.addView(riskView);
        }
        box.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

        String stats = statsText(model);
        String statsDesc = statsAccessibleDescription(context, model);
        if (!stats.isEmpty()) {
            TextView badge = Ui.text(context, "", 13);
            badge.setText(styledStats(context, model));
            badge.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            badge.setPaddingRelative(Ui.dp(context, 8), 0, 0, 0);
            badge.setContentDescription(statsDesc);
            box.addView(badge);
        }
        box.setContentDescription(statsDesc);
        return box;
    }

    /** Crisp native unified diff view mounted inside the expandable approval details section. */
    public static View diffView(Context context, DiffModel model, Consumer<AlertDialog.Builder> openDialog) {
        LinearLayout container = Ui.column(context);
        if (model.hasLines()) {
            TextView code = Ui.text(context, "", 13);
            code.setTypeface(Typeface.MONOSPACE);
            code.setTextDirection(TextView.TEXT_DIRECTION_LTR);
            code.setTextIsSelectable(true);
            code.setHorizontallyScrolling(true);
            code.setLineSpacing(0, 1.18f);
            code.setText(renderLines(context, model.lines));
            Ui.pad(code, 10, 8);

            HorizontalScrollView scroller = new HorizontalScrollView(context);
            scroller.setFillViewport(true);
            GradientDrawable border = new GradientDrawable();
            border.setCornerRadius(Ui.dp(context, 8));
            border.setColor(context.getColor(R.color.paper));
            border.setStroke(Ui.dp(context, 1), context.getColor(R.color.line));
            scroller.setBackground(border);
            scroller.addView(code, new HorizontalScrollView.LayoutParams(-2, -2));
            container.addView(scroller, new LinearLayout.LayoutParams(-1, -2));
        }

        if (model.truncated) {
            TextView note = Ui.text(context, context.getString(R.string.diff_truncated), 13);
            note.setTextColor(context.getColor(R.color.muted));
            Ui.pad(note, 0, 4);
            container.addView(note);
        }

        String unified = toUnifiedText(model);
        if (openDialog != null && !unified.isEmpty()) {
            String title = model.path.isEmpty() ? context.getString(R.string.diff_proposed_change) : model.path;
            Button openFull = Ui.button(context, context.getString(R.string.diff_open_full),
                    v -> openDialog.accept(CodeReader.dialog(context, title, "diff", unified)));
            openFull.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            container.addView(openFull);
        }
        return container;
    }

    private static CharSequence styledStats(Context context, DiffModel model) {
        String text = statsText(model);
        SpannableString span = new SpannableString(text);
        int plusIdx = text.indexOf('+');
        if (plusIdx >= 0) {
            int end = text.indexOf(' ', plusIdx);
            if (end < 0) end = text.length();
            span.setSpan(new ForegroundColorSpan(context.getColor(R.color.accent)),
                    plusIdx, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        int minusIdx = text.indexOf('-');
        if (minusIdx >= 0) {
            span.setSpan(new ForegroundColorSpan(context.getColor(R.color.danger)),
                    minusIdx, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return span;
    }

    private static SpannableString renderLines(Context context, List<DiffLine> lines) {
        int totalChars = Math.max(0, lines.size() - 1);
        for (DiffLine line : lines) totalChars += line.text.length();
        StringBuilder joined = new StringBuilder(totalChars);
        int[] starts = new int[lines.size()];
        int[] ends = new int[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) joined.append('\n');
            starts[i] = joined.length();
            joined.append(lines.get(i).text);
            ends[i] = joined.length();
        }
        SpannableString spanned = new SpannableString(joined.toString());
        int addInk = context.getColor(R.color.accent);
        int delInk = context.getColor(R.color.danger);
        int mutedInk = context.getColor(R.color.muted);
        int addBg = context.getColor(R.color.syntax_diff_add_bg);
        int delBg = context.getColor(R.color.syntax_diff_del_bg);
        LineBackgroundSpan addFill = null;
        LineBackgroundSpan delFill = null;
        for (int i = 0; i < lines.size(); i++) {
            int s = starts[i], e = ends[i];
            if (s >= e) continue;
            switch (lines.get(i).kind) {
                case ADD -> {
                    if (addFill == null) addFill = rowFill(addBg);
                    spanned.setSpan(new ForegroundColorSpan(addInk), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    spanned.setSpan(addFill, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                case REMOVE -> {
                    if (delFill == null) delFill = rowFill(delBg);
                    spanned.setSpan(new ForegroundColorSpan(delInk), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    spanned.setSpan(delFill, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                case HUNK -> {
                    spanned.setSpan(new ForegroundColorSpan(mutedInk), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    spanned.setSpan(new StyleSpan(Typeface.BOLD), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                case CONTEXT -> {}
            }
        }
        return spanned;
    }

    private static LineBackgroundSpan rowFill(int color) {
        return (canvas, paint, left, right, top, baseline, bottom, text, start, end, lineNumber) -> {
            int previous = paint.getColor();
            paint.setColor(color);
            canvas.drawRect(left, top, right, bottom, paint);
            paint.setColor(previous);
        };
    }

    private static void appendFull(StringBuilder full, String line) {
        if (full.length() >= MAX_FULL_CHARS) return;
        if (full.length() > 0) full.append('\n');
        int remaining = MAX_FULL_CHARS - full.length();
        if (line.length() <= remaining) full.append(line);
        else full.append(line, 0, remaining);
    }

    private static List<String> splitLines(String text) {
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                int end = i > start && text.charAt(i - 1) == '\r' ? i - 1 : i;
                out.add(text.substring(start, end));
                start = i + 1;
            }
        }
        if (start <= text.length()) {
            int end = text.length();
            if (end > start && text.charAt(end - 1) == '\r') end--;
            if (start < text.length() || out.isEmpty()) out.add(text.substring(start, end));
        }
        return out;
    }

    private static String sanitizeLine(String line) {
        int nl = line.indexOf('\n');
        if (nl >= 0) line = line.substring(0, nl);
        if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
        return line;
    }

    private static String normalizeRisk(String risk) {
        if (risk == null) return "";
        String norm = risk.trim().toLowerCase(Locale.ROOT);
        return ("low".equals(norm) || "medium".equals(norm) || "high".equals(norm)) ? norm : "";
    }

    private static String stringOf(JSONObject obj, String key) {
        if (obj == null || obj.isNull(key)) return "";
        Object val = obj.opt(key);
        return val instanceof String s ? s : "";
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value;
        }
        return "";
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
