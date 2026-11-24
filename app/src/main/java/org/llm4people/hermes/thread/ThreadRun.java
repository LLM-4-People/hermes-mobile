package org.llm4people.hermes.thread;

import android.app.AlertDialog;
import android.os.SystemClock;
import android.provider.Settings;

import org.json.JSONException;
import org.json.JSONObject;
import org.llm4people.hermes.AppLanguage;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.HermesConfig;
import org.llm4people.hermes.R;
import org.llm4people.hermes.VisibleText;
import org.llm4people.hermes.ambient.CalendarContextReader;
import org.llm4people.hermes.ambient.HermesNotificationListener;
import org.llm4people.hermes.ambient.NotificationAllowlist;
import org.llm4people.hermes.camera.CapturedMedia;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.os.LiveRunNotification;
import org.llm4people.hermes.perf.StreamCoalescer;
import org.llm4people.hermes.presence.PresencePolicy;
import org.llm4people.hermes.share.AttachmentUploader;
import org.llm4people.hermes.share.DeviceBridgeFlow;

import java.util.Collections;
import java.util.List;

/** Manages exactly one mobile-originated run in the selected thread. */
final class ThreadRun {
    private final ThreadWorkspace page;
    private final ThreadJournal journal;
    private final ThreadViews ui;
    private RunFollower follower;
    private RunFollower.Frame frame;
    private RunDecisions decisions;
    private boolean submitting;
    private Runnable afterAdmission;
    private boolean keepCompletionPosition;
    private long started = -1;
    private long subscription;
    private boolean canStop;
    private LiveRunNotification.Snapshot lastProgress = LiveRunNotification.Snapshot.idle();
    private StreamCoalescer liveCoalescer;
    private Runnable pendingCoalesceFlush;
    private RunFollower.Frame coalescedFrame;
    private String coalescedActivity = "";
    private boolean coalescedHasPrompts;
    private RunAction owner() { return RunAction.capture(journal, page::token, () -> subscription); }
    private String runId() { return ThreadData.string(journal.pending(), "run_id"); }

    private void reportProgress(LiveRunNotification.Snapshot next) {
        LiveRunNotification.Snapshot safe = next == null ? LiveRunNotification.Snapshot.idle() : next;
        lastProgress = safe;
        page.activeRuns.updateForeground(page.sessionId(), runId(), safe);
        page.publishRunProgress();
    }

    boolean canStop() { return canStop && !runId().isEmpty(); }

    ThreadRun(ThreadWorkspace page) {
        this.page = page;
        journal = page.journal;
        ui = page.screen;
    }

    static boolean canAdmitRun(ActiveRunTracker tracker, String sessionId) {
        if (tracker == null) return true;
        if (sessionId == null || sessionId.isEmpty()) {
            return tracker.activeCount() < ActiveRunTracker.MAX_CONCURRENT_RUNS;
        }
        return tracker.canStart(sessionId);
    }

    boolean submitting() { return submitting; }

    void send() {
        String text = ui.draft();
        if (submitting || page.importingPhoto || journal.pending() != null || VisibleText.isBlank(text)) {
            ui.stopDuplex();
            return;
        }
        if (!canAdmitRun(page.activeRuns, page.sessionId())) {
            ui.stopDuplex();
            ui.notice(page.activity.getString(R.string.thread_concurrent_limit_reached), "", null);
            return;
        }
        if (!journal.photoOwned()) {
            ui.stopDuplex();
            ui.notice(page.activity.getString(R.string.thread_photo_profile_changed), "", null);
            return;
        }
        int token = page.token();
        java.util.function.BooleanSupplier original = journal.unchangedDraft(text);
        if (!page.nearby.beforeSend(text, () -> {
            if (page.token() == token && page.journal == journal && !page.importingPhoto && original.getAsBoolean()
                    && ui.root.isShown() && text.equals(ui.draft())) send();
        })) {
            ui.stopDuplex();
            return;
        }
        JSONObject options;
        try {
            options = page.host.options(page.sessionId());
            if (options == null) throw new IllegalStateException("Missing session settings");
        }
        catch (RuntimeException unreadable) {
            Diagnostics.warning("threads", "session_options_read_failed", unreadable);
            ui.stopDuplex();
            ui.notice(page.activity.getString(R.string.thread_settings_unreadable), "", null);
            return;
        }
        submitWithCapabilities(ThreadData.body(page.remoteSessionId(), text, options), true);
    }

