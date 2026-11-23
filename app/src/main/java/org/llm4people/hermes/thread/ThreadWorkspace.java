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
import org.llm4people.hermes.artifact.ArtifactDesk;
import org.llm4people.hermes.artifact.HomePinBridge;
import org.llm4people.hermes.artifact.LiveUiPatch;
import org.llm4people.hermes.artifact.LiveUiPatchBar;
import org.llm4people.hermes.artifact.ThemePalette;
import org.llm4people.hermes.artifact.ThreadInstruments;
import org.llm4people.hermes.camera.CapturedMedia;
import org.llm4people.hermes.camera.ThreadCameraCapture;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.os.HermesWidgetPinning;
import org.llm4people.hermes.os.HermesWidgetSpec;
import org.llm4people.hermes.os.HermesWidgetStore;
import org.llm4people.hermes.os.LiveRunNotification;
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
        JSONObject options(String sessionId);
        void timing(String sessionId, long firstTextMillis, long elapsedMillis, long outputTokens);
    }

    final Activity activity;
    final HermesApi api;
    final Host host;
    final Handler main = new Handler(Looper.getMainLooper());
    final ExecutorService workers = Executors.newFixedThreadPool(3);
    private final Account account;
    private final VoiceDrafts voice;
    private final ConversationPanes container;
    private final ThreadViews index;
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
    private int inboxOffset;
    private boolean inboxLoaded, inboxLoading, inboxFailed, inboxMore;
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
        this.voice = voice;
        this.activity = activity;
        this.api = api;
        this.account = account;
        this.host = host;
        index = new ThreadViews(activity, voice);
        index.inbox(() -> create(UUID.randomUUID().toString(), null, index), () -> loadThreads(true),
                this::chooseThread, () -> loadThreads(false));
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
    public void chooseGuideArea(BooleanSupplier current, Consumer<JSONObject> selected) { nearby.chooseGuide(current, selected); }
    public boolean nearbyPermission(int code, String[] permissions, int[] results) {
        return nearby.permissionResult(code, permissions, results);
    }
    public void pauseNearbyFix() { nearby.pauseFix(); }
    boolean admitting() { return run != null && run.submitting(); }

    public View view() { return container; }
    public void dismissDialogs() {
        deviceBridge.dismissDialogs();
        if (screen != null) screen.dismiss();
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
        if (!closed && run != null) run.stop();
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
                label == null || label.trim().isEmpty() ? "Conversation" : label);
    }

    boolean importingPhoto;
    public boolean readyForPhoto() {
        if (closed || journal == null || run == null) return false;
        if (!importingPhoto && !run.submitting() && journal.pending() == null && journal.photo() == null) return true;
        String text = journal.photo() != null ? "Retirez d’abord l’image jointe pour la remplacer."
                : "Patientez jusqu’à la fin de l’envoi en cours.";
        android.widget.Toast.makeText(activity, text, android.widget.Toast.LENGTH_SHORT).show();
        return false;
    }

    boolean hasPhotoDraft() { return journal != null && journal.photo() != null; }

    void importPhoto(android.net.Uri uri, Runnable completed) {
        if (!readyForPhoto()) { completed.run(); return; }
        importingPhoto = true;
        screen.importingPhoto(true);
        screen.notice("Préparation de l’image…", "", null);
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
            screen.notice("Image illisible ou trop volumineuse. Choisissez une photo ou une capture fixe de moins de 16 Mio.", "", null);
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
        screen.attachMedia(items, suggestion -> stageDraft(suggestion.draftText()));
        screen.focusComposer();
    }

    public boolean importCapturedPhoto(ThreadCameraCapture capture, Account owner) {
        if (closed || capture == null || !capture.isReady() || owner == null || session == null) return false;
        String targetSession = session;
        request(() -> capture.consumeIfReady(owner, targetSession), item -> {
            if (item != null && targetSession.equals(session)) {
                attachMedia(Collections.singletonList(item));
            }
        }, failure -> {
            Diagnostics.warning("camera", "import_failed", failure);
            if (screen != null) {
                screen.notice("La photo capturée n’a pas pu être lue. Réessayez.", "", null);
            }
        });
        return true;
    }

    public void refreshClipboardPill() {
        if (!closed && screen != null && session != null) screen.refreshClipboardPill();
    }

    void handleDeviceRequests(String runId, List<DeviceBridgeFlow.BridgeRequest> requests) {
        if (closed || screen == null || requests == null || requests.isEmpty()) return;
        DeviceBridgeFlow.Executor executor = DeviceBridgeFlow.ambientAwareExecutor(
                activity, account, new ClipboardPastePill(activity));
        for (DeviceBridgeFlow.BridgeRequest req : requests) {
            deviceBridge.promptUser(activity, instrumentHost, screen.root, req, executor, (status, data) ->
                    workers.execute(() -> {
                        try {
                            DeviceBridgeFlow.submitResult(api, runId, req.requestId(), status, data);
                        } catch (IOException failed) {
                            Diagnostics.warning("device_bridge", "result_submit_failed", failed);
                        }
                    }));
        }
    }

    /** Checks the durable receipt even if a different discussion was restored after process death. */
    public boolean containsShare(String target, String receipt) {
        if (closed || target == null || !ThreadData.validId(target)) return false;
        ThreadJournal record = target.equals(session) && journal != null ? journal : new ThreadJournal(account, target);
        return record.containsShare(receipt);
    }

    public void openSharedDraft(String target) {
        if (closed) return;
        if (!target.equals(session)) open(new ThreadIdentity(target, target), "Conversation");
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
            String content = instruments == null ? ThreadData.content(row)
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
            inbox("Dernière conversation illisible. Choisissez une conversation pour reprendre.", null);
            return;
        }
        if (last == null || last.isEmpty()) inbox();
        else if (ThreadData.validId(last)) open(new ThreadIdentity(last, last), "Conversation");
        else inbox("L’identifiant de la dernière conversation est invalide.", null);
    }

    public boolean back() {
        if (session == null) return false;
        inbox();
        return true;
    }

    public void inbox() { inbox("", null); }

    private void inbox(String warning, Runnable recovery) {
        if (closed) return;
        reset(index);
        session = remoteSession = null;
        title = "Conversations";
        inboxWarning = warning;
        inboxRecovery = recovery;
        try { account.remove("last-thread"); }
        catch (RuntimeException unavailable) {
            Diagnostics.warning("threads", "last_selection_clear_failed");
            if (inboxWarning.isEmpty()) inboxWarning = "Impossible d’enregistrer la sélection. Vérifiez l’espace disponible.";
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
        try {
            ThreadIdentity identity = ThreadIdentity.of(row);
            if (!identity.root.equals(session)) open(identity, identity.label);
        } catch (IllegalArgumentException invalid) {
            index.notice("La réponse du serveur est incomplète.", "Réessayer", () -> loadThreads(true));
        }
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
        index.indexRows(DiscussionIndex.selectedFirst(inbox, session, remoteSession, title, firstPrompt, messageCount),
                session, inboxMore);
        if (inboxLoading) index.paging();
    }

    private void indexNotice() {
        if (!inboxWarning.isEmpty()) index.notice(inboxWarning, "Récupérer la conversation…", inboxRecovery);
        else index.notice(inboxLoaded && inbox.length() == 0 && session == null ? "Aucune conversation pour ce profil." : "", "", null);
    }

    private void loadThreads(boolean first) {
        if (closed || inboxLoading) return;
        inboxLoading = true;
        inboxFailed = false;
        index.paging();
        index.notice("Chargement des conversations…", "", null);
        int offset = first ? 0 : inboxOffset;
        request(() -> api.get("/api/sessions?source=api_server&limit=" + ThreadData.PAGE + "&offset=" + offset),
                data -> {
                    inboxLoading = false;
                    JSONArray page = data.optJSONArray("data");
                    if (page == null) {
                        inboxFailed = true;
                        index.notice("La réponse du serveur est incomplète.", "Réessayer", () -> loadThreads(first));
                        return;
                    }
                    for (int i = 0; i < page.length(); i++) {
                        JSONObject row = page.optJSONObject(i);
                        if (row != null) ThreadData.put(row, ThreadTitles.FIRST_PROMPT, openingPrompts.get(ThreadIdentity.rootId(row)));
                    }
                    inbox = DiscussionIndex.merge(first ? new JSONArray() : inbox, page);
                    inboxLoaded = true;
                    inboxOffset = offset + ThreadData.PAGE;
                    inboxMore = page.length() > 0 && data.optBoolean("has_more", page.length() >= ThreadData.PAGE);
                    paintIndex();
                    indexNotice();
                }, failure -> {
                    inboxLoading = false;
                    inboxFailed = true;
                    index.notice(errorMessage(failure), "Réessayer", () -> loadThreads(first));
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
        feedback.notice("Création de la conversation…", "", null);
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
                feedback.notice("La réponse du serveur est incomplète.", "Réessayer", () -> create(id, afterOpen, feedback));
                return;
            }
            if (feedback == index) indexNotice();
            open(ThreadIdentity.of(row), ThreadData.title(row));
            if (afterOpen != null && id.equals(session)) afterOpen.run();
        }, e -> {
            creating = false;
            feedback.notice(errorMessage(e), "Réessayer", () -> create(id, afterOpen, feedback));
        });
    }

    private volatile int noticeRequest;

    public void open(String id) { openNotice(id, null, null); }
    public void openQuestion(String id, String runId) { openNotice(id, runId, "question_requested"); }

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
                screen.notice("Cette conversation est introuvable dans le profil actif.", "", null);
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
        reset(new ThreadViews(activity, voice));
        inboxWarning = "";
        inboxRecovery = null;
        session = identity.root;
        remoteSession = identity.tip;
        String id = session;
        namedTitle = identity.name;
        firstPrompt = openingPrompts.getOrDefault(id, identity.prompt);
        title = !namedTitle.isEmpty() || firstPrompt.isEmpty() ? label : firstPrompt;
        try { journal = new ThreadJournal(account, id); }
        catch (RuntimeException unreadable) {
            Diagnostics.warning("threads", "journal_unreadable");
            inbox("Les données locales de cette conversation sont illisibles.", () -> recover(identity, label));
            return;
        }
        try {
            if (observedRun != null) journal.observe(observedRun);
            account.write("last-thread", id);
        }
        catch (RuntimeException unavailable) {
            inbox("Impossible d’enregistrer la sélection. Vérifiez l’espace disponible puis réessayez.", null);
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
        refreshPhoto();
        nearby.restore();
        attachLiveUiControls();
        if (instrumentHost != null) {
            instruments = new ThreadInstrumentPanel(this, new ThreadInstruments(account, id), instrumentHost);
            screen.instruments = instruments;
        }
        screen.slots(slotOrder);
        container.select(screen.root);
        indexNotice();
        selectionChanged();
        loadMessages(false, null);
        run.restore();
    }

    private void recover(ThreadIdentity identity, String label) {
        screen.show(new AlertDialog.Builder(activity).setTitle("Récupérer la conversation ?")
                .setMessage("Le brouillon et le suivi des envois enregistrés sur ce téléphone seront effacés. "
                        + "L’historique de la passerelle sera rechargé. Un envoi antérieur peut encore être en cours.")
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Effacer les données locales", (dialog, which) -> {
                    try {
                        java.util.Map<String, String> records = new java.util.HashMap<>();
                        records.put("thread:" + identity.root, null);
                        records.put("thread-photo:" + identity.root, null);
                        account.writeTogether(records);
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
        input.setSingleLine();
        input.setSaveEnabled(false);
        input.setText(title);
        input.selectAll();
        screen.show(new AlertDialog.Builder(activity).setTitle("Renommer la conversation").setView(input)
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Renommer", (d, w) -> {
                    if (closed || !id.equals(session)) return;
                    String value = input.getText().toString().trim();
                    if (value.isEmpty()) { screen.notice("Le titre est vide.", "Renommer", this::rename); return; }
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
        request(() -> ThreadWindow.read(api::get, query, offset, retained, priorTotal, oldest,
                () -> owner == generation && requestNo == transcriptRequest), window -> {
            if (requestNo != transcriptRequest) return;
            loading = false;
            JSONObject data = window.metadata();
            JSONArray page = window.rows();
            String tip = ThreadData.string(data, "session_id");
            if (ThreadData.validId(tip)) remoteSession = tip;
            int oldSize = messages.length();
            messages = ThreadData.visibleMessages(ThreadData.join(page, older ? messages : new JSONArray()));
            // Advance by the rows this response actually returned. The scroll anchor counts displayed rows.
            messageOffset = offset + window.rawCount();
            long total = ThreadContext.visibleTotal(data);
            boolean hasMore = window.more();
            emptyTranscript = messages.length() == 0 && !hasMore;
            if (instruments != null) instruments.proposals(messages);
            int top = screen.list.getFirstVisiblePosition();
            Object anchor = retained == 0 ? null : screen.list.getItemAtPosition(top);
            int y = screen.list.getChildCount() == 0 ? 0 : screen.list.getChildAt(0).getTop();
            screen.rows(messages, hasMore, !older && retained == 0 && !emptyTranscript,
                    older ? messages.length() - oldSize : 0);
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
            messageCount = ThreadContext.messageCount(total, page.length(), messages.length());
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
                + ThreadData.PAGE + "&include_compacted=true&visible_only=true"), data -> {
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
        nearby.clearTurn();
        voice.reader().stop();
        deviceBridge.dismissDialogs();
        if (!closed) {
            ++generation;
            main.removeCallbacksAndMessages(null);
            if (screen != null) screen.dismiss();
            if (instruments != null) { instruments.retire(); instruments = null; }
            try {
                if (run != null) run.detach();
                if (journal != null) journal.flush();
            } catch (RuntimeException unavailable) {
                storageError();
            }
        }
    }

    private void reset(ThreadViews next) {
        onBackground();
        if (journal != null) journal.retire();
        importingPhoto = false;
        loading = creating = inboxLoading = false;
        run = null;
        journal = null;
        messages = new JSONArray();
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

    @Override public void close() {
        if (closed) return;
        try { onBackground(); }
        finally {
            closed = true;
            if (journal != null) journal.retire();
            workers.shutdownNow();
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
            inbox = new JSONArray();
            inboxLoaded = inboxLoading = inboxFailed = inboxMore = false;
            inboxRecovery = null;
            openingPrompts.clear();
            namedTitle = firstPrompt = "";
            pendingLiveUiPatch = null;
            appliedLiveUiRevisionId = null;
        }
    }

    <T> void request(Callable<T> work, Consumer<T> success, Consumer<Exception> failure) {
        int generationAtStart = generation;
        workers.execute(() -> {
            try {
                T data = work.call();
                deliver(generationAtStart, () -> success.accept(data));
            } catch (Exception e) {
                deliver(generationAtStart, () -> failure.accept(e));
            }
        });
    }

    public BooleanSupplier discussionOrigin() {
        int token = generation;
        return () -> !closed && generation == token;
    }

    int token() { return generation; }

    void deliver(int token, Runnable update) {
        main.post(() -> {
            if (!closed && token == generation) {
                try { update.run(); }
                catch (IllegalStateException e) { storageError(); }
            }
        });
    }

    void invalidReply(Runnable retry) {
        screen.notice("La réponse du serveur est incomplète.", "Réessayer", retry);
    }

    void error(Exception error, Runnable retry) {
        screen.notice(errorMessage(error), "Réessayer", retry);
    }

    private static String errorMessage(Exception error) {
        String message = "La connexion à Hermes a échoué.";
        if (error instanceof HermesApi.HttpError http) {
            message = switch (http.status) {
                case 401, 403 -> "Accès refusé. Vérifiez la clé de ce profil.";
                case 404 -> "Cet élément est introuvable sur le serveur.";
                case 409 -> "Le serveur signale un conflit.";
                case 429 -> "Le serveur reçoit trop de demandes. Réessayez plus tard.";
                default -> "Le serveur a refusé la demande (HTTP " + http.status + ").";
            };
        }
        return message;
    }

    String failureDetail(JSONObject status) { return ThreadData.failureDetail(status, account.bearer); }

    void clearSuggestedActions() {
        if (closed || screen == null) return;
        screen.suggestedActions(Collections.emptyList(), null, null, null, null, null);
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
            screen.liveUiBar.present(patch);
            if (!patch.suggestedActions.isEmpty()) {
                ThemePalette palette = ThemePalette.active(activity, account);
                screen.suggestedActions(patch.suggestedActions, palette,
                        this::stageDraft,
                        page -> { if (instrumentHost != null) instrumentHost.openWorkspace(page); },
                        action -> { if (instrumentHost != null) instrumentHost.onAction(action); },
                        this::pinWidgetById);
            }
            Diagnostics.event("live_ui", "patch_presented");
        } catch (java.io.IOException rejected) {
            Diagnostics.warning("live_ui", "patch_rejected");
        }
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
                    if (screen != null) screen.notice("Impossible d’appliquer cette modification d’écran.", "", null);
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
                    if (screen != null) screen.notice("Impossible d’annuler cette modification.", "", null);
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

    void storageError() {
        Diagnostics.event("threads", "storage_failed");
        if (screen != null) screen.notice("Enregistrement local impossible. Vérifiez l’espace disponible.", "", null);
    }
}
