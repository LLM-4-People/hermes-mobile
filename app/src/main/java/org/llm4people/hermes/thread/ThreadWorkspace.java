package org.llm4people.hermes.thread;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.profile.Account;
import org.llm4people.hermes.voice.VoiceDrafts;

import java.util.UUID;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Profile-scoped native workspace. Call its public methods on the Android main thread. */
public final class ThreadWorkspace implements AutoCloseable {
    public interface Host {
        void selected(String sessionId, String title);
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
    private final FrameLayout container;
    ThreadViews screen;
    ThreadJournal journal;
    private ThreadRun run;
    private ThreadStarters starters;
    private boolean emptyTranscript;
    private JSONArray messages = new JSONArray();
    private JSONArray inbox = new JSONArray();
    private String session;
    private String remoteSession;
    private String title;
    private long messageCount = -1;
    private List<String> slotOrder = Collections.emptyList();
    private volatile int generation;
    private int inboxOffset;
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
        container = new FrameLayout(activity);
    }

    public View view() { return container; }
    public void dismissDialogs() { if (screen != null) screen.dismiss(); }
    public String sessionId() { return session; }
    public String remoteSessionId() { return remoteSession; }
    public String title() { return session == null ? "" : title; }
    public String messageCount() { return session == null || messageCount < 0 ? "" : Long.toString(messageCount); }

    public void slots(List<String> order) {
        slotOrder = new ArrayList<>(order);
        if (screen != null) screen.slots(slotOrder);
        updateStarters();
    }

    public boolean editing() { return screen != null && screen.editing(); }
    public void focusComposer() { if (screen != null) screen.focusComposer(); }

    public void stageDraft(String text) {
        if (journal == null || session == null || text == null || text.isEmpty()) return;
        String current = journal.draft();
        screen.displayDraft(current.isEmpty() ? text : current + "\n" + text);
        queueSave();
        screen.focusComposer();
    }

    private boolean canStartDraft() {
        return journal != null && ThreadStarters.available(emptyTranscript, journal.draft(), journal.pending() != null, slotOrder);
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
        reset();
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
        screen.inbox(this::newThread, row -> {
            try { open(ThreadIdentity.of(row), ThreadData.title(row)); }
            catch (IllegalArgumentException invalid) { invalidReply(this::inbox); }
        }, this::moreThreads);
        host.selected(null, title);
        moreThreads();
    }

    private void moreThreads() {
        if (loading) return;
        loading = true;
        screen.paging();
        screen.notice("Chargement des conversations…", "", null);
        int offset = inboxOffset;
        request(() -> api.get("/api/sessions?source=api_server&limit=" + ThreadData.PAGE + "&offset=" + offset),
                data -> {
                    loading = false;
                    JSONArray page = data.optJSONArray("data");
                    if (page == null) { invalidReply(this::moreThreads); return; }
                    inbox = ThreadData.join(inbox, page);
                    inboxOffset += ThreadData.PAGE;
                    screen.rows(inbox, data.optBoolean("has_more"), false, 0);
                    if (inboxWarning.isEmpty()) {
                        screen.notice(inbox.length() == 0 ? "Aucune conversation pour ce profil." : "", "", null);
                    } else {
                        screen.notice(inboxWarning, "Récupérer la conversation…", inboxRecovery);
                    }
                }, e -> {
                    loading = false;
                    error(e, this::moreThreads);
                });
    }

    public void newThread() { newThread(null); }

    public void newThread(Runnable afterOpen) {
        if (closed || creating) return;
        create(UUID.randomUUID().toString(), afterOpen);
    }

    private void create(String id, Runnable afterOpen) {
        if (creating) return;
        creating = true;
        screen.notice("Création de la conversation…", "", null);
        request(() -> {
            try { return api.post("/api/sessions", ThreadData.put(new JSONObject(), "id", id)); }
            catch (HermesApi.HttpError e) {
                if (e.status != 409 || !"session_exists".equals(e.code)) throw e;
                return api.get(ThreadData.path(id));
            }
        }, response -> {
            creating = false;
            JSONObject row = response.optJSONObject("session");
            if (row == null || !id.equals(row.optString("id"))) { invalidReply(() -> create(id, afterOpen)); return; }
            open(new ThreadIdentity(id, id), ThreadData.title(row));
            if (afterOpen != null && id.equals(session)) afterOpen.run();
        }, e -> {
            creating = false;
            error(e, () -> create(id, afterOpen));
        });
    }

    public void open(String id) {
        if (closed) return;
        if (!ThreadData.validId(id)) {
            inbox("L’identifiant de cette conversation est invalide. Choisissez une autre conversation.", null);
            return;
        }
        ThreadIdentity listed = ThreadIdentity.find(inbox, id);
        if (listed != null) { open(listed, listed.label); return; }
        inbox("Recherche de la conversation…", null);
        int lookup = generation;
        request(() -> ThreadIdentity.resolve(api, id, () -> lookup == generation), identity -> {
            if (identity != null) open(identity, identity.label);
            else inbox("Cette conversation est introuvable dans le profil actif.", null);
        }, e -> error(e, () -> open(id)));
    }

