package org.llm4people.hermes.artifact;

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
        final List<Detail> details = new ArrayList<>();
        void add(Change change) { details.add(new Detail(change, null, null)); }
        void field(String group, FieldDescription before, FieldDescription after) {
            String label = before == null ? "Champ ajouté" : after == null ? "Champ retiré" : "Champ modifié";
            Change change = new Change(group, label, before == null ? null : before.text(),
                    after == null ? null : after.text());
            details.add(new Detail(change, before, after));
        }
    }

    static List<Change> between(WorkspaceLayout old, WorkspaceLayout next) {
        List<Change> changes = new ArrayList<>();
        for (Detail detail : details(old, next)) changes.add(detail.change());
        return changes;
    }

    static List<Detail> details(WorkspaceLayout old, WorkspaceLayout next) {
        Report changes = new Report();
        changed(changes, "", "Écran", old.surface, next.surface);
        changed(changes, "", "Titre", old.title, next.title);
        changed(changes, "", "Apparence", appearance(old.theme), appearance(next.theme));
        changed(changes, "", "Densité", density(old.density), density(next.density));
        changed(changes, "", "Couleur d’accent", old.accent == null ? "" : old.accent,
                next.accent == null ? "" : next.accent);
        changed(changes, "", "Style de surface", surfaceStyle(old.surfaceStyle), surfaceStyle(next.surfaceStyle));

        int[] previous = InstrumentIdentity.match(old.blocks, next.blocks);
        List<String> oldLabels = InstrumentIdentity.blockLabels(old.blocks);
        List<String> nextLabels = InstrumentIdentity.blockLabels(next.blocks);
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
                changes.add(new Change("", "Ordre des rubriques", lines(oldTitles), lines(newTitles)));
                break;
            }
        }
        for (int i = 0; i < next.blocks.size(); i++) {
            WorkspaceLayout.Block block = next.blocks.get(i);
            String group = nextLabels.get(i);
            if (previous[i] < 0) changes.add(new Change(group, "Rubrique ajoutée", null, describe(block)));
            else {
                WorkspaceLayout.Block prior = old.blocks.get(previous[i]);
                String priorGroup = oldLabels.get(previous[i]);
                if (!priorGroup.equals(prior.title) && !priorGroup.equals(group)) group = priorGroup + " → " + group;
                blockChanges(changes, group, prior, block);
            }
        }
        for (int i = 0; i < old.blocks.size(); i++) {
            if (!oldOrder.contains(i)) {
                WorkspaceLayout.Block block = old.blocks.get(i);
                changes.add(new Change(oldLabels.get(i), "Rubrique retirée", describe(block), null));
            }
        }
        return changes.details;
    }

    private static void blockChanges(Report changes, String group, WorkspaceLayout.Block old,
                                     WorkspaceLayout.Block next) {
        changed(changes, group, "Titre de la rubrique", old.title, next.title);
        switch (next.type) {
            case "device" -> changed(changes, group, "Données transmises", old.request.preview(), next.request.preview());
            case "link" -> changed(changes, group, "Destination", old.page, next.page);
            case "value" -> changed(changes, group, "Valeur affichée", WorkspaceBlocks.sourceLabel(old.source),
                    WorkspaceBlocks.sourceLabel(next.source));
            case "section" -> {
                String label = "Texte";
                if (old.note.isEmpty()) label = "Texte ajouté";
                else if (next.note.isEmpty()) label = "Texte retiré";
                changed(changes, group, label, old.note, next.note);
                for (String id : next.actions) if (!old.actions.contains(id))
                    changes.add(new Change(group, "Action ajoutée", null, WorkspaceLayout.ACTIONS.get(id)));
                for (String id : old.actions) if (!next.actions.contains(id))
                    changes.add(new Change(group, "Action retirée", WorkspaceLayout.ACTIONS.get(id), null));
                order(changes, group, "Ordre des actions", old.actions, next.actions, WorkspaceLayout.ACTIONS);
            }
            case "form" -> {
                changed(changes, group, "Consigne du brouillon", old.prompt, next.prompt);
                Map<String, DraftForm.Field> prior = new LinkedHashMap<>();
                for (DraftForm.Field field : old.fields) prior.put(field.id, field);
                Map<String,String> oldNames = InstrumentIdentity.fieldLabels(old.fields);
                Map<String,String> nextNames = InstrumentIdentity.fieldLabels(next.fields);
                for (DraftForm.Field field : next.fields) {
                    DraftForm.Field oldField = prior.get(field.id);
                    if (oldField == null) changes.field(group, null, describe(field, nextNames.get(field.id)));
                    else if (!InstrumentIdentity.sameField(oldField, field)) changes.field(group,
                            describe(oldField, oldNames.get(field.id)), describe(field, nextNames.get(field.id)));
                }
                for (DraftForm.Field field : old.fields) if (!nextNames.containsKey(field.id))
                    changes.field(group, describe(field, oldNames.get(field.id)), null);
                order(changes, group, "Ordre des champs", new ArrayList<>(oldNames.keySet()),
                        new ArrayList<>(nextNames.keySet()), nextNames);
            }
            case "nearby_places" -> {
                changed(changes, group, "Catégorie", old.category == null ? "" : old.category,
                        next.category == null ? "" : next.category);
                changed(changes, group, "Rayon de recherche", old.radiusMeters + " m", next.radiusMeters + " m");
                changed(changes, group, "Nombre maximal de lieux", Integer.toString(old.limit), Integer.toString(next.limit));
            }
            case "metric_grid" -> {
                changed(changes, group, "Description", old.note, next.note);
                changed(changes, group, "Indicateurs", describeMetrics(old.metrics), describeMetrics(next.metrics));
            }
            case "sparkline" -> {
                changed(changes, group, "Description", old.note, next.note);
                changed(changes, group, "Style de graphique",
                        old.sparkline == null ? "" : old.sparkline.style,
                        next.sparkline == null ? "" : next.sparkline.style);
                changed(changes, group, "Légende",
                        old.sparkline == null ? "" : old.sparkline.caption,
                        next.sparkline == null ? "" : next.sparkline.caption);
                changed(changes, group, "Unité",
                        old.sparkline == null ? "" : old.sparkline.unit,
                        next.sparkline == null ? "" : next.sparkline.unit);
                changed(changes, group, "Objectif",
                        old.sparkline == null || old.sparkline.target == null ? "" : String.valueOf(old.sparkline.target),
                        next.sparkline == null || next.sparkline.target == null ? "" : String.valueOf(next.sparkline.target));
                changed(changes, group, "Série de valeurs",
                        old.sparkline == null ? "" : old.sparkline.values.toString(),
                        next.sparkline == null ? "" : next.sparkline.values.toString());
            }
            case "table" -> {
                changed(changes, group, "Description", old.note, next.note);
                changed(changes, group, "Tri interactif",
                        old.table != null && old.table.sortable ? "Activé" : "Désactivé",
                        next.table != null && next.table.sortable ? "Activé" : "Désactivé");
                changed(changes, group, "Filtre interactif",
                        old.table != null && old.table.filterable ? "Activé" : "Désactivé",
                        next.table != null && next.table.filterable ? "Activé" : "Désactivé");
                changed(changes, group, "Consigne du brouillon",
                        old.table == null ? "" : old.table.draftPrompt,
                        next.table == null ? "" : next.table.draftPrompt);
                changed(changes, group, "Colonnes", describeColumns(old.table), describeColumns(next.table));
                changed(changes, group, "Lignes", describeRows(old.table), describeRows(next.table));
            }
            case "controls" -> {
                changed(changes, group, "Description", old.note, next.note);
                changed(changes, group, "Identifiant d’état", old.stateKey == null ? "" : old.stateKey,
                        next.stateKey == null ? "" : next.stateKey);
                changed(changes, group, "Consigne du brouillon", old.prompt == null ? "" : old.prompt,
                        next.prompt == null ? "" : next.prompt);
                changed(changes, group, "Contrôles", describeControls(old.controls), describeControls(next.controls));
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

    private static FieldDescription describe(DraftForm.Field field, String name) {
        String details = InstrumentIdentity.kind(field.kind) + (field.required ? " obligatoire" : " facultatif");
        return new FieldDescription(name, details, field.options);
    }

    static String describe(WorkspaceLayout.Block block) {
        List<String> items = new ArrayList<>();
        switch (block.type) {
            case "form" -> {
                items.add(block.prompt);
                Map<String,String> names = InstrumentIdentity.fieldLabels(block.fields);
                for (DraftForm.Field field : block.fields) items.add(describe(field, names.get(field.id)).text());
            }
            case "section" -> {
                if (!block.note.isEmpty()) items.add(block.note);
                for (String id : block.actions) items.add(WorkspaceLayout.ACTIONS.get(id));
            }
            case "nearby_places" -> items.add(block.title + ", selon OpenStreetMap.");
            case "device" -> items.add(block.request.preview());
            case "link" -> items.add("Ouvrir " + block.page);
            case "value" -> items.add(WorkspaceBlocks.sourceLabel(block.source));
            case "metric_grid" -> {
                if (block.metrics != null) {
                    for (MetricGridBlock.Item m : block.metrics) items.add(m.label + " : " + m.formattedValue(""));
                }
            }
            case "sparkline" -> {
                if (block.sparkline != null) items.add(block.sparkline.accessibilitySummary());
            }
            case "table" -> {
                if (block.table != null) {
                    items.add(block.table.columns.size() + " colonnes · " + block.table.rows.size() + " lignes");
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
    private static String density(String value) {
        return switch (value) {
            case "compact" -> "Compacte";
            case "spacious" -> "Aérée";
            default -> "Confortable";
        };
    }
    private static String surfaceStyle(String style) {
        if (style == null) return "Papier";
        return switch (style) {
            case "outlined" -> "Encadrée";
            case "tinted" -> "Teintée";
            case "elevated" -> "Relevée";
            case "glass" -> "Translucide";
            default -> "Papier";
        };
    }
    private static String appearance(String theme) {
        return switch (theme) { case "light" -> "Claire"; case "dark" -> "Sombre"; default -> "Système"; };
    }
    private static String describeMetrics(List<MetricGridBlock.Item> items) {
        if (items == null) return "";
        List<String> rows = new ArrayList<>();
        for (MetricGridBlock.Item m : items) {
            String src = m.source != null ? "source=" + m.source : m.formattedValue("");
            rows.add(m.label + " · " + src + (m.delta.isEmpty() ? "" : " (" + m.delta + ")")
                    + (m.provenance.isEmpty() ? "" : " · " + m.provenance));
        }
        return lines(rows);
    }
    private static String describeColumns(DataTableBlock.Model table) {
        if (table == null) return "";
        List<String> cols = new ArrayList<>();
        for (DataTableBlock.Column c : table.columns) cols.add(c.label + (c.numeric ? " (numérique)" : ""));
        return lines(cols);
    }
    private static String describeRows(DataTableBlock.Model table) {
        if (table == null) return "";
        List<String> rows = new ArrayList<>();
        for (List<String> row : table.rows) rows.add(String.join(" · ", row));
        return lines(rows);
    }
    private static String describeControls(List<InteractiveControlsBlock.Control> controls) {
        if (controls == null) return "";
        List<String> items = new ArrayList<>();
        for (InteractiveControlsBlock.Control ctrl : controls) {
            String extra = switch (ctrl.kind) {
                case "slider" -> ctrl.min + "–" + ctrl.max + " (pas " + ctrl.step + ", défaut " + ctrl.defaultNumber + ")";
                case "chips" -> String.join(", ", ctrl.options) + (ctrl.multi ? " · multiple" : "");
                default -> ctrl.defaultChecked ? "activé par défaut" : "désactivé par défaut";
            };
            items.add(ctrl.label + " (" + ctrl.kind + ") · " + extra);
        }
        return lines(items);
    }
}
