package org.llm4people.hermes.artifact;

import android.content.Context;
import org.llm4people.hermes.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Semantic differences between two validated, bounded workspace pages. Only declared content is inspected. */
final class InstrumentChanges {
    record Change(String section, String label, String before, String after) {}

    record FieldDescription(String name, String requirement, List<String> options) {
        String text() { return name + "\n" + requirement + (options.isEmpty() ? "" : "\n" + lines(options)); }
    }
    record Detail(Change change, FieldDescription beforeField, FieldDescription afterField) {}

    private static final class Report {
        final Context context;
        final List<Detail> details = new ArrayList<>();
        Report(Context context) { this.context = context; }
        void add(Change change) { details.add(new Detail(change, null, null)); }
        void field(String group, FieldDescription before, FieldDescription after) {
            int labelRes = before == null ? R.string.ws_diff_field_added
                    : after == null ? R.string.ws_diff_field_removed : R.string.ws_diff_field_changed;
            Change change = new Change(group, context.getString(labelRes),
                    before == null ? null : before.text(),
                    after == null ? null : after.text());
            details.add(new Detail(change, before, after));
        }
    }

    static List<Change> between(Context context, WorkspaceLayout old, WorkspaceLayout next) {
        List<Change> changes = new ArrayList<>();
        for (Detail detail : details(context, old, next)) changes.add(detail.change());
        return changes;
    }

    static List<Detail> details(Context c, WorkspaceLayout old, WorkspaceLayout next) {
        Report changes = new Report(c);
        changed(changes, "", c.getString(R.string.ws_diff_screen), old.surface, next.surface);
        changed(changes, "", c.getString(R.string.ws_diff_title), old.title, next.title);
        changed(changes, "", c.getString(R.string.ws_diff_appearance), appearance(c, old.theme), appearance(c, next.theme));
        changed(changes, "", c.getString(R.string.ws_diff_density), density(c, old.density), density(c, next.density));
        changed(changes, "", c.getString(R.string.ws_diff_accent), old.accent == null ? "" : old.accent,
                next.accent == null ? "" : next.accent);
        changed(changes, "", c.getString(R.string.ws_diff_surface_style),
                surfaceStyle(c, old.surfaceStyle), surfaceStyle(c, next.surfaceStyle));

        int[] previous = InstrumentIdentity.match(old.blocks, next.blocks);
        List<String> oldLabels = InstrumentIdentity.blockLabels(c, old.blocks);
        List<String> nextLabels = InstrumentIdentity.blockLabels(c, next.blocks);
        List<Integer> oldOrder = new ArrayList<>();
        List<String> newTitles = new ArrayList<>();
        for (int i : previous) {
            if (i >= 0) {
                oldOrder.add(i);
                newTitles.add(oldLabels.get(i));
            }
        }
        Collections.sort(oldOrder);
        List<String> oldTitles = new ArrayList<>();
        for (int i : oldOrder) oldTitles.add(oldLabels.get(i));
        int n = 0;
        for (int i : previous) {
            if (i >= 0 && i != oldOrder.get(n++)) {
                changes.add(new Change("", c.getString(R.string.ws_diff_section_order),
                        lines(oldTitles), lines(newTitles)));
                break;
            }
        }
        for (int i = 0; i < next.blocks.size(); i++) {
            WorkspaceLayout.Block block = next.blocks.get(i);
            String group = nextLabels.get(i);
            if (previous[i] < 0) {
                changes.add(new Change(group, c.getString(R.string.ws_diff_section_added), null, describe(c, block)));
            } else {
                WorkspaceLayout.Block prior = old.blocks.get(previous[i]);
                String priorGroup = oldLabels.get(previous[i]);
                if (!priorGroup.equals(prior.displayTitle(c)) && !priorGroup.equals(group)) group = priorGroup + " → " + group;
                blockChanges(c, changes, group, prior, block);
            }
        }
        for (int i = 0; i < old.blocks.size(); i++) {
            if (!oldOrder.contains(i)) {
                WorkspaceLayout.Block block = old.blocks.get(i);
                changes.add(new Change(oldLabels.get(i), c.getString(R.string.ws_diff_section_removed),
                        describe(c, block), null));
            }
        }
        return changes.details;
    }

