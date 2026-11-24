package org.llm4people.hermes;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.CalendarContract;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;
import org.llm4people.hermes.ambient.AmbientAccessPanel;
import org.llm4people.hermes.ambient.HermesNotificationListener;
import org.llm4people.hermes.ambient.NotificationAllowlist;
import org.llm4people.hermes.artifact.ArtifactDesk;
import org.llm4people.hermes.artifact.HomePinBridge;
import org.llm4people.hermes.artifact.LiveUiDeliveryDefaults;
import org.llm4people.hermes.artifact.LiveUiPatch;
import org.llm4people.hermes.artifact.ThemePalette;
import org.llm4people.hermes.camera.ThreadCameraCapture;
import org.llm4people.hermes.customization.AppCustomization;
import org.llm4people.hermes.customization.AppCustomizationStore;
import org.llm4people.hermes.doctor.ConnectionDoctorPanel;
import org.llm4people.hermes.files.PublishedFiles;
import org.llm4people.hermes.hub.AppsLauncherModel;
import org.llm4people.hermes.hub.AppsLauncherPanel;
import org.llm4people.hermes.hub.NotesTasksPanel;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.onboarding.OnboardingModels;
import org.llm4people.hermes.onboarding.PairingIntent;
import org.llm4people.hermes.onboarding.PairingLinkParser;
import org.llm4people.hermes.onboarding.QrScannerActivity;
import org.llm4people.hermes.os.HermesShortcuts;
import org.llm4people.hermes.os.HermesWidgetSpec;
import org.llm4people.hermes.os.HermesWidgetStore;
import org.llm4people.hermes.os.LiveRunActionReceiver;
import org.llm4people.hermes.os.LiveRunNotification;
import org.llm4people.hermes.os.LockGracePolicy;
import org.llm4people.hermes.os.OsEntryIntent;
import org.llm4people.hermes.os.PermissionCenter;
import org.llm4people.hermes.os.PermissionsPanel;
import org.llm4people.hermes.palette.CommandPalette;
import org.llm4people.hermes.palette.CommandRegistry;
import org.llm4people.hermes.panel.BrainStudioRoutes;
import org.llm4people.hermes.panel.SessionPanel;
import org.llm4people.hermes.panel.ShareWithUserSheet;
import org.llm4people.hermes.panel.ThreadActions;
import org.llm4people.hermes.panel.UniversalShareModel;
import org.llm4people.hermes.perf.MemoryPressure;
import org.llm4people.hermes.presence.AppPresenceTracker;
import org.llm4people.hermes.presence.ContinuousLocationTracker;
import org.llm4people.hermes.presence.DeepAndroidActions;
import org.llm4people.hermes.presence.PresencePolicy;
import org.llm4people.hermes.presence.PresenceSettingsPanel;
import org.llm4people.hermes.profile.Account;
import org.llm4people.hermes.profile.AdminConsoleSheet;
import org.llm4people.hermes.profile.FirstRunWizard;
import org.llm4people.hermes.profile.ProfileGate;
import org.llm4people.hermes.profile.ProfileHubSheet;
import org.llm4people.hermes.profile.ProfileLockPolicy;
import org.llm4people.hermes.push.DeliveryItem;
import org.llm4people.hermes.push.DeliveryRouter;
import org.llm4people.hermes.push.MobilePush;
import org.llm4people.hermes.search.UniversalSearchEntries;
import org.llm4people.hermes.search.UniversalSearchModel;
import org.llm4people.hermes.search.UniversalSearchSheet;
import org.llm4people.hermes.settings.AppThemeMode;
import org.llm4people.hermes.settings.AppearanceSettingsPanel;
import org.llm4people.hermes.settings.SettingsHubModel;
import org.llm4people.hermes.settings.SettingsHubPanel;
import org.llm4people.hermes.share.SharedMediaFlow;
import org.llm4people.hermes.share.SharedTextFlow;
import org.llm4people.hermes.templates.TemplateStore;
import org.llm4people.hermes.templates.TemplatesPanel;
import org.llm4people.hermes.thread.ThreadPhotoPicker;
import org.llm4people.hermes.thread.ThreadWorkspace;
import org.llm4people.hermes.update.UpdateCheckCoordinator;
import org.llm4people.hermes.update.UpdatePanel;
import org.llm4people.hermes.voice.VoiceDrafts;

