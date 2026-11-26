package org.llm4people.hermes.thread;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.HermesConfig;
import org.llm4people.hermes.JsonValues;
import org.llm4people.hermes.R;
import org.llm4people.hermes.artifact.ArtifactDesk;
import org.llm4people.hermes.artifact.HomePinBridge;
import org.llm4people.hermes.artifact.LiveUiPatch;
import org.llm4people.hermes.artifact.LiveUiPatchBar;
import org.llm4people.hermes.artifact.ThemePalette;
import org.llm4people.hermes.artifact.ThreadInstruments;
import org.llm4people.hermes.camera.CapturedMedia;
import org.llm4people.hermes.camera.ThreadCameraCapture;
import org.llm4people.hermes.customization.AppCustomization;
import org.llm4people.hermes.customization.AppCustomizationStore;
import org.llm4people.hermes.customization.InteractiveCardView;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.os.HermesWidgetPinning;
import org.llm4people.hermes.os.HermesWidgetSpec;
import org.llm4people.hermes.os.HermesWidgetStore;
import org.llm4people.hermes.os.LiveRunNotification;
import org.llm4people.hermes.presence.DeepAndroidActions;
import org.llm4people.hermes.profile.Account;
import org.llm4people.hermes.share.ClipboardPastePill;
import org.llm4people.hermes.share.DeviceBridgeFlow;
import org.llm4people.hermes.voice.VoiceDrafts;

import java.io.IOException;

import java.util.UUID;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.LinkedHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Profile-scoped native workspace. Call its public methods on the Android main thread. */
public final class ThreadWorkspace implements AutoCloseable {
    public record ThreadSummary(String id, String title) {}

    public interface Host {
        void selected(String sessionId, String title);
        void pendingChanged();
        default void discussionPanes(boolean canToggle, boolean listVisible) {}
        default void noticeThread(boolean sameThread) {}
        default void nearbyGuide(ThreadWorkspace origin, String threadId, String runId, JSONObject assistantRow, JSONObject area) {}
        default void runProgress(LiveRunNotification.Snapshot snapshot) {}
        default JSONObject appPresenceContext() { return null; }
        default DeepAndroidActions deepAndroidActions() { return null; }
        default void onCustomizationChanged(AppCustomization customization) {}
        default void onThemeModeChanged(String themeMode) {}
        JSONObject options(String sessionId);
        void timing(String sessionId, long firstTextMillis, long elapsedMillis, long outputTokens);
    }

    private static final int RPC_WORKERS = 4;

    final Activity activity;
    final HermesApi api;
    final Host host;
    final Handler main = new Handler(Looper.getMainLooper());
    final ExecutorService workers = namedFixedPool("hermes-rpc", RPC_WORKERS);
    final ExecutorService runStreams = boundedStreamPool("hermes-run", ActiveRunTracker.MAX_CONCURRENT_RUNS);
    final ActiveRunTracker activeRuns = new ActiveRunTracker();
    private final Account account;
    private final VoiceDrafts voice;
    private final ConversationPanes container;
    final ThreadViews index;
    ThreadViews screen;
    ThreadJournal journal;
    final NearbyConsent nearby;
    final DeviceBridgeFlow deviceBridge = new DeviceBridgeFlow();
    private ThreadRun run;
    ArtifactDesk.Host instrumentHost;
    private ThreadInstrumentPanel instruments;
    private ThreadStarters starters;
    private boolean emptyTranscript;
    private JSONArray messages = new JSONArray();
    private JSONArray inbox = new JSONArray();
    private String session;
    private String remoteSession;
    private String title;
    private String namedTitle = "";
    private String firstPrompt = "";
    private boolean openingRequested;
    private final LinkedHashMap<String, String> openingPrompts = new LinkedHashMap<>();
    private long messageCount = -1;
    private List<String> slotOrder = Collections.emptyList();
    private volatile int generation;
    private volatile int profileEpoch;
    private final int maxReconnects;
    private final int pageSize;
    private AppCustomization activeCustomization;
    private int inboxOffset;
    private boolean inboxLoaded, inboxLoading, inboxFailed, inboxMore;
    private ThreadInboxFilter.Filter inboxFilter = ThreadInboxFilter.Filter.ALL;
    private JSONArray sharedInbox = new JSONArray();
    private boolean sharedLoaded, sharedLoading, sharedUnavailable, sharedFailed;
    private int sharedLoadEpoch;
    private int sharedOpenEpoch;
    private final ThreadTailCache tailCache = new ThreadTailCache();
    private final long tailCacheEpoch;
    private final InfiniteTranscriptWindow<JSONObject> transcriptWindow = InfiniteTranscriptWindow.forJson();
    private boolean gatewaySupportsKeyset;
    private boolean hasOlderMessages;
    private int messageOffset;
    private boolean closed;
    private boolean creating;
    private boolean loading;
    private String inboxWarning = "";
    private Runnable inboxRecovery;
    private final ExecutorService drafts = Executors.newSingleThreadExecutor();
    private final Runnable save = this::queueSave;

    void queueSave() {
        if (journal == null || closed) return;
        Runnable snapshot = journal.snapshot();
        int owner = generation;
        drafts.execute(() -> {
            try { snapshot.run(); }
            catch (RuntimeException unavailable) { deliver(owner, this::storageError); }
        });
    }

    public ThreadWorkspace(Activity activity, HermesApi api, Account account, Host host, VoiceDrafts voice) {
        ThreadStrings.attach(activity);
        this.voice = voice;
        this.activity = activity;
        this.api = api;
        this.account = account;
        this.host = host;
        HermesConfig config = HermesConfig.load(activity);
        this.maxReconnects = config.maxReconnects;
        this.pageSize = config.threadPageSize;
        this.tailCacheEpoch = tailCache.bindProfile(account.id);
        index = new ThreadViews(activity, voice);
        index.inbox(() -> create(UUID.randomUUID().toString(), null, index), this::refreshActiveInbox,
                this::chooseThread, () -> loadThreads(false), this::selectInboxFilter);
        container = new ConversationPanes(activity, index.root, () -> instruments != null && instruments.viewport.open(),
                this::panesChanged);
        nearby = new NearbyConsent(this, account);
    }

    public void setInstrumentHost(ArtifactDesk.Host actions) { instrumentHost = actions; }
    public boolean closeInstrument() { return instruments != null && instruments.close(); }

    public void acceptNearbyGuide(String threadId, String runId, JSONObject assistantRow, JSONObject approvedArea) {
        if (!closed && instruments != null && threadId != null && threadId.equals(session))
            instruments.admittedGuide(runId, assistantRow, approvedArea);
    }

    void refreshInstruments() {
        if (instruments != null) {
            instruments.proposals(messages);
            screen.refreshInstrumentRows();
        }
    }

    public void editNearbyArea() { nearby.show(null); }
    public boolean nearbyPermission(int code, String[] permissions, int[] results) {
        return nearby.permissionResult(code, permissions, results);
    }
    public void reconcileNearbyPermission() {
        if (!closed && nearby != null) nearby.reconcilePermissionState();
    }
    public void applyAppCustomization(AppCustomization customization) {
        if (closed) return;
        activeCustomization = customization;
        if (starters != null) starters.applyCustomization(customization);
    }
    public void pauseNearbyFix() { nearby.pauseFix(); }
    boolean admitting() { return run != null && run.submitting(); }

    public View view() { return container; }
    public void dismissDialogs() {
        deviceBridge.dismissDialogs();
        dismissAllDialogs();
    }

    private void dismissAllDialogs() {
        if (screen != null) screen.dismiss();
        if (index != null && index != screen) index.dismiss();
    }
    public String sessionId() { return session; }
    public String remoteSessionId() { return remoteSession; }
    Account account() { return account; }
    public String title() { return session == null ? "" : title; }
    public String messageCount() { return session == null || messageCount < 0 ? "" : Long.toString(messageCount); }

    public void slots(List<String> order) {
        slotOrder = new ArrayList<>(order);
        if (screen != null) screen.slots(slotOrder);
        updateStarters();
    }

    public boolean composerVisible() { return screen != null && screen.composerVisible(); }
    public Runnable hiddenPromptAction(Runnable reveal) {
        ThreadViews owner = screen;
        if (closed || owner == null) return null;
        return owner.promptRows.whenHidden(() -> {
            if (owner.instruments != null) owner.instruments.revealDiscussionIfCompact();
            if (owner.list.isShown()) host.pendingChanged();
            else reveal.run();
        });
    }

    public boolean editing() { return screen != null && screen.editing(); }
    public void focusComposer() { if (screen != null) screen.focusComposer(); }