    private static void blockChanges(Context c, Report changes, String group, WorkspaceLayout.Block old,
                                     WorkspaceLayout.Block next) {
        changed(changes, group, c.getString(R.string.ws_diff_section_title), old.displayTitle(c), next.displayTitle(c));
        switch (next.type) {
            case "device" -> changed(changes, group, c.getString(R.string.ws_diff_transmitted_data),
                    old.request.preview(c), next.request.preview(c));
            case "link" -> changed(changes, group, c.getString(R.string.ws_diff_destination), old.page, next.page);
            case "value" -> changed(changes, group, c.getString(R.string.ws_diff_displayed_value),
                    WorkspaceBlocks.sourceLabel(c, old.source), WorkspaceBlocks.sourceLabel(c, next.source));
            case "section" -> {
                int labelRes = R.string.ws_diff_text;
                if (old.note.isEmpty()) labelRes = R.string.ws_diff_text_added;
                else if (next.note.isEmpty()) labelRes = R.string.ws_diff_text_removed;
                changed(changes, group, c.getString(labelRes), old.note, next.note);
                Map<String, String> actionLabels = WorkspaceLayout.actionLabels(c);
                for (String id : next.actions) if (!old.actions.contains(id))
                    changes.add(new Change(group, c.getString(R.string.ws_diff_action_added), null, actionLabels.get(id)));
                for (String id : old.actions) if (!next.actions.contains(id))
                    changes.add(new Change(group, c.getString(R.string.ws_diff_action_removed), actionLabels.get(id), null));
                order(changes, group, c.getString(R.string.ws_diff_action_order), old.actions, next.actions, actionLabels);
            }
            case "form" -> {
                changed(changes, group, c.getString(R.string.ws_diff_draft_prompt), old.prompt, next.prompt);
                Map<String, DraftForm.Field> prior = new LinkedHashMap<>();
                for (DraftForm.Field field : old.fields) prior.put(field.id, field);
                Map<String,String> oldNames = InstrumentIdentity.fieldLabels(c, old.fields);
                Map<String,String> nextNames = InstrumentIdentity.fieldLabels(c, next.fields);
                for (DraftForm.Field field : next.fields) {
                    DraftForm.Field oldField = prior.get(field.id);
                    if (oldField == null) changes.field(group, null, describe(c, field, nextNames.get(field.id)));
                    else if (!InstrumentIdentity.sameField(oldField, field)) changes.field(group,
                            describe(c, oldField, oldNames.get(field.id)), describe(c, field, nextNames.get(field.id)));
                }
                for (DraftForm.Field field : old.fields) if (!nextNames.containsKey(field.id))
                    changes.field(group, describe(c, field, oldNames.get(field.id)), null);
                order(changes, group, c.getString(R.string.ws_diff_field_order), new ArrayList<>(oldNames.keySet()),
                        new ArrayList<>(nextNames.keySet()), nextNames);
            }
            case "nearby_places" -> {
                changed(changes, group, c.getString(R.string.ws_diff_category), old.category == null ? "" : old.category,
                        next.category == null ? "" : next.category);
                changed(changes, group, c.getString(R.string.ws_diff_radius),
                        c.getString(R.string.ws_diff_radius_meters, old.radiusMeters),
                        c.getString(R.string.ws_diff_radius_meters, next.radiusMeters));
                changed(changes, group, c.getString(R.string.ws_diff_max_places),
                        Integer.toString(old.limit), Integer.toString(next.limit));
            }
            case "metric_grid" -> {
                changed(changes, group, c.getString(R.string.ws_diff_description), old.note, next.note);
                changed(changes, group, c.getString(R.string.ws_diff_metrics),
                        describeMetrics(c, old.metrics), describeMetrics(c, next.metrics));
            }
            case "sparkline" -> {
                changed(changes, group, c.getString(R.string.ws_diff_description), old.note, next.note);
                changed(changes, group, c.getString(R.string.ws_diff_chart_style),
                        old.sparkline == null ? "" : old.sparkline.style,
                        next.sparkline == null ? "" : next.sparkline.style);
                changed(changes, group, c.getString(R.string.ws_diff_caption),
                        old.sparkline == null ? "" : old.sparkline.caption,
                        next.sparkline == null ? "" : next.sparkline.caption);
                changed(changes, group, c.getString(R.string.ws_diff_unit),
                        old.sparkline == null ? "" : old.sparkline.unit,
                        next.sparkline == null ? "" : next.sparkline.unit);
                changed(changes, group, c.getString(R.string.ws_diff_target),
                        old.sparkline == null || old.sparkline.target == null ? "" : String.valueOf(old.sparkline.target),
                        next.sparkline == null || next.sparkline.target == null ? "" : String.valueOf(next.sparkline.target));
                changed(changes, group, c.getString(R.string.ws_diff_values),
                        old.sparkline == null ? "" : old.sparkline.values.toString(),
                        next.sparkline == null ? "" : next.sparkline.values.toString());
            }
            case "table" -> {
                changed(changes, group, c.getString(R.string.ws_diff_description), old.note, next.note);
                changed(changes, group, c.getString(R.string.ws_diff_sortable),
                        c.getString(old.table != null && old.table.sortable ? R.string.ws_enabled : R.string.ws_disabled),
                        c.getString(next.table != null && next.table.sortable ? R.string.ws_enabled : R.string.ws_disabled));
                changed(changes, group, c.getString(R.string.ws_diff_filterable),
                        c.getString(old.table != null && old.table.filterable ? R.string.ws_enabled : R.string.ws_disabled),
                        c.getString(next.table != null && next.table.filterable ? R.string.ws_enabled : R.string.ws_disabled));
                changed(changes, group, c.getString(R.string.ws_diff_draft_prompt),
                        old.table == null ? "" : old.table.draftPrompt,
                        next.table == null ? "" : next.table.draftPrompt);
                changed(changes, group, c.getString(R.string.ws_diff_columns),
                        describeColumns(c, old.table), describeColumns(c, next.table));
                changed(changes, group, c.getString(R.string.ws_diff_rows),
                        describeRows(old.table), describeRows(next.table));
            }
            case "controls" -> {
                changed(changes, group, c.getString(R.string.ws_diff_description), old.note, next.note);
                changed(changes, group, c.getString(R.string.ws_diff_state_id), old.stateKey == null ? "" : old.stateKey,
                        next.stateKey == null ? "" : next.stateKey);
                changed(changes, group, c.getString(R.string.ws_diff_draft_prompt), old.prompt == null ? "" : old.prompt,
                        next.prompt == null ? "" : next.prompt);
                changed(changes, group, c.getString(R.string.ws_diff_controls),
                        describeControls(c, old.controls), describeControls(c, next.controls));
            }
        }
    }

