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
            case "link" -> changed(changes, group, "Destination", old.page, next.page);
            case "value" -> changed(changes, group, "Valeur affichée", WorkspaceBlocks.sourceLabel(old.source),
                    WorkspaceBlocks.sourceLabel(next.source));
            case "section" -> {
                changed(changes, group, "Texte", old.note, next.note);
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
            case "link" -> items.add("Ouvrir " + block.page);
            case "value" -> items.add(WorkspaceBlocks.sourceLabel(block.source));
        }
        return String.join("\n\n", items);
    }

    private static void changed(Report changes, String group, String what, String old, String next) {
        if (!old.equals(next)) changes.add(new Change(group, what, old.isEmpty() ? null : old,
                next.isEmpty() ? null : next));
    }

    private static String lines(List<String> values) { return String.join("\n", values); }
    private static String density(String value) { return "compact".equals(value) ? "Compacte" : "Confortable"; }
    private static String appearance(String theme) {
        return switch (theme) { case "light" -> "Claire"; case "dark" -> "Sombre"; default -> "Système"; };
    }
}
