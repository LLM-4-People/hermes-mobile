package org.llm4people.hermes.hub;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;
import org.llm4people.hermes.AdaptiveLayout;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.panel.UniversalShareModel;
import org.llm4people.hermes.profile.Account;
import org.llm4people.hermes.push.DeliveryInbox;
import org.llm4people.hermes.push.DeliveryItem;
import org.llm4people.hermes.settings.SettingsWrapLayout;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/**
 * Living Material 3 Notes &amp; Tasks hub with a 3-segment pill bar (Notes · Reminders · Inbox)
 * rendering live server-backed notes, scheduled reminders, deliveries, and shared items in place.
 */
public final class NotesTasksPanel implements AutoCloseable {
    public static final String ROUTE_ID = "notes_tasks";
    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    public interface Callbacks {
        void createNote();
        void openAllNotes();
        void createReminder();
        void openAllReminders();
        void openReminder(String jobId);
        void openFullInbox();
        void openDelivery(DeliveryItem item);
        void openSharedWithMe();
    }

    private final Activity activity;
    private final Account account;
    private final HermesApi api;
    private final BooleanSupplier isActive;
    private final Callbacks callbacks;
    private final DeliveryInbox deliveryInbox = new DeliveryInbox();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "hermes-notes-tasks");
        t.setDaemon(true);
        return t;
    });

    private NotesTasksModel.Segment activeSegment = NotesTasksModel.Segment.NOTES;
    private int loadGeneration;
    private volatile boolean closed;

    private LinearLayout segmentBar;
    private LinearLayout contentSlot;
    private int cachedUnreadCount;

    public NotesTasksPanel(
            Activity activity,
            Account account,
            HermesApi api,
            BooleanSupplier isActive,
            Callbacks callbacks) {
        this.activity = Objects.requireNonNull(activity, "activity");
        this.account = Objects.requireNonNull(account, "account");
        this.api = Objects.requireNonNull(api, "api");
        this.isActive = Objects.requireNonNull(isActive, "isActive");
        this.callbacks = Objects.requireNonNull(callbacks, "callbacks");
        this.cachedUnreadCount = safeUnreadCount();
    }

    public View buildView() {
        LinearLayout list = Ui.column(activity);
        ScrollView scroll = new ScrollView(activity) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                float density = getResources().getDisplayMetrics().density;
                int gutter = Ui.dp(activity, AdaptiveLayout.centeredGutterDp(
                        MeasureSpec.getSize(widthSpec) / density, AdaptiveLayout.READING_COLUMN_MAX_DP, 20));
                int top = Ui.dp(activity, 14);
                int bottom = Ui.dp(activity, 28);
                if (list.getPaddingStart() != gutter || list.getPaddingTop() != top) {
                    list.setPaddingRelative(gutter, top, gutter, bottom);
                }
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setTag("hub:notes_tasks");

        segmentBar = Ui.row(activity);
        segmentBar.setBackground(Ui.shape(activity, Ui.CARD_CORNER_DP, R.color.surface_container, R.color.line, 1));
        Ui.pad(segmentBar, 4, 4);
        list.addView(segmentBar, new LinearLayout.LayoutParams(-1, -2));

        contentSlot = Ui.column(activity);
        LinearLayout.LayoutParams contentLp = new LinearLayout.LayoutParams(-1, -2);
        contentLp.topMargin = Ui.dp(activity, 16);
        list.addView(contentSlot, contentLp);

        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        renderSegmentBar();
        loadActiveSegment();
        Diagnostics.debug("notes_tasks", "opened");
        return scroll;
    }

    public void selectSegment(NotesTasksModel.Segment segment) {
        if (segment == null || segment == activeSegment) return;
        activeSegment = segment;
        renderSegmentBar();
        loadActiveSegment();
    }

    public void refresh() {
        if (closed || contentSlot == null) return;
        cachedUnreadCount = safeUnreadCount();
        renderSegmentBar();
        loadActiveSegment();
    }

    @Override public void close() {
        closed = true;
        loadGeneration++;
        executor.shutdownNow();
    }

    private boolean isAlive(int gen) {
        return !closed && gen == loadGeneration && isActive.getAsBoolean()
                && !activity.isFinishing() && !activity.isDestroyed();
    }

    private int safeUnreadCount() {
        try {
            return deliveryInbox.unreadCount(account);
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private void renderSegmentBar() {
        if (segmentBar == null) return;
        segmentBar.removeAllViews();
        addSegmentPill(NotesTasksModel.Segment.NOTES, activity.getString(R.string.hub_seg_notes));
        addSegmentPill(NotesTasksModel.Segment.REMINDERS, activity.getString(R.string.hub_seg_reminders));
        String inboxLabel = cachedUnreadCount > 0
                ? activity.getString(R.string.hub_seg_inbox_unread, cachedUnreadCount)
                : activity.getString(R.string.hub_seg_inbox);
        addSegmentPill(NotesTasksModel.Segment.INBOX, inboxLabel);
    }

    private void addSegmentPill(NotesTasksModel.Segment segment, String label) {
        boolean selected = segment == activeSegment;
        TextView tab = new TextView(activity);
        tab.setText(label);
        tab.setTextSize(14);
        tab.setTypeface(MEDIUM);
        tab.setSingleLine(true);
        tab.setEllipsize(TextUtils.TruncateAt.END);
        tab.setGravity(Gravity.CENTER);
        tab.setMinimumHeight(Ui.dp(activity, 48));
        tab.setFocusable(true);
        tab.setClickable(true);
        tab.setSelected(selected);
        tab.setContentDescription(label);
        tab.setTextColor(activity.getColor(selected ? R.color.on_accent : R.color.ink));
        GradientDrawable fill = selected
                ? Ui.shape(activity, 12, R.color.accent, 0, 0)
                : null;
        tab.setBackground(Ui.roundedRipple(activity, 12, fill));
        Ui.pad(tab, 8, 10);
        tab.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
                info.setSelected(host.isSelected());
            }
        });
        tab.setOnClickListener(v -> selectSegment(segment));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        segmentBar.addView(tab, lp);
    }

    private void loadActiveSegment() {
        if (contentSlot == null || closed) return;
        int gen = ++loadGeneration;
        switch (activeSegment) {
            case NOTES -> loadNotes(gen);
            case REMINDERS -> loadReminders(gen);
            case INBOX -> loadInbox(gen);
        }
    }

    private void loadNotes(int gen) {
        contentSlot.removeAllViews();
        SettingsWrapLayout actions = new SettingsWrapLayout(activity, 10, 8);
        actions.addView(Ui.filledButton(activity, activity.getString(R.string.hub_new_note),
                v -> callbacks.createNote()));
        actions.addView(Ui.tonalButton(activity, activity.getString(R.string.hub_manage_notes),
                v -> callbacks.openAllNotes()));
        contentSlot.addView(actions, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout listSlot = Ui.column(activity);
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(-1, -2);
        listLp.topMargin = Ui.dp(activity, 16);
        contentSlot.addView(listSlot, listLp);
        showLoadingCard(listSlot);

        executor.execute(() -> {
            try {
                JSONObject response = api.get("/api/notes");
                List<NotesTasksModel.NoteRow> notes = NotesTasksModel.parseNotes(response);
                activity.runOnUiThread(() -> {
                    if (!isAlive(gen)) return;
                    renderNotesList(listSlot, notes);
                });
            } catch (IOException | RuntimeException error) {
                boolean unavailable = error instanceof HermesApi.HttpError http
                        && (http.status == 404 || http.status == 405 || http.status == 501);
                Diagnostics.warning("notes_tasks", unavailable ? "notes_unavailable" : "notes_load_failed");
                activity.runOnUiThread(() -> {
                    if (!isAlive(gen)) return;
                    if (unavailable) {
                        showEmptyCard(listSlot, R.drawable.ic_note,
                                activity.getString(R.string.brain_notes_unavailable), "", null, null);
                    } else {
                        showErrorCard(listSlot, activity.getString(R.string.hub_notes_error), () -> loadActiveSegment());
                    }
                });
            }
        });
    }

    private void renderNotesList(LinearLayout slot, List<NotesTasksModel.NoteRow> notes) {
        slot.removeAllViews();
        if (notes.isEmpty()) {
            showEmptyCard(slot, R.drawable.ic_note,
                    activity.getString(R.string.hub_notes_empty_title),
                    activity.getString(R.string.hub_notes_empty_body),
                    activity.getString(R.string.hub_new_note),
                    callbacks::createNote);
            return;
        }
        LinearLayout group = groupedContainer();
        for (int i = 0; i < notes.size(); i++) {
            NotesTasksModel.NoteRow note = notes.get(i);
            if (i > 0) group.addView(Ui.insetDivider(activity, 64));
            String title = note.title().isEmpty() ? activity.getString(R.string.brain_notes_title) : note.title();
            String badge = note.pinned() ? activity.getString(R.string.hub_notes_pinned) : null;
            group.addView(buildItemRow(R.drawable.ic_note, title, note.preview(), badge,
                    R.color.accent_surface, R.color.accent, callbacks::openAllNotes));
        }
        slot.addView(group, new LinearLayout.LayoutParams(-1, -2));
    }

    private void loadReminders(int gen) {
        contentSlot.removeAllViews();
        SettingsWrapLayout actions = new SettingsWrapLayout(activity, 10, 8);
        actions.addView(Ui.filledButton(activity, activity.getString(R.string.panel_new_reminder),
                v -> callbacks.createReminder()));
        actions.addView(Ui.tonalButton(activity, activity.getString(R.string.hub_manage_reminders),
                v -> callbacks.openAllReminders()));
        contentSlot.addView(actions, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout listSlot = Ui.column(activity);
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(-1, -2);
        listLp.topMargin = Ui.dp(activity, 16);
        contentSlot.addView(listSlot, listLp);
        showLoadingCard(listSlot);

        executor.execute(() -> {
            try {
                JSONObject response = api.get("/api/jobs?include_disabled=true");
                NotesTasksModel.ReminderOverview overview = NotesTasksModel.parseReminders(response);
                activity.runOnUiThread(() -> {
                    if (!isAlive(gen)) return;
                    renderRemindersList(listSlot, overview);
                });
            } catch (IOException | RuntimeException error) {
                Diagnostics.warning("notes_tasks", "reminders_load_failed");
                activity.runOnUiThread(() -> {
                    if (!isAlive(gen)) return;
                    showErrorCard(listSlot, activity.getString(R.string.hub_reminders_error), () -> loadActiveSegment());
                });
            }
        });
    }

    private void renderRemindersList(LinearLayout slot, NotesTasksModel.ReminderOverview overview) {
        slot.removeAllViews();
        if (overview.rows().isEmpty()) {
            showEmptyCard(slot, R.drawable.ic_notifications,
                    activity.getString(R.string.hub_reminders_empty_title),
                    activity.getString(R.string.hub_reminders_empty_body),
                    activity.getString(R.string.panel_new_reminder),
                    callbacks::createReminder);
            return;
        }
        SettingsWrapLayout pills = new SettingsWrapLayout(activity, 8, 6);
        pills.addView(Ui.pill(activity,
                activity.getString(R.string.hub_reminders_active_pill, overview.activeCount()),
                R.color.accent_surface, R.color.accent));
        if (overview.pausedCount() > 0) {
            pills.addView(Ui.pill(activity,
                    activity.getString(R.string.hub_reminders_paused_pill, overview.pausedCount()),
                    R.color.surface_container, R.color.muted));
        }
        if (overview.attentionCount() > 0) {
            pills.addView(Ui.pill(activity,
                    activity.getString(R.string.hub_reminders_attention_pill, overview.attentionCount()),
                    R.color.danger_surface, R.color.danger));
        }
        LinearLayout.LayoutParams pillsLp = new LinearLayout.LayoutParams(-1, -2);
        pillsLp.bottomMargin = Ui.dp(activity, 12);
        slot.addView(pills, pillsLp);

        LinearLayout group = groupedContainer();
        for (int i = 0; i < overview.rows().size(); i++) {
            NotesTasksModel.ReminderRow row = overview.rows().get(i);
            if (i > 0) group.addView(Ui.insetDivider(activity, 64));
            String subtitle = !row.schedule().isEmpty() ? row.schedule() : row.promptPreview();
            String badge = activity.getString(NotesTasksModel.reminderStatusLabelRes(row));
            int badgeBg;
            int badgeInk;
            if (row.needsAttention()) {
                badgeBg = R.color.danger_surface;
                badgeInk = R.color.danger;
            } else if (row.enabled()) {
                badgeBg = R.color.accent_surface;
                badgeInk = R.color.accent;
            } else {
                badgeBg = R.color.surface_container;
                badgeInk = R.color.muted;
            }
            group.addView(buildItemRow(R.drawable.ic_notifications, row.name(), subtitle, badge,
                    badgeBg, badgeInk, () -> {
                        if (!row.id().isEmpty()) callbacks.openReminder(row.id());
                        else callbacks.openAllReminders();
                    }));
        }
        slot.addView(group, new LinearLayout.LayoutParams(-1, -2));
    }

    private void loadInbox(int gen) {
        contentSlot.removeAllViews();
        SettingsWrapLayout actions = new SettingsWrapLayout(activity, 10, 8);
        actions.addView(Ui.tonalButton(activity, activity.getString(R.string.hub_open_inbox),
                v -> callbacks.openFullInbox()));
        actions.addView(Ui.outlinedButton(activity, activity.getString(R.string.hub_open_shared),
                v -> callbacks.openSharedWithMe()));
        contentSlot.addView(actions, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout listSlot = Ui.column(activity);
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(-1, -2);
        listLp.topMargin = Ui.dp(activity, 16);
        contentSlot.addView(listSlot, listLp);

        List<DeliveryItem> cached;
        try {
            cached = deliveryInbox.cached(account);
        } catch (RuntimeException ignored) {
            cached = Collections.emptyList();
        }
        renderInbox(listSlot, cached, Collections.emptyList());

        List<DeliveryItem> fallbackDeliveries = cached;
        executor.execute(() -> {
            List<DeliveryItem> deliveries = fallbackDeliveries;
            try {
                deliveries = deliveryInbox.sync(account, api);
            } catch (IOException | RuntimeException ignored) {
                Diagnostics.debug("notes_tasks", "inbox_sync_skipped");
            }
            List<NotesTasksModel.SharedRow> shared = Collections.emptyList();
            try {
                shared = NotesTasksModel.parseReceivedShares(api.get("/api/shares/incoming"));
            } catch (IOException | RuntimeException ignored) {
                Diagnostics.debug("notes_tasks", "shared_fetch_skipped");
            }
            List<DeliveryItem> finalDeliveries = deliveries;
            List<NotesTasksModel.SharedRow> finalShared = shared;
            int unread = NotesTasksModel.countUnreadDeliveries(finalDeliveries);
            activity.runOnUiThread(() -> {
                if (!isAlive(gen)) return;
                cachedUnreadCount = unread;
                renderSegmentBar();
                renderInbox(listSlot, finalDeliveries, finalShared);
            });
        });
    }

    private void renderInbox(
            LinearLayout slot,
            List<DeliveryItem> deliveries,
            List<NotesTasksModel.SharedRow> shared) {
        slot.removeAllViews();
        boolean hasDeliveries = deliveries != null && !deliveries.isEmpty();
        boolean hasShared = shared != null && !shared.isEmpty();
        if (!hasDeliveries && !hasShared) {
            showEmptyCard(slot, R.drawable.ic_inbox,
                    activity.getString(R.string.hub_inbox_empty_title),
                    activity.getString(R.string.hub_inbox_empty_body),
                    null, null);
            return;
        }
        if (hasDeliveries) {
            addSectionHeading(slot, activity.getString(R.string.hub_inbox_deliveries_heading), false);
            LinearLayout group = groupedContainer();
            int count = Math.min(deliveries.size(), NotesTasksModel.MAX_VISIBLE_ITEMS);
            for (int i = 0; i < count; i++) {
                DeliveryItem item = deliveries.get(i);
                if (i > 0) group.addView(Ui.insetDivider(activity, 64));
                String badge = item.isAcknowledged() ? null : activity.getString(R.string.hub_inbox_unread_pill);
                String preview = NotesTasksModel.compactPreview(item.summary);
                group.addView(buildItemRow(R.drawable.ic_inbox, item.title, preview, badge,
                        R.color.accent_surface, R.color.accent, () -> callbacks.openDelivery(item)));
            }
            slot.addView(group, new LinearLayout.LayoutParams(-1, -2));
        }
        if (hasShared) {
            addSectionHeading(slot, activity.getString(R.string.hub_inbox_shared_heading), hasDeliveries);
            LinearLayout group = groupedContainer();
            for (int i = 0; i < shared.size(); i++) {
                NotesTasksModel.SharedRow row = shared.get(i);
                if (i > 0) group.addView(Ui.insetDivider(activity, 64));
                String subtitle = row.ownerProfile().isEmpty()
                        ? ""
                        : activity.getString(R.string.hub_inbox_shared_by, row.ownerProfile());
                int kindRes = UniversalShareModel.kindLabelRes(row.resourceKind());
                String badge = kindRes != 0 ? activity.getString(kindRes) : null;
                group.addView(buildItemRow(R.drawable.ic_widgets, row.displayTitle(), subtitle, badge,
                        R.color.surface_container, R.color.muted, callbacks::openSharedWithMe));
            }
            slot.addView(group, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private void addSectionHeading(LinearLayout parent, String title, boolean topGap) {
        TextView heading = Ui.text(activity, title, 18);
        heading.setTypeface(MEDIUM);
        heading.setLetterSpacing(-0.01f);
        heading.setPaddingRelative(Ui.dp(activity, 4), Ui.dp(activity, topGap ? 20 : 4), 0, Ui.dp(activity, 8));
        heading.setAccessibilityHeading(true);
        parent.addView(heading);
    }

    private LinearLayout groupedContainer() {
        LinearLayout group = Ui.column(activity);
        group.setBackground(Ui.shape(activity, Ui.CARD_CORNER_DP, R.color.surface, R.color.line, 1));
        group.setClipToOutline(true);
        return group;
    }

    private View buildItemRow(
            int iconRes,
            String title,
            String subtitle,
            String badge,
            int badgeBgRes,
            int badgeInkRes,
            Runnable onClick) {
        LinearLayout row = Ui.row(activity);
        row.setMinimumHeight(Ui.dp(activity, 56));
        row.setPaddingRelative(Ui.dp(activity, 16), Ui.dp(activity, 12), Ui.dp(activity, 14), Ui.dp(activity, 12));
        row.setFocusable(true);
        row.setClickable(true);
        row.setBackground(Ui.roundedRipple(activity, 0, null));
        row.setOnClickListener(v -> onClick.run());
        row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
            }
        });
        row.setContentDescription(subtitle == null || subtitle.isEmpty() ? title : title + ". " + subtitle);

        row.addView(iconCircle(iconRes, 36, 20, false));

        LinearLayout copy = Ui.column(activity);
        LinearLayout titleRow = Ui.row(activity);
        TextView titleView = Ui.text(activity, title, 16);
        titleView.setTypeface(MEDIUM);
        titleView.setMaxLines(2);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        titleRow.addView(titleView, new LinearLayout.LayoutParams(0, -2, 1f));
        if (badge != null && !badge.isEmpty()) {
            TextView pill = Ui.pill(activity, badge, badgeBgRes, badgeInkRes);
            pill.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(-2, -2);
            pLp.setMarginStart(Ui.dp(activity, 8));
            titleRow.addView(pill, pLp);
        }
        copy.addView(titleRow);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView detail = Ui.text(activity, subtitle, 14);
            detail.setTextColor(activity.getColor(R.color.muted));
            detail.setMaxLines(2);
            detail.setEllipsize(TextUtils.TruncateAt.END);
            detail.setLineSpacing(0f, 1.2f);
            detail.setPaddingRelative(0, Ui.dp(activity, 3), 0, 0);
            detail.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            copy.addView(detail);
        }
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));

        ImageView arrow = new ImageView(activity);
        arrow.setImageResource(R.drawable.ic_navigate_forward);
        arrow.setImageTintList(ColorStateList.valueOf(activity.getColor(R.color.muted)));
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams arrowLp = new LinearLayout.LayoutParams(Ui.dp(activity, 18), Ui.dp(activity, 18));
        arrowLp.setMarginStart(Ui.dp(activity, 8));
        row.addView(arrow, arrowLp);
        return row;
    }

    private LinearLayout iconCircle(int iconRes, int circleDp, int iconDp, boolean filled) {
        LinearLayout circle = new LinearLayout(activity);
        circle.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(activity.getColor(filled ? R.color.accent : R.color.accent_surface));
        circle.setBackground(bg);
        circle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        ImageView icon = new ImageView(activity);
        icon.setImageResource(iconRes);
        icon.setImageTintList(ColorStateList.valueOf(activity.getColor(filled ? R.color.on_accent : R.color.accent)));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int iconPx = Ui.dp(activity, iconDp);
        circle.addView(icon, new LinearLayout.LayoutParams(iconPx, iconPx));
        int circlePx = Ui.dp(activity, circleDp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(circlePx, circlePx);
        lp.setMarginEnd(Ui.dp(activity, 12));
        circle.setLayoutParams(lp);
        return circle;
    }

    private void showLoadingCard(LinearLayout slot) {
        slot.removeAllViews();
        LinearLayout card = Ui.tonalCard(activity);
        TextView loading = Ui.text(activity, activity.getString(R.string.hub_loading), 14);
        loading.setTextColor(activity.getColor(R.color.muted));
        card.addView(loading);
        slot.addView(card, new LinearLayout.LayoutParams(-1, -2));
    }

    private void showEmptyCard(
            LinearLayout slot,
            int iconRes,
            String title,
            String body,
            String ctaLabel,
            Runnable ctaAction) {
        slot.removeAllViews();
        LinearLayout card = Ui.card(activity);
        LinearLayout header = Ui.row(activity);
        header.addView(iconCircle(iconRes, 40, 22, false));
        TextView titleView = Ui.text(activity, title, 16);
        titleView.setTypeface(MEDIUM);
        header.addView(titleView, new LinearLayout.LayoutParams(0, -2, 1f));
        card.addView(header);
        if (body != null && !body.isEmpty()) {
            TextView detail = Ui.text(activity, body, 14);
            detail.setTextColor(activity.getColor(R.color.muted));
            detail.setLineSpacing(0f, 1.25f);
            detail.setPaddingRelative(0, Ui.dp(activity, 8), 0, 0);
            card.addView(detail);
        }
        if (ctaLabel != null && !ctaLabel.isEmpty() && ctaAction != null) {
            Button cta = Ui.tonalButton(activity, ctaLabel, v -> ctaAction.run());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.topMargin = Ui.dp(activity, 12);
            card.addView(cta, lp);
        }
        slot.addView(card, new LinearLayout.LayoutParams(-1, -2));
    }

    private void showErrorCard(LinearLayout slot, String message, Runnable onRetry) {
        slot.removeAllViews();
        LinearLayout card = Ui.card(activity);
        TextView err = Ui.text(activity, message, 15);
        err.setTextColor(activity.getColor(R.color.danger));
        card.addView(err);
        Button retry = Ui.outlinedButton(activity, activity.getString(R.string.hub_retry), v -> onRetry.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = Ui.dp(activity, 10);
        card.addView(retry, lp);
        slot.addView(card, new LinearLayout.LayoutParams(-1, -2));
    }
}