    // Compare only surviving IDs; inserting a new item ahead of others is not a reorder.
    private static void order(Report changes, String group, String what, List<String> old,
                              List<String> next, Map<String,String> labels) {
        List<String> before = new ArrayList<>(old), after = new ArrayList<>(next);
        before.retainAll(next);
        after.retainAll(old);
        if (before.equals(after)) return;
        before.replaceAll(labels::get);
        after.replaceAll(labels::get);
        changes.add(new Change(group, what, lines(before), lines(after)));
    }

    private static FieldDescription describe(Context c, DraftForm.Field field, String name) {
        String kindLabel = InstrumentIdentity.kind(c, field.kind);
        String details = c.getString(field.required
                ? R.string.ws_diff_required_suffix : R.string.ws_diff_optional_suffix, kindLabel);
        return new FieldDescription(name, details, field.options);
    }

    static String describe(Context c, WorkspaceLayout.Block block) {
        List<String> items = new ArrayList<>();
        switch (block.type) {
            case "form" -> {
                items.add(block.prompt);
                Map<String,String> names = InstrumentIdentity.fieldLabels(c, block.fields);
                for (DraftForm.Field field : block.fields) items.add(describe(c, field, names.get(field.id)).text());
            }
            case "section" -> {
                if (!block.note.isEmpty()) items.add(block.note);
                for (String id : block.actions) items.add(WorkspaceLayout.actionLabel(c, id));
            }
            case "nearby_places" -> items.add(c.getString(R.string.ws_diff_osm_attribution, block.displayTitle(c)));
            case "device" -> items.add(block.request.preview(c));
            case "link" -> items.add(c.getString(R.string.ws_diff_open_page, block.page));
            case "value" -> items.add(WorkspaceBlocks.sourceLabel(c, block.source));
            case "metric_grid" -> {
                if (block.metrics != null) {
                    for (MetricGridBlock.Item m : block.metrics) {
                        items.add(m.label + " : " + m.formattedValue(c, ""));
                    }
                }
            }
            case "sparkline" -> {
                if (block.sparkline != null) items.add(block.sparkline.accessibilitySummary(c));
            }
            case "table" -> {
                if (block.table != null) {
                    items.add(c.getString(R.string.ws_diff_table_dims,
                            block.table.columns.size(), block.table.rows.size()));
                }
            }
            case "controls" -> {
                if (block.controls != null) {
                    for (InteractiveControlsBlock.Control ctrl : block.controls) items.add(ctrl.label);
                }
            }
        }
        return String.join("\n\n", items);
    }

