package org.llm4people.hermes.thread;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
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
    private ThreadInstruments.Damaged damaged;
    private Pin displayed;
    private boolean comparing;
    private long document;
    private View sheet;
    private TextView heading;
    private TextView scope;
    private FrameLayout content;
    private View changes;
    private final List<Form> forms = new ArrayList<>(2);
    private Form visibleForm;

    private static final class Form {
        final Pin pin;
        View view;
        ArtifactDesk.Host host;
        Form(Pin pin) { this.pin = pin; }
    }

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
        damaged = null;
        try { state = store.state(); }
        catch (ThreadInstruments.Damaged broken) { state = null; damaged = broken; }
        catch (IllegalStateException unreadable) { state = null; }
        dock();
    }

    void proposals(JSONArray rows) {
        byMessage.clear();
        try {
            for (Pin pin : store.proposals(rows)) {
                byMessage.computeIfAbsent(pin.messageId, ignored -> new ArrayList<>()).add(pin);
            }
        } catch (IllegalStateException unavailable) { readState(); }
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

    static String spoken(Context context, String text, List<Pin> proposals) {
        if (proposals.isEmpty()) return text;
        Map<Integer, String> titles = new HashMap<>();
        for (Pin pin : proposals) titles.put(pin.fenceOrdinal, pin.title);
        return Fences.described(text, bodies(proposals), ordinal ->
                context.getString(R.string.instrument_spoken, titles.get(ordinal)));
    }

    private static Map<Integer, String> bodies(List<Pin> proposals) {
        Map<Integer, String> bodies = new HashMap<>();
        for (Pin pin : proposals) bodies.put(pin.fenceOrdinal, pin.pageJson);
        return bodies;
    }

    String prose(String text, List<Pin> proposals) {
        if (proposals.isEmpty()) return text;
        String prose = Fences.without(text, bodies(proposals));
        return prose.trim().isEmpty() ? "" : prose;
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
        LinearLayout body = Ui.column(activity);
        boolean selected = viewport.open() && pin.sameSource(displayed);
        body.setSelected(selected);
        if (selected) body.setBackgroundColor(activity.getColor(R.color.accent_surface));
        else body.addView(Ui.divider(activity));
        Ui.pad(body, 8, 6);
        TextView title = Ui.text(activity, pin.title, 20);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setAccessibilityHeading(true);
        body.addView(title);
        boolean pinned = pin.sameSource(pinned());
        int label = pinned ? R.string.instrument_open_pin : revision(pin) ? R.string.instrument_compare : R.string.instrument_try;
        action(body, label, () -> {
            if (current() && present(pin)) show(pin, revision(pin));
        });
        cell.addView(body);
    }

    private void dock() {
        Pin pin = pinned();
        if (damaged != null) {
            ThreadInstruments.Damaged expected = damaged;
            viewport.dock(t(R.string.instrument_damaged), t(R.string.instrument_repair),
                    t(R.string.instrument_repair), () -> confirmRecovery(expected));
        } else if (state == null) {
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
        else refreshValues();
        updateControls();
        viewport.open(sheet);
        screen.refreshInstrumentRows();
    }

    private void buildSheet(Pin pin) {
        long activeDocument = ++document;
        dismissReview();
        visibleForm = null;
        changes = null;
        LinearLayout surface = Ui.column(activity);
        surface.setAccessibilityPaneTitle(pin.title);
        LinearLayout bar = Ui.row(activity);
        Ui.pad(bar, 2, 0);
        int target = Ui.dp(activity, 48);
        ImageButton back = icon(R.drawable.ic_back, R.string.instrument_return);
        back.setOnClickListener(v -> close());
        bar.addView(back, new LinearLayout.LayoutParams(target, target));
        LinearLayout titles = Ui.column(activity);
        Ui.pad(titles, 4, 4);
        heading = Ui.text(activity, pin.title, 22);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setAccessibilityHeading(true);
        heading.setMaxLines(2);
        heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titles.addView(heading);
        scope = Ui.text(activity, "", 13);
        scope.setTextColor(activity.getColor(R.color.accent));
        titles.addView(scope);
        bar.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton overflow = icon(R.drawable.ic_more, R.string.instrument_options);
        overflow.setOnClickListener(v -> options(v, pin, activeDocument));
        bar.addView(overflow, new LinearLayout.LayoutParams(target, target));
        surface.addView(bar);
        surface.addView(Ui.divider(activity));
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        LinearLayout body = Ui.column(activity);
        content = new FrameLayout(activity);
        if (comparing) {
            changes = InstrumentPreview.compare(activity, pinned().pageJson, pin.pageJson);
            InstrumentVersions versions = new InstrumentVersions(activity, side -> {
                if (!valid(pin, activeDocument)) return;
                dismissReview();
                display(side == InstrumentVersions.Side.CHANGES ? null
                        : side == InstrumentVersions.Side.PINNED ? pinned() : pin);
                updateControls();
            });
            LinearLayout.LayoutParams place = new LinearLayout.LayoutParams(-1, -2);
            place.setMargins(Ui.dp(activity, 12), Ui.dp(activity, 4), Ui.dp(activity, 12), Ui.dp(activity, 4));
            body.addView(versions, place);
        }
        body.addView(content);
        display(comparing ? null : pin);
        controls = Ui.column(activity);
        Ui.pad(controls, 16, 6);
        body.addView(controls);
        scroll.addView(body);
        surface.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        sheet = surface;
    }

    private void display(Pin pin) {
        content.removeAllViews();
        visibleForm = null;
        if (pin == null) {
            heading.setText(displayed.title);
            content.addView(changes);
            return;
        }
        Form form = null;
        for (Form cached : forms) if (pin.sameSource(cached.pin)) form = cached;
        if (form == null) {
            form = new Form(pin);
            visibleForm = form;
            form.host = rendererHost(form);
            form.view = InstrumentPreview.render(activity, pin.pageJson, form.host);
        } else {
            visibleForm = form;
            refreshValues();
        }
        forms.remove(form);
        forms.add(form);
        if (forms.size() > 2) forms.remove(0);
        if (form.view.getParent() instanceof ViewGroup oldParent) oldParent.removeView(form.view);
        heading.setText(pin.title);
        content.addView(form.view);
    }

    private void dismissReview() {
        if (visibleForm != null) ArtifactDesk.dismissReviews(activity, visibleForm.host);
    }

    private void refreshValues() {
        if (visibleForm != null) ArtifactDesk.refreshBindings(visibleForm.view, visibleForm.host);
    }

    private ImageButton icon(int drawable, int description) {
        ImageButton button = new ImageButton(activity, null, android.R.attr.borderlessButtonStyle);
        button.setImageResource(drawable);
        Ui.pad(button, 12, 12);
        button.setContentDescription(t(description));
        return button;
    }

    private void updateControls() {
        Pin pin = displayed;
        Pin saved = pinned();
        State expected = state;
        controls.removeAllViews();
        boolean installed = pin.sameSource(saved);
        if (comparing && saved != null) {
            scope.setVisibility(View.GONE);
            Runnable adopt = () -> mutate(() -> store.pin(expected, pin, workspace.messages()));
            Runnable keep = () -> show(saved, false);
            if (visibleForm != null && saved.sameSource(visibleForm.pin)) {
                primary(R.string.instrument_keep, keep);
                control(R.string.instrument_adopt_proposed, adopt);
            } else {
                primary(R.string.instrument_adopt, adopt);
                control(R.string.instrument_keep, keep);
            }
            return;
        }
        scope.setVisibility(View.VISIBLE);
        scope.setText(t(installed ? R.string.instrument_pinned : R.string.instrument_trial));
        if (damaged != null) {
            ThreadInstruments.Damaged expectedDamage = damaged;
            control(R.string.instrument_repair, () -> confirmRecovery(expectedDamage));
        } else if (state == null) {
            control(R.string.instrument_retry, () -> { readState(); updateControls(); });
        } else if (!installed) {
            if (revision(pin)) primary(R.string.instrument_compare, () -> show(pin, true));
            else primary(saved == null ? R.string.instrument_pin : R.string.instrument_trial_replaces,
                    () -> mutate(() -> store.pin(expected, pin, workspace.messages())));
        } else {
            control(R.string.instrument_revise, () -> {
                if (!pin.sameSource(pinned())) return;
                close();
                actions.stageDraft(t(R.string.instrument_revision_draft, pin.title));
            });
            if (state.canUndo) control(R.string.instrument_undo, () -> mutate(() -> store.undo(expected)));
        }
    }

    private Button action(LinearLayout parent, int label, Runnable task) {
        Button b = Ui.button(activity, t(label), v -> { if (current()) task.run(); });
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setSingleLine(false);
        parent.addView(b);
        return b;
    }

    private Button control(int label, Runnable task) {
        long serial = document;
        Pin pin = displayed;
        return action(controls, label, () -> {
            if (valid(pin, serial)) task.run();
        });
    }

    private void primary(int label, Runnable task) {
        Button b = control(label, task);
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

    private void confirmRecovery(ThreadInstruments.Damaged expected) {
        if (!current() || damaged != expected) return;
        screen.show(new AlertDialog.Builder(activity).setTitle(R.string.instrument_repair_title)
                .setMessage(R.string.instrument_repair_explain)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.instrument_repair_confirm, (dialog, which) -> {
                    if (!current()) return;
                    try { state = store.recover(expected); }
                    catch (ThreadInstruments.Conflict changed) { recover(R.string.instrument_changed); return; }
                    catch (IllegalStateException unavailable) { recover(R.string.instrument_unavailable); return; }
                    damaged = null;
                    close();
                    discard();
                    dock();
                    workspace.refreshInstruments();
                }));
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
        Pin source = visibleForm == null ? pin : visibleForm.pin;
        popup.getMenu().add(t(R.string.instrument_source)).setOnMenuItemClickListener(item -> {
            if (valid(pin, activeDocument)) screen.show(new AlertDialog.Builder(activity)
                    .setTitle(t(R.string.instrument_source_title, source.title, source.messageId))
                    .setView(MessageText.reader(activity, source.pageJson, false, true))
                    .setPositiveButton(t(R.string.instrument_close), null));
            return true;
        });
        if (present(source)) popup.getMenu().add(t(R.string.instrument_origin)).setOnMenuItemClickListener(item -> {
            if (valid(pin, activeDocument)) { close(); screen.revealProposal(source); }
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

    private ArtifactDesk.Host rendererHost(Form form) {
        return new ArtifactDesk.Host() {
            private boolean active() {
                return current() && visibleForm == form && viewport.open() && viewport.isShown()
                        && (form.pin.sameSource(pinned()) || present(form.pin));
            }
            @Override public void onAction(String action) {
                if (!active()) return;
                if ("previous".equals(action) || "return".equals(action) || "native_thread".equals(action)) close();
                else actions.onAction(action);
            }
            @Override public void openWorkspace(String id) { if (active()) actions.openWorkspace(id); }
            @Override public void stageDraft(String text) {
                if (active()) { close(); actions.stageDraft(text); }
            }
            @Override public String workspaceValue(String source) {
                return current() && visibleForm == form && (form.pin.sameSource(pinned()) || present(form.pin))
                        ? actions.workspaceValue(source) : "";
            }
            @Override public void onLayoutChanged() { if (active()) actions.onLayoutChanged(); }
            @Override public void threadSlots(List<String> order) {}
        };
    }

    boolean close() {
        if (!viewport.open()) return false;
        if (menu != null) { menu.dismiss(); menu = null; }
        dismissReview();
        viewport.close();
        dock();
        screen.refreshInstrumentRows();
        return true;
    }

    void revealDiscussionIfCompact() { if (viewport.open() && !viewport.wide()) close(); }

    private void discard() {
        ++document;
        displayed = null;
        sheet = null;
        visibleForm = null;
        changes = null;
        content = null;
        forms.clear();
    }

    void retire() {
        dismissReview();
        if (menu != null) { menu.dismiss(); menu = null; }
        byMessage.clear();
        discard();
    }
}
