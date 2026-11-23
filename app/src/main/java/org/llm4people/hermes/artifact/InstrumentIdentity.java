package org.llm4people.hermes.artifact;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Match blocks without persisted IDs, and name reordered items unambiguously. */
final class InstrumentIdentity {
    private InstrumentIdentity() {}

    static int[] match(List<WorkspaceLayout.Block> old, List<WorkspaceLayout.Block> next) {
        int[] matched = new int[next.size()];
        Arrays.fill(matched, -1);
        boolean[] used = new boolean[old.size()];
        // First consume unchanged blocks anywhere, including independent blocks with the same heading.
        for (int i = 0; i < next.size(); i++) {
            for (int j = 0; j < old.size(); j++) {
                if (!used[j] && identical(old.get(j), next.get(i))) {
                    matched[i] = j;
                    used[j] = true;
                    break;
                }
            }
        }
        // A unique surviving title can be edited. A unique surviving type can be renamed.
        // If either side has multiple candidates, a correspondence would be speculative.
        matchUnique(old, next, matched, used, true);
        matchUnique(old, next, matched, used, false);
        return matched;
    }

    private static void matchUnique(List<WorkspaceLayout.Block> old, List<WorkspaceLayout.Block> next,
                                    int[] matched, boolean[] used, boolean byTitle) {
        for (int i = 0; i < next.size(); i++) {
            if (matched[i] >= 0) continue;
            WorkspaceLayout.Block target = next.get(i);
            int candidate = -1;
            int oldCount = 0, newCount = 0;
            for (int j = 0; j < old.size(); j++) {
                if (!used[j] && matches(old.get(j), target, byTitle)) { candidate = j; oldCount++; }
            }
            for (int j = 0; j < next.size(); j++)
                if (matched[j] < 0 && matches(next.get(j), target, byTitle)) newCount++;
            if (oldCount == 1 && newCount == 1) {
                matched[i] = candidate;
                used[candidate] = true;
            }
        }
    }

    private static boolean matches(WorkspaceLayout.Block a, WorkspaceLayout.Block b, boolean byTitle) {
        return a.type.equals(b.type) && (!byTitle || a.title.equals(b.title));
    }

    private static boolean identical(WorkspaceLayout.Block a, WorkspaceLayout.Block b) {
        if (!matches(a, b, true) || !a.note.equals(b.note) || !a.actions.equals(b.actions)
                || !Objects.equals(a.page, b.page) || !Objects.equals(a.source, b.source)
                || !Objects.equals(a.request, b.request) || !Objects.equals(a.prompt, b.prompt)
                || !Objects.equals(a.category, b.category) || a.radiusMeters != b.radiusMeters || a.limit != b.limit
                || !Objects.equals(a.metrics, b.metrics) || !Objects.equals(a.table, b.table)
                || !Objects.equals(a.controls, b.controls) || !Objects.equals(a.stateKey, b.stateKey)) return false;
        if ((a.sparkline == null) != (b.sparkline == null)) return false;
        if (a.sparkline != null && (!a.sparkline.values.equals(b.sparkline.values)
                || !a.sparkline.style.equals(b.sparkline.style) || !a.sparkline.caption.equals(b.sparkline.caption))) {
            return false;
        }
        if (a.fields == null) return b.fields == null;
        if (a.fields.size() != b.fields.size()) return false;
        for (int i = 0; i < a.fields.size(); i++) {
            DraftForm.Field x = a.fields.get(i), y = b.fields.get(i);
            if (!sameField(x, y)) return false;
        }
        return true;
    }

    static boolean sameField(DraftForm.Field x, DraftForm.Field y) {
        return x.id.equals(y.id) && x.label.equals(y.label) && x.kind.equals(y.kind)
                && x.required == y.required && x.options.equals(y.options);
    }

    static List<String> blockLabels(android.content.Context context, List<WorkspaceLayout.Block> blocks) {
        List<String> titles = new ArrayList<>();
        for (WorkspaceLayout.Block block : blocks) titles.add(block.displayTitle(context));
        List<String> labels = new ArrayList<>(titles);
        for (int i = 0; i < blocks.size(); i++) {
            if (Collections.frequency(titles, titles.get(i)) < 2) continue;
            String detail = InstrumentChanges.describe(context, blocks.get(i));
            int line = detail.indexOf('\n');
            if (line >= 0) detail = detail.substring(0, line);
            if (detail.length() > 64) detail = detail.substring(0, detail.offsetByCodePoints(0,
                    Math.min(60, detail.codePointCount(0, detail.length())))) + "…";
            labels.set(i, titles.get(i) + " · " + detail);
        }
        List<String> qualified = new ArrayList<>(labels);
        for (int i = 0; i < labels.size(); i++) {
            if (Collections.frequency(labels, labels.get(i)) > 1) {
                qualified.set(i, context.getString(org.llm4people.hermes.R.string.ws_diff_block_ordinal,
                        labels.get(i), i + 1));
            }
        }
        // A user-authored title can itself contain a generated qualifier. A position prefix
        // on every block provides an unambiguous reading even in that case.
        if (new HashSet<>(qualified).size() < qualified.size()) {
            for (int i = 0; i < qualified.size(); i++) qualified.set(i, (i + 1) + ". " + qualified.get(i));
        }
        return qualified;
    }

    static Map<String,String> fieldLabels(android.content.Context context, List<DraftForm.Field> fields) {
        List<String> labels = new ArrayList<>();
        for (DraftForm.Field f : fields) labels.add(f.label);
        Map<String,String> qualified = new LinkedHashMap<>();
        for (DraftForm.Field f : fields) {
            String name = f.label;
            if (Collections.frequency(labels, name) > 1) name += " · " + kind(context, f.kind);
            qualified.put(f.id, name);
        }
        List<String> names = new ArrayList<>(qualified.values());
        qualified.replaceAll((id, name) -> Collections.frequency(names, name) > 1 ? name + " (" + id + ")" : name);
        if (new HashSet<>(qualified.values()).size() < qualified.size()) {
            qualified.replaceAll((id, name) -> id + " · " + name);
        }
        return qualified;
    }

    static String kind(android.content.Context context, String kind) {
        int res = switch (kind) {
            case "multiline" -> org.llm4people.hermes.R.string.ws_diff_kind_multiline;
            case "choice" -> org.llm4people.hermes.R.string.ws_diff_kind_choice;
            default -> org.llm4people.hermes.R.string.ws_diff_kind_text;
        };
        return context.getString(res);
    }
}
