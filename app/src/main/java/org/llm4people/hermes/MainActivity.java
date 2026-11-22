package org.llm4people.hermes;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;

import org.json.JSONException;
import org.json.JSONObject;
import org.llm4people.hermes.artifact.ArtifactDesk;
import org.llm4people.hermes.files.PublishedFiles;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.panel.SessionPanel;
import org.llm4people.hermes.profile.Account;
import org.llm4people.hermes.profile.ProfileGate;
import org.llm4people.hermes.push.MobilePush;
import org.llm4people.hermes.thread.ThreadWorkspace;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns the unlocked account and the lifetime of every screen using its credentials. */
public final class MainActivity extends Activity implements ThreadWorkspace.Host, ArtifactDesk.Host, MobilePush.EventHost {
    private final ExecutorService notifications = Executors.newSingleThreadExecutor();
    private ProfileGate gate;
    private PublishedFiles files;
    private Account account;
    private SessionPanel panels;
    private ThreadWorkspace threads;
    private WorkspaceShell shell;
    private MobilePush push;
    private MobilePush.Target delivery;
    private AlertDialog notice;
    private String displayedSurface;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        // Saved view hierarchies may contain composer or password text. Only Account storage
        // can restore private state; an OS recreation always comes back through the gate.
        View content = findViewById(android.R.id.content);
        content.setSaveFromParentEnabled(false);
        fitSystemBars(content);
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::back);
        }
        push = new MobilePush(this);
        files = new PublishedFiles(this);
        gate = new ProfileGate(this);
        gate.setOnForget(id -> MobilePush.forget(this, id));
        delivery = MobilePush.target(this, getIntent());
        unlock();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        // The activity has no public view state. Discard framework dialog and view text too.
        outState.clear();
    }

    private void unlock() {
        gate.start(delivery == null ? null : delivery.profileId, this::opened);
    }

    private void opened(Account unlocked) {
        account = unlocked;
        HermesApi api = new HermesApi(account.endpoint, account.bearer);
        shell = new WorkspaceShell(this, this::onAction);
        panels = new SessionPanel(this, api, account);
        panels.setLayoutHost(this);
        threads = new ThreadWorkspace(this, api, account, this);
        setContentView(shell.root);
        displayDiscussion();
        files.unlock(account, api);
        try {
            threads.resumeLast();
        } catch (IllegalStateException invalidSavedSession) {
            Diagnostics.warning("threads", "resume_failed");
            threads.inbox();
        }
        MobilePush.Target target = delivery;
        delivery = null;
        if (target != null && unlocked.id.equals(target.profileId)) resolve(unlocked, target);
    }

    private void resolve(Account owner, MobilePush.Target target) {
        notifications.execute(() -> {
            try {
                JSONObject event = MobilePush.resolve(owner, target);
                runOnUiThread(() -> openPushEvent(owner, event));
            } catch (IOException | RuntimeException failure) {
                Diagnostics.warning("push", "open_failed");
                runOnUiThread(() -> {
                    if (owner == account && !isFinishing()) {
                        message("Notification indisponible",
                                "Vous pouvez retrouver le travail dans vos discussions ou vos rappels.");
                    }
                });
            }
        });
    }

    @Override public void openPushEvent(Account owner, JSONObject event) {
        if (owner != account || isFinishing()) return;
        dismissOverlays();
        if ("reminder".equals(event.optString("kind"))) {
            if (!event.isNull("job_id") && !event.optString("job_id").isEmpty())
                panels.showReminder(event.optString("job_id"));
            else panels.showReminders();
        } else if (!event.isNull("session_id") && !event.optString("session_id").isEmpty()) {
            displayDiscussion();
            threads.open(event.optString("session_id"));
        }
    }

    private void dismissOverlays() {
        ArtifactDesk.dismiss(this);
        if (panels != null) panels.close();
        if (push != null) push.lock();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        MobilePush.Target incoming = MobilePush.target(this, intent);
        if (incoming == null) return;
        delivery = incoming;
        if (account != null && account.id.equals(incoming.profileId)) {
            delivery = null;
            resolve(account, incoming);
        } else {
            lock();
            unlock();
        }
    }

    @Override public void onAction(String id) {
        if (threads == null) return;
        if (!"restore".equals(id)) dismissOverlays();
        switch (id) {
            case "return": displayDiscussion(); break;
            case "threads": displayDiscussion(); threads.inbox(); break;
            case "new_thread": displayDiscussion(); threads.newThread(); break;
            case "rename": threads.rename(); break;
            case "published_files":
                if (threads.remoteSessionId() != null) files.show(threads.remoteSessionId());
                break;
            case "workspace": showDesk(); break;
            case "reminders": panels.showReminders(); break;
            case "notifications": push.settings(account); break;
            case "restore": ArtifactDesk.manage(this, account, this); break;
            case "profiles": lock(); break;
            case "stats": case "tools": case "models": case "artifacts":
                String session = threads.sessionId();
                if (session == null) {
                    message("Choisir une discussion", "Ouvrez une discussion pour utiliser cette action.");
                } else if ("stats".equals(id)) {
                    panels.showStats(session, threads.remoteSessionId());
                } else if ("artifacts".equals(id)) {
                    new ArtifactDesk(this, account, session, threads.messages(), this).show();
                } else {
                    panels.show(session, threads.remoteSessionId());
                }
                break;
            default: Diagnostics.warning("workspace", "unknown_action");
        }
    }

    private void message(String title, String body) {
        if (notice != null) notice.dismiss();
        notice = Ui.secureDialog(new AlertDialog.Builder(this).setTitle(title).setMessage(body)
                .setPositiveButton("Fermer", null));
    }

    @Override public void onLayoutChanged() {
        if (shell == null) return;
        panels.refreshLayout();
        if (shell.inDesk()) showDesk(); else {
            displayedSurface = null;
            displayDiscussion();
        }
    }

    private void displayDiscussion() {
        // Session panels and artifact sheets apply their own surface layouts.
        String surface = threads.sessionId() == null ? "threads" : "thread";
        if (surface.equals(displayedSurface) && !shell.inDesk()) return;
        displayedSurface = surface;
        View view = threads.view();
        if (view.getParent() instanceof android.view.ViewGroup)
            ((android.view.ViewGroup) view.getParent()).removeView(view);
        shell.show(ArtifactDesk.decorate(this, account, surface, view, this), false);
    }

    private void showDesk() {
        shell.show(ArtifactDesk.decorate(this, account, "home", shell.desk(account.label), this), true);
    }

    @Override public void selected(String sessionId, String title) {
        if (shell == null) return;
        String threadTitle = sessionId == null ? null : title == null || title.trim().isEmpty() ? "Discussion" : title;
        shell.selected(threadTitle);
        if (threads != null && !shell.inDesk()) displayDiscussion();
    }

    @Override public JSONObject options(String sessionId) {
        JSONObject options = SessionPanel.runOptions(this, account, sessionId);
        try {
            options.put("instructions", ArtifactDesk.proposalInstructions());
        } catch (JSONException invalid) {
            throw new IllegalStateException(invalid);
        }
        return options;
    }

    @Override public void timing(String sessionId, long firstTextMs, long elapsedMs, long outputTokens) {
        if (account != null) SessionPanel.recordTiming(this, account, sessionId, firstTextMs, elapsedMs, outputTokens);
    }

    private void back() {
        if (threads == null) {
            if (!gate.onBack()) finish();
        } else if (shell.inDesk()) {
            displayDiscussion();
        } else if (!threads.back()) {
            lock();
        }
    }

    // API 33+ invokes the registered platform callback. Older devices route Back here.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        back();
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        if (files != null && files.result(code, result, data)) return;
        super.onActivityResult(code, result, data);
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        push.onPermissionResult(code);
    }

    private void clearWorkspace() {
        if (notice != null) notice.dismiss();
        dismissOverlays();
        if (files != null) files.lock();
        if (threads != null) {
            try {
                threads.close();
            } catch (IllegalStateException storageFailure) {
                Diagnostics.error("threads", "lock_checkpoint_failed");
            }
        }
        notice = null;
        panels = null;
        threads = null;
        shell = null;
        account = null;
        displayedSurface = null;
    }

    private void lock() {
        try {
            clearWorkspace();
        } finally {
            gate.lock();
        }
    }

    @Override protected void onStop() {
        super.onStop();
        if (gate != null) lock();
    }

    @Override protected void onDestroy() {
        clearWorkspace();
        if (files != null) files.close();
        notifications.shutdownNow();
        super.onDestroy();
    }

    private void fitSystemBars(View content) {
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        content.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                v.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
    }
}
