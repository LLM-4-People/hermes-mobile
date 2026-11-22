package org.llm4people.hermes.thread;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.artifact.ArtifactDesk;
import org.llm4people.hermes.artifact.Fences;
import org.llm4people.hermes.artifact.InstrumentPreview;
import org.llm4people.hermes.artifact.ThreadInstruments;
import org.llm4people.hermes.artifact.ThreadInstruments.Pin;
import org.llm4people.hermes.artifact.ThreadInstruments.State;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** A transient native work surface anchored to an authenticated assistant turn. */
final class ThreadInstrumentPanel {
    private final ThreadWorkspace workspace;
    private final Activity activity;
    private final ThreadViews screen;
    private final int generation;
    private final ArtifactDesk.Host actions;
    private final ThreadInstruments store;
    final InstrumentViewport viewport;
    private final Map<String, List<Pin>> byMessage = new HashMap<>();
    private State state;
    private Pin displayed;
    private boolean comparing;
    private long document;
    private View sheet;
    private TextView scope;
    private LinearLayout controls;
    private PopupMenu menu;

    ThreadInstrumentPanel(ThreadWorkspace workspace, ThreadInstruments store, ArtifactDesk.Host actions) {
        this.workspace = workspace;
        activity = workspace.activity;
        screen = workspace.screen;
        generation = workspace.token();
        this.store = store;
        this.actions = actions;
        viewport = screen.attachInstrumentViewport();
        readState();
    }

    private boolean current() { return workspace.token() == generation && workspace.screen == screen; }
    private String t(int res, Object... args) { return activity.getString(res, args); }
    private Pin pinned() { return state == null ? null : state.current; }

    private void readState() {
        try { state = store.state(); }
        catch (IllegalStateException unreadable) { state = null; }
        dock();
    }

    void proposals(JSONArray rows) {
        byMessage.clear();
        try {
            for (Pin pin : store.proposals(rows)) {
                byMessage.computeIfAbsent(pin.messageId, ignored -> new ArrayList<>()).add(pin);
            }
        } catch (IllegalStateException unavailable) { state = null; dock(); }
        if (displayed != null && !displayed.sameSource(pinned()) && !present(displayed)) {
            close();
            discard();
        }
    }

    List<Pin> forRow(JSONObject row) {
        if (row == null || !"assistant".equals(row.opt("role"))) return Collections.emptyList();
        List<Pin> options = byMessage.get(ThreadData.string(row, "id"));
        if (options == null) return Collections.emptyList();
        List<Pin> matches = new ArrayList<>(options.size());
        for (Pin pin : options) if (pin.fromMessage(row)) matches.add(pin);
        return matches;
    }

    String prose(String text, List<Pin> proposals) {
        if (proposals.isEmpty()) return text;
        Map<Integer, String> bodies = new HashMap<>();
        for (Pin pin : proposals) bodies.put(pin.fenceOrdinal, pin.pageJson);
        return Fences.without(text, bodies);
    }

    private boolean present(Pin pin) {
        List<Pin> matches = byMessage.get(pin.messageId);
        if (matches != null) for (Pin item : matches) if (pin.sameSource(item)) return true;
        return false;
    }

    private boolean revision(Pin pin) {
        Pin current = pinned();
        return current != null && current.pageId.equals(pin.pageId) && !current.pageJson.equals(pin.pageJson);
    }

    void proposal(LinearLayout cell, Pin pin) {
        LinearLayout band = Ui.row(activity);
        boolean selected = viewport.open() && pin.sameSource(displayed);
        band.setBackgroundColor(activity.getColor(selected ? R.color.accent_surface : R.color.surface));
        View edge = new View(activity);
        edge.setBackgroundColor(activity.getColor(R.color.accent));
        edge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        band.addView(edge, new LinearLayout.LayoutParams(Ui.dp(activity, 3), -1));
        LinearLayout body = Ui.column(activity);
        Ui.pad(body, 12, 6);
        TextView title = Ui.text(activity, pin.title, 20);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setAccessibilityHeading(true);
        body.addView(title);
        boolean pinned = pin.sameSource(pinned());
        int label = pinned ? R.string.instrument_open_pin : revision(pin) ? R.string.instrument_compare : R.string.instrument_try;
        action(body, label, () -> {
            if (current() && present(pin)) show(pin, revision(pin));
        });
        band.addView(body, new LinearLayout.LayoutParams(0, -2, 1));
        cell.addView(band);
    }

