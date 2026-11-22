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
import org.llm4people.hermes.share.SharedTextFlow;
import org.llm4people.hermes.thread.ThreadPhotoPicker;
import org.llm4people.hermes.voice.VoiceDrafts;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns the unlocked account and the lifetime of every screen using its credentials. */
public final class MainActivity extends Activity implements ThreadWorkspace.Host, ArtifactDesk.Host, MobilePush.EventHost {
    private final ExecutorService notifications = Executors.newSingleThreadExecutor();
    private ProfileGate gate;
    private VoiceDrafts voice;
    private PublishedFiles files;
    private ThreadPhotoPicker photoPicker;
    private Account account;
    private SessionPanel panels;
    private ThreadWorkspace threads;
    private WorkspaceShell shell;
    private WorkspaceNavigation navigation;
    private ArtifactDesk.Host activeHost;
    private final Map<String, View> customPages = new HashMap<>();
    private SharedTextFlow sharedText;
    private MobilePush push;
    private MobilePush.Target delivery;
    private AlertDialog notice;
    private String handledDelivery;
    private long noticeRequest;

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
        voice = new VoiceDrafts(this);
        push = new MobilePush(this);
        files = new PublishedFiles(this);
        photoPicker = new ThreadPhotoPicker(this, state);
        sharedText = new SharedTextFlow(this, () -> { displayDiscussion(); threads.inbox(); },
                () -> navigate(WorkspaceNavigation.COMPOSER));
        sharedText.restore(state, getIntent());
        gate = new ProfileGate(this);
        gate.setOnForget(id -> MobilePush.forget(this, id));
        handledDelivery = state == null ? null : state.getString("handled_delivery");
        delivery = MobilePush.target(this, getIntent());
        if (delivery != null && key(delivery).equals(handledDelivery)) delivery = null;
        unlock();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        // The activity has no public view state. Discard framework dialog and view text too.
        outState.clear();
        sharedText.save(outState);
        photoPicker.save(outState);
        if (handledDelivery != null) outState.putString("handled_delivery", handledDelivery);
    }

    private static String key(MobilePush.Target target) {
        return target.profileId + "/" + target.eventId;
    }

    private void handled(MobilePush.Target target) {
        handledDelivery = key(target);
        setIntent(new Intent(this, MainActivity.class));
        delivery = null;
    }

    private void unlock() {
        gate.start(delivery == null ? sharedText.requestedProfile() : delivery.profileId, this::opened);
    }

    private void opened(Account unlocked) {
        account = unlocked;
        photoPicker.unlocked(unlocked);
        HermesApi api = new HermesApi(account.endpoint, account.bearer);
        navigation = new WorkspaceNavigation();
        activeHost = new WorkspaceHost(this, () -> account == unlocked);
        shell = new WorkspaceShell(this, this::onAction, voice.reader()::active);
        panels = new SessionPanel(this, api, account);
        panels.setLayoutHost(activeHost);
        threads = new ThreadWorkspace(this, api, account, this, voice);
        threads.setInstrumentHost(activeHost);
        setContentView(shell.root);
        displayDiscussion();
        files.unlock(account, api);
        try {
            threads.resumeLast();
        } catch (IllegalStateException invalidSavedSession) {
            Diagnostics.warning("threads", "resume_failed");
            threads.inbox();
        }
        sharedText.opened(unlocked, threads);
        MobilePush.Target target = delivery;
        if (target != null) {
            handled(target);
            if (unlocked.id.equals(target.profileId)) resolve(unlocked, target);
        }
    }

    private void resolve(Account owner, MobilePush.Target target) {
        long request = ++noticeRequest;
        threads.cancelNotice();
        notifications.execute(() -> {
            try {
                JSONObject event = MobilePush.resolve(owner, target);
                runOnUiThread(() -> { if (noticeRequest == request) openPushEvent(owner, event); });
            } catch (IOException | RuntimeException failure) {
                Diagnostics.warning("push", "open_failed");
                runOnUiThread(() -> {
                    if (owner == account && request == noticeRequest && !isFinishing()) {
                        message("Notification indisponible",
                                "Vous pouvez retrouver le travail dans vos discussions ou vos rappels.");
                    }
                });
            }
        });
    }

    @Override public void openPushEvent(Account owner, JSONObject event) {
        if (owner != account || isFinishing()) return;
        ++noticeRequest;
        if ("reminder".equals(event.optString("kind"))) {
            threads.cancelNotice();
            dismissOverlays();
            if (!event.isNull("job_id") && !event.optString("job_id").isEmpty())
                panels.showReminder(event.optString("job_id"));
            else panels.showReminders();
        } else if (!event.isNull("session_id") && !event.optString("session_id").isEmpty()) {
            threads.openNotice(event.optString("session_id"), event.optString("run_id"), event.optString("kind"));
        }
    }

    @Override public void noticeThread(boolean sameThread) {
        boolean onThread = WorkspaceNavigation.DISCUSSION.equals(navigation.current())
                || WorkspaceNavigation.COMPOSER.equals(navigation.current());
        dismissOverlays(sameThread && onThread);
        if (!sameThread || !onThread) displayDiscussion();
    }

    private void dismissOverlays() { dismissOverlays(false); }

    private void dismissOverlays(boolean keepThreadEditor) {
        if (notice != null) notice.dismiss();
        notice = null;
        if (files != null) files.dismiss();
        if (!keepThreadEditor) {
            if (threads != null) threads.dismissDialogs();
            ArtifactDesk.dismiss(this);
        }
        if (panels != null) panels.close();
        if (push != null) push.lock();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (sharedText.receive(intent)) {
            setIntent(new Intent(this, MainActivity.class));
            delivery = null;
            clearWorkspace();
            unlock();
            return;
        }
        MobilePush.Target incoming = MobilePush.target(this, intent);
        if (incoming == null) return;
        sharedText.leave();
        setIntent(intent);
        delivery = incoming;
        if (account != null && account.id.equals(incoming.profileId)) {
            handled(incoming);
            resolve(account, incoming);
        } else {
            lock();
            unlock();
        }
    }

    @Override public void onAction(String id) {
        if (threads == null) return;
        if ("read_reply".equals(id)) {
            threads.readLastReply();
            return;
        }
        voice.reader().stop();
        if ("stop_reading".equals(id)) return;
        if (!"restore".equals(id)) dismissOverlays();
        switch (id) {
            case "previous": back(); break;
            case "return": displayDiscussion(); break;
            case "pages": navigate(WorkspaceNavigation.GALLERY); break;
            case "attach_photo": photoPicker.launch(account, threads); break;
            case "native_thread": navigate(WorkspaceNavigation.COMPOSER); break;
            case "threads": displayDiscussion(); threads.inbox(); break;
            case "new_thread": displayDiscussion(); threads.newThread(); break;
            case "create_instrument":
                if (threads.sessionId() != null) stageDraft(getString(R.string.instrument_create_request));
                break;
            case "rename": threads.rename(); break;
            case "published_files":
                if (threads.remoteSessionId() != null) files.show(threads.remoteSessionId());
                break;
            case "workspace": navigate(WorkspaceNavigation.HOME); break;
            case "reminders": panels.showReminders(); break;
            case "notifications": push.settings(account); break;
            case "restore": ArtifactDesk.manage(this, account, activeHost); break;
            case "profiles": lock(); break;
            case "stats": case "tools": case "models": case "artifacts":
                String session = threads.sessionId();
                if (session == null) {
                    message("Choisir une discussion", "Ouvrez une discussion pour utiliser cette action.");
                } else if ("stats".equals(id)) {
                    panels.showStats(session, threads.remoteSessionId());
                } else if ("artifacts".equals(id)) {
                    new ArtifactDesk(this, account, session, threads.messages(), activeHost).show();
                } else {
                    ThreadWorkspace origin = threads;
                    panels.show(session, origin.remoteSessionId(),
                            () -> threads == origin && session.equals(origin.sessionId()), this::stageDraft);
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
        customPages.clear();
        render(true);
    }

    private void displayDiscussion() { navigate(WorkspaceNavigation.DISCUSSION); }

    private void navigate(String destination) {
        navigation.open(destination);
        render(false);
    }

    @Override public void openWorkspace(String pageId) {
        if (account == null || pageId == null || !pageId.startsWith("page/")) return;
        dismissOverlays();
        navigate(pageId);
    }

    @Override public void threadSlots(List<String> order) {
        if (threads != null) threads.slots(order);
    }

    @Override public BooleanSupplier deviceActionOrigin() {
        if (threads == null || account == null) return () -> false;
        String route = navigation.current();
        BooleanSupplier origin = threads.discussionOrigin();
        return () -> origin.getAsBoolean() && navigation != null && route.equals(navigation.current());
    }

    @Override public String workspaceValue(String source) {
        if (account == null || threads == null) return "";
        return switch (source) {
            case "profile.label" -> account.label;
            case "thread.title" -> threads.title();
            case "thread.messageCount" -> threads.messageCount();
            default -> "";
        };
    }

    @Override public void stageDraft(String text) {
        if (threads == null || text == null || text.trim().isEmpty()) return;
        dismissOverlays();
        Runnable edit = () -> {
            if (!threads.composerVisible()) navigate(WorkspaceNavigation.COMPOSER);
            threads.stageDraft(text);
        };
        if (threads.sessionId() != null) { edit.run(); return; }
        notice = Ui.secureDialog(new AlertDialog.Builder(this).setTitle("Créer une discussion ?")
                .setMessage("Le texte sera ajouté au brouillon de cette nouvelle discussion.")
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Créer le brouillon", (d, w) -> {
                    navigate(WorkspaceNavigation.COMPOSER);
                    threads.newThread(() -> threads.stageDraft(text));
                }));
    }

    private void leavingSurface() {
        shell.dismissMenu();
        voice.reader().stop();
    }

    private void render(boolean force) {
        String route = navigation.current();
        customPages.keySet().removeIf(id -> !navigation.contains(id));
        switch (route) {
            case WorkspaceNavigation.DISCUSSION:
            case WorkspaceNavigation.COMPOSER:
                boolean composer = WorkspaceNavigation.COMPOSER.equals(route);
                String surface = threads.sessionId() == null ? "threads" : "thread";
                String key = composer ? route : surface;
                if (!navigation.display(key, this::leavingSurface) && !force) return;
                boolean editing = threads.editing();
                View nativeView = threads.view();
                if (nativeView.getParent() instanceof android.view.ViewGroup)
                    ((android.view.ViewGroup) nativeView.getParent()).removeView(nativeView);
                View content;
                if (composer) {
                    threadSlots(Collections.emptyList());
                    content = nativeView;
                } else content = ArtifactDesk.decorate(this, account, surface, nativeView, activeHost);
                shell.show(content, composer ? "Discussion d’origine" : null);
                ArtifactDesk.refreshBindings(shell.content(), activeHost);
                if (editing) threads.focusComposer();
                break;
            case WorkspaceNavigation.HOME:
                navigation.display(route, this::leavingSurface);
                shell.show(ArtifactDesk.decorate(this, account, "home", shell.desk(account.label), activeHost), "Atelier");
                break;
            case WorkspaceNavigation.GALLERY:
                navigation.display(route, this::leavingSurface);
                shell.show(ArtifactDesk.pages(this, account, activeHost), "Mes écrans");
                break;
            default:
                View page = customPages.get(route);
                if (page == null) page = ArtifactDesk.page(this, account, route, activeHost);
                if (page == null) {
                    navigation.forgetCurrentPage();
                    render(true);
                } else {
                    navigation.display(route, this::leavingSurface);
                    customPages.put(route, page);
                    shell.show(page, "Écran personnalisé");
                    ArtifactDesk.refreshBindings(page, activeHost);
                }
        }
        pendingChanged();
    }

    @Override public void pendingChanged() {
        if (shell == null) return;
        WorkspaceShell owner = shell;
        Runnable open = null;
        String route = navigation.current();
        if (threads != null && (WorkspaceNavigation.DISCUSSION.equals(route) || WorkspaceNavigation.COMPOSER.equals(route))) {
            Runnable reveal = threads.hiddenPromptAction(() -> navigate(WorkspaceNavigation.COMPOSER));
            if (reveal != null) open = () -> {
                if (shell == owner && route.equals(navigation.current())) reveal.run();
            };
        }
        owner.pendingResponse(open);
    }

    @Override public void selected(String sessionId, String title) {
        if (shell == null) return;
        String threadTitle = sessionId == null ? null : title == null || title.trim().isEmpty() ? "Discussion" : title;
        shell.selected(sessionId, threadTitle);
        sharedText.selected(sessionId);
        deliverSelectedPhoto();
        if (WorkspaceNavigation.DISCUSSION.equals(navigation.current())) render(false);
        ArtifactDesk.refreshBindings(shell.content(), activeHost);
    }

    @Override public JSONObject options(String sessionId) {
        JSONObject options = SessionPanel.runOptions(this, account, sessionId);
        try {
            options.put("instructions", ArtifactDesk.proposalInstructions(account, sessionId));
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
        } else if ((WorkspaceNavigation.DISCUSSION.equals(navigation.current())
                || WorkspaceNavigation.COMPOSER.equals(navigation.current())) && threads.closeInstrument()) {
            return;
        } else if (navigation.back()) {
            render(false);
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
        if (photoPicker != null && photoPicker.result(code, result, data)) {
            deliverSelectedPhoto();
            return;
        }
        if (files != null && files.result(code, result, data)) return;
        super.onActivityResult(code, result, data);
    }

    private void deliverSelectedPhoto() {
        if (photoPicker.deliver(account, threads) && !threads.composerVisible()) navigate(WorkspaceNavigation.COMPOSER);
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (!voice.permissionResult(code, permissions, results)) push.onPermissionResult(code);
    }

    private void clearWorkspace() {
        if (sharedText != null) sharedText.lock();
        if (shell != null) shell.dismissMenu();
        if (voice != null) voice.reader().stop();
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
        navigation = null;
        customPages.clear();
        activeHost = null;
        account = null;
    }

    private void lock() {
        try {
            clearWorkspace();
        } finally {
            gate.lock();
        }
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (voice != null) voice.windowFocusChanged(focused);
    }

    @Override protected void onPause() {
        voice.pause();
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        voice.resume();
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
