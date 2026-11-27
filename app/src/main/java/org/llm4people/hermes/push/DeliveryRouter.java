package org.llm4people.hermes.push;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.text.format.Formatter;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.AppLanguage;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.RouteRegistry;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.artifact.CodeReader;
import org.llm4people.hermes.artifact.DeviceAction;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.profile.Account;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * Native delivery inbox (Notes &amp; Tasks) and push policy (Settings) renderer and typed action router.
 * Zero raw JSON dumps: every pushed item renders in plain everyday French with reviewed native actions.
 */
public final class DeliveryRouter {
    public static final String ROUTE_DELIVERIES = "deliveries";
    public static final String ROUTE_PUSH_POLICY = "push_policy";

    public interface Actions {
        default void openDiscussion(String sessionId, String runId) {}
        default void openPublishedFile(String sessionId, String fileId, String filename) {}
        default void openCodeSnippet(String title, String language, String content) {}
        default void previewUiPatch(DeliveryItem item) {}
        default void refreshWidget(DeliveryItem item) {}
        default void reviewDeviceAction(DeliveryItem item) {}
        default void openReminder(String jobId, String sessionId) {}
        default void openPushPolicy() {}
    }

    private final Activity activity;
    private final Account account;
    private final HermesApi api;
    private final DeliveryInbox inbox;
    private final Actions actions;
    private AlertDialog activeDialog;
    private AlertDialog inboxDialog;
    private Button inboxRefreshButton;
    private int inboxEpoch;
    private boolean dismissed;
    private boolean refreshing;

    public DeliveryRouter(Activity activity, Account account, HermesApi api, DeliveryInbox inbox, Actions actions) {
        this.activity = Objects.requireNonNull(activity, "activity");
        this.account = Objects.requireNonNull(account, "account");
        this.api = api;
        this.inbox = inbox == null ? new DeliveryInbox() : inbox;
        this.actions = actions == null ? new Actions() {} : actions;
    }

    /** Plugs delivery inbox and notification-policy routes into the shared RouteRegistry. */
    public static void registerRoutes(RouteRegistry registry,
                                      Runnable openInbox, Runnable openPolicy) {
        Objects.requireNonNull(registry, "registry");
        registry.registerAction(ROUTE_DELIVERIES, Objects.requireNonNull(openInbox, "openInbox"));
        registry.registerAction(ROUTE_PUSH_POLICY, Objects.requireNonNull(openPolicy, "openPolicy"));
    }

    public void dismiss() {
        dismissed = true;
        refreshing = false;
        inboxEpoch++;
        inboxDialog = null;
        inboxRefreshButton = null;
        if (activeDialog != null) {
            try { activeDialog.dismiss(); } catch (RuntimeException ignored) {}
            activeDialog = null;
        }
        if (api != null) {
            api.revoke();
        }
    }

    public void showInbox() {
        dismissed = false;
        refreshing = false;
        int epoch = ++inboxEpoch;
        List<DeliveryItem> local = inbox.cached(account);
        presentInbox(buildInboxDialog(local, null), epoch);
        if (api != null) refreshInBackground(epoch, inboxDialog);
    }

    private void refreshInBackground(int expectedEpoch, AlertDialog expectedInboxDialog) {
        if (dismissed || refreshing || api == null || api.isRevoked()) return;
        refreshing = true;
        if (inboxRefreshButton != null) inboxRefreshButton.setEnabled(false);
        PushConnection.WORK.execute(() -> {
            try {
                List<DeliveryItem> updated = inbox.sync(account, api);
                activity.runOnUiThread(() -> {
                    if (expectedEpoch == inboxEpoch) refreshing = false;
                    if (canRefreshInbox(expectedEpoch, expectedInboxDialog)) {
                        presentInbox(buildInboxDialog(updated, null), expectedEpoch);
                    }
                });
            } catch (Exception failure) {
                Diagnostics.warning("push", "inbox_sync_failed", failure);
                activity.runOnUiThread(() -> {
                    if (expectedEpoch == inboxEpoch) refreshing = false;
                    if (canRefreshInbox(expectedEpoch, expectedInboxDialog)) {
                        presentInbox(buildInboxDialog(inbox.cached(account),
                                activity.getString(R.string.push_inbox_sync_error)), expectedEpoch);
                    }
                });
            }
        });
    }