    public void startDictation() {
        if (closed || screen == null) return;
        screen.focusComposer();
        main.post(() -> {
            if (!closed && screen != null) screen.triggerDictation();
        });
    }

    public void stopActiveRun() {
        if (closed) return;
        if (run != null && run.canStop()) {
            run.stop();
            return;
        }
        String bgSession = activeRuns.mostRecentStoppableSession();
        if (bgSession != null) activeRuns.stopBackground(bgSession, api, workers);
    }

    /** Stop a specific running discussion (foreground or background) by canonical root session ID. */
    public boolean stopRun(String sessionId) {
        if (closed || sessionId == null || sessionId.isEmpty()) return false;
        if (sessionId.equals(session) && run != null && run.canStop()) {
            run.stop();
            return true;
        }
        return activeRuns.stopBackground(sessionId, api, workers);
    }

    /** Number of concurrent active tasks currently running across discussions in this profile. */
    public int activeRunCount() {
        return closed ? 0 : activeRuns.activeCount();
    }

    int profileEpoch() {
        return profileEpoch;
    }

    void publishRunProgress() {
        if (closed) return;
        if (host != null) host.runProgress(activeRuns.aggregateSnapshot());
        if (inboxLoaded || inbox.length() > 0 || session != null) {
            paintIndex();
        }
    }

    public List<ThreadSummary> recentThreads() {
        List<ThreadSummary> result = new ArrayList<>();
        for (int i = 0; i < inbox.length(); i++) {
            JSONObject row = inbox.optJSONObject(i);
            if (row == null) continue;
            String id = ThreadIdentity.rootId(row);
            String label = ThreadData.title(row);
            if (ThreadData.validId(id) && !label.isEmpty()) {
                result.add(new ThreadSummary(id, label));
            }
        }
        return result;
    }

    public void openSummary(String rootId, String label) {
        if (closed || !ThreadData.validId(rootId)) return;
        if (!rootId.equals(session)) open(new ThreadIdentity(rootId, rootId),
                label == null || label.trim().isEmpty() ? activity.getString(R.string.thread_conversation) : label);
    }

    boolean importingPhoto;
    public boolean readyForPhoto() {
        if (closed || journal == null || run == null) return false;
        if (!importingPhoto && !run.submitting() && journal.pending() == null && journal.photo() == null) return true;
        int res = journal.photo() != null ? R.string.thread_remove_photo_first : R.string.thread_wait_current_send;
        android.widget.Toast.makeText(activity, res, android.widget.Toast.LENGTH_SHORT).show();
        return false;
    }

    boolean hasPhotoDraft() { return journal != null && journal.photo() != null; }

    void importPhoto(android.net.Uri uri, Runnable completed) {
        if (!readyForPhoto()) { completed.run(); return; }
        importingPhoto = true;
        screen.importingPhoto(true);
        screen.notice(activity.getString(R.string.thread_preparing_image), "", null);
        long maxSourceBytes = HermesConfig.load(activity).artifactDefaultMaxBytes;
        request(() -> DiscussionPhoto.read(activity.getContentResolver(), uri, maxSourceBytes), photo -> {
            importingPhoto = false;
            screen.importingPhoto(false);
            try {
                if (journal.pending() != null || journal.photo() != null) return;
                journal.photo(photo.info, photo.pixels);
                refreshPhoto();
                screen.notice("", "", null);
                screen.focusComposer();
            } finally { completed.run(); }
        }, failure -> {
            importingPhoto = false;
            screen.importingPhoto(false);
            completed.run();
            screen.notice(activity.getString(R.string.thread_image_too_large), "", null);
        });
    }

    void refreshPhoto() {
        JSONObject current = journal.photo();
        screen.photo(current, () -> {
            if (current != journal.photo() || journal.pending() != null) return;
            try { journal.photo(null, null); }
            catch (IllegalStateException unavailable) { storageError(); return; }
            refreshPhoto();
        });
        updateStarters();
    }

    public void stageDraft(String text) {
        if (journal == null || session == null || text == null || text.isEmpty()) return;
        String current = journal.draft();
        screen.displayDraft(current.isEmpty() ? text : current + "\n" + text);
        queueSave();
        screen.focusComposer();
    }

    public void attachMedia(List<CapturedMedia.Item> items) {
        if (closed || screen == null || session == null || items == null || items.isEmpty()) return;
        screen.attachMedia(items, suggestion -> stageDraft(suggestion.draftText(activity)));
        screen.focusComposer();
    }

    private final java.util.Set<android.net.Uri> inFlightCaptureSlots =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public boolean importCapturedPhoto(ThreadCameraCapture capture, Account owner) {
        if (closed || capture == null || owner == null || session == null) return false;
        String targetSession = session;
        android.net.Uri slot = capture.claimReadySlot(owner, targetSession);
        if (slot == null) return false;
        android.content.Context appContext = activity.getApplicationContext();
        int generationAtStart = generation;
        int epochAtStart = profileEpoch;
        return submitCapturedSlot(slot, workers, inFlightCaptureSlots,
                () -> !closed && epochAtStart == profileEpoch && (api == null || !api.isRevoked()),
                () -> {
                    CapturedMedia.Item item = ThreadCameraCapture.readAndDeleteSlot(appContext, slot);
                    deliver(generationAtStart, epochAtStart, () -> {
                        if (item != null && targetSession.equals(session)) {
                            attachMedia(Collections.singletonList(item));
                        }
                    });
                },
                failure -> deliver(generationAtStart, epochAtStart, () -> {
                    Diagnostics.warning("camera", "import_failed", failure);
                    if (screen != null) {
                        screen.notice(activity.getString(R.string.thread_camera_read_failed), "", null);
                    }
                }),
                uri -> org.llm4people.hermes.camera.PrivateCaptureProvider.deleteSlot(appContext, uri));
    }

    interface SlotWork { void run() throws Exception; }

    static <T> boolean submitCapturedSlot(
            T slot,
            ExecutorService executor,
            java.util.Set<T> tracked,
            BooleanSupplier active,
            SlotWork work,
            Consumer<Exception> onFailure,
            Consumer<T> deleter) {
        if (slot == null || executor == null || tracked == null || deleter == null) return false;
        tracked.add(slot);
        try {
            executor.execute(() -> {
                try {
                    if (active == null || !active.getAsBoolean()) return;
                    if (work != null) work.run();
                } catch (Exception failure) {
                    if (onFailure != null) onFailure.accept(failure);
                } finally {
                    tracked.remove(slot);
                    deleter.accept(slot);
                }
            });
            return true;
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            tracked.remove(slot);
            deleter.accept(slot);
            Diagnostics.warning("camera", "import_rejected", rejected);
            return false;
        }
    }

    static <T> void drainCaptureSlots(java.util.Set<T> tracked, Consumer<T> deleter) {
        if (tracked == null || deleter == null) return;
        for (java.util.Iterator<T> it = tracked.iterator(); it.hasNext(); ) {
            T slot = it.next();
            it.remove();
            deleter.accept(slot);
        }
    }

    public void refreshClipboardPill() {
        if (!closed && screen != null && session != null) screen.refreshClipboardPill();
    }

    void handleDeviceRequests(String runId, List<DeviceBridgeFlow.BridgeRequest> requests) {
        if (closed || screen == null || requests == null || requests.isEmpty()) return;
        int epoch = profileEpoch;
        DeviceBridgeFlow.Executor executor = DeviceBridgeFlow.deepAwareExecutor(
                activity, account, new ClipboardPastePill(activity), host == null ? null : host.deepAndroidActions());
        for (DeviceBridgeFlow.BridgeRequest req : requests) {
            deviceBridge.promptUser(activity, instrumentHost, screen.root, req, executor, (status, data) ->
                    dispatchDeviceResult(workers,
                            () -> !closed && epoch == profileEpoch && !api.isRevoked(),
                            () -> {
                                try {
                                    DeviceBridgeFlow.submitResult(api, runId, req.requestId(), status, data);
                                } catch (IOException failed) {
                                    Diagnostics.warning("device_bridge", "result_submit_failed", failed);
                                }
                            }));
        }
    }

    static boolean dispatchDeviceResult(ExecutorService workers, BooleanSupplier active, Runnable submit) {
        if (workers == null || active == null || submit == null || workers.isShutdown() || !active.getAsBoolean()) {
            return false;
        }
        try {
            workers.execute(() -> {
                if (!active.getAsBoolean()) return;
                submit.run();
            });
            return true;
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            Diagnostics.debug("device_bridge", "result_submit_rejected");
            return false;
        }
    }