    void restore() {
        restore(null);
    }

    void restore(ActiveRunTracker.ReclaimedState reclaimed) {
        JSONObject pending = journal.pending();
        if (pending == null) return;
        if (!journal.sameOwner()) { uncertain(); return; }
        ui.outgoing(outgoing(pending));
        canStop = !runId().isEmpty();
        ui.busy(true, canStop);
        if (reclaimed != null && reclaimed.latestFrame != null) {
            frame = reclaimed.latestFrame;
            int count = reclaimed.latestFrame.prompts.size();
            String label = count == 0 ? reclaimed.latestFrame.activity : count == 1
                    ? page.activity.getString(R.string.thread_hermes_awaiting_reply)
                    : page.activity.getResources().getQuantityString(R.plurals.thread_pending_requests, count, count);
            ui.live(label, reclaimed.latestFrame.text, count > 0, reclaimed.latestFrame.origin,
                    reclaimed.latestFrame.reasoning);
        }
        if (runId().isEmpty()) submitWithCapabilities(pending.optJSONObject("body"), false);
        else if (journal.reconnectsExhausted()) trackingInterrupted();
        else follow();
    }

    boolean afterAdmission(Runnable next) {
        if (!submitting) return false;
        afterAdmission = next;
        return true;
    }

    private void admissionFinished() {
        submitting = false;
        if (afterAdmission != null) page.main.post(afterAdmission);
        afterAdmission = null;
    }

    void refreshNotice(String notifiedRun, String observed) {
        // A completion refresh must never replay an unacknowledged send from the editor.
        if (submitting || observed == null && (runId().isEmpty() || !runId().equals(notifiedRun) || !journal.sameOwner())) return;
        if (observed != null && (!observed.equals(runId()) || !journal.sameOwner())) {
            detach();
            frame = null;
            started = -1;
            journal.observe(observed);
        } else checkpoint();
        keepCompletionPosition = true;
        if (journal.reconnectsExhausted()) journal.resetReconnects();
        restore();
    }

