package org.llm4people.hermes;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;

import org.json.JSONException;
import org.json.JSONObject;
import org.llm4people.hermes.artifact.ArtifactDesk;
import org.llm4people.hermes.camera.ThreadCameraCapture;
import org.llm4people.hermes.files.PublishedFiles;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.os.HermesShortcuts;
import org.llm4people.hermes.os.HermesWidgetStore;
import org.llm4people.hermes.os.LiveRunActionReceiver;
import org.llm4people.hermes.os.LiveRunNotification;
import org.llm4people.hermes.os.LockGracePolicy;
import org.llm4people.hermes.os.OsEntryIntent;
import org.llm4people.hermes.panel.BrainStudioRoutes;
import org.llm4people.hermes.panel.SessionPanel;
import org.llm4people.hermes.panel.ShareWithUserSheet;
import org.llm4people.hermes.panel.ThreadActions;
import org.llm4people.hermes.panel.UniversalShareModel;
import org.llm4people.hermes.profile.Account;
import org.llm4people.hermes.profile.ProfileGate;
import org.llm4people.hermes.push.MobilePush;
import org.llm4people.hermes.share.SharedMediaFlow;
import org.llm4people.hermes.share.SharedTextFlow;
import org.llm4people.hermes.thread.ThreadPhotoPicker;
import org.llm4people.hermes.thread.ThreadWorkspace;
import org.llm4people.hermes.voice.VoiceDrafts;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/** Owns the unlocked account and the lifetime of every screen using its credentials. */
public final class MainActivity extends Activity implements ThreadWorkspace.Host, ArtifactDesk.Host, MobilePush.EventHost {
    private final ExecutorService notifications = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ShellRoutes shellRoutes = new ShellRoutes();
    private final ActionRegistry actions = new ActionRegistry();
    private ProfileGate gate;
    private VoiceDrafts voice;
    private PublishedFiles files;
    private ThreadActions threadActions;
    private ThreadPhotoPicker photoPicker;
    private ThreadCameraCapture cameraCapture;
    private Account account;
    private SessionPanel panels;
    private RouteRegistry featureRoutes;
    private ThreadWorkspace threads;
    private WorkspaceShell shell;
    private WorkspaceNavigation navigation;
    private ArtifactDesk.Host activeHost;
    private final Map<String, View> customPages = new HashMap<>();
    private SharedTextFlow sharedText;
    private SharedMediaFlow sharedMedia;
    private MobilePush push;
    private MobilePush.Target delivery;
    private OsEntryIntent.Request pendingOsEntry;
    private AlertDialog notice;
    private String handledDelivery;
    private long noticeRequest;
    private long backgroundedAtElapsedMs = -1L;
    private long activeGraceMillis;
    private boolean imeVisible;
    private final Runnable graceExpiry = () -> {
        if (account != null && backgroundedAtElapsedMs >= 0L) {
            Diagnostics.event("security", "grace_expired");
            lock();
        }
    };
    private final Runnable liveStopHandler = () -> main.post(() -> {
        if (threads != null && account != null) threads.stopActiveRun();
    });

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
        HermesShortcuts.publish(this);
        voice = new VoiceDrafts(this);
        push = new MobilePush(this);
        files = new PublishedFiles(this);
        threadActions = new ThreadActions(this);
        photoPicker = new ThreadPhotoPicker(this, state);
        cameraCapture = new ThreadCameraCapture(this, state);
        sharedText = new SharedTextFlow(this, () -> { displayDiscussion(); threads.inbox(); },
                () -> navigate(WorkspaceNavigation.COMPOSER));
        sharedText.restore(state, getIntent());
        sharedMedia = new SharedMediaFlow(this, () -> { displayDiscussion(); threads.inbox(); },
                () -> navigate(WorkspaceNavigation.COMPOSER));
        sharedMedia.restore(state, getIntent());
        gate = new ProfileGate(this);
        gate.setOnForget(id -> MobilePush.forget(this, id));
        handledDelivery = state == null ? null : state.getString("handled_delivery");
        delivery = MobilePush.target(this, getIntent());
        if (delivery != null && key(delivery).equals(handledDelivery)) delivery = null;
        pendingOsEntry = OsEntryIntent.parseRequest(getIntent());
        registerBuiltInRoutes();
        registerBuiltInActions();
        unlock();
    }

    /** Extensibility hook for feature modules registering additional destinations. */
    public ShellRoutes shellRoutes() { return shellRoutes; }

    /** Extensibility hook for feature modules registering additional workspace actions. */
    public ActionRegistry actionRegistry() { return actions; }

    private void registerBuiltInRoutes() {
        shellRoutes.register(WorkspaceNavigation.DISCUSSION, (route, force) -> renderDiscussion(false, force));
        shellRoutes.register(WorkspaceNavigation.COMPOSER, (route, force) -> renderDiscussion(true, force));
        shellRoutes.register(WorkspaceNavigation.HOME, (route, force) -> {
            navigation.display(route, this::leavingSurface);
            shell.show(ArtifactDesk.decorate(this, account, "home", shell.desk(account.label), activeHost), "Atelier");
        });
        shellRoutes.register(WorkspaceNavigation.BRAIN, (route, force) -> {
            navigation.display(route, this::leavingSurface);
            shell.show(shell.brain(), getString(R.string.nav_brain));
        });
        shellRoutes.register(WorkspaceNavigation.ACTIVITY, (route, force) -> {
            navigation.display(route, this::leavingSurface);
            shell.show(shell.activitySurface(WorkspaceDialogs.securitySummary(this, account.id)),
                    getString(R.string.nav_activity));
        });
        shellRoutes.register(WorkspaceNavigation.GALLERY, (route, force) -> {
            navigation.display(route, this::leavingSurface);
            shell.show(ArtifactDesk.pages(this, account, activeHost), "Mes écrans");
        });
        shellRoutes.setFallback((route, force) -> {
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
        });
    }

    private void registerBuiltInActions() {
        actions.register("previous", this::back);
        actions.register("return", this::displayDiscussion);
        actions.register("nav_discussions", () -> switchTab(WorkspaceNavigation.DISCUSSION));
        actions.register("nav_atelier", () -> switchTab(WorkspaceNavigation.HOME));
        actions.register("nav_brain", () -> switchTab(WorkspaceNavigation.BRAIN));
        actions.register("nav_activity", () -> switchTab(WorkspaceNavigation.ACTIVITY));
        actions.register("pages", () -> navigate(WorkspaceNavigation.GALLERY));
        actions.register("nearby_area", () -> { if (threads != null) threads.editNearbyArea(); });
        actions.register("attach_photo", () -> photoPicker.launch(account, threads));
        actions.register("capture_camera", () -> {
            if (threads.sessionId() != null) cameraCapture.launch(account, threads.sessionId());
        });
        actions.register("native_thread", () -> navigate(WorkspaceNavigation.COMPOSER));
        actions.register("toggle_discussions", () -> threads.toggleDiscussions());
        actions.register("threads", () -> { displayDiscussion(); threads.inbox(); });
        actions.register("new_thread", () -> { displayDiscussion(); threads.newThread(); });
        actions.register("create_instrument", () -> {
            if (threads.sessionId() != null) stageDraft(getString(R.string.instrument_create_request));
            else message("Choisir une discussion", "Ouvrez une discussion pour créer un outil.");
        });
        actions.register("rename", () -> threads.rename());
        actions.register("published_files", () -> {
            if (threads.remoteSessionId() != null) files.show(threads.remoteSessionId());
        });
        actions.register("workspace", () -> navigate(WorkspaceNavigation.HOME));
        actions.register("brain", () -> {
            if (featureRoutes != null) panels.showBrainHub(featureRoutes);
        });
        actions.register("reminders", () -> panels.showReminders());
        actions.register("notifications", () -> push.settings(account));
        actions.register("restore", () -> ArtifactDesk.manage(this, account, activeHost));
        actions.register("profiles", this::lock);
        actions.register("pin_widget", () -> WorkspaceDialogs.pinQuickWidget(this));
        actions.register("search", () -> {
            if (notice != null) notice.dismiss();
            notice = WorkspaceDialogs.showSearch(this, threads, this::onAction, () -> notice = null);
        });
        actions.register("security", () -> {
            if (account == null) return;
            if (notice != null) notice.dismiss();
            notice = WorkspaceDialogs.showSecurity(this, account, gate, this::lock, () -> {
                if (navigation != null && WorkspaceNavigation.ACTIVITY.equals(navigation.current())) render(true);
            }, () -> notice = null);
        });
        actions.register("skills", () -> {
            Account owner = account;
            panels.showSkills(() -> account == owner && !isFinishing(), this::stageDraft);
        });
        actions.register("stats", () -> openThreadAction("stats"));
        actions.register("tools", () -> openThreadAction("tools"));
        actions.register("models", () -> openThreadAction("models"));
        actions.register("artifacts", () -> openThreadAction("artifacts"));
    }

    private void openThreadAction(String id) {
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
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        // The activity has no public view state. Discard framework dialog and view text too.
        outState.clear();
        sharedText.save(outState);
        sharedMedia.save(outState);
        photoPicker.save(outState);
        cameraCapture.save(outState);
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
        String requested = delivery != null ? delivery.profileId
                : sharedText.requestedProfile() != null ? sharedText.requestedProfile()
                : sharedMedia.requestedProfile();
        gate.start(requested, this::opened);
    }

    private void opened(Account unlocked) {
        clearGraceTimer();
        account = unlocked;
        photoPicker.unlocked(unlocked);
        cameraCapture.unlocked(unlocked);
        HermesApi api = new HermesApi(account.endpoint, account.bearer);
        navigation = new WorkspaceNavigation();
        activeHost = new WorkspaceHost(this, () -> account == unlocked);
        shell = new WorkspaceShell(this, this::onAction, voice.reader()::active);
        shell.setImeVisible(imeVisible);
        panels = new SessionPanel(this, api, account);
        panels.setLayoutHost(activeHost);
        threads = new ThreadWorkspace(this, api, account, this, voice);
        threads.setInstrumentHost(activeHost);
        LiveRunActionReceiver.setStopHandler(liveStopHandler);
        threadActions.unlock(account, api);
        featureRoutes = BrainStudioRoutes.register(new RouteRegistry(), brainHandlers());
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
        sharedMedia.opened(unlocked, threads);
        deliverCapturedPhoto();
        MobilePush.Target target = delivery;
        if (target != null) {
            handled(target);
            if (unlocked.id.equals(target.profileId)) resolve(unlocked, target);
        }
        dispatchPendingOsEntry();
    }

    private void dispatchPendingOsEntry() {
        OsEntryIntent.Request request = pendingOsEntry;
        if (request == null || account == null || threads == null) return;
        pendingOsEntry = null;
        setIntent(new Intent(this, MainActivity.class));
        HermesShortcuts.reportUsed(this, request.target());
        switch (request.target()) {
            case ASK -> {
                displayDiscussion();
                if (threads.sessionId() != null) threads.focusComposer();
                else threads.newThread(threads::focusComposer);
            }
            case NEW_DISCUSSION -> {
                displayDiscussion();
                threads.newThread(threads::focusComposer);
            }
            case DICTATE -> {
                displayDiscussion();
                if (threads.sessionId() != null) threads.startDictation();
                else threads.newThread(threads::startDictation);
            }
            case ATELIER -> switchTab(WorkspaceNavigation.HOME);
            case ACTIVITY -> switchTab(WorkspaceNavigation.ACTIVITY);
            case WIDGET_ACTION -> {
                String draft = new HermesWidgetStore(this)
                        .draftForAction(request.widgetId(), request.itemIndex());
                displayDiscussion();
                if (draft != null && !draft.isEmpty()) {
                    stageDraft(draft);
                } else if (threads.sessionId() != null) {
                    threads.focusComposer();
                } else {
                    threads.newThread(threads::focusComposer);
                }
            }
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
        if (threadActions != null) threadActions.dismiss();
        if (!keepThreadEditor) {
            if (threads != null) threads.dismissDialogs();
            ArtifactDesk.dismiss(this);
        }
        if (panels != null) panels.close();
        if (push != null) push.lock();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Android invokes onNewIntent() before onStart()/onResume(); enforce grace expiry first
        // so an expired session locks immediately and queues incoming OS intents until re-unlock.
        checkGraceOnReturn();
        if (sharedText.receive(intent) || sharedMedia.receive(intent)) {
            setIntent(new Intent(this, MainActivity.class));
            delivery = null;
            pendingOsEntry = null;
            clearWorkspace();
            unlock();
            return;
        }
        OsEntryIntent.Request entry = OsEntryIntent.parseRequest(intent);
        if (entry != null) {
            setIntent(intent);
            pendingOsEntry = entry;
            if (account != null && threads != null) dispatchPendingOsEntry();
            return;
        }
        MobilePush.Target incoming = MobilePush.target(this, intent);
        if (incoming == null) return;
        sharedText.leave();
        sharedMedia.leave();
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
        if (actions.contains(id)) actions.dispatch(id);
        else if (featureRoutes == null || !featureRoutes.dispatch(id)) {
            Diagnostics.warning("workspace", "unknown_action");
        }
    }

    private BrainStudioRoutes.Handlers brainHandlers() {
        return new BrainStudioRoutes.Handlers() {
            private Runnable returnToHub() {
                RouteRegistry current = featureRoutes;
                return current == null ? null : () -> panels.showBrainHub(current);
            }

            private BooleanSupplier activeThreadCheck() {
                ThreadWorkspace origin = threads;
                String currentSession = origin == null ? null : origin.sessionId();
                return () -> origin != null && threads == origin && currentSession != null
                        && currentSession.equals(origin.sessionId());
            }

            private String activeRemoteSessionId() {
                if (threads == null) return null;
                String tip = threads.remoteSessionId();
                return tip != null && !tip.isEmpty() ? tip : threads.sessionId();
            }

            @Override public void openMemory() { panels.showMemory(returnToHub()); }

            @Override public void openNotes() {
                panels.showNotes(activeThreadCheck(), MainActivity.this::stageDraft, returnToHub());
            }

            @Override public void openSharedWithMe() {
                panels.showSharedWithMe(MainActivity.this::stageDraft, returnToHub());
            }

            @Override public void openGlobalSearch() {
                panels.showGlobalSearch(id -> { displayDiscussion(); threads.open(id); }, returnToHub());
            }

            @Override public void openSkillStudio() {
                panels.showSkillStudio(activeThreadCheck(), MainActivity.this::stageDraft, returnToHub());
            }

            @Override public void openWorkspaceFiles() {
                String session = activeRemoteSessionId();
                if (session == null) {
                    message("Choisir une discussion", "Ouvrez une discussion pour parcourir les fichiers du projet.");
                    return;
                }
                panels.showWorkspaceFiles(session, activeThreadCheck(), MainActivity.this::stageDraft, returnToHub());
            }

            @Override public void openProfileDefaults() { panels.showProfileDefaults(returnToHub()); }

            @Override public void togglePinThread() {
                String remote = activeRemoteSessionId();
                if (remote != null) threadActions.togglePin(remote, null);
            }

            @Override public void forkThread() {
                String session = activeRemoteSessionId();
                if (session != null) {
                    threadActions.showForkDialog(session, threads.messages(),
                            forkedId -> { displayDiscussion(); threads.open(forkedId); });
                }
            }

            @Override public void exportThread() {
                String session = activeRemoteSessionId();
                if (session != null) threadActions.showExportDialog(session, threads.title());
            }

            @Override public void shareThread() {
                String session = activeRemoteSessionId();
                if (session != null) {
                    ShareWithUserSheet.openShareSheet(panels, UniversalShareModel.KIND_SESSION,
                            session, threads.title(), null);
                }
            }
        };
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

    private void switchTab(String destination) {
        if (navigation == null) return;
        navigation.selectTab(destination);
        render(false);
    }

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

    private void renderDiscussion(boolean composer, boolean force) {
        String surface = threads.sessionId() == null ? "threads" : "thread";
        String key = composer ? WorkspaceNavigation.COMPOSER : surface;
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
    }

    private void render(boolean force) {
        String route = navigation.current();
        customPages.keySet().removeIf(id -> !navigation.contains(id));
        shell.setActiveRoute(route);
        shellRoutes.render(route, force);
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

    @Override public void nearbyGuide(ThreadWorkspace origin, String threadId, String runId, JSONObject assistantRow, JSONObject approvedArea) {
        if (origin != null && threads == origin && threadId != null && threadId.equals(origin.sessionId())
                && origin.view().isAttachedToWindow() && origin.view().isShown())
            origin.acceptNearbyGuide(threadId, runId, assistantRow, approvedArea);
    }

    @Override public void runProgress(LiveRunNotification.Snapshot snapshot) {
        if (account == null) {
            LiveRunNotification.cancel(this);
            return;
        }
        LiveRunNotification.update(this, snapshot);
    }

    @Override public void discussionPanes(boolean canToggle, boolean listVisible) {
        if (shell != null) shell.discussionPanes(canToggle, listVisible);
    }

    @Override public void selected(String sessionId, String title) {
        if (shell == null) return;
        String threadTitle = sessionId == null ? null : title == null || title.trim().isEmpty() ? "Discussion" : title;
        shell.selected(sessionId, threadTitle);
        sharedText.selected(sessionId);
        sharedMedia.selected(sessionId);
        deliverSelectedPhoto();
        deliverCapturedPhoto();
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
        if (cameraCapture != null && cameraCapture.onActivityResult(code, result)) {
            deliverCapturedPhoto();
            return;
        }
        if (photoPicker != null && photoPicker.result(code, result, data)) {
            deliverSelectedPhoto();
            return;
        }
        if (files != null && files.result(code, result, data)) return;
        if (threadActions != null && threadActions.result(code, result, data)) return;
        super.onActivityResult(code, result, data);
    }

    private void deliverSelectedPhoto() {
        if (photoPicker.deliver(account, threads) && !threads.composerVisible()) navigate(WorkspaceNavigation.COMPOSER);
    }

    private void deliverCapturedPhoto() {
        if (cameraCapture != null && account != null && threads != null && threads.sessionId() != null) {
            if (threads.importCapturedPhoto(cameraCapture, account) && !threads.composerVisible()) {
                navigate(WorkspaceNavigation.COMPOSER);
            }
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (threads != null && threads.nearbyPermission(code, permissions, results)) return;
        if (!voice.permissionResult(code, permissions, results)) push.onPermissionResult(code);
    }

    private void clearGraceTimer() {
        main.removeCallbacks(graceExpiry);
        backgroundedAtElapsedMs = -1L;
        activeGraceMillis = 0L;
    }

    private void clearWorkspace() {
        clearGraceTimer();
        LiveRunActionReceiver.clearStopHandler(liveStopHandler);
        LiveRunNotification.cancel(this);
        if (sharedText != null) sharedText.lock();
        if (sharedMedia != null) sharedMedia.lock();
        if (photoPicker != null) photoPicker.interruptImport();
        if (shell != null) shell.dismissMenu();
        if (voice != null) voice.reader().stop();
        dismissOverlays();
        if (files != null) files.lock();
        if (threadActions != null) threadActions.lock();
        if (threads != null) {
            try {
                threads.close();
            } catch (IllegalStateException storageFailure) {
                Diagnostics.error("threads", "lock_checkpoint_failed");
            }
        }
        notice = null;
        panels = null;
        featureRoutes = null;
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

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (shell != null) {
            shell.dismissMenu();
            shell.updateNavigationChrome(newConfig.screenWidthDp, newConfig.screenHeightDp);
        }
        View content = findViewById(android.R.id.content);
        if (content != null) content.requestApplyInsets();
        Diagnostics.event("workspace", "window_resized");
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (voice != null) voice.windowFocusChanged(focused);
        if (focused && threads != null) threads.refreshClipboardPill();
    }

    @Override protected void onStart() {
        super.onStart();
        checkGraceOnReturn();
    }

    @Override protected void onPause() {
        if (threads != null) threads.pauseNearbyFix();
        voice.pause();
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        checkGraceOnReturn();
        voice.resume();
    }

    private void checkGraceOnReturn() {
        if (account == null || backgroundedAtElapsedMs < 0L) return;
        long now = SystemClock.elapsedRealtime();
        if (LockGracePolicy.shouldLockOnReturn(backgroundedAtElapsedMs, now, activeGraceMillis, false)) {
            Diagnostics.event("security", "grace_expired_on_return");
            lock();
        } else {
            clearGraceTimer();
            Diagnostics.event("security", "grace_resumed");
        }
    }

    @Override protected void onStop() {
        super.onStop();
        if (gate == null) return;
        if (account == null || isFinishing() || isChangingConfigurations()) {
            if (!isChangingConfigurations()) lock();
            return;
        }
        LockGracePolicy.Window window = LockGracePolicy.window(this, account.id);
        if (window.millis <= 0L) {
            lock();
            return;
        }
        backgroundedAtElapsedMs = SystemClock.elapsedRealtime();
        activeGraceMillis = window.millis;
        main.removeCallbacks(graceExpiry);
        main.postDelayed(graceExpiry, window.millis);
        Diagnostics.event("security", "grace_started");
    }

    @Override protected void onDestroy() {
        clearWorkspace();
        if (files != null) files.close();
        if (threadActions != null) threadActions.close();
        notifications.shutdownNow();
        super.onDestroy();
    }

    private void fitSystemBars(View content) {
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        content.setOnApplyWindowInsetsListener((v, insets) -> {
            boolean ime;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                v.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                ime = insets.isVisible(WindowInsets.Type.ime());
            } else {
                int bottom = insets.getSystemWindowInsetBottom();
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), bottom);
                ime = bottom > Ui.dp(this, 100);
            }
            imeVisible = ime;
            if (shell != null) shell.setImeVisible(ime);
            return insets;
        });
    }
}