import java.io.IOException;
import java.util.ArrayList;
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
    private HermesApi activeApi;
    private SessionPanel panels;
    private RouteRegistry featureRoutes;
    private ThreadWorkspace threads;
    private WorkspaceShell shell;
    private WorkspaceNavigation navigation;
    private ArtifactDesk.Host activeHost;
    private static final int REQUEST_PRESENCE_LOCATION = 4092;
    private static final int REQUEST_PRESENCE_FINE_LOCATION = 4093;
    private static final int REQUEST_PRESENCE_NOTIFICATIONS = 4094;
    private final AppPresenceTracker presenceTracker = new AppPresenceTracker();
    private final ContinuousLocationTracker locationTracker = new ContinuousLocationTracker();
    private final DeepAndroidActions.NudgeRateLimiter nudgeRateLimiter = new DeepAndroidActions.NudgeRateLimiter();
    private long presenceEpoch;
    private AmbientAccessPanel activeAmbientPanel;
    private PresenceSettingsPanel activePresencePanel;
    private UpdatePanel activeUpdatePanel;
    private ConnectionDoctorPanel activeDoctorPanel;
    private PermissionsPanel activePermissionsPanel;
    private TemplatesPanel activeTemplatesPanel;
    private NotesTasksPanel activeNotesTasksPanel;
    private AppsLauncherPanel activeAppsPanel;
    private SettingsHubPanel activeSettingsPanel;
    private AppearanceSettingsPanel activeAppearancePanel;
    private CommandPalette activePalette;
    private UniversalSearchSheet activeSearchSheet;
    private ProfileHubSheet activeProfileHub;
    private AdminConsoleSheet activeAdminConsole;
    private AutoCloseable activeFirstRunStep2;
    private boolean activeProfileIsAdmin;
    private UpdateCheckCoordinator.BadgeState updateBadge;
    private final Map<String, View> customPages = new HashMap<>();
    private SharedTextFlow sharedText;
    private SharedMediaFlow sharedMedia;
    private MobilePush push;
    private MobilePush.Target delivery;
    private OsEntryIntent.Request pendingOsEntry;
    private PairingLinkParser.ParsedInput pendingPairing;
    private java.util.function.Consumer<PairingLinkParser.ParsedInput> pairingLinkHandler;
    private String pendingPinnedSurface;
    private String pendingPinnedWidget;
    private AlertDialog notice;
    private String handledDelivery;
    private long noticeRequest;
    private long backgroundedAtElapsedMs = -1L;
    private LockGracePolicy.Window activeGraceWindow = LockGracePolicy.Window.IMMEDIATE;
    private boolean internalActivityTransition;
    private long internalTransitionAtMs = -1L;
    private boolean screenLockedWhileBackgrounded;
    private final android.content.BroadcastReceiver screenOffReceiver = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                screenLockedWhileBackgrounded = true;
                clearInternalTransition();
                if (account != null) {
                    Diagnostics.event("security", "screen_off_locked");
                    lock();
                }
            }
        }
    };
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

    @Override protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppThemeMode.wrapContext(AppLanguage.wrapContext(newBase)));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        AppLanguage.refreshRuntime(this);
        AppThemeMode.applyStoredOnStartup(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        // Saved view hierarchies may contain composer or password text. Only Account storage
        // can restore private state; an OS recreation always comes back through the gate.
        View content = findViewById(android.R.id.content);
        content.setSaveFromParentEnabled(false);
        fitSystemBars(content);
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::back);
            registerReceiver(screenOffReceiver, new android.content.IntentFilter(Intent.ACTION_SCREEN_OFF),
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenOffReceiver, new android.content.IntentFilter(Intent.ACTION_SCREEN_OFF));
        }
        notifications.execute(() -> HermesShortcuts.publish(getApplicationContext()));
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
        gate.setOnForget(id -> {
            nudgeRateLimiter.reset();
            MobilePush.forget(this, id);
            HermesWidgetStore.forgetProfile(this, id);
            HermesNotificationListener.clearSession();
        });
        handledDelivery = state == null ? null : state.getString("handled_delivery");
        delivery = MobilePush.target(this, getIntent());
        if (delivery != null && key(delivery).equals(handledDelivery)) delivery = null;
        pendingOsEntry = OsEntryIntent.parseRequest(getIntent());
        capturePairingDeepLink(getIntent());
        capturePinnedShortcutIntent(getIntent());
        registerBuiltInRoutes();
        registerBuiltInActions();
        unlock();
    }

    /** Extensibility hook for feature modules registering additional destinations. */
    public ShellRoutes shellRoutes() { return shellRoutes; }

    /** Peek at a pending {@code hermes://pair} deep link awaiting explicit user confirmation. */
    public PairingLinkParser.ParsedInput pendingPairing() { return pendingPairing; }

    /** Consume and clear a pending {@code hermes://pair} deep link so single-use codes are never replayed. */
    public PairingLinkParser.ParsedInput consumePendingPairing() {
        PairingLinkParser.ParsedInput current = pendingPairing;
        pendingPairing = null;
        return current;
    }

    /** Register the UI handler (e.g., {@code ProfileGate}/{@code FirstRunWizard}) for warm {@code hermes://pair} intents. */
    public void setPairingLinkHandler(java.util.function.Consumer<PairingLinkParser.ParsedInput> handler) {
        this.pairingLinkHandler = handler;
    }

    enum PairingDeepLinkRoute {
        NONE,
        DELIVER_TO_LOCKED_HANDLER,
        LOCK_AND_UNLOCK_FOR_CONFIRMATION,
        OPEN_SETUP_GATE
    }

    /** Pure-Java SSoT routing decision for warm {@code hermes://pair} intents across locked and unlocked states. */
    static PairingDeepLinkRoute resolvePairingDeepLinkRoute(
            boolean hasPendingPairing,
            boolean hasUnlockedAccount,
            boolean hasPairingHandler) {
        if (!hasPendingPairing) return PairingDeepLinkRoute.NONE;
        if (!hasUnlockedAccount && hasPairingHandler) {
            return PairingDeepLinkRoute.DELIVER_TO_LOCKED_HANDLER;
        }
        return hasUnlockedAccount
                ? PairingDeepLinkRoute.LOCK_AND_UNLOCK_FOR_CONFIRMATION
                : PairingDeepLinkRoute.OPEN_SETUP_GATE;
    }

    private boolean capturePairingDeepLink(Intent intent) {
        PairingLinkParser.ParsedInput parsed = PairingIntent.parse(intent);
        if (parsed == null) return false;
        pendingPairing = parsed;
        setIntent(new Intent(this, MainActivity.class));
        return true;
    }

    private void registerBuiltInRoutes() {
        shellRoutes.register(WorkspaceNavigation.DISCUSSION, (route, force) -> renderDiscussion(false, force));
        shellRoutes.register(WorkspaceNavigation.COMPOSER, (route, force) -> renderDiscussion(true, force));
        shellRoutes.register(WorkspaceNavigation.NOTES_TASKS, (route, force) -> {
            boolean changed = navigation.display(route, this::leavingSurface);
            if (!changed && activeNotesTasksPanel != null) {
                activeNotesTasksPanel.refresh();
                return;
            }
            if (activeNotesTasksPanel != null) activeNotesTasksPanel.close();
            Account owner = account;
            HermesApi hubApi = new HermesApi(owner.endpoint, owner.bearer,
                    HermesApi.Limits.from(HermesConfig.load(this)));
            activeNotesTasksPanel = new NotesTasksPanel(
                    this,
                    owner,
                    hubApi,
                    () -> account == owner && !isFinishing() && !isDestroyed(),
                    notesTasksCallbacks(owner));
            shell.show(activeNotesTasksPanel.buildView(), getString(R.string.hub_tasks_title));
        });
        shellRoutes.register(WorkspaceNavigation.APPS, (route, force) -> {
            boolean changed = navigation.display(route, this::leavingSurface);
            if (!changed && !force && activeAppsPanel != null) {
                activeAppsPanel.refresh();
                return;
            }
            Account owner = account;
            activeAppsPanel = new AppsLauncherPanel(this, appsStateProvider(owner), appsCallbacks(owner));
            View nativeApps = activeAppsPanel.buildView();
            View decorated = ArtifactDesk.decorate(this, owner, "home", nativeApps, activeHost);
            shell.show(decorated, getString(R.string.hub_apps_title), decorated != nativeApps);
        });
        shellRoutes.register(WorkspaceNavigation.SETTINGS, (route, force) -> {
            boolean changed = navigation.display(route, this::leavingSurface);
            if (!changed && activeSettingsPanel != null) {
                activeSettingsPanel.refresh();
                return;
            }
            Account owner = account;
            activeSettingsPanel = new SettingsHubPanel(this, settingsStateProvider(owner), this::onAction);
            shell.show(activeSettingsPanel.buildView(), getString(R.string.settings_panel_title));
        });
        shellRoutes.register(AppearanceSettingsPanel.ROUTE_ID, (route, force) -> {
            boolean changed = navigation.display(route, this::leavingSurface);
            if (!changed && activeAppearancePanel != null) {
                activeAppearancePanel.syncThemeMode(ThemePalette.load(account, ThemePalette.isNight(this)).theme);
                activeAppearancePanel.refreshAll();
                return;
            }
            if (activeAppearancePanel != null) activeAppearancePanel.dismissDialogs();
            Account owner = account;
            String initialTheme = ThemePalette.load(owner, ThemePalette.isNight(this)).theme;
            activeAppearancePanel = new AppearanceSettingsPanel(
                    this,
                    AppCustomizationStore.forAccount(owner),
                    initialTheme,
                    new AppearanceSettingsPanel.Listener() {
                        @Override public void onCustomizationChanged(AppCustomization customization) {
                            if (account == owner) applyAppCustomization(owner, customization, true);
                        }
                        @Override public void onThemeModeChanged(String themeMode) {
                            if (account == owner) applyThemeModeOverride(owner, themeMode);
                        }
                    });
            shell.show(activeAppearancePanel.buildView(), getString(R.string.appearance_panel_title));
        });
        shellRoutes.register(AmbientAccessPanel.ROUTE_ID, (route, force) -> {
            navigation.display(route, this::leavingSurface);
            if (activeAmbientPanel != null) activeAmbientPanel.close();
            activeAmbientPanel = new AmbientAccessPanel(this, NotificationAllowlist.forAccount(account),
                    ambientCallbacks());
            shell.show(activeAmbientPanel.buildView(), getString(R.string.ambient_panel_title));
        });
        shellRoutes.register(PresenceSettingsPanel.ROUTE_ID, (route, force) -> {
            navigation.display(route, this::leavingSurface);
            if (activePresencePanel != null) activePresencePanel.close();
            Account owner = account;
            activePresencePanel = new PresenceSettingsPanel(
                    this,
                    PresencePolicy.load(owner),
                    locationTracker,
                    AppCustomizationStore.forAccount(owner),
                    next -> { if (account == owner) PresencePolicy.save(owner, next); },
                    new PresenceSettingsPanel.Listener() {
                        @Override public void onPolicyChanged(PresencePolicy policy) {
                            if (account == owner) locationTracker.syncWithPolicy(MainActivity.this, policy);
                        }
                        @Override public void onCustomizationChanged(AppCustomization customization) {
                            if (account == owner) applyAppCustomization(owner, customization, true);
                        }
                        @Override public void onRequestLocationPermission() {
                            if (account == owner) {
                                requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION},
                                        REQUEST_PRESENCE_LOCATION);
                            }
                        }
                        @Override public void onRequestPreciseLocationPermission() {
                            if (account == owner) {
                                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                                        REQUEST_PRESENCE_FINE_LOCATION);
                            }
                        }
                        @Override public void onRequestNotificationPermission() {
                            if (account == owner) {
                                if (Build.VERSION.SDK_INT >= 33) {
                                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                                            REQUEST_PRESENCE_NOTIFICATIONS);
                                } else {
                                    PermissionCenter.openNotificationSettings(MainActivity.this);
                                }
                            }
                        }
                    });
            shell.show(activePresencePanel.buildView(), getString(R.string.presence_panel_title));
        });
        shellRoutes.register(UpdatePanel.ROUTE_ID, (route, force) -> {
            navigation.display(route, this::leavingSurface);
            if (activeUpdatePanel != null) activeUpdatePanel.close();
            activeUpdatePanel = new UpdatePanel(this);
            shell.show(activeUpdatePanel.buildView(), getString(R.string.update_panel_title));
        });
        shellRoutes.register(ConnectionDoctorPanel.ROUTE_ID, (route, force) -> {
            navigation.display(route, this::leavingSurface);
            if (activeDoctorPanel != null) activeDoctorPanel.close();
            Account owner = account;
            HermesApi doctorApi = new HermesApi(owner.endpoint, owner.bearer,
                    HermesApi.Limits.from(HermesConfig.load(this)));
            activeDoctorPanel = new ConnectionDoctorPanel(this, owner, doctorApi, doctorFixHandlers(owner));
            shell.show(activeDoctorPanel.buildView(), getString(R.string.doctor_panel_title));
        });
        shellRoutes.register(PermissionsPanel.ROUTE_ID, (route, force) -> {
            navigation.display(route, this::leavingSurface);
            activePermissionsPanel = new PermissionsPanel(this);
            shell.show(activePermissionsPanel.buildView(), getString(R.string.perm_panel_title));
        });
        shellRoutes.register(TemplatesPanel.ROUTE_ID, (route, force) -> {
            navigation.display(route, this::leavingSurface);
            if (activeTemplatesPanel != null) activeTemplatesPanel.close();
            Account owner = account;
            activeTemplatesPanel = new TemplatesPanel(this, owner, this::stageDraft,
                    () -> account == owner && !isFinishing() && !isDestroyed());
            shell.show(activeTemplatesPanel.buildView(), getString(R.string.templates_panel_title));
        });
        shellRoutes.register(WorkspaceNavigation.GALLERY, (route, force) -> {
            navigation.display(route, this::leavingSurface);
            shell.show(ArtifactDesk.pages(this, account, activeHost), getString(R.string.ws_my_screens), true);
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
                shell.show(page, getString(R.string.ws_custom_screen), true);
                ArtifactDesk.refreshBindings(page, activeHost);
            }
        });
    }

    private void registerBuiltInActions() {
        actions.register("previous", this::back);
        actions.register("return", this::displayDiscussion);
        actions.register("nav_discussions", () -> switchTab(WorkspaceNavigation.DISCUSSION));
        actions.register("nav_tasks", () -> switchTab(WorkspaceNavigation.NOTES_TASKS));
        actions.register("nav_apps", () -> switchTab(WorkspaceNavigation.APPS));
        actions.register("nav_settings", this::openSettingsHub);
        actions.register(SettingsHubPanel.ROUTE_ID, this::openSettingsHub);
        actions.register(AppearanceSettingsPanel.ROUTE_ID, () -> navigate(AppearanceSettingsPanel.ROUTE_ID));
        actions.register(SettingsHubPanel.ACTION_VOICE_OPTIONS, () -> voice.openOptionsDialog(null, false));
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
            else message(getString(R.string.ws_choose_discussion_title),
                    getString(R.string.ws_choose_discussion_tool_body));
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
        actions.register("notifications", () -> push.settings(account, deliveryActions(account)));
        actions.register("deliveries", () -> push.openInbox(account, deliveryActions(account)));
        actions.register("push_policy", () -> push.openPolicy(account));
        actions.register(AmbientAccessPanel.ROUTE_ID, () -> navigate(AmbientAccessPanel.ROUTE_ID));
        actions.register(PresenceSettingsPanel.ROUTE_ID, () -> navigate(PresenceSettingsPanel.ROUTE_ID));
        actions.register(ConnectionDoctorPanel.ROUTE_ID, () -> navigate(ConnectionDoctorPanel.ROUTE_ID));
        actions.register(PermissionsPanel.ROUTE_ID, () -> navigate(PermissionsPanel.ROUTE_ID));
        actions.register(TemplatesPanel.ROUTE_ID, () -> navigate(TemplatesPanel.ROUTE_ID));
        actions.register(UpdatePanel.ROUTE_ID, () -> navigate(UpdatePanel.ROUTE_ID));
        actions.register("command_palette", this::openCommandPalette);
        actions.register("language", () -> {
            Account owner = account;
            if (notice != null) notice.dismiss();
            // Tracked in notice so lock/switch dismisses it; the sync binds the profile that opened it.
            notice = AppLanguage.showPicker(this, selected -> {
                if (owner != null && owner == account) {
                    HermesApi syncApi = new HermesApi(owner.endpoint, owner.bearer,
                            HermesApi.Limits.from(HermesConfig.load(this)));
                    AppLanguage.syncToServerAsync(syncApi, selected);
                    refreshSettingsHubIfVisible();
                }
            });
        });
        actions.register("restore", () -> ArtifactDesk.manage(this, account, activeHost));
        actions.register("profiles", this::lock);
        actions.register("profile_hub", () -> openProfileHub(false, false));
        actions.register("switch_profile", () -> openProfileHub(false, false));
        actions.register("pair_device", () -> openProfileHub(true, false));
        actions.register("invite_person", () -> openProfileHub(false, true));
        actions.register("admin_console", this::openAdminConsole);
        actions.register("pin_widget", () -> {
            if (account != null) WorkspaceDialogs.pinQuickWidget(this, account.id);
        });
        actions.register("search", this::openUniversalSearch);
        actions.register("security", () -> {
            if (account == null) return;
            if (notice != null) notice.dismiss();
            notice = WorkspaceDialogs.showSecurity(this, account, gate, this::lock, () -> {
                refreshSettingsHubIfVisible();
            }, () -> notice = null);
        });
        actions.register("skills", () -> {
            Account owner = account;
            panels.showSkills(() -> account == owner && !isFinishing(), this::stageDraft);
        });
        actions.register("stats", () -> panels.showStats(threads.sessionId(), threads.remoteSessionId()));
        actions.register("tools", () -> openThreadAction("tools"));
        actions.register("models", () -> openThreadAction("models"));
        actions.register("artifacts", () -> openThreadAction("artifacts"));
    }

    private void openThreadAction(String id) {
        String session = threads.sessionId();
        if (session == null) {
            message(getString(R.string.ws_choose_discussion_title),
                    getString(R.string.ws_choose_discussion_body));
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
        String widgetId = pendingPinnedWidget != null ? pendingPinnedWidget
                : (pendingOsEntry != null && pendingOsEntry.target() == OsEntryIntent.Target.WIDGET_ACTION
                ? pendingOsEntry.widgetId() : null);
        String widgetOwner = widgetId != null ? new HermesWidgetStore(this).ownerForWidget(widgetId) : null;
        String requested = delivery != null ? delivery.profileId
                : sharedText.requestedProfile() != null ? sharedText.requestedProfile()
                : sharedMedia.requestedProfile() != null ? sharedMedia.requestedProfile()
                : widgetOwner;
        setPairingLinkHandler(parsed -> {
            if (gate != null && account == null) gate.openAddWithPairing(parsed);
        });
        PairingLinkParser.ParsedInput incomingPairing = consumePendingPairing();
        if (incomingPairing != null) {
            gate.setPendingPairing(incomingPairing);
        }
        gate.start(requested, this::opened);
    }

    private void opened(Account unlocked) {
        clearGraceTimer();
        pairingLinkHandler = null;
        account = unlocked;
        photoPicker.unlocked(unlocked);
        cameraCapture.unlocked(unlocked);
        AppLanguage.refreshRuntime(this);
        HermesNotificationListener.bindProfile(this, unlocked);
        presenceEpoch = presenceTracker.bindProfile(unlocked.id, presenceEpoch + 1);
        locationTracker.syncWithPolicy(this, PresencePolicy.load(unlocked));
        AppCustomization savedCustom = AppCustomizationStore.forAccount(unlocked).current();
        if (!savedCustom.equals(AppCustomization.defaults())) {
            applyAppCustomization(unlocked, savedCustom, false);
        }
        HermesApi api = new HermesApi(account.endpoint, account.bearer,
                HermesApi.Limits.from(HermesConfig.load(this)));
        activeApi = api;
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
        featureRoutes = BrainStudioRoutes.register(new RouteRegistry(), this, brainHandlers());
        DeliveryRouter.registerRoutes(featureRoutes, this,
                () -> { if (account == unlocked && push != null) push.openInbox(account, deliveryActions(account)); },
                () -> { if (account == unlocked && push != null) push.openPolicy(account); });
        AmbientAccessPanel.registerRoute(featureRoutes, this,
                () -> { if (account == unlocked) navigate(AmbientAccessPanel.ROUTE_ID); });
        PresenceSettingsPanel.registerRoute(featureRoutes, this,
                () -> { if (account == unlocked) navigate(PresenceSettingsPanel.ROUTE_ID); });
        ConnectionDoctorPanel.registerRoute(featureRoutes, this,
                () -> { if (account == unlocked) navigate(ConnectionDoctorPanel.ROUTE_ID); });
        PermissionsPanel.registerRoute(featureRoutes, this,
                () -> { if (account == unlocked) navigate(PermissionsPanel.ROUTE_ID); });
        TemplatesPanel.registerRoute(featureRoutes, this,
                () -> { if (account == unlocked) navigate(TemplatesPanel.ROUTE_ID); });
        UpdatePanel.registerRoute(featureRoutes, this,
                () -> { if (account == unlocked) navigate(UpdatePanel.ROUTE_ID); });
        AppearanceSettingsPanel.registerRoute(featureRoutes, this,
                () -> { if (account == unlocked) navigate(AppearanceSettingsPanel.ROUTE_ID); });
        UpdateCheckCoordinator.checkIfDueAsync(this, state -> {
            if (account == unlocked && !isFinishing() && !isDestroyed()) {
                updateBadge = state;
                refreshSettingsHubIfVisible();
            }
        });
        setContentView(shell.root);
        displayDiscussion();
        files.unlock(account, api);
        OnboardingModels.PairingClaim freshClaim = gate.consumeFreshPairingClaim();
        boolean hasStartupOverride = freshClaim != null
                || delivery != null
                || pendingPinnedSurface != null
                || pendingPinnedWidget != null
                || pendingOsEntry != null
                || sharedText.hasPending()
                || sharedMedia.hasPending()
                || sharedText.requestedProfile() != null
                || sharedMedia.requestedProfile() != null;
        if (freshClaim != null && freshClaim.onboarding() != null) {
            activeProfileIsAdmin = freshClaim.onboarding().isAdmin();
            try { unlocked.write("is_admin_hint", activeProfileIsAdmin ? "1" : "0"); }
            catch (RuntimeException error) { Diagnostics.warning("profile", "admin_hint_write_failed", error); }
        } else {
            try { activeProfileIsAdmin = "1".equals(unlocked.read("is_admin_hint")); }
            catch (RuntimeException error) {
                Diagnostics.warning("profile", "admin_hint_read_failed", error);
                activeProfileIsAdmin = false;
            }
        }
        if (freshClaim != null && !FirstRunWizard.isCompleted(unlocked)) {
            closeFirstRunStep2();
            activeFirstRunStep2 = FirstRunWizard.showStep2(
                    this, unlocked, api, notifications,
                    () -> account == unlocked && !isFinishing() && !isDestroyed(),
                    new FirstRunWizard.Step2Callbacks() {
                        @Override public void onThemeSelected(String themeMode) {
                            if (account == unlocked) onThemeModeChanged(themeMode);
                        }
                        @Override public void onStageStarterDraft(String draftText) {
                            if (account == unlocked) stageDraft(draftText);
                        }
                        @Override public void onFinished() {
                            activeFirstRunStep2 = null;
                        }
                    });
        }
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
        if (!hasStartupOverride && savedCustom.startupTab != AppCustomization.StartupTab.CHATS
                && WorkspaceNavigation.DISCUSSION.equals(navigation.current())) {
            switchTab(startupDestination(savedCustom.startupTab));
        }
    }

    private static String startupDestination(AppCustomization.StartupTab tab) {
        if (tab == null) return WorkspaceNavigation.DISCUSSION;
        return switch (tab) {
            case NOTES_TASKS -> WorkspaceNavigation.NOTES_TASKS;
            case APPS -> WorkspaceNavigation.APPS;
            case CHATS -> WorkspaceNavigation.DISCUSSION;
        };
    }

    private void openSettingsHub() {
        if (navigation != null && WorkspaceNavigation.SETTINGS.equals(navigation.current())) {
            if (activeSettingsPanel != null) {
                activeSettingsPanel.scrollToTop();
                activeSettingsPanel.refresh();
            }
            return;
        }
        navigate(WorkspaceNavigation.SETTINGS);
    }

    private void refreshSettingsHubIfVisible() {
        if (account != null && activeSettingsPanel != null && navigation != null
                && WorkspaceNavigation.SETTINGS.equals(navigation.current())) {
            activeSettingsPanel.refresh();
        }
    }

    private void refreshActiveHubIfVisible() {
        refreshSettingsHubIfVisible();
        if (account == null || navigation == null) return;
        String current = navigation.current();
        if (WorkspaceNavigation.NOTES_TASKS.equals(current) && activeNotesTasksPanel != null) {
            activeNotesTasksPanel.refresh();
        } else if (WorkspaceNavigation.APPS.equals(current) && activeAppsPanel != null) {
            activeAppsPanel.refresh();
        }
    }

    private NotesTasksPanel.Callbacks notesTasksCallbacks(Account owner) {
        return new NotesTasksPanel.Callbacks() {
            private BooleanSupplier activeThreadCheck() {
                ThreadWorkspace origin = threads;
                String currentSession = origin == null ? null : origin.sessionId();
                return () -> origin != null && threads == origin && currentSession != null
                        && currentSession.equals(origin.sessionId());
            }

            @Override public void createNote() {
                if (account == owner && panels != null) {
                    panels.showCreateNote("", "", activeThreadCheck(), MainActivity.this::stageDraft, null);
                }
            }

            @Override public void openAllNotes() {
                if (account == owner && panels != null) {
                    panels.showNotes(activeThreadCheck(), MainActivity.this::stageDraft, null);
                }
            }

            @Override public void createReminder() {
                if (account == owner && panels != null) panels.showReminders();
            }

            @Override public void openAllReminders() {
                if (account == owner && panels != null) panels.showReminders();
            }

            @Override public void openReminder(String jobId) {
                if (account == owner && panels != null) panels.showReminder(jobId);
            }

            @Override public void openFullInbox() {
                if (account == owner && push != null) push.openInbox(owner, deliveryActions(owner));
            }

            @Override public void openDelivery(DeliveryItem item) {
                if (account == owner && push != null && item != null) {
                    push.openDelivery(owner, item, deliveryActions(owner));
                }
            }

            @Override public void openSharedWithMe() {
                if (account == owner && panels != null) {
                    panels.showSharedWithMe(MainActivity.this::stageDraft, null);
                }
            }
        };
    }

    private AppsLauncherPanel.StateProvider appsStateProvider(Account owner) {
        return new AppsLauncherPanel.StateProvider() {
            @Override public String voiceLocaleLabel() {
                return voice == null ? "" : getString(voice.locale().labelRes);
            }

            @Override public List<AppsLauncherModel.ScreenEntry> screens() {
                LiveUiPatch.StateManager manager = LiveUiPatch.StateManager.forAccount(
                        owner, ThemePalette.isNight(MainActivity.this));
                return AppsLauncherModel.mergeScreens(
                        ArtifactDesk.savedPageTitles(MainActivity.this, owner),
                        manager.tabs());
            }

            @Override public int templateCount() {
                return TemplateStore.forAccount(owner).allTemplates(MainActivity.this).size();
            }
        };
    }

    private AppsLauncherPanel.Callbacks appsCallbacks(Account owner) {
        return new AppsLauncherPanel.Callbacks() {
            @Override public void startVoiceChat() {
                if (account != owner || threads == null) return;
                displayDiscussion();
                if (threads.sessionId() != null) threads.startDictation();
                else threads.newThread(threads::startDictation);
            }

            @Override public void openVoiceOptions() {
                if (account == owner) onAction(SettingsHubPanel.ACTION_VOICE_OPTIONS);
            }

            @Override public void openScreen(String pageId) {
                if (account == owner) openWorkspace(pageId);
            }

            @Override public void createTool() {
                if (account == owner) onAction("create_instrument");
            }

            @Override public void openScreensGallery() {
                if (account == owner) onAction("pages");
            }

            @Override public void pinHomeWidget() {
                if (account == owner) onAction("pin_widget");
            }

            @Override public void restoreDefaults() {
                if (account == owner) onAction("restore");
            }

            @Override public void openTemplates() {
                if (account == owner) onAction(TemplatesPanel.ROUTE_ID);
            }
        };
    }

    private SettingsHubPanel.StateProvider settingsStateProvider(Account owner) {
        return new SettingsHubPanel.StateProvider() {
            @Override public String profileName() {
                return owner.label;
            }
            @Override public String serverEndpoint() {
                return owner.endpoint;
            }
            @Override public String lockModeLabel() {
                return getString(ProfileLockPolicy.mode(MainActivity.this, owner.id).labelRes);
            }
            @Override public String lockTimeoutLabel() {
                return getString(LockGracePolicy.window(MainActivity.this, owner.id).labelRes);
            }
            @Override public String securitySummary() {
                return WorkspaceDialogs.securitySummary(MainActivity.this, owner.id);
            }
            @Override public String themeModeLabel() {
                String mode = ThemePalette.load(owner, ThemePalette.isNight(MainActivity.this)).theme;
                return getString(SettingsHubModel.themeModeLabelRes(mode));
            }
            @Override public String appearanceSummary() {
                AppCustomization custom = AppCustomizationStore.forAccount(owner).current();
                return SettingsHubModel.formatAppearanceSummary(
                        themeModeLabel(),
                        getString(SettingsHubModel.paletteLabelRes(custom.palette)),
                        getString(SettingsHubModel.densityLabelRes(custom.density)));
            }
            @Override public String languageSummary() {
                AppLanguage.Mode mode = AppLanguage.readMode(MainActivity.this);
                if (mode == AppLanguage.Mode.AUTO) {
                    String resolved = getString(SettingsHubModel.resolvedLanguageNameRes(
                            AppLanguage.resolvedTag(MainActivity.this)));
                    return getString(R.string.settings_lang_auto, resolved);
                }
                return getString(SettingsHubModel.languageModeLabelRes(mode));
            }
            @Override public String voiceLocaleLabel() {
                return voice == null ? "" : getString(voice.locale().labelRes);
            }
            @Override public String updateBadge() {
                return UpdatePanel.badgeLabel(MainActivity.this, updateBadge);
            }
            @Override public String versionName() {
                return UpdatePanel.installedVersionName(MainActivity.this);
            }
        };
    }

    private boolean capturePinnedShortcutIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return false;
        String surfaceId = intent.getStringExtra(HomePinBridge.EXTRA_SURFACE_ID);
        if (HomePinBridge.isValidSurfaceId(surfaceId)) {
            pendingPinnedSurface = surfaceId;
            pendingPinnedWidget = null;
            pendingOsEntry = null;
            return true;
        }
        String widgetId = intent.getStringExtra(HomePinBridge.EXTRA_WIDGET_ID);
        if (HermesWidgetSpec.validWidgetId(widgetId)) {
            pendingPinnedWidget = widgetId;
            pendingPinnedSurface = null;
            pendingOsEntry = null;
            return true;
        }
        return false;
    }

    private void dispatchPendingOsEntry() {
        if (account == null || threads == null) return;
        if (pendingPinnedSurface != null) {
            String surface = pendingPinnedSurface;
            pendingPinnedSurface = null;
            setIntent(new Intent(this, MainActivity.class));
            openWorkspace(surface);
            return;
        }
        if (pendingPinnedWidget != null) {
            String wid = pendingPinnedWidget;
            pendingPinnedWidget = null;
            setIntent(new Intent(this, MainActivity.class));
            String draft = new HermesWidgetStore(this).draftForAction(wid, 0, account.id);
            displayDiscussion();
            if (draft != null && !draft.isEmpty()) stageDraft(draft);
            else if (threads.sessionId() != null) threads.focusComposer();
            else threads.newThread(threads::focusComposer);
            return;
        }
        OsEntryIntent.Request request = pendingOsEntry;
        if (request == null) return;
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
            case ATELIER -> switchTab(WorkspaceNavigation.APPS);
            case ACTIVITY -> switchTab(WorkspaceNavigation.NOTES_TASKS);
            case WIDGET_ACTION -> {
                String draft = new HermesWidgetStore(this)
                        .draftForAction(request.widgetId(), request.itemIndex(), account.id);
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
                        message(getString(R.string.ws_notification_unavailable_title),
                                getString(R.string.ws_notification_unavailable_body));
                    }
                });
            }
        });
    }

    @Override public void openPushEvent(Account owner, JSONObject event) {
        if (owner != account || isFinishing()) return;
        long request = ++noticeRequest;
        String kind = event.optString("kind");
        if (DeliveryItem.Kind.fromWire(kind) != null) {
            threads.cancelNotice();
            dismissOverlays();
            if (MobilePush.needsDeliveryFetch(event)) {
                String deliveryId = event.optString("event_id", "");
                if (deliveryId.isEmpty()) deliveryId = event.optString("delivery_id", "");
                final String targetId = deliveryId;
                final HermesApi client = activeApi;
                if (client == null) return;
                notifications.execute(() -> {
                    try {
                        DeliveryItem fetched = new org.llm4people.hermes.push.DeliveryInbox().fetchOne(
                                owner, client, targetId);
                        runOnUiThread(() -> {
                            if (owner == account && request == noticeRequest && !isFinishing()) {
                                push.openDelivery(owner, fetched, deliveryActions(owner));
                            }
                        });
                    } catch (IOException | RuntimeException failure) {
                        Diagnostics.warning("push", "delivery_fetch_failed", failure);
                        runOnUiThread(() -> {
                            if (owner == account && request == noticeRequest && !isFinishing()) {
                                push.openInbox(owner, deliveryActions(owner));
                            }
                        });
                    }
                });
                return;
            }
            try {
                DeliveryItem item = DeliveryItem.fromJson(event);
                push.openDelivery(owner, item, deliveryActions(owner));
            } catch (IllegalArgumentException malformed) {
                Diagnostics.warning("push", "delivery_open_malformed");
                push.openInbox(owner, deliveryActions(owner));
            }
            return;
        }
        if ("reminder".equals(kind)) {
            threads.cancelNotice();
            dismissOverlays();
            if (!event.isNull("job_id") && !event.optString("job_id").isEmpty())
                panels.showReminder(event.optString("job_id"));
            else panels.showReminders();
        } else if (!event.isNull("session_id") && !event.optString("session_id").isEmpty()) {
            threads.openNotice(event.optString("session_id"), event.optString("run_id"), kind);
        }
    }

    private DeliveryRouter.Actions deliveryActions(Account owner) {
        return new DeliveryRouter.Actions() {
            @Override public void openDiscussion(String sessionId, String runId) {
                if (owner != account || threads == null || sessionId == null || sessionId.isEmpty()) return;
                dismissOverlays();
                threads.openNotice(sessionId, runId == null ? "" : runId, "notice");
            }

            @Override public void openPublishedFile(String sessionId, String fileId, String filename) {
                if (owner != account || files == null) return;
                String targetSession = (sessionId != null && !sessionId.isEmpty())
                        ? sessionId : (threads == null ? null : threads.remoteSessionId());
                if (targetSession != null) {
                    dismissOverlays();
                    files.show(targetSession);
                }
            }

            @Override public void openReminder(String jobId, String sessionId) {
                if (owner != account || panels == null) return;
                dismissOverlays();
                if (jobId != null && !jobId.isEmpty()) panels.showReminder(jobId);
                else panels.showReminders();
            }

            @Override public void previewUiPatch(DeliveryItem item) {
                if (owner != account || threads == null || item == null) return;
                JSONObject payload = LiveUiDeliveryDefaults.apply(item.payloadObject(), item.id, item.title);
                dismissOverlays();
                displayDiscussion();
                if (item.sessionId != null && !item.sessionId.isEmpty()
                        && !item.sessionId.equals(threads.sessionId())) {
                    threads.openWithLiveUiPatch(item.sessionId, payload);
                } else if (threads.sessionId() != null) {
                    threads.onLiveUiPatch(payload);
                } else {
                    previewStandaloneUiPatch(owner, payload);
                }
            }

            @Override public void refreshWidget(DeliveryItem item) {
                if (owner != account || item == null) return;
                boolean updated = HomePinBridge.applyDeliveryWidgetUpdate(
                        MainActivity.this, item.payloadObject(), owner.id);
                Toast.makeText(MainActivity.this,
                        updated ? R.string.widget_updated_toast : R.string.widget_pin_invalid,
                        Toast.LENGTH_SHORT).show();
            }

            @Override public void openPushPolicy() {
                if (owner == account && push != null) push.openPolicy(owner);
            }
        };
    }

    private void previewStandaloneUiPatch(Account owner, JSONObject payload) {
        try {
            LiveUiPatch patch = LiveUiPatch.parse(payload, ThemePalette.isNight(this));
            StringBuilder details = new StringBuilder(patch.summary);
            for (String line : patch.frenchHighlights()) {
                details.append("\n• ").append(line);
            }
            if (notice != null) notice.dismiss();
            notice = Ui.secureDialog(new AlertDialog.Builder(this)
                    .setTitle(R.string.live_ui_delivery_title)
                    .setMessage(details.toString())
                    .setNegativeButton(R.string.ws_close, null)
                    .setPositiveButton(R.string.live_ui_delivery_apply, (d, w) -> {
                        if (owner != account) return;
                        try {
                            LiveUiPatch.StateManager manager = LiveUiPatch.StateManager.forAccount(
                                    owner, ThemePalette.isNight(this));
                            manager.apply(patch);
                            for (LiveUiPatch.Op op : patch.ops) {
                                if (op instanceof LiveUiPatch.Op.UpsertWidget uw) {
                                    HomePinBridge.syncWidget(this, uw.widget(), owner.id);
                                } else if (op instanceof LiveUiPatch.Op.RemoveWidget rw) {
                                    HomePinBridge.removeWidget(this, rw.widgetId(), owner.id);
                                }
                            }
                            onLayoutChanged();
                        } catch (IOException | IllegalStateException e) {
                            Diagnostics.warning("live_ui", "delivery_apply_failed");
                            message(getString(R.string.live_ui_delivery_failed_title),
                                    getString(R.string.live_ui_delivery_failed_body));
                        }
                    }));
        } catch (IOException rejected) {
            Diagnostics.warning("live_ui", "delivery_patch_rejected");
            message(getString(R.string.live_ui_delivery_invalid_title),
                    getString(R.string.live_ui_delivery_invalid_body));
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
        if (activeAmbientPanel != null) {
            activeAmbientPanel.close();
            activeAmbientPanel = null;
        }
        if (activePresencePanel != null) activePresencePanel.dismissDialogs();
        if (activeUpdatePanel != null) activeUpdatePanel.dismissDialogs();
        if (activeDoctorPanel != null) activeDoctorPanel.dismissDialogs();
        if (activeTemplatesPanel != null) activeTemplatesPanel.dismissDialogs();
        if (activeAppearancePanel != null) activeAppearancePanel.dismissDialogs();
        if (activePalette != null) activePalette.dismiss();
        activePalette = null;
        if (activeSearchSheet != null) activeSearchSheet.close();
        activeSearchSheet = null;
        if (activeProfileHub != null) activeProfileHub.close();
        activeProfileHub = null;
        if (activeAdminConsole != null) activeAdminConsole.close();
        activeAdminConsole = null;
        closeFirstRunStep2();
        if (files != null) files.dismiss();
        if (threadActions != null) threadActions.dismiss();
        if (!keepThreadEditor) {
            if (voice != null) voice.cancel();
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
        if (capturePairingDeepLink(intent)) {
            switch (resolvePairingDeepLinkRoute(
                    pendingPairing != null, account != null, pairingLinkHandler != null)) {
                case DELIVER_TO_LOCKED_HANDLER -> pairingLinkHandler.accept(consumePendingPairing());
                case LOCK_AND_UNLOCK_FOR_CONFIRMATION -> {
                    // Lock/switch cleanly to the setup gate so the user explicitly confirms before saving a new profile.
                    lock();
                    unlock();
                }
                case OPEN_SETUP_GATE -> unlock();
                case NONE -> {}
            }
            return;
        }
        if (sharedText.receive(intent) || sharedMedia.receive(intent)) {
            setIntent(new Intent(this, MainActivity.class));
            delivery = null;
            pendingOsEntry = null;
            pendingPinnedSurface = null;
            pendingPinnedWidget = null;
            clearWorkspace();
            unlock();
            return;
        }
        OsEntryIntent.Request entry = OsEntryIntent.parseRequest(intent);
        if (entry != null) {
            setIntent(intent);
            pendingOsEntry = entry;
            pendingPinnedSurface = null;
            pendingPinnedWidget = null;
            openWarmEntry(entry.target() == OsEntryIntent.Target.WIDGET_ACTION ? entry.widgetId() : null);
            return;
        }
        if (capturePinnedShortcutIntent(intent)) {
            setIntent(intent);
            openWarmEntry(pendingPinnedWidget);
            return;
        }
        MobilePush.Target incoming = MobilePush.target(this, intent);
        if (incoming == null) return;
        sharedText.leave();
        sharedMedia.leave();
        pendingOsEntry = null;
        pendingPinnedSurface = null;
        pendingPinnedWidget = null;
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

    /** A warm widget tap owned by another profile switches profiles like a push does, instead of being dropped. */
    private void openWarmEntry(String widgetId) {
        if (account == null || threads == null) {
            if (widgetId != null && gate != null) unlock();
            return;
        }
        String owner = widgetId == null ? null : new HermesWidgetStore(this).ownerForWidget(widgetId);
        if (owner != null && !owner.equals(account.id)) {
            lock();
            unlock();
        } else {
            dispatchPendingOsEntry();
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

            @Override public void openMemory() { panels.showMemory(null); }

            @Override public void openNotes() {
                panels.showNotes(activeThreadCheck(), MainActivity.this::stageDraft, null);
            }

            @Override public void openSharedWithMe() {
                panels.showSharedWithMe(MainActivity.this::stageDraft, null);
            }

            @Override public void openGlobalSearch() {
                panels.showGlobalSearch(id -> { displayDiscussion(); threads.open(id); }, null);
            }

            @Override public void openSkillStudio() {
                panels.showSkillStudio(activeThreadCheck(), MainActivity.this::stageDraft, null);
            }

            @Override public void openWorkspaceFiles() {
                String session = activeRemoteSessionId();
                if (session == null) {
                    message(getString(R.string.ws_choose_discussion_title),
                            getString(R.string.ws_choose_discussion_files_body));
                    return;
                }
                panels.showWorkspaceFiles(session, activeThreadCheck(), MainActivity.this::stageDraft, null);
            }

            @Override public void openProfileDefaults() { panels.showProfileDefaults(null); }

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

    private AmbientAccessPanel.ActionCallbacks ambientCallbacks() {
        return new AmbientAccessPanel.ActionCallbacks() {
            @Override public void stageDraft(String text) {
                MainActivity.this.stageDraft(text);
            }

            @Override public void createNote(String title, String body, String fallbackDraft) {
                if (panels == null) {
                    stageDraft(fallbackDraft);
                    return;
                }
                ThreadWorkspace origin = threads;
                String currentSession = origin == null ? null : origin.sessionId();
                BooleanSupplier activeCheck = () -> origin != null && threads == origin
                        && currentSession != null && currentSession.equals(origin.sessionId());
                panels.showCreateNote(title, body, activeCheck, MainActivity.this::stageDraft, null);
            }

            @Override public void addToCalendar(String title, String description, String fallbackDraft) {
                Intent insert = new Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                        .putExtra(CalendarContract.Events.TITLE, title == null ? "" : title)
                        .putExtra(CalendarContract.Events.DESCRIPTION, description == null ? "" : description);
                try {
                    startActivity(insert);
                    Diagnostics.event("ambient", "calendar_intent_opened");
                } catch (ActivityNotFoundException | SecurityException unavailable) {
                    Diagnostics.warning("ambient", "calendar_intent_unavailable");
                    stageDraft(fallbackDraft);
                }
            }
        };
    }

    private void openCommandPalette() {
        Account owner = account;
        if (owner == null || threads == null) return;
        if (activePalette != null) activePalette.dismiss();
        CommandRegistry registry = new CommandRegistry(
                CommandRegistry.defaultNativeCommands(this, activeProfileIsAdmin, this::onAction));
        registry.addAll(CommandRegistry.fromTemplates(
                TemplateStore.forAccount(owner).allTemplates(this), this::stageDraft));
        List<CommandRegistry.RecentThread> recent = new ArrayList<>();
        for (ThreadWorkspace.ThreadSummary summary : threads.recentThreads()) {
            recent.add(new CommandRegistry.RecentThread(summary.id(), summary.title(), ""));
        }
        registry.addAll(CommandRegistry.fromRecentThreads(recent, sessionId -> {
            if (account != owner || threads == null) return;
            displayDiscussion();
            threads.open(sessionId);
        }));
        CommandPalette palette = new CommandPalette(this, registry,
                () -> account == owner && !isFinishing() && !isDestroyed());
        activePalette = palette;
        palette.show(() -> {
            if (activePalette == palette) activePalette = null;
        });
    }

    private void openUniversalSearch() {
        Account owner = account;
        if (owner == null || threads == null) return;
        if (activeSearchSheet != null) activeSearchSheet.close();
        List<UniversalSearchEntries.ChatRef> recentChats = new ArrayList<>();
        for (ThreadWorkspace.ThreadSummary item : threads.recentThreads()) {
            recentChats.add(new UniversalSearchEntries.ChatRef(item.id(), item.title(), ""));
        }
        List<SettingsHubModel.SectionSpec> settingsSections =
                SettingsHubPanel.buildSections(this, settingsStateProvider(owner));
        List<UniversalSearchEntries.ToolRef> extra = new ArrayList<>();
        extra.add(new UniversalSearchEntries.ToolRef(
                SettingsHubPanel.ROUTE_ID, getString(R.string.nav_settings),
                getString(R.string.settings_subtitle), "", UniversalSearchModel.Category.SETTINGS));
        extra.add(new UniversalSearchEntries.ToolRef(
                "command_palette", getString(R.string.palette_title),
                "", "", UniversalSearchModel.Category.TOOLS));
        extra.add(new UniversalSearchEntries.ToolRef(
                "pages", getString(R.string.nav_apps) + " · " + getString(R.string.ws_my_screens),
                "", "", UniversalSearchModel.Category.TOOLS));
        extra.add(new UniversalSearchEntries.ToolRef(
                "reminders", getString(R.string.activity_reminders_title),
                getString(R.string.activity_reminders_hint), "", UniversalSearchModel.Category.TOOLS));
        extra.add(new UniversalSearchEntries.ToolRef(
                "deliveries", getString(R.string.activity_deliveries_title),
                getString(R.string.activity_deliveries_hint), "", UniversalSearchModel.Category.TOOLS));
        extra.add(new UniversalSearchEntries.ToolRef(
                BrainStudioRoutes.ROUTE_NOTES, getString(R.string.brain_notes_title),
                getString(R.string.brain_notes_subtitle), "", UniversalSearchModel.Category.TOOLS));
        extra.add(new UniversalSearchEntries.ToolRef(
                "profile_hub", getString(R.string.settings_switch_profile),
                "", "", UniversalSearchModel.Category.SETTINGS));
        if (activeProfileIsAdmin) {
            extra.add(new UniversalSearchEntries.ToolRef(
                    "admin_console", getString(R.string.admin_console_title),
                    "", "", UniversalSearchModel.Category.SETTINGS));
        }
        List<UniversalSearchModel.LocalEntry> entries =
                UniversalSearchEntries.build(recentChats, settingsSections, extra);
        UniversalSearchSheet[] holder = new UniversalSearchSheet[1];
        holder[0] = UniversalSearchSheet.show(this, activeApi, entries, new UniversalSearchSheet.Callbacks() {
            @Override public void openThread(String id, String title) {
                if (account != owner || threads == null) return;
                displayDiscussion();
                threads.openSummary(id, title);
            }
            @Override public void openNote(String noteId) {
                if (account != owner || panels == null) return;
                panels.showNotes(() -> account == owner && threads != null && threads.sessionId() != null,
                        MainActivity.this::stageDraft, null);
            }
            @Override public void openSharedItem(UniversalSearchModel.Result item) {
                if (account != owner) return;
                switch (UniversalSearchEntries.routeSharedItem(item)) {
                    case OPEN_SHARED_THREADS -> {
                        if (threads != null) {
                            displayDiscussion();
                            threads.openSharedInbox();
                        }
                    }
                    case OPEN_SHARED_NOTES -> {
                        if (panels != null) {
                            panels.showSharedNotes(() -> account == owner && threads != null && threads.sessionId() != null,
                                    MainActivity.this::stageDraft, null);
                        }
                    }
                    case OPEN_SHARED_HUB -> onAction(BrainStudioRoutes.ROUTE_SHARED_WITH_ME);
                }
            }
            @Override public void runAction(String actionId) {
                if (account != owner || actionId == null || actionId.isEmpty()) return;
                onAction(actionId);
            }
            @Override public void onDismissed() {
                if (activeSearchSheet == holder[0]) activeSearchSheet = null;
            }
        });
        activeSearchSheet = holder[0];
    }

    private void openProfileHub(boolean startPairDevice, boolean startInvite) {
        Account owner = account;
        if (owner == null) return;
        if (activeProfileHub != null) activeProfileHub.close();
        int activeRuns = threads == null ? 0 : threads.activeRunCount();
        ProfileHubSheet hub = new ProfileHubSheet(
                this,
                owner,
                activeRuns,
                activeProfileIsAdmin,
                () -> account == owner && !isFinishing() && !isDestroyed(),
                new ProfileHubSheet.Callbacks() {
                    @Override public void onSwitchProfile(String profileId) {
                        if (account != owner) return;
                        clearWorkspace();
                        gate.start(profileId, MainActivity.this::opened);
                    }
                    @Override public void onAddProfile() {
                        if (account != owner) return;
                        lock();
                        gate.openAddWithPairing(null);
                    }
                    @Override public void onScanQrCode() {
                        if (account != owner) return;
                        QrScannerActivity.launch(MainActivity.this);
                    }
                    @Override public void onOpenAdminConsole() {
                        if (account == owner) openAdminConsole();
                    }
                    @Override public void onAdminStateResolved(boolean resolvedAdmin) {
                        if (account == owner) {
                            activeProfileIsAdmin = resolvedAdmin;
                            try { owner.write("is_admin_hint", resolvedAdmin ? "1" : "0"); }
                            catch (RuntimeException error) { Diagnostics.warning("profile", "admin_hint_write_failed", error); }
                        }
                    }
                });
        activeProfileHub = hub;
        if (startPairDevice) {
            hub.triggerPairDevice();
        } else if (startInvite) {
            hub.triggerInvitePerson();
        } else {
            hub.show();
        }
    }

    private void openAdminConsole() {
        Account owner = account;
        if (owner == null) return;
        if (activeAdminConsole != null) activeAdminConsole.close();
        AdminConsoleSheet console = new AdminConsoleSheet(
                this,
                owner,
                activeProfileIsAdmin,
                () -> account == owner && !isFinishing() && !isDestroyed());
        activeAdminConsole = console;
        if (!console.show()) {
            console.close();
            if (activeAdminConsole == console) activeAdminConsole = null;
        }
    }

    private void closeFirstRunStep2() {
        if (activeFirstRunStep2 != null) {
            try { activeFirstRunStep2.close(); } catch (Exception ignored) {}
            activeFirstRunStep2 = null;
        }
    }

    private ConnectionDoctorPanel.FixHandlers doctorFixHandlers(Account owner) {
        return new ConnectionDoctorPanel.FixHandlers() {
            @Override public void openPushSettings() {
                if (account == owner) onAction("notifications");
            }
            @Override public void openAppUpdates() {
                if (account == owner) onAction(UpdatePanel.ROUTE_ID);
            }
            @Override public void openAmbientAccess() {
                if (account == owner) onAction(AmbientAccessPanel.ROUTE_ID);
            }
            @Override public void openLanguageSettings() {
                if (account == owner) onAction("language");
            }
            @Override public void openPermissions() {
                if (account == owner) onAction(PermissionsPanel.ROUTE_ID);
            }
        };
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (account != null && threads != null && event != null
                && event.getAction() == KeyEvent.ACTION_DOWN
                && CommandRegistry.isPaletteShortcut(event.getKeyCode(),
                event.isCtrlPressed(), event.isMetaPressed())) {
            openCommandPalette();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void message(String title, String body) {
        if (notice != null) notice.dismiss();
        notice = Ui.secureDialog(new AlertDialog.Builder(this).setTitle(title).setMessage(body)
                .setPositiveButton(R.string.ws_close, null));
    }

    @Override public void onLayoutChanged() {
        if (shell == null) return;
        if (threads != null) threads.onLayoutChanged();
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
        if (threads == null || account == null || text == null || text.trim().isEmpty()) return;
        dismissOverlays();
        final Account owner = account;
        final ThreadWorkspace origin = threads;
        Runnable edit = () -> {
            if (account != owner || threads != origin || isFinishing() || isDestroyed()) return;
            if (!origin.composerVisible()) navigate(WorkspaceNavigation.COMPOSER);
            origin.stageDraft(text);
        };
        if (origin.sessionId() != null) { edit.run(); return; }
        AlertDialog dialog = Ui.secureDialog(new AlertDialog.Builder(this).setTitle(R.string.ws_create_discussion_title)
                .setMessage(R.string.ws_create_discussion_body)
                .setNegativeButton(R.string.ws_cancel, null)
                .setPositiveButton(R.string.ws_create_draft, (d, w) -> {
                    if (account != owner || threads != origin || isFinishing() || isDestroyed()) return;
                    navigate(WorkspaceNavigation.COMPOSER);
                    origin.newThread(() -> {
                        if (account == owner && threads == origin && !isFinishing() && !isDestroyed()) {
                            origin.stageDraft(text);
                        }
                    });
                }));
        notice = dialog;
        dialog.setOnDismissListener(d -> {
            if (notice == d) notice = null;
        });
    }

    private void leavingSurface() {
        shell.dismissMenu();
        voice.reader().stop();
        if (activeAmbientPanel != null) {
            activeAmbientPanel.close();
            activeAmbientPanel = null;
        }
        if (activePresencePanel != null) {
            activePresencePanel.close();
            activePresencePanel = null;
        }
        if (activeUpdatePanel != null) {
            activeUpdatePanel.close();
            activeUpdatePanel = null;
        }
        if (activeDoctorPanel != null) {
            activeDoctorPanel.close();
            activeDoctorPanel = null;
        }
        activePermissionsPanel = null;
        if (activeTemplatesPanel != null) {
            activeTemplatesPanel.close();
            activeTemplatesPanel = null;
        }
        if (activeNotesTasksPanel != null) {
            activeNotesTasksPanel.close();
            activeNotesTasksPanel = null;
        }
        activeAppsPanel = null;
        activeSettingsPanel = null;
        if (activeAppearancePanel != null) {
            activeAppearancePanel.dismissDialogs();
            activeAppearancePanel = null;
        }
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
        shell.show(content, composer ? getString(R.string.ws_original_discussion) : null);
        ArtifactDesk.refreshBindings(shell.content(), activeHost);
        if (editing) threads.focusComposer();
    }

    private void render(boolean force) {
        String route = navigation.current();
        customPages.keySet().removeIf(id -> !navigation.contains(id));
        shell.setActiveRoute(route);
        shellRoutes.render(route, force);
        presenceTracker.updateScreen(presenceEpoch, route, threads == null ? "" : threads.sessionId());
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
        presenceTracker.updateActiveRunCount(presenceEpoch, threads == null ? 0 : threads.activeRunCount());
        LiveRunNotification.update(this, snapshot);
    }

    @Override public JSONObject appPresenceContext() {
        Account owner = account;
        if (owner == null) return null;
        PresencePolicy policy = PresencePolicy.load(owner);
        if (!policy.anySharingEnabled()) return null;
        long now = System.currentTimeMillis();
        return presenceTracker.toWireContext(policy, locationTracker.currentSnapshot(policy, now), now);
    }

    @Override public DeepAndroidActions deepAndroidActions() {
        Account owner = account;
        if (owner == null) return null;
        DeepAndroidActions.HostBridge osBridge = DeepAndroidActions.androidOsBridge(
                this,
                owner.id,
                (targetKind, targetId) -> navigateFromDeepAction(owner, targetKind, targetId),
                (title, category, draftText) -> {
                    if (account != owner) return false;
                    try {
                        TemplateStore.forAccount(owner).saveCustom(null, title, category, draftText);
                        return true;
                    } catch (IOException | RuntimeException failed) {
                        Diagnostics.warning("deep_action", "template_create_failed");
                        return false;
                    }
                });
        DeepAndroidActions.HostBridge bridge = new DeepAndroidActions.HostBridge() {
            @Override public boolean navigate(String targetKind, String targetId) {
                return osBridge.navigate(targetKind, targetId);
            }
            @Override public void onCustomizationChanged(AppCustomization updated) {
                if (account == owner) applyAppCustomization(owner, updated, true);
            }
            @Override public boolean applyThemeMode(String themeMode) {
                return applyThemeModeOverride(owner, themeMode);
            }
            @Override public boolean createTemplate(String title, String category, String draftText) {
                return osBridge.createTemplate(title, category, draftText);
            }
            @Override public boolean setTimer(int durationSeconds, String label) {
                return osBridge.setTimer(durationSeconds, label);
            }
            @Override public boolean openShareSheet(String text, String subject) {
                return osBridge.openShareSheet(text, subject);
            }
            @Override public boolean openAppOrLink(String packageName, String deepLinkUrl) {
                return osBridge.openAppOrLink(packageName, deepLinkUrl);
            }
            @Override public boolean syncShortcutsAndWidgets(
                    String widgetTitle, String widgetSubtitle, boolean homeScreenSafe) {
                return osBridge.syncShortcutsAndWidgets(widgetTitle, widgetSubtitle, homeScreenSafe);
            }
            @Override public boolean postLocalNudge(String title, String body) {
                return osBridge.postLocalNudge(title, body);
            }
        };
        return new DeepAndroidActions(
                presenceTracker,
                locationTracker,
                AppCustomizationStore.forAccount(owner),
                nudgeRateLimiter,
                bridge);
    }

    @Override public void onCustomizationChanged(AppCustomization customization) {
        Account owner = account;
        if (owner != null && customization != null) {
            applyAppCustomization(owner, customization, true);
        }
    }

    @Override public void onThemeModeChanged(String themeMode) {
        Account owner = account;
        if (owner != null && themeMode != null) {
            applyThemeModeOverride(owner, themeMode);
        }
    }

    private void applyAppCustomization(Account owner, AppCustomization custom, boolean refreshLayout) {
        if (owner == null || custom == null) return;
        try {
            boolean night = ThemePalette.isNight(this);
            ThemePalette.OverrideSpec existing = ThemePalette.load(owner, night);
            ThemePalette.OverrideSpec spec = custom.toThemeOverride(existing, night);
            ThemePalette.save(owner, spec);
            if (refreshLayout && account == owner) {
                onLayoutChanged();
            }
        } catch (IOException | RuntimeException failed) {
            Diagnostics.warning("customization", "live_theme_sync_failed");
        }
    }

    private boolean applyThemeModeOverride(Account owner, String themeMode) {
        if (owner == null || account != owner || themeMode == null) return false;
        String safeMode = themeMode.trim().toLowerCase(java.util.Locale.ROOT);
        if (!ThemePalette.THEMES.contains(safeMode)) return false;
        try {
            boolean night = ThemePalette.isNight(this);
            ThemePalette.OverrideSpec current = ThemePalette.load(owner, night);
            AppCustomization saved = AppCustomizationStore.forAccount(owner).current();
            ThemePalette.OverrideSpec updated = saved.adaptOverrideForThemeMode(current, safeMode, night);
            ThemePalette.save(owner, updated);
            if (!AppThemeMode.applyAndRecreateIfNeeded(this, safeMode)) {
                onLayoutChanged();
            }
            return true;
        } catch (IOException | RuntimeException failed) {
            Diagnostics.warning("customization", "theme_mode_sync_failed");
            return false;
        }
    }

    private boolean navigateFromDeepAction(Account owner, String targetKind, String targetId) {
        if (account != owner || isFinishing() || isDestroyed()) return false;
        DeepAndroidActions.NavigateDestination dest = DeepAndroidActions.resolveNavigateDestination(targetKind);
        if (dest == null) return false;
        String id = targetId == null ? "" : targetId.trim();
        return switch (dest) {
            case DISCUSSIONS -> {
                switchTab(WorkspaceNavigation.DISCUSSION);
                yield true;
            }
            case APPS -> {
                switchTab(WorkspaceNavigation.APPS);
                yield true;
            }
            case NOTES_TASKS -> {
                switchTab(WorkspaceNavigation.NOTES_TASKS);
                yield true;
            }
            case SETTINGS -> {
                navigate(WorkspaceNavigation.SETTINGS);
                yield true;
            }
            case APPEARANCE -> {
                navigate(AppearanceSettingsPanel.ROUTE_ID);
                yield true;
            }
            case COMMAND_PALETTE -> {
                openCommandPalette();
                yield true;
            }
            case TEMPLATES -> {
                navigate(TemplatesPanel.ROUTE_ID);
                yield true;
            }
            case THREAD -> {
                if (threads == null) yield false;
                displayDiscussion();
                if (!id.isEmpty()) threads.open(id);
                yield true;
            }
            case AMBIENT -> {
                navigate(AmbientAccessPanel.ROUTE_ID);
                yield true;
            }
            case CONNECTION_DOCTOR -> {
                navigate(ConnectionDoctorPanel.ROUTE_ID);
                yield true;
            }
            case PERMISSIONS -> {
                navigate(PermissionsPanel.ROUTE_ID);
                yield true;
            }
            case PRESENCE -> {
                navigate(PresenceSettingsPanel.ROUTE_ID);
                yield true;
            }
            case UPDATES -> {
                navigate(UpdatePanel.ROUTE_ID);
                yield true;
            }
            case MEMORY -> {
                if (panels == null) yield false;
                brainHandlers().openMemory();
                yield true;
            }
            case NOTES -> {
                if (panels == null) yield false;
                brainHandlers().openNotes();
                yield true;
            }
            case SHARED_WITH_ME -> {
                if (panels == null) yield false;
                brainHandlers().openSharedWithMe();
                yield true;
            }
            case SEARCH -> {
                openUniversalSearch();
                yield true;
            }
            case SKILLS -> {
                if (panels == null) yield false;
                brainHandlers().openSkillStudio();
                yield true;
            }
            case WORKSPACE_FILES -> {
                if (panels == null) yield false;
                brainHandlers().openWorkspaceFiles();
                yield true;
            }
            case PROFILE_DEFAULTS -> {
                if (panels == null) yield false;
                brainHandlers().openProfileDefaults();
                yield true;
            }
            case TAB, PANEL -> !id.isEmpty() && navigateFromDeepAction(owner, id, "");
        };
    }

    @Override public void discussionPanes(boolean canToggle, boolean listVisible) {
        if (shell != null) shell.discussionPanes(canToggle, listVisible);
    }

    @Override public void selected(String sessionId, String title) {
        if (shell == null) return;
        String threadTitle = sessionId == null ? null : title == null || title.trim().isEmpty()
                ? getString(R.string.ws_discussion) : title;
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
        if (code == QrScannerActivity.REQUEST_CODE) {
            PairingLinkParser.ParsedInput scanned = QrScannerActivity.extractScannedPairing(result, data);
            if (scanned != null && gate != null) {
                if (account != null) lock();
                gate.openAddWithPairing(scanned);
            }
            return;
        }
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
        PermissionCenter.recordCompletedPrompts(this, permissions, results);
        if (PermissionCenter.isManagedRequestCode(code)) {
            if (activePermissionsPanel != null) {
                activePermissionsPanel.onPermissionResult(code, permissions, results);
            } else {
                refreshSettingsHubIfVisible();
            }
            return;
        }
        if (code == AmbientAccessPanel.REQUEST_READ_CALENDAR || code == AmbientAccessPanel.REQUEST_READ_CONTACTS) {
            if (activeAmbientPanel != null) activeAmbientPanel.onPermissionResult(code, permissions, results);
            return;
        }
        if (code == REQUEST_PRESENCE_NOTIFICATIONS) {
            boolean granted = PermissionCenter.isCompletedResult(results)
                    && results[0] == PackageManager.PERMISSION_GRANTED;
            if (account != null) {
                if (activePresencePanel != null) {
                    activePresencePanel.onNotificationPermissionResult(granted);
                } else if (granted) {
                    PresencePolicy current = PresencePolicy.load(account);
                    PresencePolicy updated = current.withProactiveNudges(true);
                    if (!updated.equals(current)) PresencePolicy.save(account, updated);
                }
            }
            return;
        }
        if (code == REQUEST_PRESENCE_LOCATION || code == REQUEST_PRESENCE_FINE_LOCATION) {
            boolean granted = false;
            if (results != null) {
                for (int r : results) {
                    if (r == PackageManager.PERMISSION_GRANTED) {
                        granted = true;
                        break;
                    }
                }
            }
            if (account != null) {
                boolean fine = code == REQUEST_PRESENCE_FINE_LOCATION;
                if (activePresencePanel != null) {
                    activePresencePanel.onLocationPermissionResult(fine, granted);
                } else {
                    PresencePolicy current = PresencePolicy.load(account);
                    PresencePolicy updated = current;
                    if (granted) {
                        updated = fine
                                ? current.withContinuousLocation(true).withPreciseLocation(true)
                                : current.withContinuousLocation(true);
                        if (!updated.equals(current)) PresencePolicy.save(account, updated);
                    }
                    locationTracker.syncWithPolicy(this, updated);
                }
            }
            return;
        }
        if (threads != null && threads.nearbyPermission(code, permissions, results)) return;
        if (!voice.permissionResult(code, permissions, results)) push.onPermissionResult(code, results);
    }

    private void clearGraceTimer() {
        main.removeCallbacks(graceExpiry);
        backgroundedAtElapsedMs = -1L;
    }

    private void clearWorkspace() {
        clearGraceTimer();
        clearInternalTransition();
        MemoryPressure.clearAll();
        nudgeRateLimiter.reset();
        presenceTracker.clearOnLock();
        locationTracker.stopAndWipe();
        HermesNotificationListener.clearSession();
        if (activeAmbientPanel != null) activeAmbientPanel.close();
        activeAmbientPanel = null;
        if (activePresencePanel != null) activePresencePanel.close();
        activePresencePanel = null;
        if (activeUpdatePanel != null) activeUpdatePanel.close();
        activeUpdatePanel = null;
        if (activeDoctorPanel != null) activeDoctorPanel.close();
        activeDoctorPanel = null;
        activePermissionsPanel = null;
        if (activeTemplatesPanel != null) activeTemplatesPanel.close();
        activeTemplatesPanel = null;
        if (activeNotesTasksPanel != null) activeNotesTasksPanel.close();
        activeNotesTasksPanel = null;
        activeAppsPanel = null;
        activeSettingsPanel = null;
        if (activeAppearancePanel != null) activeAppearancePanel.dismissDialogs();
        activeAppearancePanel = null;
        if (activePalette != null) activePalette.dismiss();
        activePalette = null;
        if (activeSearchSheet != null) activeSearchSheet.close();
        activeSearchSheet = null;
        if (activeProfileHub != null) activeProfileHub.close();
        activeProfileHub = null;
        if (activeAdminConsole != null) activeAdminConsole.close();
        activeAdminConsole = null;
        closeFirstRunStep2();
        activeProfileIsAdmin = false;
        LiveRunActionReceiver.clearStopHandler(liveStopHandler);
        LiveRunNotification.cancel(this);
        if (sharedText != null) sharedText.lock();
        if (sharedMedia != null) sharedMedia.lock();
        if (photoPicker != null) photoPicker.interruptImport();
        if (shell != null) shell.dismissMenu();
        if (voice != null) {
            voice.reader().stop();
            voice.cancel();
        }
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
        if (activeApi != null) activeApi.revoke();
        activeApi = null;
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
        if (focused) refreshActiveHubIfVisible();
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
        if (account != null) {
            locationTracker.syncWithPolicy(this, PresencePolicy.load(account));
            if (activePresencePanel != null
                    && navigation != null && PresenceSettingsPanel.ROUTE_ID.equals(navigation.current())) {
                activePresencePanel.refresh();
            }
        }
        if (account != null && activeAmbientPanel != null
                && navigation != null && AmbientAccessPanel.ROUTE_ID.equals(navigation.current())) {
            activeAmbientPanel.refreshAll();
        }
        if (account != null && activeUpdatePanel != null
                && navigation != null && UpdatePanel.ROUTE_ID.equals(navigation.current())) {
            activeUpdatePanel.onResume();
        }
        if (account != null && activePermissionsPanel != null
                && navigation != null && PermissionsPanel.ROUTE_ID.equals(navigation.current())) {
            activePermissionsPanel.refresh();
        }
        if (account != null && activeDoctorPanel != null
                && navigation != null && ConnectionDoctorPanel.ROUTE_ID.equals(navigation.current())) {
            activeDoctorPanel.refresh();
        }
        refreshActiveHubIfVisible();
        if (push != null) push.onResume();
        voice.resume();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_LOW && threads != null) {
            threads.onTrimMemory();
        }
    }

    @Override public void onLowMemory() {
        super.onLowMemory();
        if (threads != null) threads.onTrimMemory();
    }

    @Override public void startActivityForResult(Intent intent, int requestCode, Bundle options) {
        markInternalTransition();
        try {
            super.startActivityForResult(intent, requestCode, options);
        } catch (RuntimeException err) {
            clearInternalTransition();
            throw err;
        }
    }

    @Override public void startActivity(Intent intent, Bundle options) {
        boolean marked = intent != null && Intent.ACTION_CHOOSER.equals(intent.getAction());
        if (marked) markInternalTransition();
        try {
            super.startActivity(intent, options);
        } catch (RuntimeException err) {
            if (marked) clearInternalTransition();
            throw err;
        }
    }

    private void markInternalTransition() {
        internalActivityTransition = true;
        internalTransitionAtMs = SystemClock.elapsedRealtime();
    }

    private void clearInternalTransition() {
        internalActivityTransition = false;
        internalTransitionAtMs = -1L;
    }

    private boolean isDeviceLockedNow() {
        android.app.KeyguardManager km = getSystemService(android.app.KeyguardManager.class);
        return screenLockedWhileBackgrounded || (km != null && km.isDeviceLocked());
    }

    private void checkGraceOnReturn() {
        boolean deviceLocked = isDeviceLockedNow();
        screenLockedWhileBackgrounded = false;
        long now = SystemClock.elapsedRealtime();
        if (internalActivityTransition) {
            long startedAt = internalTransitionAtMs;
            clearInternalTransition();
            long timeoutMs = account != null
                    ? LockGracePolicy.internalTransitionTimeoutMillis(LockGracePolicy.window(this, account.id))
                    : LockGracePolicy.INTERNAL_TRANSITION_MAX_MS;
            boolean expired = LockGracePolicy.isInternalTransitionExpired(startedAt, now, timeoutMs);
            if (account != null && (deviceLocked || expired)) {
                Diagnostics.event("security", "internal_transition_locked");
                lock();
            } else {
                clearGraceTimer();
            }
            return;
        }
        if (account == null || backgroundedAtElapsedMs < 0L) return;
        if (LockGracePolicy.shouldLockOnReturn(
                activeGraceWindow, backgroundedAtElapsedMs, now, false, deviceLocked, false)) {
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
        if (isChangingConfigurations()) return;
        if (isFinishing()) {
            lock();
            return;
        }
        boolean deviceLocked = isDeviceLockedNow();
        if (internalActivityTransition && !deviceLocked) {
            if (account != null) {
                backgroundedAtElapsedMs = SystemClock.elapsedRealtime();
                long timeoutMs = LockGracePolicy.internalTransitionTimeoutMillis(
                        LockGracePolicy.window(this, account.id));
                main.removeCallbacks(graceExpiry);
                main.postDelayed(graceExpiry, timeoutMs);
            }
            return;
        }
        if (account == null) {
            lock();
            return;
        }
        LockGracePolicy.Window window = LockGracePolicy.window(this, account.id);
        if (deviceLocked || window == LockGracePolicy.Window.IMMEDIATE) {
            lock();
            return;
        }
        backgroundedAtElapsedMs = SystemClock.elapsedRealtime();
        activeGraceWindow = window;
        main.removeCallbacks(graceExpiry);
        if (!window.locksOnScreenOffOnly() && window.millis > 0L) {
            main.postDelayed(graceExpiry, window.millis);
        }
        Diagnostics.event("security", "grace_started");
    }

    @Override protected void onDestroy() {
        try {
            unregisterReceiver(screenOffReceiver);
        } catch (IllegalArgumentException ignored) {}
        pendingPairing = null;
        pairingLinkHandler = null;
        clearWorkspace();
        if (voice != null) {
            voice.reader().stop();
            voice.cancel();
        }
        if (gate != null) gate.close();
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