    private void submitWithCapabilities(JSONObject body, boolean fresh) {
        if (submitting) return;
        JSONObject selectedPhoto = journal.photo();
        long nearbyVersion = page.nearby.version();
        submitting = true;
        ui.busy(true, false);
        ui.notice(page.activity.getString(fresh ? R.string.thread_preparing_send : R.string.thread_verifying_send), "", null);
        reportProgress(new LiveRunNotification.Snapshot(
                LiveRunNotification.Phase.STARTING, 0, 0, false, false));
        page.request(() -> page.api.get("/v1/capabilities"), capabilities -> {
            JSONObject features = capabilities.optJSONObject("features");
            JSONObject idem = features == null ? null : features.optJSONObject("runs_idempotency");
            if (body == null || idem == null || !idem.optBoolean("supported")) {
                admissionFinished();
                reportProgress(LiveRunNotification.Snapshot.idle());
                ui.stopDuplex();
                if (!fresh) { uncertain(); return; }
                ui.busy(false, false);
                ui.notice(page.activity.getString(R.string.thread_gateway_idempotent_required), "", null);
                return;
            }
            if (!fresh && !NearbyTurn.canReplay(journal.pending(), features)) {
                admissionFinished();
                reportProgress(LiveRunNotification.Snapshot.idle());
                ui.stopDuplex();
                uncertain();
                return;
            }
            boolean ignoresToolsets = body.has("allowed_toolsets") && !features.optBoolean("run_toolset_selection");
            boolean ignoresExclusions = body.has("excluded_tools") && !features.optBoolean("run_tool_exclusions");
            if (ignoresToolsets || ignoresExclusions) {
                admissionFinished();
                reportProgress(LiveRunNotification.Snapshot.idle());
                ui.stopDuplex();
                if (!fresh) { uncertain(); return; }
                ui.busy(false, false);
                ui.notice(page.activity.getString(R.string.thread_gateway_tools_unsupported), "", null);
                return;
            }
            List<CapturedMedia.Item> stagedAttachments = fresh ? ui.stagedAttachments() : Collections.emptyList();
            if (fresh) {
                keepCompletionPosition = false;
                // Edits while capability discovery was in flight must stay in the composer.
                String snapshot = ThreadData.string(body, "input");
                JSONObject area = page.nearby.frozen();
                if (!snapshot.equals(ui.draft()) || selectedPhoto != journal.photo() || !page.nearby.stillCurrent(nearbyVersion, area)) {
                    admissionFinished();
                    reportProgress(LiveRunNotification.Snapshot.idle());
                    ui.stopDuplex();
                    ui.busy(false, false);
                    ui.notice(page.activity.getString(R.string.thread_draft_changed_resend), "", null);
                    return;
                }
                if (!stagedAttachments.isEmpty() && !AttachmentUploader.supportsUploads(features)) {
                    admissionFinished();
                    reportProgress(LiveRunNotification.Snapshot.idle());
                    ui.stopDuplex();
                    ui.busy(false, false);
                    ui.notice(page.activity.getString(R.string.thread_gateway_files_unsupported), "", null);
                    return;
                }
                if (area != null && !NearbyTurn.supportsScope(features)) {
                    admissionFinished();
                    reportProgress(LiveRunNotification.Snapshot.idle());
                    ui.stopDuplex();
                    ui.busy(false, false);
                    int token = page.token();
                    java.util.function.BooleanSupplier unchanged = journal.unchangedDraft(snapshot);
                    ui.notice(page.activity.getString(R.string.thread_gateway_nearby_unsupported),
                            page.activity.getString(R.string.nearby_send_without), () -> {
                        if (page.token() == token && page.journal == journal && unchanged.getAsBoolean()
                                && snapshot.equals(ui.draft())) page.nearby.omit(this::send);
                    });
                    return;
                }
                if (!stagedAttachments.isEmpty()) {
                    String remoteSession = page.remoteSessionId();
                    if (remoteSession == null || remoteSession.trim().isEmpty()) {
                        admissionFinished();
                        reportProgress(LiveRunNotification.Snapshot.idle());
                        ui.stopDuplex();
                        ui.busy(false, false);
                        ui.notice(page.activity.getString(R.string.thread_not_ready_files), "", null);
                        return;
                    }
                    long maxUploadBytes = HermesConfig.load(page.activity).artifactDefaultMaxBytes;
                    ui.notice(page.activity.getString(R.string.thread_uploading_files), "", null);
                    page.request(() -> AttachmentUploader.uploadAll(
                            page.api::postMultipart, remoteSession, stagedAttachments, maxUploadBytes), receipts -> {
                        if (!snapshot.equals(ui.draft()) || selectedPhoto != journal.photo()
                                || !page.nearby.stillCurrent(nearbyVersion, area)
                                || !stagedAttachments.equals(ui.stagedAttachments())) {
                            admissionFinished();
                            reportProgress(LiveRunNotification.Snapshot.idle());
                            ui.stopDuplex();
                            ui.busy(false, false);
                            ui.notice(page.activity.getString(R.string.thread_draft_changed_resend), "", null);
                            return;
                        }
                        AttachmentUploader.attachToRunBody(body, receipts);
                        finalizeStageAndAdmit(body, features, idem, area, stagedAttachments);
                    }, error -> {
                        admissionFinished();
                        reportProgress(LiveRunNotification.Snapshot.idle());
                        ui.stopDuplex();
                        ui.busy(false, false);
                        page.error(error, this::send);
                    });
                    return;
                }
                finalizeStageAndAdmit(body, features, idem, area, Collections.emptyList());
                return;
            } else {
                JSONObject pending = journal.pending();
                long ttl = Math.min(pending.optLong("ttl"), idem.optLong("retention_seconds"));
                if (!ThreadData.canReplay(SystemClock.elapsedRealtime(), pending.optLong("elapsed", -1),
                        bootCount(), pending.optInt("boot", -1), ttl,
                        pending.optBoolean("durable") && idem.optBoolean("durable"))) {
                    admissionFinished();
                    reportProgress(LiveRunNotification.Snapshot.idle());
                    ui.stopDuplex();
                    uncertain();
                    return;
                }
            }
            admit(body, false, Collections.emptyList());
        }, error -> {
            admissionFinished();
            ui.busy(!fresh, false);
            reportProgress(LiveRunNotification.Snapshot.idle());
            ui.stopDuplex();
            page.error(error, fresh ? this::send : this::restore);
        });
    }

