package org.llm4people.hermes.thread;

import android.graphics.Rect;
import android.view.View;
import android.widget.AbsListView;
import android.widget.ListView;
import org.llm4people.hermes.Ui;
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

    private final Rect viewport = new Rect();
    private boolean wasInView;
    private boolean checkingViewport;

    PromptRows(ThreadViews ui) {
        this.ui = ui;
        ui.list.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> checkViewport());
    }

    void onScroll() {
        checkViewport();
    }

    private void checkViewport() {
        if (owner == null || !visible() || checkingViewport) return;
        checkingViewport = true;
        ui.list.postOnAnimation(() -> {
            checkingViewport = false;
            if (owner == null) return;
            boolean inView = cardInViewport();
            if (inView != wasInView) {
                wasInView = inView;
                owner.viewportChanged();
            }
        });
    }

    private boolean cardInViewport() {
        if (!ui.list.isShown()) return false;
        // An unmeasured native page is about to lay out its footers. Check the resulting viewport.
        if (!ui.list.isLaidOut()) return true;
        int minHeight = Math.min(ui.list.getHeight(), Ui.dp(ui.activity, 48));
        for (PromptCard card : cards.values()) {
            if (card.isShown() && card.getGlobalVisibleRect(viewport)
                    && viewport.height() >= Math.min(minHeight, card.getHeight())) return true;
        }
        return false;
    }

    boolean visible() { return !cards.isEmpty(); }

    /** Reveal the current cards without leaving a readable transcript or moving it until tapped. */
    Runnable whenHidden(Runnable reveal) {
        RunDecisions source = owner;
        wasInView = cardInViewport();
        if (source == null || !source.observing() || !visible() || wasInView) return null;
        return () -> {
            if (owner != source || !source.observing() || !visible() || cardInViewport()) return;
            if (!ui.list.isShown()) reveal.run();
            ui.list.post(() -> {
                if (owner == source && source.observing() && visible() && ui.list.isShown())
                    ui.list.setSelectionFromTop(ui.list.getCount() - cards.size(), 0);
            });
        };
    }

    void clear() {
        for (View card : cards.values()) ui.list.removeFooterView(card);
        cards.clear();
        owner = null;
        wasInView = false;
    }

    void render(List<JSONObject> prompts, RunDecisions actions) {
        ListView list = ui.list;
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
        for (Map.Entry<RunPrompts.Key, JSONObject> entry : pending.entrySet()) {
            RunPrompts.Key key = entry.getKey();
            JSONObject prompt = entry.getValue();
            PromptCard card = cards.get(key);
            if (card == null) {
                card = new PromptCard(ui, prompt, value -> actions.respond(prompt, value),
                        () -> actions.verify(prompt));
                cards.put(key, card);
                list.addFooterView(card, null, true);
            }
            card.state(actions.state(key));
        }
    }
}
