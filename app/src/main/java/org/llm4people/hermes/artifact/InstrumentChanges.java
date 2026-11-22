package org.llm4people.hermes.artifact;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Semantic differences between two validated, bounded workspace pages. Only declared content is inspected. */
final class InstrumentChanges {
    record Change(String section, String label, String before, String after) {}

    static List<Change> between(WorkspaceLayout old, WorkspaceLayout next) {
        List<Change> changes = new ArrayList<>();
        changed(changes, "", "Écran", old.surface, next.surface);
        changed(changes, "", "Titre", old.title, next.title);
        changed(changes, "", "Apparence", appearance(old.theme), appearance(next.theme));
        changed(changes, "", "Densité", density(old.density), density(next.density));

        int[] previous = InstrumentIdentity.match(old.blocks, next.blocks);
        List<String> labels = InstrumentIdentity.blockLabels(old.blocks);
        List<Integer> oldOrder = new ArrayList<>();
        List<String> newTitles = new ArrayList<>();
        for (int i : previous) {
            if (i >= 0) {
                oldOrder.add(i);
                newTitles.add(labels.get(i));
            }
        }
        Collections.sort(oldOrder);
        List<String> oldTitles = new ArrayList<>();
        for (int i : oldOrder) oldTitles.add(labels.get(i));
        int n = 0;
        for (int i : previous) {
            if (i >= 0 && i != oldOrder.get(n++)) {
                changes.add(new Change("", "Ordre des rubriques", lines(oldTitles), lines(newTitles)));
                break;
            }
        }
        for (int i = 0; i < next.blocks.size(); i++) {
            WorkspaceLayout.Block block = next.blocks.get(i);
            if (previous[i] < 0) changes.add(new Change(block.title, "Rubrique ajoutée", null, describe(block)));
            else blockChanges(changes, old.blocks.get(previous[i]), block);
        }
        for (int i = 0; i < old.blocks.size(); i++) {
            if (!oldOrder.contains(i)) {
                WorkspaceLayout.Block block = old.blocks.get(i);
                changes.add(new Change(block.title, "Rubrique retirée", describe(block), null));
            }
        }
        return changes;
    }

    private static void blockChanges(List<Change> changes, WorkspaceLayout.Block old, WorkspaceLayout.Block next) {
        String group = next.title;
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
                Map<String, String> prior = fields(old), after = fields(next);
                for (DraftForm.Field field : next.fields) {
                    if (!prior.containsKey(field.id))
                        changes.add(new Change(group, "Champ ajouté", null, after.get(field.id)));
                    else changed(changes, group, "Champ modifié", prior.get(field.id), after.get(field.id));
                }
                for (DraftForm.Field field : old.fields) if (!after.containsKey(field.id))
                    changes.add(new Change(group, "Champ retiré", prior.get(field.id), null));
                Map<String,String> labels = InstrumentIdentity.fieldLabels(next.fields);
                order(changes, group, "Ordre des champs", new ArrayList<>(prior.keySet()),
                        new ArrayList<>(after.keySet()), labels);
            }
        }
    }

    // Compare only surviving IDs; inserting a new item ahead of others is not a reorder.
    private static void order(List<Change> changes, String group, String what, List<String> old,
                              List<String> next, Map<String,String> labels) {
        List<String> before = new ArrayList<>(old), after = new ArrayList<>(next);
        before.retainAll(next);
        after.retainAll(old);
        if (before.equals(after)) return;
        before.replaceAll(labels::get);
        after.replaceAll(labels::get);
        changes.add(new Change(group, what, lines(before), lines(after)));
    }

    private static Map<String,String> fields(WorkspaceLayout.Block form) {
        Map<String,String> result = new LinkedHashMap<>();
        for (DraftForm.Field f : form.fields) {
            String details = InstrumentIdentity.kind(f.kind) + (f.required ? " obligatoire" : " facultatif");
            if (!f.options.isEmpty()) details += "\n" + lines(f.options);
            result.put(f.id, f.label + "\n" + details);
        }
        return result;
    }

    static String describe(WorkspaceLayout.Block block) {
        List<String> items = new ArrayList<>();
        switch (block.type) {
            case "form" -> {
                items.add(block.prompt);
                items.addAll(fields(block).values());
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

    private static void changed(List<Change> changes, String group, String what, String old, String next) {
        if (!old.equals(next)) changes.add(new Change(group, what, old.isEmpty() ? null : old,
                next.isEmpty() ? null : next));
    }

    private static String lines(List<String> values) { return String.join("\n", values); }
    private static String density(String value) { return "compact".equals(value) ? "Compacte" : "Confortable"; }
    private static String appearance(String theme) {
        return switch (theme) { case "light" -> "Claire"; case "dark" -> "Sombre"; default -> "Système"; };
    }
}