    private void finalizeStageAndAdmit(JSONObject body, JSONObject features, JSONObject idem,
                                       JSONObject area, List<CapturedMedia.Item> stagedAttachments) {
        try {
            NearbyTurn.attach(body, area);
            AppLanguage.acceptLanguageHeader(page.activity);
            body.put("user_language", AppLanguage.resolvedTag(page.activity));
            JSONObject presenceContext = page.host == null ? null : page.host.appPresenceContext();
            if (presenceContext != null) body.put("app_presence", presenceContext);
            if (features.optBoolean("device_bridge")) {
                NotificationAllowlist allowlist = NotificationAllowlist.forAccount(page.account());
                NotificationAllowlist.Settings ambient = allowlist.settings();
                boolean listenerOn = HermesNotificationListener.isListenerEnabled(page.activity);
                boolean hasApps = !allowlist.effectiveAllowedPackages().isEmpty();
                boolean calendarGranted = CalendarContextReader.hasPermission(page.activity);
                List<String> caps = DeviceBridgeFlow.executableCapabilities(
                        true, true, true, ambient, listenerOn, calendarGranted, hasApps,
                        PresencePolicy.load(page.account()));
                body.put("device_capabilities", DeviceBridgeFlow.capabilitiesJson(caps));
            }
            journal.stage(body, SystemClock.elapsedRealtime(), bootCount(), idem.optLong("retention_seconds"),
                    idem.optBoolean("durable"), area);
            page.nearby.clearTurn();
        } catch (IllegalStateException | JSONException e) {
            admissionFinished();
            reportProgress(LiveRunNotification.Snapshot.idle());
            ui.stopDuplex();
            ui.busy(false, false);
            page.storageError();
            return;
        }
        page.clearSuggestedActions();
        ui.clearDraft();
        if (!stagedAttachments.isEmpty()) ui.clearAttachments();
        page.refreshPhoto();
        ui.outgoing(outgoing(journal.pending()));
        started = SystemClock.elapsedRealtime();
        admit(body, true, stagedAttachments);
    }

    private String outgoing(JSONObject receipt) {
        String caption = ThreadData.string(receipt.optJSONObject("body"), "input");
        return receipt.has("photo") ? caption + "\n" + page.activity.getString(R.string.thread_attached_image) : caption;
    }