    private void open(ThreadIdentity identity, String label) {
        if (closed) return;
        reset();
        session = identity.root;
        remoteSession = identity.tip;
        String id = session;
        title = label;
        try { journal = new ThreadJournal(account, id); }
        catch (RuntimeException unreadable) {
            Diagnostics.warning("threads", "journal_unreadable");
            inbox("Les données locales de cette conversation sont illisibles.", () -> recover(identity, label));
            return;
        }
        try { account.write("last-thread", id); }
        catch (RuntimeException unavailable) {
            inbox("Impossible d’enregistrer la sélection. Vérifiez l’espace disponible puis réessayez.", null);
            return;
        }
        run = new ThreadRun(this);
        screen.chat(journal.draft(), text -> {
            journal.draft(text);
            updateStarters();
            main.removeCallbacks(save);
            main.postDelayed(save, 300);
        }, run::send, run::stop, () -> loadMessages(true, null));
        screen.slots(slotOrder);
        host.selected(id, title);
        loadMessages(false, null);
        run.restore();
    }

    private void recover(ThreadIdentity identity, String label) {
        screen.show(new AlertDialog.Builder(activity).setTitle("Récupérer la conversation ?")
                .setMessage("Le brouillon et le suivi des envois enregistrés sur ce téléphone seront effacés. "
                        + "L’historique de la passerelle sera rechargé. Un envoi antérieur peut encore être en cours.")
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Effacer les données locales", (dialog, which) -> {
                    try { account.remove("thread:" + identity.root); }
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
                            r -> { metadataRequest++; title = value; host.selected(id, value); }, e -> error(e, this::rename));
                }));
    }

    void loadMessages(boolean older, Runnable loaded) {
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
        request(() -> api.get(path + "/messages?order=latest&visible_only=true&limit=" + ThreadData.PAGE + "&offset=" + offset), data -> {
            if (requestNo != transcriptRequest) return;
            loading = false;
            JSONArray page = data.optJSONArray("data");
            if (page == null) { invalidReply(() -> loadMessages(older, loaded)); return; }
            String tip = ThreadData.string(data, "session_id");
            if (ThreadData.validId(tip)) remoteSession = tip;
            int oldSize = messages.length();
            messages = ThreadData.visibleMessages(ThreadData.join(page, older ? messages : new JSONArray()));
            // Advance by the rows this response actually returned. The scroll anchor counts displayed rows.
            messageOffset = offset + page.length();
            long total = ThreadContext.visibleTotal(data);
            boolean hasMore = total < 0 ? page.length() == ThreadData.PAGE : messageOffset < total;
            emptyTranscript = messages.length() == 0 && !hasMore;
            screen.rows(messages, hasMore, !older && !emptyTranscript,
                    older ? messages.length() - oldSize : 0);
            if (journal.pending() == null) screen.notice("", "", null);
            long previousCount = messageCount;
            messageCount = ThreadContext.messageCount(total, page.length(), messages.length());
            boolean receivedTitle = data.has("thread_title");
            if (receivedTitle) legacyTitleTip = null;
            String nextTitle = titleVersion == metadataRequest
                    ? ThreadContext.title(data, "thread_title", title) : title;
            if (previousCount != messageCount || !title.equals(nextTitle)) {
                title = nextTitle;
                host.selected(session, title);
            }
            if (!older && !receivedTitle && titleVersion == metadataRequest) readLegacyTitle();
            if (loaded != null) loaded.run();
            updateStarters();
        }, e -> {
            if (requestNo != transcriptRequest) return;
            loading = false;
            error(e, () -> loadMessages(older, loaded));
        });
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
            if (version == metadataRequest && tip.equals(legacyTitleTip) && !label.isEmpty() && !label.equals(title)) {
                title = label;
                host.selected(session, title);
            }
        }, failure -> {
            if (tip.equals(legacyTitleTip)) legacyTitleTip = null;
            Diagnostics.event("threads", "metadata_unavailable");
        });
    }

    private String legacyTitleTip;
    private int metadataRequest;
    private int transcriptRequest;

    public void onBackground() {
        if (!closed) {
            ++generation;
            main.removeCallbacksAndMessages(null);
            if (screen != null) screen.dismiss();
            try {
                if (run != null) run.detach();
                if (journal != null) journal.flush();
            } catch (RuntimeException unavailable) {
                storageError();
            }
        }
    }

    private void reset() {
        onBackground();
        if (journal != null) journal.retire();
        loading = creating = false;
        run = null;
        journal = null;
        messages = new JSONArray();
        messageOffset = 0;
        messageCount = -1;
        emptyTranscript = false;
        starters = null;
        legacyTitleTip = null;
        screen = new ThreadViews(activity, voice);
        container.removeAllViews();
        container.addView(screen.root);
    }

    @Override public void close() {
        if (closed) return;
        try { onBackground(); }
        finally {
            closed = true;
            if (journal != null) journal.retire();
            workers.shutdownNow();
            drafts.shutdownNow();
            container.removeAllViews();
            screen = null;
            starters = null;
            run = null;
            journal = null;
            session = remoteSession = null;
            messages = new JSONArray();
            inbox = new JSONArray();
            inboxRecovery = null;
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
        screen.notice(message, "Réessayer", retry);
    }

    String failureDetail(JSONObject status) { return ThreadData.failureDetail(status, account.bearer); }

    void storageError() {
        Diagnostics.event("threads", "storage_failed");
        if (screen != null) screen.notice("Enregistrement local impossible. Vérifiez l’espace disponible.", "", null);
    }
}
