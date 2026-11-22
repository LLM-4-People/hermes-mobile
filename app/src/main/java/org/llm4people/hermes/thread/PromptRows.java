package org.llm4people.hermes.thread;

import android.view.View;
import android.widget.ListView;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Stable native transcript footers preserve each prompt editor and its selection during streaming. */
final class PromptRows {
    private final ThreadViews ui;
    private final Map<RunPrompts.Key, PromptCard> cards = new HashMap<>();
    private RunDecisions owner;

    PromptRows(ThreadViews ui) { this.ui = ui; }

    boolean visible() { return !cards.isEmpty(); }

    /** The shell can reveal the same cards when a custom discussion omits the native transcript. */
    Runnable whenHidden(Runnable reveal) {
        RunDecisions source = owner;
        if (!hidden(source)) return null;
        return () -> {
            if (!hidden(source)) return;
            reveal.run();
            ui.list.post(() -> {
                if (owner == source && source.observing() && visible() && ui.list.isShown())
                    ui.list.setSelectionFromTop(ui.list.getCount() - cards.size(), 0);
            });
        };
    }

    private boolean hidden(RunDecisions source) {
        return source != null && source == owner && source.observing() && visible() && !ui.list.isShown();
    }

    void clear() {
        for (View card : cards.values()) ui.list.removeFooterView(card);
        cards.clear();
        owner = null;
    }

    void render(List<JSONObject> prompts, RunDecisions actions) {
        ListView list = ui.list;
        boolean atEnd = list.getLastVisiblePosition() >= list.getCount() - 1;
        if (owner != actions) {
            clear();
            owner = actions;
        }
        Map<RunPrompts.Key, JSONObject> pending = new java.util.LinkedHashMap<>();
        for (JSONObject prompt : prompts) {
            RunPrompts.Key key = RunPrompts.key(prompt);
            if (actions.state(key) != null) pending.put(key, prompt);
        }
        Iterator<Map.Entry<RunPrompts.Key, PromptCard>> it = cards.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<RunPrompts.Key, PromptCard> entry = it.next();
            if (!pending.containsKey(entry.getKey())) {
                list.removeFooterView(entry.getValue());
                it.remove();
            }
        }
        int firstAdded = -1;
        for (Map.Entry<RunPrompts.Key, JSONObject> entry : pending.entrySet()) {
            RunPrompts.Key key = entry.getKey();
            JSONObject prompt = entry.getValue();
            PromptCard card = cards.get(key);
            if (card == null) {
                if (firstAdded == -1) firstAdded = list.getCount();
                card = new PromptCard(ui, prompt, value -> actions.respond(prompt, value),
                        () -> actions.verify(prompt));
                cards.put(key, card);
                list.addFooterView(card, null, true);
            }
            card.state(actions.state(key));
        }
        if (firstAdded >= 0 && atEnd && !list.hasFocus()) {
            int target = firstAdded;
            list.post(() -> { if (owner == actions && !list.hasFocus()) list.setSelectionFromTop(target, 0); });
        }
    }
}