    private void dock() {
        Pin pin = pinned();
        if (state == null) {
            viewport.dock(t(R.string.instrument_unavailable), t(R.string.instrument_retry), t(R.string.instrument_retry), () -> {
                if (current()) { readState(); workspace.refreshInstruments(); }
            });
        } else if (pin != null) {
            viewport.dock(pin.title, t(R.string.instrument_open), t(R.string.instrument_open_pin), () -> {
                if (current() && pin.sameSource(pinned())) show(pin, false);
            });
        } else if (state.canUndo) {
            State expected = state;
            viewport.dock(t(R.string.instrument_unpinned), t(R.string.instrument_undo), t(R.string.instrument_undo),
                    () -> mutate(() -> store.undo(expected)));
        } else viewport.dock("", "", "", null);
    }

    private void show(Pin pin, boolean compare) {
        if (!current() || (!pin.sameSource(pinned()) && !present(pin))) return;
        if (compare && !revision(pin)) compare = false;
        boolean reuse = sheet != null && pin.sameSource(displayed) && comparing == compare;
        displayed = pin;
        comparing = compare;
        if (!reuse) buildSheet(pin);
        updateControls();
        viewport.open(sheet);
        screen.refreshInstrumentRows();
    }

    private void buildSheet(Pin pin) {
        long activeDocument = ++document;
        LinearLayout surface = Ui.column(activity);
        surface.setAccessibilityPaneTitle(pin.title);
        LinearLayout bar = Ui.row(activity);
        Ui.pad(bar, 8, 0);
        Button back = Ui.button(activity, t(R.string.instrument_discussion), v -> close());
        back.setContentDescription(t(R.string.instrument_return));
        back.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        bar.addView(back, new LinearLayout.LayoutParams(0, -2, 1));
        Button overflow = Ui.button(activity, "⋮", v -> options(v, pin, activeDocument));
        overflow.setTextSize(22);
        overflow.setMinWidth(Ui.dp(activity, 48));
        overflow.setContentDescription(t(R.string.instrument_options));
        bar.addView(overflow);
        surface.addView(bar);
        surface.addView(Ui.divider(activity));
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        LinearLayout body = Ui.column(activity);
        Ui.pad(body, 16, 10);
        TextView title = Ui.text(activity, pin.title, 27);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setAccessibilityHeading(true);
        body.addView(title);
        scope = Ui.text(activity, "", 14);
        scope.setTextColor(activity.getColor(R.color.accent));
        Ui.pad(scope, 0, 5);
        body.addView(scope);
        if (comparing) {
            body.addView(InstrumentPreview.compare(activity, pinned().pageJson, pin.pageJson));
        } else {
            body.addView(InstrumentPreview.render(activity, pin.pageJson, rendererHost(pin, activeDocument)));
        }
        controls = Ui.column(activity);
        Ui.pad(controls, 0, 6);
        body.addView(controls);
        scroll.addView(body);
        surface.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        sheet = surface;
    }

    private void updateControls() {
        Pin pin = displayed;
        Pin saved = pinned();
        State expected = state;
        controls.removeAllViews();
        boolean installed = pin.sameSource(saved);
        if (comparing && saved != null) {
            scope.setText(t(R.string.instrument_compare_sources,
                    t(R.string.instrument_current_source, saved.messageId), t(R.string.instrument_candidate_source, pin.messageId)));
            primary(controls, R.string.instrument_adopt, () -> mutate(() -> store.pin(expected, pin, workspace.messages())));
            action(controls, R.string.instrument_keep, () -> show(saved, false));
            action(controls, R.string.instrument_switch, () -> show(pin, false));
            return;
        }
        scope.setText(t(installed ? R.string.instrument_pinned : R.string.instrument_trial));
        if (state == null) {
            action(controls, R.string.instrument_retry, () -> { readState(); updateControls(); });
        } else if (!installed) {
            if (revision(pin)) primary(controls, R.string.instrument_compare, () -> show(pin, true));
            else primary(controls, saved == null ? R.string.instrument_pin : R.string.instrument_trial_replaces,
                    () -> mutate(() -> store.pin(expected, pin, workspace.messages())));
        } else {
            action(controls, R.string.instrument_revise, () -> {
                if (!pin.sameSource(pinned())) return;
                close();
                actions.stageDraft(t(R.string.instrument_revision_draft, pin.title));
            });
            if (state.canUndo) action(controls, R.string.instrument_undo, () -> mutate(() -> store.undo(expected)));
        }
    }

