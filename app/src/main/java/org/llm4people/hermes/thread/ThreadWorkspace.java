package org.llm4people.hermes.thread;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.profile.Account;

import java.util.UUID;
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
    private final FrameLayout container;
    ThreadViews screen;
    ThreadJournal journal;
    private ThreadRun run;
    private JSONArray messages = new JSONArray();
    private JSONArray inbox = new JSONArray();
    private String session;
    private String title;
    private int generation;
    private int inboxOffset;
    private int messageOffset;
    private boolean closed;
    private boolean creating;
    private boolean loading;
    private final Runnable save = () -> {
        if (journal != null) {
            try { journal.flush(); }
            catch (IllegalStateException e) { storageError(); }
        }
    };

    public ThreadWorkspace(Activity activity, HermesApi api, Account account, Host host) {
        this.activity = activity;
        this.api = api;
        this.account = account;
        this.host = host;
        container = new FrameLayout(activity);
    }

    public View view() { return container; }
    public String sessionId() { return session; }
    public JSONArray messages() { return messages; }
    public void resumeLast() {
        String last = account.read("last-thread");
        if (last == null || last.isEmpty()) inbox();
        else open(last);
    }

    public boolean back() {
        if (session == null) return false;
        inbox();
        return true;
    }

    public void inbox() {
        if (closed) return;
        reset();
        session = null;
        title = "Conversations";
        account.remove("last-thread");
        inboxOffset = 0;
        inbox = new JSONArray();
        screen.inbox(this::newThread, row -> open(row.optString("id"), ThreadData.title(row)), this::moreThreads);
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
                    screen.notice(inbox.length() == 0 ? "Aucune conversation pour ce profil." : "", "", null);
                }, e -> {
                    loading = false;
                    error(e, this::moreThreads);
                });
    }

    public void newThread() {
        if (closed || creating) return;
        create(UUID.randomUUID().toString());
    }

    private void create(String id) {
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
            if (row == null || !id.equals(row.optString("id"))) { invalidReply(() -> create(id)); return; }
            open(id, ThreadData.title(row));
        }, e -> {
            creating = false;
            error(e, () -> create(id));
        });
    }

    public void open(String id) { open(id, "Conversation"); }

    private void open(String id, String label) {
        if (closed) return;
        if (!ThreadData.validId(id)) {
            inbox();
            screen.notice("L’identifiant de cette conversation est invalide.", "", null);
            return;
        }
        reset();
        session = id;
        title = label;
        journal = new ThreadJournal(account, id);
        account.write("last-thread", id);
        run = new ThreadRun(this);
        screen.chat(journal.draft(), text -> {
            journal.draft(text);
            main.removeCallbacks(save);
            main.postDelayed(save, 300);
        }, run::send, run::stop, run::approve, () -> loadMessages(true, null));
        host.selected(id, title);
        int opened = generation;
        request(() -> api.get(ThreadData.path(id)), response -> {
            JSONObject row = response.optJSONObject("session");
            if (row != null && opened == generation) {
                title = ThreadData.title(row);
                host.selected(session, title);
            }
        }, e -> Diagnostics.event("threads", "title_unavailable"));
        loadMessages(false, null);
        run.restore();
    }

    public void rename() {
        if (closed || session == null) return;
        String id = session;
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
                    request(() -> api.patch(ThreadData.path(id), ThreadData.put(new JSONObject(), "title", value)),
                            r -> { title = value; host.selected(id, value); }, e -> error(e, this::rename));
                }));
    }

    void loadMessages(boolean older, Runnable loaded) {
        if (older && loading) return;
        loading = true;
        screen.paging();
        int offset = older ? messageOffset : 0;
        String path = ThreadData.path(session);
        int requestNo = ++transcriptRequest;
        request(() -> api.get(path + "/messages?order=latest&limit=" + ThreadData.PAGE + "&offset=" + offset), data -> {
            if (requestNo != transcriptRequest) return;
            loading = false;
            JSONArray page = data.optJSONArray("data");
            if (page == null) { invalidReply(() -> loadMessages(older, loaded)); return; }
            int oldSize = messages.length();
            messages = ThreadData.join(page, older ? messages : new JSONArray());
            messageOffset = offset + page.length();
            screen.rows(messages, page.length() == ThreadData.PAGE, !older, older ? messages.length() - oldSize : 0);
            if (journal.pending() == null) {
                screen.notice(messages.length() == 0 ? "Écrivez le premier message." : "", "", null);
            }
            if (loaded != null) loaded.run();
        }, e -> {
            if (requestNo != transcriptRequest) return;
            loading = false;
            error(e, () -> loadMessages(older, loaded));
        });
    }
    private int transcriptRequest;

    public void onBackground() {
        if (!closed) {
            ++generation;
            main.removeCallbacksAndMessages(null);
            if (screen != null) screen.dismiss();
            if (run != null) run.detach();
            save.run();
        }
    }

    private void reset() {
        onBackground();
        loading = creating = false;
        run = null;
        journal = null;
        messages = new JSONArray();
        messageOffset = 0;
        screen = new ThreadViews(activity);
        container.removeAllViews();
        container.addView(screen.root);
    }

    @Override public void close() {
        if (closed) return;
        onBackground();
        closed = true;
        workers.shutdownNow();
        container.removeAllViews();
        screen = null;
        run = null;
        journal = null;
        messages = new JSONArray();
        inbox = new JSONArray();
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

    void storageError() {
        Diagnostics.event("threads", "storage_failed");
        if (screen != null) screen.notice("Enregistrement local impossible. Vérifiez l’espace disponible.", "", null);
    }
}