    private int bootCount() {
        return Settings.Global.getInt(page.activity.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
    }

    private void admit(JSONObject body, boolean fresh, List<CapturedMedia.Item> stagedAttachments) {
        String key = journal.pending().optString("key");
        ui.notice(page.activity.getString(R.string.thread_sending_to_hermes), "", null);
        JSONObject image = journal.pending().optJSONObject("photo");
        page.request(() -> page.api.post("/v1/runs", journal.wire(body, image), key), response -> {
            admissionFinished();
            String id = ThreadData.string(response, "run_id");
            if (!ThreadData.validId(id)) { uncertain(); return; }
            journal.setRunId(id);
            ui.notice("", "", null);
            ui.busy(true, true);
            follow();
        }, error -> {
            admissionFinished();
            reportProgress(LiveRunNotification.Snapshot.idle());
            ui.stopDuplex();
            started = -1;
            // Only a definitive rejection of the first attempt can return the text to the draft.
            boolean imageUnavailable = image != null && error instanceof DiscussionPhoto.Unavailable;
            if (!fresh && imageUnavailable) { uncertain(); return; }
            if (fresh && (imageUnavailable || error instanceof HermesApi.HttpError http
                    && (http.status == 400 || http.status == 401 || http.status == 403 || http.status == 413 || http.status == 422))) {
                journal.reject();
                page.nearby.restore();
                ui.displayDraft(journal.draft());
                if (stagedAttachments != null && !stagedAttachments.isEmpty()) page.attachMedia(stagedAttachments);
                page.refreshPhoto();
                ui.outgoing("");
                ui.busy(false, false);
                if (imageUnavailable) ui.notice(page.activity.getString(R.string.thread_saved_image_unreadable), "", null);
                else if (image != null && error instanceof HermesApi.HttpError http
                        && (http.status == 400 || http.status == 413 || http.status == 422))
                    ui.notice(page.activity.getString(R.string.thread_image_rejected), "", null);
                else page.error(error, this::send);
            } else {
                ui.notice(page.activity.getString(R.string.thread_missing_ack),
                        page.activity.getString(R.string.thread_verify_send_action), this::restore);
            }
        });
    }

    private void uncertain() {
        reportProgress(LiveRunNotification.Snapshot.idle());
        ui.stopDuplex();
        RunAction owner = owner();
        canStop = false;
        ui.busy(true, false);
        ui.notice(page.activity.getString(R.string.thread_maybe_received),
                page.activity.getString(R.string.thread_check_conversation), owner.guard(() -> page.loadMessages(false,
                        owner.guard(() -> offerDismiss(owner)))));
    }

    private void offerDismiss(RunAction owner) {
        ui.show(new AlertDialog.Builder(page.activity)
                .setTitle(R.string.thread_close_tracking_title)
                .setMessage(R.string.thread_close_tracking_body)
                .setNegativeButton(R.string.thread_continue_tracking, (d, w) -> { if (owner.current()) uncertain(); })
                .setPositiveButton(R.string.thread_close_tracking_confirm, (d, w) -> {
                    if (!owner.current()) return;
                    canStop = false;
                    if (finishTracking()) return;
                    ui.outgoing("");
                    ui.busy(false, false);
                    ui.notice("", "", null);
                    ui.live("", "", false);
                }));
    }

    private boolean finishTracking() {
        journal.finish();
        if (journal.pending() == null) return false;
        // Resume the preserved local receipt with its own cursor and retry state.
        frame = null;
        started = -1;
        ui.live("", "", false);
        restore();
        return true;
    }

    private void clearLiveCoalescer() {
        if (pendingCoalesceFlush != null) {
            page.main.removeCallbacks(pendingCoalesceFlush);
            pendingCoalesceFlush = null;
        }
        if (liveCoalescer != null) {
            liveCoalescer.reset();
            liveCoalescer = null;
        }
        coalescedFrame = null;
        coalescedActivity = "";
        coalescedHasPrompts = false;
    }

    private void follow() {
        clearLiveCoalescer();
        if (follower != null) follower.close();
        int token = page.token();
        long serial = ++subscription;
        JSONObject receipt = journal.pending();
        String id = runId();
        if (id.isEmpty()) return;
        if (decisions == null || !decisions.current()) decisions = new RunDecisions(page);
        decisions.observe(owner());
        frame = null;
        canStop = true;
        ui.busy(true, true);
        RunPreview saved = new RunPreview(receipt);
        ui.live(page.activity.getString(R.string.live_run_working_title), saved.toString(), false, saved.origin());
        reportProgress(new LiveRunNotification.Snapshot(
                LiveRunNotification.Phase.WORKING, 0, 0, false, true));
        StreamCoalescer coalescer = new StreamCoalescer(StreamCoalescer.DEFAULT_FRAME_BUDGET_MS, ignored -> {
            if (coalescedFrame != null) {
                ui.live(coalescedActivity, coalescedFrame.text, coalescedHasPrompts,
                        coalescedFrame.origin, coalescedFrame.reasoning);
            }
        });
        liveCoalescer = coalescer;
        RunFollower nextFollower = new RunFollower(page.api, id, receipt, started, new RunFollower.Listener() {
            private boolean current() { return serial == subscription && receipt == journal.pending(); }

            private void deliver(Runnable update) {
                page.deliver(token, () -> { if (current()) update.run(); });
            }

            @Override public void update(RunFollower.Frame next) {
                deliver(() -> {
                    frame = next;
                    int count = next.prompts.size();
                    String activity = count == 0 ? next.activity : count == 1
                            ? page.activity.getString(R.string.thread_hermes_awaiting_reply)
                            : page.activity.getResources().getQuantityString(R.plurals.thread_pending_requests, count, count);
                    boolean hasPrompts = count > 0;
                    boolean reasoningVisibilityChanged = coalescedFrame == null
                            || next.reasoning.isEmpty() != coalescedFrame.reasoning.isEmpty();
                    boolean firstVisibleReply = !next.text.isEmpty()
                            && (coalescedFrame == null || coalescedFrame.text.isEmpty());
                    boolean urgent = hasPrompts
                            || !next.deviceRequests.isEmpty()
                            || !next.uiPatches.isEmpty()
                            || coalescedFrame == null
                            || !activity.equals(coalescedActivity)
                            || reasoningVisibilityChanged
                            || firstVisibleReply
                            || next.origin != coalescedFrame.origin;
                    coalescedFrame = next;
                    coalescedActivity = activity;
                    coalescedHasPrompts = hasPrompts;
                    String coalesceKey = next.reasoning.isEmpty()
                            ? next.text : (next.text + "\u0000" + next.reasoning);
                    long now = SystemClock.elapsedRealtime();
                    if (urgent) {
                        if (pendingCoalesceFlush != null) {
                            page.main.removeCallbacks(pendingCoalesceFlush);
                            pendingCoalesceFlush = null;
                        }
                        boolean emitted = coalescer.offer(coalesceKey, now);
                        if (!emitted && coalescer.hasPending()) emitted = coalescer.flush(now);
                        if (!emitted) ui.live(activity, next.text, hasPrompts, next.origin, next.reasoning);
                    } else {
                        boolean emitted = coalescer.offer(coalesceKey, now);
                        if (emitted && pendingCoalesceFlush != null) {
                            page.main.removeCallbacks(pendingCoalesceFlush);
                            pendingCoalesceFlush = null;
                        } else if (!emitted && coalescer.hasPending() && pendingCoalesceFlush == null) {
                            long delay = Math.max(1L, coalescer.pendingDelayMs(now));
                            Runnable flushTask = () -> {
                                pendingCoalesceFlush = null;
                                if (token == page.token() && current() && liveCoalescer == coalescer) {
                                    coalescer.flush(SystemClock.elapsedRealtime());
                                }
                            };
                            pendingCoalesceFlush = flushTask;
                            page.main.postDelayed(flushTask, delay);
                        }
                    }
                    decisions.present(next.prompts);
                    if (!next.deviceRequests.isEmpty()) page.handleDeviceRequests(id, next.deviceRequests);
                    if (!next.uiPatches.isEmpty()) {
                        for (JSONObject rawPatch : next.uiPatches) page.onLiveUiPatch(rawPatch);
                    }
                    ui.notice("", "", null);
                    reportProgress(new LiveRunNotification.Snapshot(
                            hasPrompts ? LiveRunNotification.Phase.AWAITING_DECISION
                                    : next.toolSteps > 0 ? LiveRunNotification.Phase.TOOL_RUNNING
                                    : LiveRunNotification.Phase.WORKING,
                            next.toolSteps, 0, hasPrompts, canStop));
                });
            }
            @Override public void complete(JSONObject status, long firstText, long elapsed) {
                deliver(() -> {
                    clearLiveCoalescer();
                    completed(status, firstText, elapsed);
                });
            }
            @Override public void unknown(JSONObject status) {
                deliver(() -> {
                    clearLiveCoalescer();
                    unresolved(status);
                });
            }
            @Override public void disconnected() {
                deliver(() -> {
                    if (liveCoalescer == coalescer && coalescer.hasPending()) {
                        coalescer.flush(SystemClock.elapsedRealtime());
                    }
                    clearLiveCoalescer();
                    checkpoint();
                    decisions.disconnected();
                    started = -1;
                    long delay = journal.nextReconnectDelayMillis();
                    if (delay < 0) { trackingInterrupted(); return; }
                    ui.notice(page.activity.getString(R.string.thread_reconnecting), "", null);
                    page.main.postDelayed(() -> { if (token == page.token() && current()) follow(); }, delay);
                });
            }
        });
        follower = nextFollower;
        page.runStreams.execute(nextFollower::follow);
    }

    private void unresolved(JSONObject status) {
        ++subscription;
        canStop = false;
        reportProgress(LiveRunNotification.Snapshot.idle());
        ui.stopDuplex();
        decisions.clear();
        frame = null;
        started = -1;
        ui.busy(true, false);
        ui.live(page.activity.getString(R.string.thread_run_interrupted), "", false);
        RunAction owner = owner();
        JSONObject receipt = ThreadData.unknownReceipt(status);
        if (receipt == null) {
            ui.notice(page.activity.getString(R.string.thread_gateway_confirm_run),
                    page.activity.getString(R.string.push_inbox_refresh), owner.guard(this::follow));
            return;
        }
        ui.notice(page.activity.getString(R.string.thread_gateway_unblock_notice),
                page.activity.getString(R.string.thread_review_action), owner.guard(() ->
            ui.show(new AlertDialog.Builder(page.activity).setTitle(R.string.thread_unblock_title)
                    .setMessage(R.string.thread_unblock_body)
                    .setNegativeButton(R.string.thread_wait_action, (d,w) -> { if (owner.current()) follow(); })
                    .setPositiveButton(R.string.thread_close_run_action, (d,w) -> { if (owner.current()) resolve(owner, receipt); }))));
    }

    private void resolve(RunAction owner, JSONObject receipt) {
        String path = owner.path();
        ui.notice(page.activity.getString(R.string.thread_checking_run), "", null);
        page.request(() -> page.api.get(path), owner.guard(status -> {
            JSONObject current = ThreadData.unknownReceipt(status);
            if (!owner.run.equals(status.opt("run_id")) || current == null
                    || !current.optString("admission_id").equals(receipt.optString("admission_id"))
                    || current.optLong("execution_generation") != receipt.optLong("execution_generation")) {
                follow();
                return;
            }
            page.request(() -> page.api.post(path + "/resolve-unknown", receipt),
                    owner.guard(done -> follow()), owner.guard(error -> page.error(error, owner.guard(this::follow))));
        }), owner.guard(error -> page.error(error, owner.guard(this::follow))));
    }

    private void trackingInterrupted() {
        reportProgress(LiveRunNotification.Snapshot.idle());
        ui.stopDuplex();
        RunAction owner = owner().when(journal::reconnectsExhausted);
        ui.notice(page.activity.getString(R.string.thread_tracking_interrupted_notice),
                page.activity.getString(R.string.thread_check_conversation), owner.guard(() -> page.loadMessages(false,
                        owner.guard(() -> reviewLost(owner)))));
    }

    private void reviewLost(RunAction owner) {
        Runnable resume = owner.guard(() -> {
            try { journal.resetReconnects(); }
            catch (IllegalStateException unavailable) { page.storageError(); return; }
            follow();
        });
        ui.notice(page.activity.getString(R.string.thread_unknown_result),
                page.activity.getString(R.string.thread_resume_tracking), resume);
        ui.show(new AlertDialog.Builder(page.activity).setTitle(R.string.thread_tracking_interrupted_title)
                .setMessage(R.string.thread_tracking_interrupted_body)
                .setNegativeButton(R.string.thread_close_tracking_ellipsis, (d,w) -> { if (owner.current()) offerDismiss(owner); })
                .setPositiveButton(R.string.thread_resume_action, (d,w) -> resume.run()));
    }

    private void completed(JSONObject status, long firstText, long elapsed) {
        ++subscription;
        canStop = false;
        reportProgress(LiveRunNotification.Snapshot.idle());
        decisions.clear();
        RunAction owner = owner();
        String outcome = status.optString("status");
        String output = ThreadData.string(status, "output");
        String label = page.activity.getString(switch (outcome) {
            case "completed" -> R.string.thread_reply_completed;
            case "cancelled" -> R.string.thread_run_stopped;
            case "interrupted" -> R.string.thread_run_interrupted_gateway;
            default -> R.string.thread_run_failed;
        });
        String detail = "failed".equals(outcome) ? page.failureDetail(status) : "";
        String visionHint = "failed".equals(outcome) && journal.pending().has("photo")
                ? page.activity.getString(R.string.thread_vision_hint) : "";
        String summary = (detail.isEmpty() ? label : label + "\n" + detail) + visionHint;
        if ("completed".equals(outcome) && elapsed >= 0 && firstText >= 0) {
            JSONObject usage = status.optJSONObject("usage");
            long tokens = usage != null && "run".equals(usage.optString("scope"))
                    ? usage.optLong("output_tokens", -1) : -1;
            page.host.timing(page.sessionId(), firstText, elapsed, tokens);
        }
        frame = null;
        ui.busy(true, false);
        ui.live(summary, output, false);
        if ("completed".equals(outcome)) ui.duplexReplyComplete(output);
        else ui.stopDuplex();
        boolean keepPosition = keepCompletionPosition;
        keepCompletionPosition = false;
        page.loadMessages(false, owner.guard(() -> {
            JSONObject receipt = journal.pending();
            boolean enrolled = journal.sameOwner() && receipt.has("nearby_guide");
            if (finishTracking()) return;
            if (enrolled && page.nearby.granted()) {
                NearbyTurn.Guide guide = NearbyTurn.response(receipt, status, page.messages(), SystemClock.elapsedRealtime(), bootCount());
                if (guide != null) page.host.nearbyGuide(page, page.sessionId(), owner.run, guide.row(), guide.area());
            }
            ui.outgoing("");
            ui.busy(false, false);
            boolean visible = false;
            for (int i = page.messages().length() - 1; i >= 0; i--) {
                JSONObject row = page.messages().optJSONObject(i);
                if (row != null && "assistant".equals(row.optString("role"))) {
                    visible = output.equals(ThreadData.content(row));
                    break;
                }
            }
            ui.live("completed".equals(outcome) && (output.isEmpty() || visible) ? "" : summary,
                    visible ? "" : output, false);
            if (status.has("suggested_actions")) page.onSuggestedActions(status.opt("suggested_actions"));
            else page.clearSuggestedActions();
            ui.notice("", "", null);
        }), keepPosition);
    }

    void stop() {
        RunAction owner = owner().when(() -> canStop && journal.sameOwner());
        if (owner.current() && !owner.run.isEmpty()) stop(owner, false);
    }

    private void stop(RunAction owner, boolean retry) {
        Runnable check = owner.guard(() -> stop(owner, true));
        if (retry) {
            page.request(() -> page.api.get(owner.path()), owner.guard(status -> {
                if (owner.stoppable(status)) stop(owner, false);
                else follow();
            }), owner.guard(error -> page.error(error, check)));
            return;
        }
        page.request(() -> page.api.stopRun(owner.run),
                owner.guard(ok -> ui.notice(page.activity.getString(R.string.thread_stop_requested), "", null)),
                owner.guard(error -> page.error(error, check)));
    }

    private void checkpoint() {
        if (frame == null || journal.pending() == null) return;
        journal.checkpoint(frame.seq, frame.text, frame.origin, frame.highSurrogate);
    }

    /**
     * Hands off an active admitted run into {@link ActiveRunTracker} when switching discussions inside
     * the same profile, so SSE streaming continues live without losing deltas or locking the user out.
     * Returns true when {@link ActiveRunTracker} took ownership of {@link ThreadJournal}.
     */
    boolean detachForThreadSwitch() {
        ++subscription;
        clearLiveCoalescer();
        canStop = false;
        ui.stopDuplex();
        if (decisions != null) decisions.clear();
        if (follower != null) {
            follower.close();
            follower = null;
        }
        checkpoint();
        String activeRunId = runId();
        if (!activeRunId.isEmpty() && journal.pending() != null && journal.sameOwner() && !page.api.isRevoked()) {
            boolean handedOff = page.activeRuns.moveToBackground(
                    page.sessionId(),
                    activeRunId,
                    journal,
                    frame,
                    lastProgress,
                    page.api,
                    page.runStreams,
                    page.main,
                    page::profileEpoch,
                    page.profileEpoch(),
                    page::publishRunProgress);
            if (handedOff) {
                page.publishRunProgress();
                return true;
            }
        }
        reportProgress(LiveRunNotification.Snapshot.idle());
        return false;
    }

    void detach() {
        ++subscription;
        clearLiveCoalescer();
        canStop = false;
        reportProgress(LiveRunNotification.Snapshot.idle());
        ui.stopDuplex();
        if (decisions != null) decisions.clear();
        if (follower != null) {
            follower.close();
            follower = null;
        }
        checkpoint();
    }
}