    private boolean canRefreshInbox(int expectedEpoch, AlertDialog expectedInboxDialog) {
        return !dismissed
                && expectedEpoch == inboxEpoch
                && !activity.isFinishing()
                && !activity.isDestroyed()
                && expectedInboxDialog != null
                && activeDialog == expectedInboxDialog
                && activeDialog.isShowing();
    }

    AlertDialog.Builder buildInboxDialog(List<DeliveryItem> items, String statusNote) {
        LinearLayout box = Ui.column(activity);
        Ui.pad(box, 16, 12);

        HorizontalScrollView toolbarScroll = new HorizontalScrollView(activity);
        toolbarScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout toolbar = Ui.row(activity);
        Button refreshBtn = Ui.outlinedButton(activity, activity.getString(R.string.push_inbox_refresh), v -> {
            if (api != null && !dismissed && !refreshing) refreshInBackground(inboxEpoch, inboxDialog);
        });
        refreshBtn.setEnabled(!refreshing);
        inboxRefreshButton = refreshBtn;
        toolbar.addView(refreshBtn);
        Button policyBtn = Ui.outlinedButton(activity, activity.getString(R.string.push_policy_button),
                v -> actions.openPushPolicy());
        LinearLayout.LayoutParams policyLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        policyLp.setMarginStart(Ui.dp(activity, 8));
        toolbar.addView(policyBtn, policyLp);
        toolbarScroll.addView(toolbar);
        box.addView(toolbarScroll);

        if (statusNote != null && !statusNote.isEmpty()) {
            TextView note = Ui.text(activity, statusNote, 14);
            note.setTextColor(activity.getColor(R.color.muted));
            note.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            Ui.pad(note, 0, 6);
            box.addView(note);
        }

        if (items.isEmpty()) {
            TextView empty = Ui.text(activity, activity.getString(R.string.push_inbox_empty), 16);
            empty.setTextColor(activity.getColor(R.color.muted));
            Ui.pad(empty, 0, 12);
            box.addView(empty);
        } else {
            DateFormat fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT,
                    AppLanguage.resolvedLocale(activity));
            for (DeliveryItem item : items) {
                box.addView(Ui.divider(activity));
                box.addView(buildRowView(item, fmt));
            }
        }

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(box);
        return new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.push_inbox_title))
                .setView(scroll)
                .setNegativeButton(R.string.push_inbox_close, null);
    }

    private View buildRowView(DeliveryItem item, DateFormat fmt) {
        LinearLayout row = Ui.column(activity);
        Ui.pad(row, 4, 10);

        String badge = item.kind.label(activity);
        if (!item.isAcknowledged()) {
            badge = badge + " · " + activity.getString(R.string.push_inbox_unread_badge);
        }
        if (item.createdAtEpochSec > 0) {
            badge = badge + " · " + fmt.format(new Date(item.createdAtEpochSec * 1000L));
        }
        TextView meta = Ui.text(activity, badge, 13);
        meta.setTextColor(activity.getColor(item.isAcknowledged() ? R.color.muted : R.color.accent));
        row.addView(meta);

        TextView title = Ui.text(activity, item.title, 17);
        title.setTypeface(Typeface.DEFAULT, item.isAcknowledged() ? Typeface.NORMAL : Typeface.BOLD);
        Ui.pad(title, 0, 2);
        row.addView(title);

        if (!item.summary.isEmpty()) {
            TextView preview = Ui.text(activity, item.summary, 14);
            preview.setTextColor(activity.getColor(R.color.muted));
            preview.setMaxLines(2);
            row.addView(preview);
        }

        row.addView(Ui.button(activity, item.primaryActionLabel(activity), v -> openDelivery(item)));
        return row;
    }

    public void openDelivery(DeliveryItem item) {
        Objects.requireNonNull(item, "item");
        ackInBackground(item);
        switch (item.kind) {
            case NOTICE, CARD, BRIEFING -> present(buildReadableCardDialog(item));
            case FILE -> present(buildFileDialog(item));
            case UI_PATCH -> present(buildUiPatchDialog(item));
            case WIDGET_UPDATE -> present(buildWidgetUpdateDialog(item));
            case DEVICE_ACTION -> openDeviceActionReview(item);
            case REMINDER_RESULT -> present(buildReminderResultDialog(item));
        }
    }

    AlertDialog.Builder buildReadableCardDialog(DeliveryItem item) {
        LinearLayout content = Ui.column(activity);
        Ui.pad(content, 16, 12);

        TextView kindLabel = Ui.text(activity, item.kind.label(activity), 13);
        kindLabel.setTextColor(activity.getColor(R.color.accent));
        content.addView(kindLabel);

        JSONObject payload = item.payloadObject();
        String body = firstNonEmpty(payload, "body", "text", "message", "summary", "description");
        if (!body.isEmpty()) {
            TextView bodyView = Ui.text(activity, body, 16);
            Ui.pad(bodyView, 0, 8);
            content.addView(bodyView);
        }

        JSONArray sections = payload.optJSONArray("sections");
        if (sections != null) {
            for (int i = 0; i < sections.length(); i++) {
                JSONObject sec = sections.optJSONObject(i);
                if (sec == null) continue;
                String heading = sec.optString("heading", sec.optString("title", "")).trim();
                String text = sec.optString("body", sec.optString("text", "")).trim();
                if (!heading.isEmpty()) {
                    TextView h = Ui.text(activity, heading, 16);
                    h.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                    Ui.pad(h, 0, 6);
                    content.addView(h);
                }
                if (!text.isEmpty()) {
                    TextView t = Ui.text(activity, text, 15);
                    Ui.pad(t, 0, 2);
                    content.addView(t);
                }
            }
        }

        JSONArray highlights = payload.optJSONArray("highlights");
        if (highlights == null) highlights = payload.optJSONArray("items");
        if (highlights != null) {
            for (int i = 0; i < highlights.length(); i++) {
                String line = highlights.optString(i, "").trim();
                if (!line.isEmpty()) {
                    TextView bullet = Ui.text(activity, "• " + line, 15);
                    Ui.pad(bullet, 0, 3);
                    content.addView(bullet);
                }
            }
        }

        if (content.getChildCount() == 1 && !item.summary.isEmpty()) {
            TextView fallbackSummary = Ui.text(activity, item.summary, 16);
            Ui.pad(fallbackSummary, 0, 8);
            content.addView(fallbackSummary);
        }

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(item.title)
                .setView(scroll)
                .setNegativeButton(R.string.push_inbox_close, null);
        if (item.sessionId != null) {
            builder.setPositiveButton(R.string.push_inbox_open_thread,
                    (d, w) -> actions.openDiscussion(item.sessionId, item.runId));
        }
        return builder;
    }

    AlertDialog.Builder buildFileDialog(DeliveryItem item) {
        JSONObject payload = item.payloadObject();
        String filename = firstNonEmpty(payload, "filename", "name", "path");
        if (filename.isEmpty()) filename = item.title;
        String fileId = firstNonEmpty(payload, "file_id", "artifact_id", "id");
        String language = firstNonEmpty(payload, "language", "lang");
        String excerpt = firstNonEmpty(payload, "content", "excerpt", "snippet");
        long size = Math.max(0L, payload.optLong("size", 0L));

        StringBuilder details = new StringBuilder(filename);
        if (size > 0L) details.append("\n").append(Formatter.formatShortFileSize(activity, size));
        String mime = firstNonEmpty(payload, "mime", "mime_type");
        if (!mime.isEmpty()) details.append("\n").append(mime);

        final String finalFilename = filename;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(item.title)
                .setMessage(details.toString())
                .setNegativeButton(R.string.push_inbox_close, null);
        if (item.sessionId != null || !fileId.isEmpty()) {
            builder.setPositiveButton(R.string.push_inbox_open_file,
                    (d, w) -> actions.openPublishedFile(item.sessionId, fileId, finalFilename));
        }
        if (!excerpt.isEmpty()) {
            builder.setNeutralButton(R.string.push_inbox_read_excerpt, (d, w) -> {
                actions.openCodeSnippet(finalFilename, language, excerpt);
                present(CodeReader.dialog(activity, finalFilename, language, excerpt));
            });
        }
        return builder;
    }

    AlertDialog.Builder buildUiPatchDialog(DeliveryItem item) {
        JSONObject payload = item.payloadObject();
        String summary = firstNonEmpty(payload, "summary", "description");
        String message = summary.isEmpty()
                ? activity.getString(R.string.push_inbox_ui_patch_prompt)
                : activity.getString(R.string.push_inbox_ui_patch_prompt) + "\n\n" + summary;
        return new AlertDialog.Builder(activity)
                .setTitle(item.title)
                .setMessage(message)
                .setNegativeButton(R.string.push_inbox_close, null)
                .setPositiveButton(R.string.push_inbox_preview_ui, (d, w) -> actions.previewUiPatch(item));
    }

    AlertDialog.Builder buildWidgetUpdateDialog(DeliveryItem item) {
        JSONObject payload = item.payloadObject();
        String summary = firstNonEmpty(payload, "summary", "status", "headline");
        String message = summary.isEmpty()
                ? activity.getString(R.string.push_inbox_widget_prompt)
                : activity.getString(R.string.push_inbox_widget_prompt) + "\n\n" + summary;
        return new AlertDialog.Builder(activity)
                .setTitle(item.title)
                .setMessage(message)
                .setNegativeButton(R.string.push_inbox_close, null)
                .setPositiveButton(R.string.push_inbox_refresh_widget, (d, w) -> actions.refreshWidget(item));
    }

    AlertDialog.Builder buildReminderResultDialog(DeliveryItem item) {
        JSONObject payload = item.payloadObject();
        String body = firstNonEmpty(payload, "summary", "body", "result", "text", "message");
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(item.title)
                .setMessage(body.isEmpty() ? item.kind.label(activity) : body)
                .setNegativeButton(R.string.push_inbox_close, null);
        if (item.sessionId != null) {
            builder.setPositiveButton(R.string.push_inbox_open_thread,
                    (d, w) -> actions.openDiscussion(item.sessionId, item.runId));
        } else if (item.jobId != null) {
            builder.setPositiveButton(R.string.push_inbox_open_reminder,
                    (d, w) -> actions.openReminder(item.jobId, null));
        }
        return builder;
    }

    /**
     * Device actions NEVER execute silently: always require explicit user confirmation
     * and validate against an allowlisted set of safe Android intents.
     */
    private void openDeviceActionReview(DeliveryItem item) {
        actions.reviewDeviceAction(item);
        ReviewedAction parsed = parseSafeDeviceAction(activity, item.payloadObject());
        if (parsed == null) {
            Diagnostics.warning("push", "device_action_rejected");
            present(new AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.push_inbox_action_confirm_title))
                    .setMessage(activity.getString(R.string.push_inbox_action_invalid))
                    .setNegativeButton(R.string.push_inbox_close, null));
            return;
        }
        boolean[] handled = {false};
        present(new AlertDialog.Builder(activity)
                .setTitle(parsed.titleFr)
                .setMessage(parsed.previewFr)
                .setNegativeButton(R.string.push_inbox_action_cancel, null)
                .setPositiveButton(parsed.openLabelFr, (d, w) -> {
                    if (handled[0]) return;
                    handled[0] = true;
                    try {
                        activity.startActivity(parsed.intent);
                    } catch (ActivityNotFoundException | SecurityException unavailable) {
                        Diagnostics.warning("push", "device_action_unavailable");
                        present(new AlertDialog.Builder(activity)
                                .setTitle(parsed.titleFr)
                                .setMessage(activity.getString(R.string.push_inbox_action_unavailable))
                                .setPositiveButton(R.string.push_inbox_close, null));
                    }
                }));
    }

    static final class ReviewedAction {
        final String titleFr;
        final String previewFr;
        final String openLabelFr;
        final Intent intent;

        ReviewedAction(String titleFr, String previewFr, String openLabelFr, Intent intent) {
            this.titleFr = titleFr;
            this.previewFr = previewFr;
            this.openLabelFr = openLabelFr;
            this.intent = intent;
        }
    }

    static ReviewedAction parseSafeDeviceAction(Context context, JSONObject payload) {
        if (payload == null) return null;
        Context localized = context == null ? null : AppLanguage.wrapContext(context);
        JSONObject action = payload.optJSONObject("action");
        if (action == null) action = payload;
        String kind = action.optString("kind", action.optString("action_type", "")).trim();
        return switch (kind) {
            case "calendar_event" -> {
                String startRaw = action.optString("start", "").trim();
                String endRaw = action.optString("end", "").trim();
                DeviceAction.CalendarEvent cal = DeviceAction.parseCalendarEvent(
                        action.optString("title", ""), startRaw, endRaw, action.optString("location", ""));
                if (cal == null) yield null;
                String startLine = localized == null ? startRaw
                        : localized.getString(R.string.push_action_calendar_start, startRaw);
                String endLine = localized == null ? endRaw
                        : localized.getString(R.string.push_action_calendar_end, endRaw);
                String locLine = cal.location() == null ? ""
                        : "\n" + (localized == null ? cal.location()
                        : localized.getString(R.string.push_action_calendar_location, cal.location()));
                String preview = cal.title() + "\n\n" + startLine + "\n" + endLine + locLine;
                yield new ReviewedAction(
                        localized == null ? "" : localized.getString(R.string.push_action_calendar_title),
                        preview,
                        localized == null ? "" : localized.getString(R.string.push_action_calendar_open),
                        cal.target());
            }
            case "share_text" -> {
                DeviceAction.ShareText share = DeviceAction.parseShareText(action.optString("text", ""));
                if (share == null) yield null;
                yield new ReviewedAction(
                        localized == null ? "" : localized.getString(R.string.push_action_share_title),
                        share.text(),
                        localized == null ? "" : localized.getString(R.string.push_action_share_open),
                        Intent.createChooser(share.target(), null));
            }
            case "map_search" -> {
                DeviceAction.MapSearch map = DeviceAction.parseMapSearch(action.optString("query", ""));
                if (map == null) yield null;
                yield new ReviewedAction(
                        localized == null ? "" : localized.getString(R.string.push_action_map_title),
                        map.query(),
                        localized == null ? "" : localized.getString(R.string.push_action_map_open),
                        map.target());
            }
            default -> null;
        };
    }

    private void ackInBackground(DeliveryItem item) {
        if (item.isAcknowledged()) return;
        inbox.upsert(account, item.withAcknowledged(System.currentTimeMillis() / 1000L));
        if (api == null) return;
        PushConnection.WORK.execute(() -> {
            try {
                inbox.acknowledge(account, api, item.id);
            } catch (Exception failure) {
                Diagnostics.warning("push", "delivery_ack_failed", failure);
            }
        });
    }

    private void presentInbox(AlertDialog.Builder builder, int epoch) {
        if (dismissed || epoch != inboxEpoch || activity.isFinishing() || activity.isDestroyed()) return;
        if (activeDialog != null) {
            try { activeDialog.dismiss(); } catch (RuntimeException ignored) {}
        }
        activeDialog = Ui.secureDialog(builder);
        inboxDialog = activeDialog;
    }

    private void present(AlertDialog.Builder builder) {
        if (dismissed || activity.isFinishing() || activity.isDestroyed()) return;
        refreshing = false;
        inboxEpoch++;
        inboxDialog = null;
        inboxRefreshButton = null;
        if (activeDialog != null) {
            try { activeDialog.dismiss(); } catch (RuntimeException ignored) {}
        }
        activeDialog = Ui.secureDialog(builder);
    }

    private static String firstNonEmpty(JSONObject obj, String... keys) {
        if (obj == null) return "";
        for (String key : keys) {
            if (!obj.isNull(key)) {
                String val = obj.optString(key, "").trim();
                if (!val.isEmpty()) return val;
            }
        }
        return "";
    }
}