    /** Checks the durable receipt even if a different discussion was restored after process death. */
    public boolean containsShare(String target, String receipt) {
        if (closed || target == null || !ThreadData.validId(target)) return false;
        ThreadJournal record = target.equals(session) && journal != null ? journal : new ThreadJournal(account, target, maxReconnects);
        return record.containsShare(receipt);
    }

    public void openSharedDraft(String target) {
        if (closed) return;
        if (!target.equals(session)) open(new ThreadIdentity(target, target), activity.getString(R.string.thread_conversation));
        if (target.equals(session) && journal != null) {
            screen.displayDraft(journal.draft());
            screen.focusComposer();
        }
    }

    public boolean appendShare(String expectedSession, String receipt, String text) {
        if (closed || journal == null || !expectedSession.equals(session)) return false;
        journal.appendShare(receipt, text);
        return true;
    }

    private boolean canStartDraft() {
        return journal != null && journal.photo() == null && ThreadStarters.available(emptyTranscript, journal.draft(), journal.pending() != null, slotOrder);
    }

    private void updateStarters() {
        boolean available = canStartDraft();
        if (available && starters == null) {
            ThreadViews owner = screen;
            int opened = generation;
            starters = new ThreadStarters(owner.list, text -> {
                if (opened != generation || owner != screen || !canStartDraft()) return;
                if (text.isEmpty()) owner.focusComposer();
                else stageDraft(text);
                View editor = owner.root.findFocus();
                if (editor instanceof EditText && editor.isShown()) {
                    activity.getSystemService(InputMethodManager.class).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT);
                }
            });
            if (activeCustomization != null) starters.applyCustomization(activeCustomization);
        }
        if (starters != null) starters.show(available);
    }

    public JSONArray messages() { return messages; }

    public void readLastReply() {
        if (closed || session == null) return;
        for (int i = messages.length() - 1; i >= 0; i--) {
            JSONObject row = messages.optJSONObject(i);
            if (!"assistant".equals(ThreadData.string(row, "role"))
                    || "hidden".equals(ThreadData.string(row, "display_kind"))) continue;
            String content = instruments == null ? ThreadData.content(activity, row)
                    : ThreadInstrumentPanel.spoken(activity, row, instruments.forRow(row));
            if (!content.trim().isEmpty()) {
                voice.reader().readReply(content);
                return;
            }
        }
        voice.reader().readReply("");
    }

    public void resumeLast() {
        if (closed) return;
        String last;
        try { last = account.read("last-thread"); }
        catch (RuntimeException unavailable) {
            Diagnostics.warning("threads", "last_thread_read_failed", unavailable);
            inbox(activity.getString(R.string.thread_last_unreadable), null);
            return;
        }
        if (last == null || last.isEmpty()) inbox();
        else if (ThreadData.validId(last)) open(new ThreadIdentity(last, last), activity.getString(R.string.thread_conversation));
        else inbox(activity.getString(R.string.thread_last_invalid), null);
    }

    public boolean back() {
        if (session == null) return false;
        inbox();
        return true;
    }

    public void inbox() { inbox("", null); }

    /** Opens the Chats inbox directly on the inline Shared filter tab. */
    public void openSharedInbox() {
        if (closed) return;
        inbox();
        if (inboxFilter == ThreadInboxFilter.Filter.SHARED) {
            paintIndex();
            if ((!sharedLoaded || sharedFailed) && !sharedLoading) {
                loadSharedThreads();
            } else {
                indexNotice();
            }
        } else {
            selectInboxFilter(ThreadInboxFilter.Filter.SHARED);
        }
    }

    private void inbox(String warning, Runnable recovery) {
        if (closed) return;
        switchScreen(index);
        session = remoteSession = null;
        title = activity.getString(R.string.thread_conversations);
        inboxWarning = warning;
        inboxRecovery = recovery;
        try { account.remove("last-thread"); }
        catch (RuntimeException unavailable) {
            Diagnostics.warning("threads", "last_selection_clear_failed");
            if (inboxWarning.isEmpty()) inboxWarning = activity.getString(R.string.thread_selection_save_failed);
        }
        inboxOffset = 0;
        inbox = new JSONArray();
        inboxLoaded = false;
        container.select(null);
        selectionChanged();
        loadThreads(true);
    }

    private void chooseThread(JSONObject row) {
        if (closed || !index.list.isShown()) return;
        if (ThreadInboxFilter.isSharedRow(row)) {
            openSharedThread(row);
            return;
        }
        try {
            ThreadIdentity identity = ThreadIdentity.of(row);
            if (!identity.root.equals(session)) open(identity, identity.label);
        } catch (IllegalArgumentException invalid) {
            index.notice(activity.getString(R.string.thread_incomplete_server_reply),
                    activity.getString(R.string.instrument_retry), () -> loadThreads(true));
        }
    }

    private void selectInboxFilter(ThreadInboxFilter.Filter next) {
        if (closed) return;
        ThreadInboxFilter.Filter target = next == null ? ThreadInboxFilter.Filter.ALL : next;
        if (inboxFilter == target) return;
        sharedOpenEpoch++;
        inboxFilter = target;
        index.setInboxFilter(target);
        if (target == ThreadInboxFilter.Filter.SHARED) {
            paintIndex();
            if ((!sharedLoaded || sharedFailed) && !sharedLoading) {
                loadSharedThreads();
            } else {
                indexNotice();
            }
            return;
        }
        paintIndex();
        indexNotice();
    }

    private void refreshActiveInbox() {
        if (inboxFilter == ThreadInboxFilter.Filter.SHARED) {
            loadSharedThreads();
        } else {
            loadThreads(true);
        }
    }

    private void loadSharedThreads() {
        if (closed || sharedLoading) return;
        sharedLoading = true;
        sharedUnavailable = false;
        sharedFailed = false;
        int epoch = ++sharedLoadEpoch;
        if (inboxFilter == ThreadInboxFilter.Filter.SHARED) {
            index.notice(activity.getString(R.string.thread_filter_shared_loading), "", null);
        }
        request(() -> api.get("/api/shares/incoming?resource_kind=session"),
                data -> {
                    if (epoch != sharedLoadEpoch) return;
                    sharedLoading = false;
                    sharedLoaded = true;
                    sharedFailed = false;
                    sharedInbox = ThreadInboxFilter.parseSharedSessions(data);
                    paintIndex();
                    indexNotice();
                }, failure -> {
                    if (epoch != sharedLoadEpoch) return;
                    sharedLoading = false;
                    if (failure instanceof HermesApi.HttpError http
                            && (http.status == 404 || http.status == 405 || http.status == 501)) {
                        sharedLoaded = true;
                        sharedUnavailable = true;
                        sharedFailed = false;
                        sharedInbox = new JSONArray();
                        Diagnostics.debug("threads", "shared_sessions_absent");
                        paintIndex();
                        indexNotice();
                    } else {
                        sharedFailed = true;
                        Diagnostics.warning("threads", "shared_sessions_failed", failure);
                        if (inboxFilter == ThreadInboxFilter.Filter.SHARED) {
                            index.notice(activity.getString(R.string.thread_filter_shared_failed),
                                    activity.getString(R.string.instrument_retry), this::loadSharedThreads);
                        }
                    }
                });
    }

    private void openSharedThread(JSONObject row) {
        if (closed) return;
        String shareId = ThreadInboxFilter.shareId(row);
        if (shareId.isEmpty()) return;
        int openEpoch = ++sharedOpenEpoch;
        String label = ThreadData.title(activity, row);
        index.notice(activity.getString(R.string.thread_filter_shared_loading), "", null);
        request(() -> api.get("/api/shares/incoming/" + HermesApi.segment(shareId) + "/content"),
                reply -> {
                    if (closed || openEpoch != sharedOpenEpoch || inboxFilter != ThreadInboxFilter.Filter.SHARED) {
                        return;
                    }
                    indexNotice();
                    String content = ThreadInboxFilter.extractSharedContent(
                            reply,
                            activity.getString(R.string.thread_you),
                            activity.getString(R.string.thread_speaker_hermes));
                    AlertDialog.Builder reader = org.llm4people.hermes.artifact.CodeReader.dialog(
                            activity, label, "markdown", content);
                    if (!content.isEmpty()) {
                        String prompt = activity.getString(R.string.ws_share_draft_prompt, label, content);
                        reader.setPositiveButton(R.string.thread_shared_use_in_new,
                                (d, w) -> create(UUID.randomUUID().toString(), () -> stageDraft(prompt), index));
                    }
                    index.show(reader);
                }, failure -> {
                    if (closed || openEpoch != sharedOpenEpoch || inboxFilter != ThreadInboxFilter.Filter.SHARED) {
                        return;
                    }
                    Diagnostics.warning("threads", "shared_session_open_failed", failure);
                    index.notice(activity.getString(R.string.thread_shared_open_failed),
                            activity.getString(R.string.instrument_retry), () -> openSharedThread(row));
                });
    }

    public void toggleDiscussions() {
        if (!closed) container.toggle();
    }

    private void panesChanged() {
        if (closed) return;
        host.discussionPanes(container.canToggle(), container.sidebarVisible());
        if (container.sidebarVisible() && !inboxLoaded && !inboxLoading && !inboxFailed) loadThreads(true);
    }

    private void selectionChanged() {
        host.selected(session, title);
        paintIndex();
    }

    private void paintIndex() {
        if (inboxFilter == ThreadInboxFilter.Filter.SHARED) {
            index.indexRows(sharedInbox, null, false, Collections.emptyMap(), null);
            return;
        }
        JSONArray visible = inboxFilter == ThreadInboxFilter.Filter.PINNED
                ? ThreadInboxFilter.filterLocal(inbox, ThreadInboxFilter.Filter.PINNED)
                : DiscussionIndex.selectedFirst(inbox, session, remoteSession, title, firstPrompt, messageCount);
        index.indexRows(visible, session, inboxMore, activeRuns.badges(), this::stopRun);
        if (inboxLoading) index.paging();
    }

    private void indexNotice() {
        if (inboxFilter == ThreadInboxFilter.Filter.SHARED) {
            if (sharedLoading) return;
            index.notice(sharedLoaded && sharedInbox.length() == 0
                    ? activity.getString(ThreadInboxFilter.emptyMessageRes(inboxFilter, sharedUnavailable))
                    : "", "", null);
            return;
        }
        if (!inboxWarning.isEmpty()) {
            index.notice(inboxWarning, activity.getString(R.string.thread_recover_action), inboxRecovery);
            return;
        }
        if (inboxFilter == ThreadInboxFilter.Filter.PINNED) {
            JSONArray pinned = ThreadInboxFilter.filterLocal(inbox, ThreadInboxFilter.Filter.PINNED);
            index.notice(inboxLoaded && pinned.length() == 0
                    ? activity.getString(ThreadInboxFilter.emptyMessageRes(inboxFilter, false))
                    : "", "", null);
            return;
        }
        index.notice(inboxLoaded && inbox.length() == 0 && session == null
                ? activity.getString(R.string.thread_empty_profile) : "", "", null);
    }

    private void loadThreads(boolean first) {
        if (closed || inboxLoading) return;
        inboxLoading = true;
        inboxFailed = false;
        if (inboxFilter != ThreadInboxFilter.Filter.SHARED) {
            index.paging();
            index.notice(activity.getString(R.string.thread_loading_list), "", null);
        }
        int offset = first ? 0 : inboxOffset;
        request(() -> api.get("/api/sessions?source=api_server&limit=" + pageSize + "&offset=" + offset),
                data -> {
                    inboxLoading = false;
                    JSONArray page = data.optJSONArray("data");
                    if (page == null) {
                        inboxFailed = true;
                        if (inboxFilter != ThreadInboxFilter.Filter.SHARED) {
                            index.notice(activity.getString(R.string.thread_incomplete_server_reply),
                                    activity.getString(R.string.instrument_retry), () -> loadThreads(first));
                        }
                        return;
                    }
                    for (int i = 0; i < page.length(); i++) {
                        JSONObject row = page.optJSONObject(i);
                        if (row != null) ThreadData.put(row, ThreadTitles.FIRST_PROMPT, openingPrompts.get(ThreadIdentity.rootId(row)));
                    }
                    inbox = DiscussionIndex.merge(first ? new JSONArray() : inbox, page);
                    inboxLoaded = true;
                    inboxOffset = offset + pageSize;
                    inboxMore = page.length() > 0 && data.optBoolean("has_more", page.length() >= pageSize);
                    if (inboxFilter != ThreadInboxFilter.Filter.SHARED) {
                        paintIndex();
                        indexNotice();
                    }
                }, failure -> {
                    inboxLoading = false;
                    inboxFailed = true;
                    if (inboxFilter != ThreadInboxFilter.Filter.SHARED) {
                        index.notice(errorMessage(failure), activity.getString(R.string.instrument_retry), () -> loadThreads(first));
                    }
                });
    }

    public void newThread() { newThread(null); }

    public void newThread(Runnable afterOpen) {
        if (closed || creating) return;
        create(UUID.randomUUID().toString(), afterOpen, screen);
    }

    private void create(String id, Runnable afterOpen, ThreadViews feedback) {
        if (closed || creating) return;
        creating = true;
        feedback.notice(activity.getString(R.string.thread_creating), "", null);
        request(() -> {
            try { return api.post("/api/sessions", ThreadData.put(new JSONObject(), "id", id)); }
            catch (HermesApi.HttpError e) {
                if (e.status != 409 || !"session_exists".equals(e.code)) throw e;
                return api.get(ThreadData.path(id));
            }
        }, response -> {
            creating = false;
            JSONObject row = response.optJSONObject("session");
            if (row == null || !id.equals(row.optString("id"))) {
                feedback.notice(activity.getString(R.string.thread_incomplete_server_reply),
                        activity.getString(R.string.instrument_retry), () -> create(id, afterOpen, feedback));
                return;
            }
            if (feedback == index) indexNotice();
            open(ThreadIdentity.of(row), ThreadData.title(activity, row));
            if (afterOpen != null && id.equals(session)) afterOpen.run();
        }, e -> {
            creating = false;
            feedback.notice(errorMessage(e), activity.getString(R.string.instrument_retry), () -> create(id, afterOpen, feedback));
        });
    }

    private volatile int noticeRequest;

    public void open(String id) { openNotice(id, null, null); }

    public void cancelNotice() { ++noticeRequest; }

    private record NoticeTarget(ThreadIdentity thread, String observedRun) {}

    public void openNotice(String id, String runId, String kind) {
        if (closed || !ThreadData.validId(id)) return;
        String promptKind = ThreadIdentity.noticePromptKind(kind);
        if (promptKind != null && !ThreadData.validId(runId)) return;
        int lookup = ++noticeRequest, owner = generation;
        Runnable retry = () -> openNotice(id, runId, kind);
        request(() -> {
            ThreadIdentity identity = ThreadIdentity.resolve(api, id, () -> owner == generation && lookup == noticeRequest);
            String observed = null;
            if (identity != null && promptKind != null) {
                try {
                    if (ThreadIdentity.hasPendingPrompt(id, runId, promptKind, api.get("/v1/runs/" + HermesApi.segment(runId))))
                        observed = runId;
                } catch (HermesApi.HttpError gone) {
                    if (gone.status != 404) throw gone;
                }
            }
            return new NoticeTarget(identity, observed);
        }, target -> {
            if (lookup != noticeRequest) return;
            ThreadIdentity identity = target.thread();
            if (identity == null) {
                screen.notice(activity.getString(R.string.thread_not_found_profile), "", null);
                return;
            }
            boolean same = journal != null && identity.root.equals(session);
            host.noticeThread(same);
            if (!same) { open(identity, identity.label, target.observedRun()); return; }
            remoteSession = identity.tip;
            if (target.observedRun() != null && run.afterAdmission(() -> {
                if (!closed && owner == generation && lookup == noticeRequest) retry.run();
            })) return;
            run.refreshNotice(runId, target.observedRun());
            loadMessages(false, null, true);
        }, failure -> { if (lookup == noticeRequest) error(failure, retry); });
    }

    private void open(ThreadIdentity identity, String label) { open(identity, label, null); }

    private void open(ThreadIdentity identity, String label, String observedRun) {
        if (closed) return;
        switchScreen(new ThreadViews(activity, voice));
        inboxWarning = "";
        inboxRecovery = null;
        session = identity.root;
        remoteSession = identity.tip;
        String id = session;
        ActiveRunTracker.ReclaimedState reclaimed = activeRuns.reclaimForeground(id);
        namedTitle = identity.name;
        firstPrompt = openingPrompts.getOrDefault(id, identity.prompt);
        title = !namedTitle.isEmpty() || firstPrompt.isEmpty() ? label : firstPrompt;
        try { journal = new ThreadJournal(account, id, maxReconnects); }
        catch (RuntimeException unreadable) {
            Diagnostics.warning("threads", "journal_unreadable");
            inbox(activity.getString(R.string.thread_local_unreadable), () -> recover(identity, label));
            return;
        }
        try {
            if (observedRun != null) journal.observe(observedRun);
            account.write("last-thread", id);
        }
        catch (RuntimeException unavailable) {
            Diagnostics.warning("threads", "last_thread_write_failed", unavailable);
            inbox(activity.getString(R.string.thread_selection_save_retry), null);
            return;
        }
        run = new ThreadRun(this);
        ThreadJournal owner = journal;
        screen.chat(journal.draft(), text -> {
            if (closed || owner != journal) return;
            owner.draft(text);
            updateStarters();
            main.removeCallbacks(save);
            main.postDelayed(save, 300);
        }, () -> { if (owner == journal && !closed) run.send(); },
                () -> { if (owner == journal && !closed) run.stop(); },
                () -> { if (owner == journal && !closed) loadMessages(true, null); });
        screen.attachInfiniteScroll(new InfiniteScrollController.Callbacks() {
            @Override public boolean hasOlder() { return hasOlderMessages; }
            @Override public boolean hasNewer() { return transcriptWindow.hasNewer(); }
            @Override public boolean isLoadingOlder() { return loading; }
            @Override public boolean isLoadingNewer() { return loading; }
            @Override public void loadOlder() { if (owner == journal && !closed) loadMessages(true, null); }
            @Override public void loadNewer() { if (owner == journal && !closed) loadNewerMessages(); }
        }, () -> {
            if (owner != journal || closed) return;
            if (transcriptWindow.hasNewer()) loadMessages(false, null);
            else if (screen != null) screen.followTail();
        });
        refreshPhoto();
        nearby.restore();
        attachLiveUiControls();
        if (instrumentHost != null) {
            instruments = new ThreadInstrumentPanel(this, new ThreadInstruments(account, id), instrumentHost);
            screen.instruments = instruments;
        }
        hydrateFromTailCache(id);
        screen.slots(slotOrder);
        container.select(screen.root);
        indexNotice();
        selectionChanged();
        loadMessages(false, null);
        run.restore(reclaimed);
    }

    private void recover(ThreadIdentity identity, String label) {
        screen.show(new AlertDialog.Builder(activity).setTitle(R.string.thread_recover_title)
                .setMessage(R.string.thread_recover_body)
                .setNegativeButton(R.string.ws_cancel, null)
                .setPositiveButton(R.string.thread_clear_local_data, (dialog, which) -> {
                    try {
                        java.util.Map<String, String> records = new java.util.HashMap<>();
                        records.put("thread:" + identity.root, null);
                        records.put("thread-photo:" + identity.root, null);
                        account.writeTogether(records);
                        tailCache.remove(identity.root);
                        ThreadTailCache.wipeSnapshot(ThreadTailCache.fromAccount(account), identity.root);
                    }
                    catch (RuntimeException failure) { storageError(); return; }
                    open(identity, label);
                }));
    }

    public void rename() {
        if (closed || session == null) return;
        String id = session;
        String target = remoteSession;
        EditText input = new EditText(activity);
        org.llm4people.hermes.Ui.styleDialogInput(activity, input);
        input.setSingleLine();
        input.setSaveEnabled(false);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setHint(R.string.thread_rename_title);
        input.setText(title);
        input.selectAll();
        android.widget.LinearLayout box = org.llm4people.hermes.Ui.column(activity);
        org.llm4people.hermes.Ui.pad(box, 20, 8);
        box.addView(input);
        screen.show(new AlertDialog.Builder(activity).setTitle(R.string.thread_rename_title).setView(box)
                .setNegativeButton(R.string.ws_cancel, null)
                .setPositiveButton(R.string.thread_rename_confirm, (d, w) -> {
                    if (closed || !id.equals(session)) return;
                    String value = input.getText().toString().trim();
                    if (value.isEmpty()) {
                        screen.notice(activity.getString(R.string.thread_empty_title),
                                activity.getString(R.string.thread_rename_confirm), this::rename);
                        return;
                    }
                    request(() -> api.patch(ThreadData.path(target), ThreadData.put(new JSONObject(), "title", value)),
                            r -> { metadataRequest++; namedTitle = title = value; selectionChanged(); }, e -> error(e, this::rename));
                }));
    }

    void loadMessages(boolean older, Runnable loaded) { loadMessages(older, loaded, false); }

    void loadMessages(boolean older, Runnable loaded, boolean keepPosition) {
        if (older && loading) return;
        if (!older && emptyTranscript) {
            emptyTranscript = false;
            updateStarters();
        }
        loading = true;
        screen.paging();
        int offset = older ? messageOffset : 0;
        String path = ThreadData.path(session);
        int requestNo = ++transcriptRequest;
        int titleVersion = metadataRequest;
        String query = path + "/messages?order=latest&visible_only=true&include_compacted=true&limit=";
        int retained = keepPosition ? messageOffset : 0;
        ThreadData.RowId oldest = retained == 0 ? null : ThreadData.rowId(messages.optJSONObject(0));
        long priorTotal = messageCount;
        int owner = generation;
        Long keysetBeforeId = older && gatewaySupportsKeyset ? transcriptWindow.oldestId() : null;
        request(() -> {
            if (keysetBeforeId != null) {
                JSONObject response = api.get(query + pageSize + "&before_id=" + keysetBeforeId);
                JSONArray pageRows = response.optJSONArray("data");
                if (pageRows == null) throw new java.io.IOException("Missing transcript");
                JSONObject pag = response.optJSONObject("pagination");
                boolean moreFlag = pag != null
                        ? pag.optBoolean("has_more", pageRows.length() >= pageSize)
                        : pageRows.length() >= pageSize;
                return new ThreadWindow(response, pageRows, pageRows.length(), moreFlag);
            }
            return ThreadWindow.read(api::get, query, offset, retained, priorTotal, oldest,
                    () -> owner == generation && requestNo == transcriptRequest, pageSize);
        }, window -> {
            if (requestNo != transcriptRequest) return;
            loading = false;
            JSONObject data = window.metadata();
            JSONArray page = window.rows();
            String tip = ThreadData.string(data, "session_id");
            if (ThreadData.validId(tip)) remoteSession = tip;
            int oldSize = messages.length();
            JSONObject pag = data.optJSONObject("pagination");
            Long cursorOldest = pag == null ? null : InfiniteTranscriptWindow.parsePositiveLong(pag.opt("oldest_id"));
            Long cursorNewest = pag == null ? null : InfiniteTranscriptWindow.parsePositiveLong(pag.opt("newest_id"));
            if (cursorOldest != null || cursorNewest != null) gatewaySupportsKeyset = true;
            long total = ThreadContext.visibleTotal(data);
            boolean hasMore = window.more();
            JSONArray visiblePage = ThreadData.visibleMessages(page);
            int prependedCount = 0;
            int anchorShift = 0;
            if (keysetBeforeId != null) {
                InfiniteTranscriptWindow.PageResult result = transcriptWindow.prependOlder(
                        InfiniteTranscriptWindow.fromJsonArray(visiblePage), hasMore, cursorOldest, saturatedTotal(total, -1));
                messages = InfiniteTranscriptWindow.toJsonArray(transcriptWindow.snapshot());
                hasMore = transcriptWindow.hasOlder();
                prependedCount = result.prependedCount();
                anchorShift = result.anchorDelta();
            } else if (older) {
                InfiniteTranscriptWindow.PageResult result = transcriptWindow.prependOlder(
                        InfiniteTranscriptWindow.fromJsonArray(visiblePage), hasMore, cursorOldest,
                        saturatedTotal(total, -1));
                messages = InfiniteTranscriptWindow.toJsonArray(transcriptWindow.snapshot());
                hasMore = transcriptWindow.hasOlder();
                prependedCount = result.prependedCount();
                anchorShift = result.anchorDelta();
            } else {
                messages = ThreadData.visibleMessages(ThreadData.join(page, new JSONArray()));
                transcriptWindow.replaceLatest(InfiniteTranscriptWindow.fromJsonArray(messages), hasMore, cursorOldest, cursorNewest,
                        saturatedTotal(total, messages.length()));
                messages = InfiniteTranscriptWindow.toJsonArray(transcriptWindow.snapshot());
                hasMore = transcriptWindow.hasOlder();
            }
            hasOlderMessages = hasMore;
            // Advance by the rows this response actually returned. The scroll anchor counts displayed rows.
            messageOffset = offset + window.rawCount();
            emptyTranscript = messages.length() == 0 && !hasMore;
            if (instruments != null) instruments.proposals(messages);
            int top = screen.list.getFirstVisiblePosition();
            Object anchor = retained == 0 ? null : screen.list.getItemAtPosition(top);
            int y = screen.list.getChildCount() == 0 ? 0 : screen.list.getChildAt(0).getTop();
            screen.rows(messages, hasMore, !older && retained == 0 && !emptyTranscript, prependedCount, anchorShift);
            if (!older && messages.length() > oldSize && oldSize > 0) {
                screen.onNewMessagesArrived(messages.length() - oldSize);
            }
            if (!older && retained == 0 && !emptyTranscript && session != null) {
                cacheTail(session, remoteSession, title, messages, hasMore,
                        transcriptWindow.oldestId(), transcriptWindow.newestId(),
                        saturatedTotal(total, messages.length()));
            }
            if (retained > 0) {
                if (anchor instanceof JSONObject previous) {
                    ThreadData.RowId id = ThreadData.rowId(previous);
                    for (int i = 0; i < screen.list.getCount(); i++) {
                        Object item = screen.list.getItemAtPosition(i);
                        if (item instanceof JSONObject row && (id == null ? previous.toString().equals(row.toString())
                                : id.equals(ThreadData.rowId(row)))) { top = i; break; }
                    }
                }
                screen.list.setSelectionFromTop(top, y);
            }
            if (journal.pending() == null) screen.notice("", "", null);
            long previousCount = messageCount;
            messageCount = ThreadContext.messageCount(total, page.length(), messages.length(), pageSize);
            boolean receivedTitle = data.has("thread_title");
            if (receivedTitle) legacyTitleTip = null;
            if (receivedTitle && titleVersion == metadataRequest)
                namedTitle = ThreadData.string(data, "thread_title").trim();
            if (!hasMore) rememberOpening(ThreadTitles.firstPrompt(messages));
            String nextTitle = ThreadTitles.display(namedTitle, firstPrompt, !hasMore && messages.length() == 0);
            if (previousCount != messageCount || !title.equals(nextTitle)) {
                title = nextTitle;
                selectionChanged();
            }
            if (!older && !receivedTitle && titleVersion == metadataRequest) readLegacyTitle();
            if (hasMore && firstPrompt.isEmpty() && namedTitle.isEmpty() && !openingRequested) readOpening();
            if (loaded != null) loaded.run();
            updateStarters();
        }, e -> {
            if (requestNo != transcriptRequest) return;
            loading = false;
            error(e, () -> loadMessages(older, loaded, keepPosition));
        });
    }

    /** Real downward keyset paging ({@code after_id}) when scrolling forward from deep history. */
    void loadNewerMessages() {
        if (closed || loading || session == null) return;
        Long afterId = transcriptWindow.newestId();
        if (!gatewaySupportsKeyset || afterId == null || afterId <= 0L) {
            loadMessages(false, null);
            return;
        }
        loading = true;
        int requestNo = ++transcriptRequest;
        String path = ThreadData.path(session);
        String url = path + "/messages?order=oldest&visible_only=true&include_compacted=true&limit="
                + pageSize + "&after_id=" + afterId;
        request(() -> {
            JSONObject response = api.get(url);
            JSONArray pageRows = response.optJSONArray("data");
            if (pageRows == null) throw new java.io.IOException("Missing transcript");
            JSONObject pag = response.optJSONObject("pagination");
            boolean moreNewer = pag != null
                    ? pag.optBoolean("has_more", pageRows.length() >= pageSize)
                    : pageRows.length() >= pageSize;
            return new ThreadWindow(response, pageRows, pageRows.length(), moreNewer);
        }, window -> {
            if (requestNo != transcriptRequest) return;
            loading = false;
            JSONObject data = window.metadata();
            JSONObject pag = data.optJSONObject("pagination");
            Long cursorNewest = pag == null ? null : InfiniteTranscriptWindow.parsePositiveLong(pag.opt("newest_id"));
            long total = ThreadContext.visibleTotal(data);
            JSONArray visiblePage = ThreadData.visibleMessages(window.rows());
            InfiniteTranscriptWindow.PageResult result = transcriptWindow.appendNewer(
                    InfiniteTranscriptWindow.fromJsonArray(visiblePage), window.more(), cursorNewest,
                    saturatedTotal(total, -1));
            messages = InfiniteTranscriptWindow.toJsonArray(transcriptWindow.snapshot());
            hasOlderMessages = transcriptWindow.hasOlder();
            if (instruments != null) instruments.proposals(messages);
            screen.rows(messages, hasOlderMessages, false, 0, result.anchorDelta());
            if (!transcriptWindow.hasNewer() && session != null && messages.length() > 0) {
                cacheTail(session, remoteSession, title, messages, hasOlderMessages,
                        transcriptWindow.oldestId(), transcriptWindow.newestId(),
                        saturatedTotal(total, messages.length()));
            }
        }, e -> {
            if (requestNo != transcriptRequest) return;
            loading = false;
            error(e, this::loadNewerMessages);
        });
    }

    private static int saturatedTotal(long total, int fallback) {
        return total >= 0 ? JsonValues.saturateInt(total) : fallback;
    }

    /** Release in-RAM decoded tail snapshots under OS memory pressure without bumping profileEpoch. */
    public void onTrimMemory() {
        tailCache.onTrimMemory();
    }
    private void rememberOpening(String value) {
        firstPrompt = value;
        if (value.isEmpty()) {
            openingPrompts.remove(session);
            return;
        }
        openingPrompts.put(session, value);
        if (openingPrompts.size() > 128) openingPrompts.remove(openingPrompts.keySet().iterator().next());
    }

    // A latest page is not an opening. One small oldest-page request supplies the source
    // on restored or compacted unnamed discussions without fetching the intervening history.
    private void readOpening() {
        openingRequested = true;
        String path = ThreadData.path(session);
        request(() -> api.get(path + "/messages?order=oldest&limit="
                + pageSize + "&include_compacted=true&visible_only=true"), data -> {
            JSONArray page = data.optJSONArray("data");
            if (page == null || !firstPrompt.isEmpty()) return;
            rememberOpening(ThreadTitles.firstPrompt(page));
            if (namedTitle.isEmpty() && !firstPrompt.isEmpty() && !firstPrompt.equals(title)) {
                title = firstPrompt;
                selectionChanged();
            }
        }, failure -> Diagnostics.event("threads", "opening_unavailable"));
    }

    // Older gateways return segment titles separately. Check the root only when the live tip
    // is untitled. A page navigation or polling refresh never scans segments or walks older pages.
    private void readLegacyTitle() {
        String root = session;
        String tip = remoteSession;
        if (tip.equals(legacyTitleTip)) return;
        legacyTitleTip = tip;
        int version = metadataRequest;
        request(() -> ThreadContext.legacyTitle(root, tip, api::get), label -> {
            if (version != metadataRequest || !tip.equals(legacyTitleTip) || label.isEmpty()) return;
            namedTitle = label;
            if (!label.equals(title)) {
                title = label;
                selectionChanged();
            }
        }, failure -> {
            if (tip.equals(legacyTitleTip)) legacyTitleTip = null;
            Diagnostics.event("threads", "metadata_unavailable");
        });
    }

    private String legacyTitleTip;
    private int metadataRequest;
    private volatile int transcriptRequest;
    private JSONObject pendingLiveUiPatch;
    private String appliedLiveUiRevisionId;

    public void onBackground() {
        if (nearby != null) nearby.clearTurn();
        if (voice != null && voice.reader() != null) voice.reader().stop();
        deviceBridge.dismissDialogs();
        if (!closed) {
            ++generation;
            main.removeCallbacksAndMessages(null);
            activeRuns.stopAllWatchers();
            dismissAllDialogs();
            if (instruments != null) { instruments.retire(); instruments = null; }
            try {
                if (run != null) run.detach();
                if (journal != null) journal.flush();
            } catch (RuntimeException unavailable) {
                storageError();
            }
            notifyIdleSafely(host);
        }
    }

    /** Switch between discussions inside the same unlocked profile without cancelling active SSE runs. */
    private void switchScreen(ThreadViews next) {
        nearby.clearTurn();
        voice.reader().stop();
        deviceBridge.dismissDialogs();
        boolean handedOff = false;
        if (!closed) {
            ++generation;
            main.removeCallbacksAndMessages(null);
            dismissAllDialogs();
            if (instruments != null) { instruments.retire(); instruments = null; }
            try {
                if (run != null) handedOff = run.detachForThreadSwitch();
                if (journal != null) journal.flush();
            } catch (RuntimeException unavailable) {
                storageError();
            }
        }
        if (!handedOff && journal != null) journal.retire();
        importingPhoto = false;
        loading = creating = inboxLoading = sharedLoading = false;
        run = null;
        journal = null;
        messages = new JSONArray();
        transcriptWindow.clear();
        hasOlderMessages = false;
        messageOffset = 0;
        messageCount = -1;
        emptyTranscript = false;
        starters = null;
        legacyTitleTip = null;
        openingRequested = false;
        namedTitle = firstPrompt = "";
        appliedLiveUiRevisionId = null;
        screen = next;
    }

    static void revokeApiSafely(HermesApi client) {
        if (client != null) client.revoke();
    }

    static void notifyIdleSafely(Host target) {
        if (target != null) target.runProgress(LiveRunNotification.Snapshot.idle());
    }

    @Override public void close() {
        if (closed) return;
        ++profileEpoch;
        revokeApiSafely(api);
        try { onBackground(); }
        finally {
            closed = true;
            dismissAllDialogs();
            if (journal != null) journal.retire();
            workers.shutdownNow();
            android.content.Context appContext = activity.getApplicationContext();
            drainCaptureSlots(inFlightCaptureSlots,
                    uri -> org.llm4people.hermes.camera.PrivateCaptureProvider.deleteSlot(appContext, uri));
            runStreams.shutdownNow();
            drafts.shutdownNow();
            index.indexRows(new JSONArray(), null, false);
            index.notice("", "", null);
            index.root.removeAllViews();
            container.retire();
            screen = null;
            starters = null;
            run = null;
            journal = null;
            session = remoteSession = null;
            messages = new JSONArray();
            tailCache.clear();
            transcriptWindow.clear();
            hasOlderMessages = false;
            inbox = new JSONArray();
            inboxLoaded = inboxLoading = inboxFailed = inboxMore = false;
            sharedInbox = new JSONArray();
            sharedLoaded = sharedLoading = sharedUnavailable = sharedFailed = false;
            inboxRecovery = null;
            openingPrompts.clear();
            namedTitle = firstPrompt = "";
            pendingLiveUiPatch = null;
            appliedLiveUiRevisionId = null;
        }
    }

    private static ExecutorService namedFixedPool(String prefix, int size) {
        AtomicInteger counter = new AtomicInteger(1);
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, prefix + "-" + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
        return Executors.newFixedThreadPool(size, factory);
    }

    private static ExecutorService boundedStreamPool(String prefix, int maxThreads) {
        AtomicInteger counter = new AtomicInteger(1);
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                maxThreads, maxThreads, 30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r, prefix + "-" + counter.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    <T> void request(Callable<T> work, Consumer<T> success, Consumer<Exception> failure) {
        int generationAtStart = generation;
        int epochAtStart = profileEpoch;
        workers.execute(() -> {
            if (closed || epochAtStart != profileEpoch || api.isRevoked()) return;
            try {
                T data = work.call();
                deliver(generationAtStart, epochAtStart, () -> success.accept(data));
            } catch (Exception e) {
                deliver(generationAtStart, epochAtStart, () -> failure.accept(e));
            }
        });
    }

    public BooleanSupplier discussionOrigin() {
        int token = generation;
        int epoch = profileEpoch;
        return () -> !closed && generation == token && profileEpoch == epoch;
    }

    int token() { return generation; }

    void deliver(int token, Runnable update) {
        deliver(token, profileEpoch, update);
    }

    void deliver(int token, int epoch, Runnable update) {
        main.post(() -> {
            if (!closed && token == generation && epoch == profileEpoch) {
                try { update.run(); }
                catch (IllegalStateException e) { storageError(); }
            }
        });
    }

    void error(Exception error, Runnable retry) {
        screen.notice(errorMessage(error), activity.getString(R.string.instrument_retry), retry);
    }

    private String errorMessage(Exception error) {
        if (error instanceof HermesApi.HttpError http) {
            return switch (http.status) {
                case 401, 403 -> activity.getString(R.string.thread_http_unauthorized);
                case 404 -> activity.getString(R.string.thread_http_not_found);
                case 409 -> activity.getString(R.string.thread_http_conflict);
                case 429 -> activity.getString(R.string.thread_http_rate_limited);
                default -> activity.getString(R.string.thread_http_refused, http.status);
            };
        }
        return activity.getString(R.string.thread_connection_failed);
    }

    String failureDetail(JSONObject status) { return ThreadData.failureDetail(status, account.bearer); }

    void clearSuggestedActions() {
        if (closed || screen == null) return;
        screen.suggestedActions(Collections.emptyList(), null, null, null, null, null);
        screen.interactiveCards(Collections.emptyList(), null);
    }

    /** Reconciles composer quick actions and LiveUiPatchBar after external layout/rollback changes. */
    public void onLayoutChanged() {
        if (closed || screen == null || account == null) return;
        refreshQuickActions();
        if (appliedLiveUiRevisionId != null && screen.liveUiBar != null) {
            LiveUiPatch.StateManager manager = LiveUiPatch.StateManager.forAccount(
                    account, ThemePalette.isNight(activity));
            boolean stillApplied = false;
            for (LiveUiPatch.StateManager.AppliedRevision rev : manager.history()) {
                if (appliedLiveUiRevisionId.equals(rev.revisionId)) {
                    stillApplied = true;
                    break;
                }
            }
            if (!stillApplied) {
                appliedLiveUiRevisionId = null;
                screen.liveUiBar.showReverted();
            }
        }
    }

    void onSuggestedActions(Object rawSuggestedActions) {
        if (closed || screen == null) return;
        try {
            List<LiveUiPatch.SuggestedAction> actions = LiveUiPatch.parseSuggestedActions(rawSuggestedActions);
            ThemePalette palette = ThemePalette.active(activity, account);
            screen.suggestedActions(actions, palette,
                    this::stageDraft,
                    page -> { if (instrumentHost != null) instrumentHost.openWorkspace(page); },
                    action -> { if (instrumentHost != null) instrumentHost.onAction(action); },
                    this::pinWidgetById);
        } catch (java.io.IOException rejected) {
            Diagnostics.warning("live_ui", "suggested_actions_rejected");
        }
    }

    public void openWithLiveUiPatch(String id, JSONObject rawPatch) {
        if (closed || rawPatch == null) return;
        pendingLiveUiPatch = rawPatch;
        open(id);
    }

    public void onLiveUiPatch(JSONObject rawPatch) {
        if (closed || rawPatch == null) return;
        if (screen == null || screen.liveUiBar == null) {
            pendingLiveUiPatch = rawPatch;
            return;
        }
        pendingLiveUiPatch = null;
        try {
            LiveUiPatch patch = LiveUiPatch.parse(rawPatch, ThemePalette.isNight(activity));
            appliedLiveUiRevisionId = null;
            if (!patch.ops.isEmpty()) {
                screen.liveUiBar.present(patch);
            } else {
                screen.liveUiBar.clear();
            }
            if (!patch.suggestedActions.isEmpty()) {
                ThemePalette palette = ThemePalette.active(activity, account);
                screen.suggestedActions(patch.suggestedActions, palette,
                        this::stageDraft,
                        page -> { if (instrumentHost != null) instrumentHost.openWorkspace(page); },
                        action -> { if (instrumentHost != null) instrumentHost.onAction(action); },
                        this::pinWidgetById);
            }
            screen.interactiveCards(patch.interactiveCards,
                    patch.interactiveCards.isEmpty() ? null : interactiveCardCallbacks(patch.interactiveCards));
            Diagnostics.event("live_ui", "patch_presented");
        } catch (java.io.IOException rejected) {
            Diagnostics.warning("live_ui", "patch_rejected");
        }
    }

    private InteractiveCardView.Callbacks interactiveCardCallbacks(List<InteractiveCardView.Spec> cards) {
        return new InteractiveCardView.Callbacks() {
            @Override public void onQuickPickDraft(String cardId, String choiceId, String draftText) {
                if (!closed && draftText != null && !draftText.isEmpty()) stageDraft(draftText);
            }
            @Override public void onApplyCustomization(String cardId, AppCustomization proposed) {
                if (closed || account == null || proposed == null) return;
                InteractiveCardView.Spec matched = null;
                if (cards != null && cardId != null) {
                    for (InteractiveCardView.Spec candidate : cards) {
                        if (candidate != null && cardId.equals(candidate.cardId)) {
                            matched = candidate;
                            break;
                        }
                    }
                }
                AppCustomizationStore store = AppCustomizationStore.forAccount(account);
                AppCustomization merged = matched != null ? matched.mergeOnto(store.current()) : proposed;
                AppCustomization applied = store.apply(merged);
                if (host != null) {
                    if (matched != null && matched.customizationDelta != null
                            && matched.customizationDelta.themeMode() != null) {
                        host.onThemeModeChanged(matched.customizationDelta.themeMode());
                    }
                    host.onCustomizationChanged(applied);
                }
            }
            @Override public void onUndoCustomization(String cardId) {
                if (closed || account == null) return;
                AppCustomization restored = AppCustomizationStore.forAccount(account).undo();
                if (host != null) host.onCustomizationChanged(restored);
            }
        };
    }

    private void pinWidgetById(String widgetId) {
        if (closed || account == null || widgetId == null || widgetId.isEmpty()) return;
        LiveUiPatch.StateManager manager = LiveUiPatch.StateManager.forAccount(account, ThemePalette.isNight(activity));
        for (LiveUiPatch.WidgetSpec spec : manager.widgets()) {
            if (widgetId.equals(spec.widgetId)) {
                HomePinBridge.requestPinWidget(activity, spec, account.id);
                return;
            }
        }
        LiveUiPatch pending = screen != null && screen.liveUiBar != null ? screen.liveUiBar.pendingPatch() : null;
        if (pending != null) {
            for (LiveUiPatch.Op op : pending.ops) {
                if (op instanceof LiveUiPatch.Op.UpsertWidget uw && widgetId.equals(uw.widget().widgetId)) {
                    HomePinBridge.requestPinWidget(activity, uw.widget(), account.id);
                    return;
                }
            }
        }
        HermesWidgetSpec osSpec = new HermesWidgetStore(activity).spec(widgetId);
        if (osSpec != null && osSpec.homeScreenSafe
                && (osSpec.ownerProfileId == null || account.id.equals(osSpec.ownerProfileId))) {
            HermesWidgetPinning.PinResult result = HermesWidgetPinning.requestPinWidget(
                    activity, osSpec.withOwner(account.id));
            Toast.makeText(activity, HermesWidgetPinning.guidanceMessage(activity, result), Toast.LENGTH_LONG).show();
        }
    }

    private void attachLiveUiControls() {
        if (screen == null || account == null) return;
        screen.attachLiveUiBar(new LiveUiPatchBar.Listener() {
            @Override public void onApply(LiveUiPatch patch) {
                if (closed) return;
                try {
                    LiveUiPatch.StateManager manager = LiveUiPatch.StateManager.forAccount(account, ThemePalette.isNight(activity));
                    manager.apply(patch);
                    appliedLiveUiRevisionId = patch.revisionId;
                    if (screen != null && screen.liveUiBar != null) screen.liveUiBar.showApplied(patch);
                    refreshQuickActions();
                    for (LiveUiPatch.Op op : patch.ops) {
                        if (op instanceof LiveUiPatch.Op.UpsertWidget uw) {
                            HomePinBridge.syncWidget(activity, uw.widget(), account.id);
                        } else if (op instanceof LiveUiPatch.Op.RemoveWidget rw) {
                            HomePinBridge.removeWidget(activity, rw.widgetId(), account.id);
                        }
                    }
                    if (instrumentHost != null) instrumentHost.onLayoutChanged();
                    request(() -> api.applyMobileUiRevision(patch.revisionId),
                            ignored -> Diagnostics.event("live_ui", "server_applied"),
                            ignored -> Diagnostics.event("live_ui", "server_apply_skipped"));
                } catch (java.io.IOException | IllegalStateException e) {
                    Diagnostics.warning("live_ui", "apply_failed");
                    if (screen != null) screen.notice(activity.getString(R.string.thread_live_ui_apply_failed), "", null);
                }
            }

            @Override public void onRollback(String revisionId) {
                if (closed) return;
                try {
                    LiveUiPatch.StateManager manager = LiveUiPatch.StateManager.forAccount(account, ThemePalette.isNight(activity));
                    java.util.Set<String> affectedWidgets = new java.util.HashSet<>(manager.widgetIds());
                    List<LiveUiPatch.StateManager.AppliedRevision> reverted = manager.rollbackAllTo(revisionId);
                    affectedWidgets.addAll(manager.widgetIds());
                    HomePinBridge.syncWidgets(activity, manager.widgets(), affectedWidgets, account.id);
                    appliedLiveUiRevisionId = null;
                    if (screen != null && screen.liveUiBar != null) screen.liveUiBar.showReverted();
                    refreshQuickActions();
                    if (instrumentHost != null) instrumentHost.onLayoutChanged();
                    // The gateway only reverts its active head, so mirror every locally popped revision newest-first.
                    List<String> revertedIds = new java.util.ArrayList<>();
                    for (LiveUiPatch.StateManager.AppliedRevision rev : reverted) revertedIds.add(rev.revisionId);
                    if (revertedIds.isEmpty()) revertedIds.add(revisionId);
                    request(() -> {
                        int skipped = 0;
                        for (String id : revertedIds) {
                            try { api.revertMobileUiRevision(id); }
                            catch (java.io.IOException | IllegalArgumentException unavailable) { skipped++; }
                        }
                        return skipped;
                    }, skipped -> Diagnostics.event("live_ui", skipped == 0 ? "server_reverted" : "server_revert_skipped"),
                            ignored -> Diagnostics.event("live_ui", "server_revert_skipped"));
                } catch (java.io.IOException | IllegalStateException e) {
                    Diagnostics.warning("live_ui", "rollback_failed");
                    if (screen != null) screen.notice(activity.getString(R.string.thread_live_ui_rollback_failed), "", null);
                }
            }

            @Override public void onDismiss(LiveUiPatch patch) {
                appliedLiveUiRevisionId = null;
                Diagnostics.event("live_ui", "patch_dismissed");
            }

            @Override public void onPinWidget(LiveUiPatch.WidgetSpec widget) {
                if (closed || account == null || widget == null) return;
                HomePinBridge.requestPinWidget(activity, widget, account.id);
            }
        });
        refreshQuickActions();
        if (pendingLiveUiPatch != null) {
            JSONObject queued = pendingLiveUiPatch;
            pendingLiveUiPatch = null;
            onLiveUiPatch(queued);
        }
    }

    private void refreshQuickActions() {
        if (closed || screen == null || account == null) return;
        ThemePalette palette = ThemePalette.active(activity, account);
        LiveUiPatch.StateManager manager = LiveUiPatch.StateManager.forAccount(account, palette.night);
        screen.quickActions(manager.quickActions(), palette,
                this::stageDraft,
                page -> { if (instrumentHost != null) instrumentHost.openWorkspace(page); },
                action -> { if (instrumentHost != null) instrumentHost.onAction(action); });
    }

    private void hydrateFromTailCache(String threadId) {
        if (closed || screen == null || threadId == null || threadId.isEmpty()) return;
        ThreadTailCache.Entry cached = tailCache.get(tailCacheEpoch, threadId);
        if (cached == null) {
            cached = ThreadTailCache.loadSnapshot(ThreadTailCache.fromAccount(account), threadId);
            if (cached != null) tailCache.put(tailCacheEpoch, cached);
        }
        if (cached == null || cached.encodedRows().isEmpty()) return;
        List<JSONObject> parsed = new java.util.ArrayList<>(cached.encodedRows().size());
        for (String raw : cached.encodedRows()) {
            try { parsed.add(new JSONObject(raw)); }
            catch (org.json.JSONException ignored) {
                Diagnostics.warning("threads", "tail_cache_row_corrupt", ignored);
            }
        }
        if (parsed.isEmpty()) return;
        transcriptWindow.replaceLatest(parsed, cached.hasMore(), cached.oldestId(), cached.newestId(), cached.totalCount());
        messages = InfiniteTranscriptWindow.toJsonArray(transcriptWindow.snapshot());
        hasOlderMessages = transcriptWindow.hasOlder();
        emptyTranscript = false;
        if (instruments != null) instruments.proposals(messages);
        screen.rows(messages, hasOlderMessages, true, 0);
    }

    private void cacheTail(String threadId, String remoteId, String threadTitle, JSONArray rows,
                           boolean hasMore, Long oldestId, Long newestId, int totalCount) {
        if (closed || threadId == null || threadId.isEmpty() || rows == null || rows.length() == 0) return;
        int start = Math.max(0, rows.length() - ThreadTailCache.MAX_MESSAGES_PER_THREAD);
        List<String> encoded = new java.util.ArrayList<>(rows.length() - start);
        for (int i = start; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null) encoded.add(row.toString());
        }
        if (encoded.isEmpty()) return;
        ThreadTailCache.Entry entry = new ThreadTailCache.Entry(
                threadId, remoteId, threadTitle, encoded, hasMore, oldestId, newestId,
                totalCount, System.currentTimeMillis());
        if (!tailCache.put(tailCacheEpoch, entry)) return;
        int epochSnapshot = profileEpoch;
        drafts.execute(() -> {
            if (!closed && epochSnapshot == profileEpoch) {
                ThreadTailCache.saveSnapshot(ThreadTailCache.fromAccount(account), entry);
            }
        });
    }

    void storageError() {
        Diagnostics.event("threads", "storage_failed");
        if (screen != null) screen.notice(activity.getString(R.string.thread_storage_failed), "", null);
    }
}