    private static void changed(Report changes, String group, String what, String old, String next) {
        if (!old.equals(next)) changes.add(new Change(group, what, old.isEmpty() ? null : old,
                next.isEmpty() ? null : next));
    }

    private static String lines(List<String> values) { return String.join("\n", values); }
    private static String density(Context c, String value) {
        return c.getString(switch (value) {
            case "compact" -> R.string.ws_diff_density_compact;
            case "spacious" -> R.string.ws_diff_density_spacious;
            default -> R.string.ws_diff_density_comfortable;
        });
    }
    private static String surfaceStyle(Context c, String style) {
        if (style == null) return c.getString(R.string.ws_diff_style_paper);
        return c.getString(switch (style) {
            case "outlined" -> R.string.ws_diff_style_outlined;
            case "tinted" -> R.string.ws_diff_style_tinted;
            case "elevated" -> R.string.ws_diff_style_elevated;
            case "glass" -> R.string.ws_diff_style_glass;
            default -> R.string.ws_diff_style_paper;
        });
    }
    private static String appearance(Context c, String theme) {
        return c.getString(switch (theme) {
            case "light" -> R.string.ws_diff_theme_light;
            case "dark" -> R.string.ws_diff_theme_dark;
            default -> R.string.ws_diff_theme_system;
        });
    }
    private static String describeMetrics(Context c, List<MetricGridBlock.Item> items) {
        if (items == null) return "";
        List<String> rows = new ArrayList<>();
        for (MetricGridBlock.Item m : items) {
            String src = m.source != null ? "source=" + m.source : m.formattedValue(c, "");
            rows.add(m.label + " · " + src + (m.delta.isEmpty() ? "" : " (" + m.delta + ")")
                    + (m.provenance.isEmpty() ? "" : " · " + m.provenance));
        }
        return lines(rows);
    }
    private static String describeColumns(Context c, DataTableBlock.Model table) {
        if (table == null) return "";
        List<String> cols = new ArrayList<>();
        for (DataTableBlock.Column col : table.columns) {
            cols.add(col.numeric ? c.getString(R.string.ws_diff_numeric_col, col.label) : col.label);
        }
        return lines(cols);
    }
    private static String describeRows(DataTableBlock.Model table) {
        if (table == null) return "";
        List<String> rows = new ArrayList<>();
        for (List<String> row : table.rows) rows.add(String.join(" · ", row));
        return lines(rows);
    }
    private static String describeControls(Context c, List<InteractiveControlsBlock.Control> controls) {
        if (controls == null) return "";
        List<String> items = new ArrayList<>();
        for (InteractiveControlsBlock.Control ctrl : controls) {
            String extra = switch (ctrl.kind) {
                case "slider" -> c.getString(R.string.ws_diff_slider_spec,
                        String.valueOf(ctrl.min), String.valueOf(ctrl.max),
                        String.valueOf(ctrl.step), String.valueOf(ctrl.defaultNumber));
                case "chips" -> {
                    String joined = String.join(", ", ctrl.options);
                    yield ctrl.multi ? c.getString(R.string.ws_diff_chips_multi, joined) : joined;
                }
                default -> c.getString(ctrl.defaultChecked
                        ? R.string.ws_diff_checked_default : R.string.ws_diff_unchecked_default);
            };
            items.add(ctrl.label + " (" + ctrl.kind + ") · " + extra);
        }
        return lines(items);
    }
}