    private Button action(LinearLayout parent, int label, Runnable task) {
        Button b = Ui.button(activity, t(label), v -> { if (current()) task.run(); });
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setSingleLine(false);
        parent.addView(b);
        return b;
    }

    private void primary(LinearLayout parent, int label, Runnable task) {
        Button b = action(parent, label, task);
        android.graphics.drawable.GradientDrawable fill = new android.graphics.drawable.GradientDrawable();
        fill.setColor(activity.getColor(R.color.accent));
        fill.setCornerRadius(Ui.dp(activity, 8));
        b.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(activity.getColor(R.color.accent_surface)), fill, null));
        b.setTextColor(activity.getColor(R.color.on_accent));
        b.setGravity(Gravity.CENTER);
    }

    private void mutate(Supplier<State> write) {
        if (!current()) return;
        try { state = write.get(); }
        catch (ThreadInstruments.Conflict changed) { recover(R.string.instrument_changed); return; }
        catch (IllegalArgumentException gone) { recover(R.string.instrument_no_source); return; }
        catch (IllegalStateException unavailable) { recover(R.string.instrument_unavailable); return; }
        if (pinned() == null) close();
        else show(pinned(), false);
        dock();
        screen.refreshInstrumentRows();
    }

    private void recover(int message) {
        close();
        discard();
        readState();
        workspace.refreshInstruments();
        screen.notice(t(message), t(R.string.instrument_retry), () -> { if (current()) workspace.loadMessages(false, null); });
    }

    private void options(View anchor, Pin pin, long activeDocument) {
        if (!valid(pin, activeDocument)) return;
        if (menu != null) menu.dismiss();
        PopupMenu popup = new PopupMenu(activity, anchor);
        menu = popup;
        popup.getMenu().add(t(R.string.instrument_source)).setOnMenuItemClickListener(item -> {
            if (valid(pin, activeDocument)) screen.show(new AlertDialog.Builder(activity)
                    .setTitle(t(R.string.instrument_source_title, pin.title))
                    .setView(MessageText.reader(activity, pin.pageJson, false, true))
                    .setPositiveButton(t(R.string.instrument_close), null));
            return true;
        });
        if (present(pin)) popup.getMenu().add(t(R.string.instrument_origin)).setOnMenuItemClickListener(item -> {
            if (valid(pin, activeDocument)) { close(); screen.revealProposal(pin); }
            return true;
        });
        if (pin.sameSource(pinned())) {
            State expected = state;
            popup.getMenu().add(t(R.string.instrument_unpin)).setOnMenuItemClickListener(item -> {
                if (valid(pin, activeDocument)) mutate(() -> store.clear(expected));
                return true;
            });
        }
        popup.show();
    }

    private boolean valid(Pin pin, long serial) {
        return current() && viewport.open() && viewport.isShown() && document == serial && pin.sameSource(displayed)
                && (pin.sameSource(pinned()) || present(pin));
    }

    private ArtifactDesk.Host rendererHost(Pin pin, long serial) {
        return new ArtifactDesk.Host() {
            @Override public void onAction(String action) {
                if (!valid(pin, serial)) return;
                if ("previous".equals(action) || "return".equals(action) || "native_thread".equals(action)) close();
                else actions.onAction(action);
            }
            @Override public void openWorkspace(String id) { if (valid(pin, serial)) actions.openWorkspace(id); }
            @Override public void stageDraft(String text) {
                if (valid(pin, serial)) { close(); actions.stageDraft(text); }
            }
            @Override public String workspaceValue(String source) {
                return current() && document == serial ? actions.workspaceValue(source) : "";
            }
            @Override public void onLayoutChanged() { if (valid(pin, serial)) actions.onLayoutChanged(); }
            @Override public void threadSlots(List<String> order) {}
        };
    }

    boolean close() {
        if (!viewport.open()) return false;
        if (menu != null) { menu.dismiss(); menu = null; }
        viewport.close();
        dock();
        screen.refreshInstrumentRows();
        return true;
    }

    void livePrompt() { if (viewport.open() && !viewport.wide()) close(); }

    private void discard() { ++document; displayed = null; sheet = null; }

    void retire() {
        if (viewport.open()) ArtifactDesk.dismiss(activity);
        if (menu != null) { menu.dismiss(); menu = null; }
        byMessage.clear();
        discard();
    }
}
