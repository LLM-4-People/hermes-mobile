package org.llm4people.hermes.profile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;
import org.llm4people.hermes.AppLanguage;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.HermesConfig;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.ambient.NotificationAllowlist;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.onboarding.OnboardingModels;
import org.llm4people.hermes.onboarding.PairingLinkParser;
import org.llm4people.hermes.os.BiometricVault;
import org.llm4people.hermes.os.LockGracePolicy;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Local authentication flow. Its owner must discard gateway clients and account references on lock. */
public final class ProfileGate {
    static final int MIN_PASSPHRASE = 12;
    private static final int TEXT = InputType.TYPE_CLASS_TEXT;
    private static final int SECRET = TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
    private static final int PIN_INPUT = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "hermes-profile-io");
        t.setDaemon(true);
        return t;
    });
    private static final ExecutorService PROBE_IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "hermes-profile-probe");
        t.setDaemon(true);
        return t;
    });

    enum ProbeOutcome {
        READY,
        OUTDATED_SERVER,
        AUTH_REJECTED,
        NOT_FOUND,
        SERVER_ERROR,
        UNREACHABLE
    }

    record ProbeResult(ProbeOutcome outcome, long latencyMs, int httpStatus) {}

    record SplitEndpoint(String baseUrl, String profileSlug, String composedEndpoint,
                         boolean splitApplied, boolean conflict) {}

    record RollbackFields(String serverUrl, String profileSlug, String apiKey) {}

    /** Pure-Java resolver restoring claimed endpoint, profile slug, and API key on local save/biometric rollback. */
    static RollbackFields resolveRollbackFields(OnboardingModels.PairingClaim claim) {
        if (claim == null) return null;
        String apiKey = claim.apiKey() == null ? "" : claim.apiKey().trim();
        if (apiKey.isEmpty()) return null;
        String rawSlug = claim.profileSlug() == null ? "" : claim.profileSlug().trim();
        String formSlug = rawSlug.isEmpty() || "default".equalsIgnoreCase(rawSlug) ? "" : rawSlug;
        String baseServer = "";
        try {
            String resolved = claim.resolvedEndpoint();
            if (resolved != null && !resolved.isEmpty()) {
                SplitEndpoint split = splitPastedEndpoint(resolved, formSlug);
                if (!split.conflict()) {
                    baseServer = split.baseUrl();
                    if (formSlug.isEmpty()) formSlug = split.profileSlug();
                } else if (claim.serverUrl() != null && !claim.serverUrl().trim().isEmpty()) {
                    baseServer = FirstRunWizard.stripTrailingProfilePath(claim.serverUrl());
                }
            }
        } catch (IllegalArgumentException invalid) {
            if (claim.serverUrl() != null) baseServer = claim.serverUrl().trim();
        }
        return new RollbackFields(baseServer, formSlug, apiKey);
    }

    /** Thread-safe single-flight probe epoch, revocation, and UI-reset controller. */
    static final class ProbeController {
        private final Object lock = new Object();
        private HermesApi activeApi;
        private int generation;
        private Runnable resetUi;

        int begin(Runnable onCancelUi) {
            HermesApi stale;
            int epoch;
            synchronized (lock) {
                epoch = ++generation;
                stale = activeApi;
                activeApi = null;
                resetUi = onCancelUi;
            }
            if (stale != null) stale.revoke();
            return epoch;
        }

        boolean attach(int epoch, HermesApi api) {
            synchronized (lock) {
                if (epoch != generation) return false;
                activeApi = api;
                return true;
            }
        }

        void detach(HermesApi api) {
            synchronized (lock) {
                if (activeApi == api) activeApi = null;
            }
        }

        boolean isCurrent(int epoch) {
            synchronized (lock) {
                return epoch == generation;
            }
        }

        boolean finishUi(int epoch) {
            synchronized (lock) {
                if (epoch != generation) return false;
                resetUi = null;
                return true;
            }
        }

        void cancel(boolean notifyResetUi) {
            HermesApi stale;
            Runnable reset;
            synchronized (lock) {
                generation++;
                stale = activeApi;
                activeApi = null;
                reset = resetUi;
                resetUi = null;
            }
            if (stale != null) stale.revoke();
            if (notifyResetUi && reset != null) reset.run();
        }
    }

    /** Idempotent single-settlement guard ensuring an un-enrolled biometric profile is rolled back exactly once. */
    static final class BiometricEnrollmentGuard {
        private boolean settled;
        private Runnable onRollback;

        void arm(Runnable rollback) {
            settled = false;
            onRollback = rollback;
        }

        boolean markEnrolled() {
            if (settled) return false;
            settled = true;
            onRollback = null;
            return true;
        }

        boolean rollbackOnce() {
            if (settled) return false;
            settled = true;
            Runnable action = onRollback;
            onRollback = null;
            if (action != null) action.run();
            return true;
        }
    }

    private final Activity activity;
    private final AccountStore store;
    private final UnlockBackoff backoff;
    private Consumer<Account> onOpen;
    private Consumer<String> onForget = id -> {};
    private Account active;
    private AlertDialog confirmation;
    private BiometricVault.Session activeBiometric;
    private BiometricEnrollmentGuard pendingBiometricGuard;
    private final ProbeController probe = new ProbeController();
    private PairingLinkParser.ParsedInput pendingPairing;
    private OnboardingModels.PairingClaim lastPairingClaim;
    private FirstRunWizard.StagedPairing stagedPairing;
    private Consumer<PairingLinkParser.ParsedInput> activeAddPairingHandler;
    private Runnable clearVisibleSecrets;
    private int generation;
    private boolean nested;
    private boolean busy;

    /** Queues a {@code hermes://pair} deep link to be confirmed on the connect screen. */
    public void setPendingPairing(PairingLinkParser.ParsedInput parsed) {
        pendingPairing = parsed;
    }

    /**
     * Opens or updates the connect screen with an incoming pairing link requiring explicit confirmation.
     * When the user is already on the connect form, applies the link in place without wiping existing inputs.
     */
    public void openAddWithPairing(PairingLinkParser.ParsedInput parsed) {
        if (active == null && activeAddPairingHandler != null && parsed != null
                && !activity.isDestroyed() && !activity.isFinishing()) {
            activeAddPairingHandler.accept(parsed);
            return;
        }
        pendingPairing = parsed;
        if (active == null && !activity.isDestroyed() && !activity.isFinishing()) {
            add(!store.profiles().isEmpty());
        }
    }

    /** Returns and clears the claimed onboarding metadata from a freshly paired profile, or null. */
    public OnboardingModels.PairingClaim consumeFreshPairingClaim() {
        OnboardingModels.PairingClaim claimed = lastPairingClaim;
        lastPairingClaim = null;
        return claimed;
    }

    public ProfileGate(Activity activity) {
        this.activity = activity;
        this.store = new AccountStore(activity);
        this.backoff = UnlockBackoff.forContext(activity);
    }

    /** Membership check available to notification receivers while profile data is locked. */
    public static boolean contains(Context context, String id) {
        return new AccountStore(context).contains(id);
    }

    /** Pure in-place character comparison avoiding immutable heap Strings for sensitive fields. */
    static boolean sameChars(CharSequence a, CharSequence b) {
        if (a == null || b == null) return a == b;
        int len = a.length();
        if (len != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < len; i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    /**
     * Pure smart parser for single-host {@code https://host/p/<profile>} URLs pasted into the server field.
     * Auto-splits cleanly when the profile field is blank or matches the same slug, and surfaces
     * explicit conflicts without silently overwriting a different user-entered slug.
     */
    static SplitEndpoint splitPastedEndpoint(String serverInput, String currentSlug) {
        GatewayAddress.Split split = GatewayAddress.split(serverInput);
        String trimmedSlug = currentSlug == null ? "" : currentSlug.trim();
        if (split.profileSlug().isEmpty()) {
            if (trimmedSlug.isEmpty()) {
                return new SplitEndpoint(split.baseUrl(), "", split.baseUrl(), false, false);
            }
            String slug = GatewayAddress.normalizeProfileSlug(trimmedSlug);
            String composed = GatewayAddress.normalize(split.baseUrl(), slug);
            return new SplitEndpoint(split.baseUrl(), slug, composed, false, false);
        }
        String extractedSlug = split.profileSlug();
        String composed = split.baseUrl() + "/p/" + extractedSlug;
        if (trimmedSlug.isEmpty()) {
            return new SplitEndpoint(split.baseUrl(), extractedSlug, composed, true, false);
        }
        String normalizedCurrent = GatewayAddress.normalizeProfileSlug(trimmedSlug);
        if (normalizedCurrent.equalsIgnoreCase(extractedSlug)) {
            return new SplitEndpoint(split.baseUrl(), extractedSlug, composed, true, false);
        }
        String normalizedBase = GatewayAddress.normalize(serverInput);
        return new SplitEndpoint(normalizedBase, normalizedCurrent, "", false, true);
    }

    /** Pure classifier turning a {@code GET /v1/capabilities} response or failure into a safe status outcome. */
    static ProbeResult classifyProbeResult(JSONObject capabilities, Throwable error, long elapsedMs) {
        boolean hasCapabilities = capabilities != null;
        boolean idempotencySupported = false;
        if (hasCapabilities) {
            JSONObject features = capabilities.optJSONObject("features");
            JSONObject idem = features == null ? null : features.optJSONObject("runs_idempotency");
            idempotencySupported = idem != null && idem.optBoolean("supported");
        }
        return classifyProbeOutcome(hasCapabilities, idempotencySupported, error, elapsedMs);
    }

    /** Host-JVM testable pure outcome mapper decoupled from Android's stubbed {@code org.json} runtime. */
    static ProbeResult classifyProbeOutcome(boolean hasCapabilities, boolean idempotencySupported,
                                            Throwable error, long elapsedMs) {
        long latency = Math.max(0L, elapsedMs);
        if (error == null && hasCapabilities) {
            return new ProbeResult(
                    idempotencySupported ? ProbeOutcome.READY : ProbeOutcome.OUTDATED_SERVER,
                    latency, 200);
        }
        if (error instanceof HermesApi.HttpError http) {
            if (http.status == 401 || http.status == 403) {
                return new ProbeResult(ProbeOutcome.AUTH_REJECTED, latency, http.status);
            }
            if (http.status == 404) {
                return new ProbeResult(ProbeOutcome.NOT_FOUND, latency, http.status);
            }
            return new ProbeResult(ProbeOutcome.SERVER_ERROR, latency, http.status);
        }
        return new ProbeResult(ProbeOutcome.UNREACHABLE, latency, 0);
    }

    public void setOnForget(Consumer<String> callback) {
        onForget = Objects.requireNonNull(callback);
    }

    public void start(Consumer<Account> callback) {
        start(null, callback);
    }

    public void start(String requestedId, Consumer<Account> callback) {
        onOpen = Objects.requireNonNull(callback);
        invalidate();
        Map<String, String> profiles = store.profiles();
        if (pendingPairing != null) {
            add(!profiles.isEmpty());
        } else if (requestedId != null && profiles.containsKey(requestedId)) {
            unlock(requestedId, profiles.get(requestedId));
        } else if (requestedId != null) {
            chooser(activity.getString(R.string.profile_setup_missing_on_device));
        } else if (profiles.isEmpty()) {
            add(false);
        } else {
            chooser(null);
        }
    }

    public void lock() {
        pendingPairing = null;
        invalidate();
        if (onOpen != null && !activity.isDestroyed() && !activity.isFinishing()) {
            if (store.profiles().isEmpty()) add(false);
            else chooser(null);
        }
    }

    /** Permanently tears down the gate on Activity destroy: cancels biometrics, revokes probes, dismisses dialogs, zeroes Account keys. */
    public void close() {
        onOpen = null;
        pendingPairing = null;
        invalidate();
    }

    /** Returns true only when handling navigation within the gate. */
    public boolean onBack() {
        if (!nested || active != null) return false;
        if (busy) return true;
        chooser(null);
        return true;
    }

    private void cancelBiometric() {
        BiometricVault.Session session = activeBiometric;
        activeBiometric = null;
        if (session != null) session.cancel();
        BiometricEnrollmentGuard guard = pendingBiometricGuard;
        pendingBiometricGuard = null;
        if (guard != null) guard.rollbackOnce();
    }

    private void cancelProbe() {
        cancelProbe(false);
    }

    private void cancelProbe(boolean resetUi) {
        probe.cancel(resetUi);
    }

    private void wipeVisibleSecrets() {
        Runnable wipe = clearVisibleSecrets;
        clearVisibleSecrets = null;
        if (wipe != null) wipe.run();
    }

    private void dismissConfirmation() {
        AlertDialog toDismiss = confirmation;
        confirmation = null;
        if (toDismiss != null) {
            try { toDismiss.dismiss(); } catch (RuntimeException ignored) {}
        }
    }

    /** Executes all screen-transition cleanup actions best-effort so one failure never leaks subsequent state. */
    static void runScreenCleanup(Runnable... actions) {
        if (actions == null) return;
        for (Runnable action : actions) {
            if (action == null) continue;
            try {
                action.run();
            } catch (RuntimeException err) {
                Diagnostics.warning("profile", "screen_cleanup_failed", err);
            }
        }
    }

    private int beginScreen(boolean canNavigateBack) {
        generation++;
        busy = false;
        nested = canNavigateBack;
        activeAddPairingHandler = null;
        runScreenCleanup(this::wipeVisibleSecrets, this::cancelBiometric, this::cancelProbe, this::dismissConfirmation);
        return generation;
    }

    private void invalidate() {
        generation++;
        busy = false;
        nested = false;
        lastPairingClaim = null;
        stagedPairing = null;
        activeAddPairingHandler = null;
        runScreenCleanup(this::wipeVisibleSecrets, this::cancelBiometric, this::cancelProbe, this::dismissConfirmation);
        if (active != null) {
            active.close();
            active = null;
        }
    }

    /** Tracked like every gate dialog so lock, switch and close dismiss it. */
    private void showLanguagePicker() {
        dismissConfirmation();
        confirmation = AppLanguage.showPicker(activity, null);
    }

    private void chooser(String message) {
        beginScreen(false);
        stagedPairing = null;
        ProfileScreen screen = new ProfileScreen(activity, activity.getString(R.string.profile_choose_title), null);
        boolean bioAvailable = BiometricVault.availability(activity) != BiometricVault.Availability.UNAVAILABLE;
        store.profiles().forEach((id, label) -> {
            long waitMs = backoff.remainingMs(id);
            String badge;
            ProfileScreen.BadgeTone tone;
            if (waitMs > 0L) {
                badge = activity.getString(R.string.profile_setup_badge_cooldown, UnlockBackoff.formatRemaining(waitMs));
                tone = ProfileScreen.BadgeTone.COOLDOWN;
            } else if (bioAvailable && BiometricVault.isEnrolled(activity, id)) {
                badge = activity.getString(R.string.profile_setup_badge_biometric);
                tone = ProfileScreen.BadgeTone.BIOMETRIC;
            } else {
                badge = activity.getString(R.string.profile_setup_badge_locked);
                tone = ProfileScreen.BadgeTone.NEUTRAL;
            }
            screen.profileRow(label, badge, tone, view -> unlock(id, label));
        });
        screen.action(activity.getString(R.string.profile_add_action), true, view -> add(true));
        screen.action(activity.getString(R.string.language_action), false,
                view -> { if (!busy) showLanguagePicker(); });
        if (message != null) screen.status(message, false);
    }

    private void add(boolean canGoBack) {
        int screenEpoch = beginScreen(canGoBack);
        lastPairingClaim = null;
        stagedPairing = null;
        PairingLinkParser.ParsedInput incomingLink = pendingPairing;
        pendingPairing = null;
        ProfileScreen screen = new ProfileScreen(
                activity, activity.getString(R.string.profile_connect_title), activity.getString(R.string.profile_connect_detail));
        final EditText[] fields = new EditText[5];
        final Button[] testBtnRef = new Button[1];
        final TextView[] probeStatusRef = new TextView[1];
        final TextView[] stagedSummaryRef = new TextView[1];
        final Runnable[] refreshReadinessRef = new Runnable[1];
        final boolean[] programmaticUpdate = {false};

        Consumer<PairingLinkParser.ParsedInput> instantHandler = FirstRunWizard.attachInstantSetupCard(
                activity,
                screen,
                incomingLink != null,
                PROBE_IO,
                () -> screenEpoch == generation && !busy,
                new FirstRunWizard.Step1Targets() {
                    @Override public void fillFields(String suggestedName, String serverUrl, String profileSlug,
                                                     String apiKey, boolean focusPassphrase) {
                        programmaticUpdate[0] = true;
                        try {
                            stagedPairing = null;
                            if (fields[3] != null) fields[3].setHint("");
                            if (stagedSummaryRef[0] != null) {
                                stagedSummaryRef[0].setText("");
                                stagedSummaryRef[0].setVisibility(View.GONE);
                            }
                            if (fields[0] != null && fields[0].getText().toString().trim().isEmpty()
                                    && suggestedName != null && !suggestedName.isEmpty()) {
                                fields[0].setText(suggestedName);
                            }
                            if (fields[1] != null && serverUrl != null && !serverUrl.isEmpty()) {
                                fields[1].setText(serverUrl);
                                fields[1].setError(null);
                            }
                            if (fields[2] != null && profileSlug != null) {
                                fields[2].setText(profileSlug);
                                fields[2].setError(null);
                            }
                            if (fields[3] != null && apiKey != null && !apiKey.isEmpty()) {
                                fields[3].setText(apiKey);
                                fields[3].setError(null);
                            }
                        } finally {
                            programmaticUpdate[0] = false;
                        }
                        if (refreshReadinessRef[0] != null) refreshReadinessRef[0].run();
                    }

                    @Override public void stagePairing(FirstRunWizard.StagedPairing staged) {
                        stagedPairing = staged;
                        String defaultLabel = activity.getString(R.string.onboarding_default_profile_label);
                        String profileName = FirstRunWizard.formProfileName(
                                staged.displayName(), staged.profileSlug(), defaultLabel);
                        String profileLabel = FirstRunWizard.confirmProfileLabel(
                                staged.displayName(), staged.profileSlug(), defaultLabel);
                        programmaticUpdate[0] = true;
                        try {
                            if (fields[0] != null && fields[0].getText().toString().trim().isEmpty()) {
                                fields[0].setText(profileName);
                                fields[0].setError(null);
                            }
                            if (fields[1] != null) {
                                fields[1].setText(staged.serverUrl());
                                fields[1].setError(null);
                            }
                            if (fields[2] != null) {
                                fields[2].setText(staged.formProfileSlug());
                                fields[2].setError(null);
                            }
                            if (fields[3] != null) {
                                fields[3].setText("");
                                fields[3].setError(null);
                                fields[3].setHint(R.string.onboarding_key_paired_auto_hint);
                            }
                        } finally {
                            programmaticUpdate[0] = false;
                        }
                        String stagedSummary = activity.getString(R.string.onboarding_paste_staged,
                                staged.serverUrl(), profileLabel);
                        if (probeStatusRef[0] != null) {
                            screen.setNote(probeStatusRef[0], stagedSummary, R.color.accent);
                        }
                        if (stagedSummaryRef[0] != null) {
                            screen.setNote(stagedSummaryRef[0], stagedSummary, R.color.accent);
                            stagedSummaryRef[0].setVisibility(View.VISIBLE);
                        }
                        if (refreshReadinessRef[0] != null) refreshReadinessRef[0].run();
                    }

                    @Override public void triggerConnectionProbe(String endpoint, String bearer) {
                        if (testBtnRef[0] != null && probeStatusRef[0] != null
                                && endpoint != null && !endpoint.isEmpty() && bearer != null && !bearer.isEmpty()) {
                            runConnectionProbe(screen, testBtnRef[0], probeStatusRef[0], endpoint, bearer);
                        }
                    }

                    @Override public void showInstantStatus(String message, int colorRes) {
                        if (probeStatusRef[0] != null) screen.setNote(probeStatusRef[0], message, colorRes);
                    }

                    @Override public void onClaimedMetadata(OnboardingModels.PairingClaim claim) {
                        lastPairingClaim = claim;
                    }

                    @Override public void trackDialog(AlertDialog dialog) {
                        if (confirmation != null && confirmation != dialog) {
                            try { confirmation.dismiss(); } catch (RuntimeException ignored) {}
                        }
                        confirmation = dialog;
                    }

                    @Override public void onScanQrRequested() {
                        org.llm4people.hermes.onboarding.QrScannerActivity.launch(activity);
                    }
                });
        activeAddPairingHandler = instantHandler;

        EditText name = screen.field(activity.getString(R.string.profile_name_label),
                activity.getString(R.string.profile_name_hint), TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, 80, false);
        EditText server = screen.field(activity.getString(R.string.profile_server_label),
                activity.getString(R.string.profile_setup_server_hint), TEXT | InputType.TYPE_TEXT_VARIATION_URI, 2048, false);
        EditText serverProfile = screen.field(
                activity.getString(R.string.profile_server_slug_label),
                activity.getString(R.string.profile_server_slug_hint),
                TEXT | InputType.TYPE_TEXT_VARIATION_URI, 64, false);
        TextView endpointPreview = screen.liveNote(activity.getString(R.string.profile_server_slug_help));
        watchEndpointPreview(screen, server, serverProfile, endpointPreview);
        EditText key = screen.field(activity.getString(R.string.profile_key_label), "", SECRET, 8192, true);
        Runnable clearStaged = () -> {
            if (stagedPairing != null) {
                stagedPairing = null;
                key.setHint("");
            }
            if (stagedSummaryRef[0] != null) {
                stagedSummaryRef[0].setText("");
                stagedSummaryRef[0].setVisibility(View.GONE);
            }
            if (refreshReadinessRef[0] != null) refreshReadinessRef[0].run();
        };
        key.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (!programmaticUpdate[0] && stagedPairing != null && s.length() > 0) {
                    clearStaged.run();
                } else if (!programmaticUpdate[0] && refreshReadinessRef[0] != null) {
                    refreshReadinessRef[0].run();
                }
            }
        });
        TextWatcher targetDriftWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (!programmaticUpdate[0] && stagedPairing != null
                        && !stagedPairing.matchesTarget(server.getText().toString(), serverProfile.getText().toString())) {
                    clearStaged.run();
                } else if (!programmaticUpdate[0] && refreshReadinessRef[0] != null) {
                    refreshReadinessRef[0].run();
                }
            }
        };
        server.addTextChangedListener(targetDriftWatcher);
        serverProfile.addTextChangedListener(targetDriftWatcher);

        Button testButton = screen.action(activity.getString(R.string.profile_setup_test_action), false, null);
        TextView probeStatus = screen.liveNote("");
        testBtnRef[0] = testButton;
        probeStatusRef[0] = probeStatus;
        testButton.setOnClickListener(view -> {
            if (busy) return;
            String endpoint = validatedEndpoint(server, serverProfile);
            if (endpoint == null) return;
            FirstRunWizard.StagedPairing activeStaged = stagedPairing;
            if (activeStaged != null
                    && activeStaged.matchesTarget(server.getText().toString(), serverProfile.getText().toString())) {
                runStagedPairingProbe(screen, testButton, probeStatus, activeStaged, clearStaged);
                return;
            }
            String bearer = checkedBearer(key);
            if (bearer == null) return;
            runConnectionProbe(screen, testButton, probeStatus, endpoint, bearer);
        });

        screen.section(activity.getString(R.string.profile_lock_section), activity.getString(R.string.profile_lock_detail));
        TextView stagedSummary = screen.liveNote("");
        stagedSummary.setVisibility(View.GONE);
        stagedSummaryRef[0] = stagedSummary;

        // Multi-mode lock selector (Biometric / Phone lock, PIN 6+, Passphrase 12+)
        BiometricVault.Availability bioAvail = BiometricVault.availability(activity);
        boolean bioAvailable = bioAvail != BiometricVault.Availability.UNAVAILABLE;
        ProfileLockPolicy.Mode[] selectedMode = {ProfileLockPolicy.defaultModeForSetup(bioAvail)};

        TextView modeHeading = Ui.text(activity, activity.getString(R.string.lock_mode_section_label), 14);
        modeHeading.setTypeface(Ui.mediumTypeface());
        modeHeading.setAccessibilityHeading(true);
        screen.card(modeHeading, 10);

        HorizontalScrollView modeScroll = new HorizontalScrollView(activity);
        modeScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout modeRow = Ui.row(activity);
        modeRow.setBaselineAligned(false);
        modeScroll.addView(modeRow);
        ProfileLockPolicy.Mode[] allModes = ProfileLockPolicy.Mode.values();
        Button[] modeButtons = new Button[allModes.length];

        LinearLayout bioSlot = Ui.column(activity);
        TextView bioInfo = Ui.text(activity, activity.getString(
                bioAvailable ? R.string.lock_mode_biometric_detail : R.string.lock_mode_biometric_unavailable), 14);
        bioInfo.setTextColor(activity.getColor(bioAvailable ? R.color.muted : R.color.danger));
        bioSlot.addView(bioInfo);

        LinearLayout pinSlot = Ui.column(activity);
        EditText pin = screen.field(pinSlot, activity.getString(R.string.lock_pin_label),
                activity.getString(R.string.lock_pin_hint), PIN_INPUT, ProfileLockPolicy.MAX_PIN_DIGITS, true);
        EditText pinRepeat = screen.field(pinSlot, activity.getString(R.string.lock_pin_confirm_label),
                "", PIN_INPUT, ProfileLockPolicy.MAX_PIN_DIGITS, true);
        TextView pinHint = Ui.text(activity, "", 13);
        pinHint.setTextColor(activity.getColor(R.color.muted));
        pinHint.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, -2);
        hintLp.topMargin = Ui.dp(activity, 4);
        pinSlot.addView(pinHint, hintLp);

        LinearLayout passphraseSlot = Ui.column(activity);
        EditText phrase = screen.field(passphraseSlot, activity.getString(R.string.profile_passphrase_label),
                activity.getResources().getQuantityString(R.plurals.profile_passphrase_hint, MIN_PASSPHRASE, MIN_PASSPHRASE),
                SECRET, 512, true);
        EditText repeat = screen.field(passphraseSlot, activity.getString(R.string.profile_passphrase_confirm_label),
                "", SECRET, 512, true);
        TextView passphraseHint = Ui.text(activity, "", 13);
        passphraseHint.setTextColor(activity.getColor(R.color.muted));
        passphraseHint.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout.LayoutParams passHintLp = new LinearLayout.LayoutParams(-1, -2);
        passHintLp.topMargin = Ui.dp(activity, 4);
        passphraseSlot.addView(passphraseHint, passHintLp);

        clearVisibleSecrets = () -> {
            key.getText().clear();
            pin.getText().clear();
            pinRepeat.getText().clear();
            phrase.getText().clear();
            repeat.getText().clear();
        };

        Runnable applyModeUi = () -> {
            for (int i = 0; i < allModes.length; i++) {
                boolean activeChoice = allModes[i] == selectedMode[0];
                ProfileScreen.styleChoiceChip(activity, modeButtons[i], activeChoice);
            }
            bioSlot.setVisibility(selectedMode[0] == ProfileLockPolicy.Mode.BIOMETRIC ? View.VISIBLE : View.GONE);
            pinSlot.setVisibility(selectedMode[0] == ProfileLockPolicy.Mode.PIN ? View.VISIBLE : View.GONE);
            passphraseSlot.setVisibility(selectedMode[0] == ProfileLockPolicy.Mode.PASSPHRASE ? View.VISIBLE : View.GONE);
            if (refreshReadinessRef[0] != null) refreshReadinessRef[0].run();
        };

        for (int i = 0; i < allModes.length; i++) {
            ProfileLockPolicy.Mode choice = allModes[i];
            Button chip = ProfileScreen.choiceChip(activity, activity.getString(choice.labelRes), v -> {
                if (busy) return;
                selectedMode[0] = choice;
                applyModeUi.run();
            });
            modeButtons[i] = chip;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.setMarginStart(Ui.dp(activity, 8));
            modeRow.addView(chip, lp);
        }
        screen.card(modeScroll, 6);
        screen.card(bioSlot, 10);
        screen.card(pinSlot, 4);
        screen.card(passphraseSlot, 4);

        fields[0] = name;
        fields[1] = server;
        fields[2] = serverProfile;
        fields[3] = key;
        fields[4] = phrase;

        watchPinStrength(screen, pin, pinRepeat, pinHint, () -> {
            if (refreshReadinessRef[0] != null) refreshReadinessRef[0].run();
        });
        watchPassphraseStrength(screen, phrase, repeat, passphraseHint, () -> {
            if (refreshReadinessRef[0] != null) refreshReadinessRef[0].run();
        });
        name.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (!programmaticUpdate[0] && refreshReadinessRef[0] != null) refreshReadinessRef[0].run();
            }
        });

        TextView readinessNote = screen.liveNote("");
        Button saveButton = screen.action(activity.getString(R.string.profile_save_action), true, null);
        Runnable refreshReadiness = () -> {
            FirstRunWizard.StagedPairing activeStaged = stagedPairing;
            boolean hasStaged = activeStaged != null
                    && activeStaged.matchesTarget(server.getText().toString(), serverProfile.getText().toString());
            ProfileLockPolicy.SetupBlocker blocker = ProfileLockPolicy.evaluateSetupForm(
                    name.getText().toString(),
                    store.profiles().values(),
                    server.getText().toString(),
                    serverProfile.getText().toString(),
                    key.getText().toString(),
                    hasStaged,
                    selectedMode[0],
                    bioAvailable,
                    pin.getText(),
                    pinRepeat.getText(),
                    phrase.getText(),
                    repeat.getText());
            boolean ready = blocker == ProfileLockPolicy.SetupBlocker.READY;
            saveButton.setEnabled(!busy && ready);
            if (ready) {
                screen.setNote(readinessNote, activity.getString(R.string.lock_setup_ready), R.color.accent);
            } else {
                screen.setNote(readinessNote, blockerMessage(blocker), R.color.muted);
            }
        };
        refreshReadinessRef[0] = refreshReadiness;

        saveButton.setOnClickListener(view -> {
            if (busy) return;
            String label = name.getText().toString().trim();
            if (label.isEmpty() || hasControl(label)) {
                invalid(name, activity.getString(R.string.profile_setup_name_required)); return;
            }
            if (store.profiles().values().stream().anyMatch(label::equalsIgnoreCase)) {
                invalid(name, activity.getString(R.string.profile_setup_name_duplicate)); return;
            }
            String endpoint = validatedEndpoint(server, serverProfile);
            if (endpoint == null) return;
            FirstRunWizard.StagedPairing activeStaged = stagedPairing;
            boolean useStaged = activeStaged != null
                    && activeStaged.matchesTarget(server.getText().toString(), serverProfile.getText().toString());
            String bearer = useStaged ? "" : checkedBearer(key);
            if (!useStaged && bearer == null) return;

            ProfileLockPolicy.Mode chosenMode = selectedMode[0];
            char[] pass;
            if (chosenMode == ProfileLockPolicy.Mode.BIOMETRIC) {
                if (!bioAvailable) {
                    screen.status(activity.getString(R.string.lock_mode_biometric_unavailable), true);
                    return;
                }
                pass = ProfileLockPolicy.generateVaultSecret();
            } else if (chosenMode == ProfileLockPolicy.Mode.PIN) {
                ProfileLockPolicy.CredentialState pinState =
                        ProfileLockPolicy.evaluatePin(pin.getText(), pinRepeat.getText());
                if (pinState != ProfileLockPolicy.CredentialState.READY) {
                    boolean mismatch = pinState == ProfileLockPolicy.CredentialState.MISMATCH
                            || pinState == ProfileLockPolicy.CredentialState.AWAITING_CONFIRM;
                    invalid(mismatch ? pinRepeat : pin, activity.getString(
                            pinState == ProfileLockPolicy.CredentialState.MISMATCH
                                    ? R.string.lock_pin_mismatch
                                    : R.string.lock_pin_digits_only));
                    return;
                }
                pass = password(pin);
            } else {
                pass = password(phrase);
                char[] check = password(repeat);
                boolean matches = Arrays.equals(pass, check);
                Arrays.fill(check, '\0');
                if (pass.length < MIN_PASSPHRASE || !matches) {
                    boolean shortPhrase = pass.length < MIN_PASSPHRASE;
                    Arrays.fill(pass, '\0');
                    invalid(shortPhrase ? phrase : repeat, shortPhrase
                            ? activity.getResources().getQuantityString(R.plurals.profile_passphrase_too_short,
                                    MIN_PASSPHRASE, MIN_PASSPHRASE)
                            : activity.getString(R.string.profile_setup_passphrase_mismatch));
                    return;
                }
            }
            cancelProbe(true);
            saveProfileWithMode(screen, saveButton, label, endpoint, bearer,
                    useStaged ? activeStaged : null, chosenMode, pass,
                    clearStaged, claimed -> {
                        RollbackFields restored = resolveRollbackFields(claimed);
                        if (restored == null) return;
                        programmaticUpdate[0] = true;
                        try {
                            stagedPairing = null;
                            if (!restored.serverUrl().isEmpty()) {
                                server.setText(restored.serverUrl());
                                server.setError(null);
                            }
                            serverProfile.setText(restored.profileSlug());
                            serverProfile.setError(null);
                            key.setHint("");
                            key.setText(restored.apiKey());
                        } finally {
                            programmaticUpdate[0] = false;
                        }
                        if (stagedSummaryRef[0] != null) {
                            stagedSummaryRef[0].setText("");
                            stagedSummaryRef[0].setVisibility(View.GONE);
                        }
                        refreshReadiness.run();
                    }, refreshReadiness);
        });
        applyModeUi.run();
        if (canGoBack) screen.action(activity.getString(R.string.profile_back_to_profiles), false, view -> {
            if (busy) return;
            wipeVisibleSecrets();
            chooser(null);
        });
        else screen.action(activity.getString(R.string.language_action), false,
                view -> { if (!busy) showLanguagePicker(); });

        // All EditText fields and refs are now initialized — safe to process any scanned/deep-linked pairing input.
        if (incomingLink != null) {
            instantHandler.accept(incomingLink);
        }
    }

    private String validatedEndpoint(EditText server, EditText serverProfile) {
        String serverText = server.getText().toString();
        String slugText = serverProfile.getText().toString();
        try {
            GatewayAddress.normalize(serverText);
        } catch (IllegalArgumentException ex) {
            Diagnostics.warning("profile", "invalid_gateway_address", ex);
            invalid(server, activity.getString(R.string.profile_server_url_invalid));
            return null;
        }
        try {
            GatewayAddress.normalizeProfileSlug(slugText);
        } catch (IllegalArgumentException ex) {
            Diagnostics.warning("profile", "invalid_server_profile", ex);
            invalid(serverProfile, activity.getString(R.string.profile_server_slug_invalid));
            return null;
        }
        try {
            return GatewayAddress.normalize(serverText, slugText);
        } catch (IllegalArgumentException ex) {
            Diagnostics.warning("profile", "conflicting_server_profile", ex);
            invalid(serverProfile, activity.getString(R.string.profile_server_slug_conflict));
            return null;
        }
    }

    @FunctionalInterface
    interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    interface ThrowingConsumer<T> {
        void accept(T value) throws Exception;
    }

    /**
     * Pure-Java rollback guard: revokes the newly enrolled biometric vault only when migrating
     * from PIN/passphrase to biometric, never wiping an existing biometric-primary profile's sole vault.
     */
    static boolean shouldRevokeBiometricOnBiometricRollback(ProfileLockPolicy.Mode previousMode) {
        return previousMode != ProfileLockPolicy.Mode.BIOMETRIC;
    }

    /**
     * Executes a two-phase lock-mode transition: prepares the slow PBKDF2-resealed envelope before
     * modifying persisted lock mode, re-checks eligibility immediately before mutating mode, then
     * commits lock mode and envelope back-to-back with rollback on failure.
     */
    static boolean commitLockModeTransition(
            ThrowingSupplier<String> prepareEnvelope,
            BooleanSupplier stillEligible,
            BooleanSupplier commitMode,
            ThrowingConsumer<String> commitEnvelope,
            Runnable rollbackMode,
            Runnable onSuccess,
            Runnable onRollback,
            String failureDiagnosticAction) {
        boolean modeCommitted = false;
        try {
            String resealed = prepareEnvelope.get();
            if (stillEligible != null && !stillEligible.getAsBoolean()) {
                throw new java.io.IOException("Profile no longer active");
            }
            if (!commitMode.getAsBoolean()) {
                throw new java.io.IOException("Lock mode commit failed");
            }
            modeCommitted = true;
            commitEnvelope.accept(resealed);
            if (onSuccess != null) onSuccess.run();
            return true;
        } catch (Exception err) {
            Diagnostics.warning("security", failureDiagnosticAction);
            if (modeCommitted && rollbackMode != null) {
                rollbackMode.run();
            }
            if (onRollback != null) onRollback.run();
            return false;
        }
    }

    /** Restores previous lock mode only if the profile still exists; otherwise removes any orphaned mode entry. */
    static void rollbackPersistedMode(
            BooleanSupplier profileStillExists,
            Runnable restorePreviousMode,
            Runnable forgetMode) {
        if (profileStillExists != null && profileStillExists.getAsBoolean()) {
            if (restorePreviousMode != null) restorePreviousMode.run();
        } else if (forgetMode != null) {
            forgetMode.run();
        }
    }

    /** Update an already-unlocked profile's lock mode (Biometric / PIN / Passphrase) atomically. */
    public BiometricVault.Session updateLockMode(
            Account unlocked,
            ProfileLockPolicy.Mode nextMode,
            char[] nextSecret,
            Consumer<Boolean> onDone) {
        Objects.requireNonNull(nextMode, "nextMode");
        Objects.requireNonNull(onDone, "onDone");
        if (busy || unlocked == null || unlocked != active) {
            if (nextSecret != null) Arrays.fill(nextSecret, '\0');
            onDone.accept(false);
            return null;
        }
        int epoch = generation;
        ProfileLockPolicy.Mode previousMode = ProfileLockPolicy.mode(activity, unlocked.id);
        if (nextMode == ProfileLockPolicy.Mode.BIOMETRIC) {
            if (nextSecret != null) Arrays.fill(nextSecret, '\0');
            busy = true;
            BiometricVault.Session session = enrollBiometric(unlocked, new BiometricVault.EnrollmentCallback() {
                @Override public void onEnrolled() {
                    char[] generated = ProfileLockPolicy.generateVaultSecret();
                    IO.execute(() -> {
                        boolean ok;
                        try {
                            ok = commitLockModeTransition(
                                    () -> store.prepareLockSecret(unlocked, generated),
                                    () -> unlocked.unlocked() && store.contains(unlocked.id),
                                    () -> ProfileLockPolicy.setMode(activity, unlocked.id, ProfileLockPolicy.Mode.BIOMETRIC),
                                    resealed -> store.commitLockSecret(unlocked, resealed),
                                    () -> rollbackPersistedMode(
                                            () -> store.contains(unlocked.id),
                                            () -> ProfileLockPolicy.setMode(activity, unlocked.id, previousMode),
                                            () -> ProfileLockPolicy.forget(activity, unlocked.id)),
                                    null,
                                    shouldRevokeBiometricOnBiometricRollback(previousMode)
                                            ? () -> BiometricVault.revoke(activity, unlocked.id) : null,
                                    "lock_mode_biometric_reseal_failed");
                        } finally {
                            Arrays.fill(generated, '\0');
                        }
                        boolean finalOk = ok;
                        activity.runOnUiThread(() -> {
                            if (epoch == generation) busy = false;
                            if (epoch == generation && active == unlocked) onDone.accept(finalOk);
                        });
                    });
                }

                @Override public void onCancelled() {
                    if (epoch == generation) busy = false;
                    if (epoch == generation && active == unlocked) onDone.accept(false);
                }

                @Override public void onError() {
                    if (epoch == generation) busy = false;
                    if (epoch == generation && active == unlocked) onDone.accept(false);
                }
            });
            if (session == null) {
                if (epoch == generation) busy = false;
                return null;
            }
            return session.withOnCancel(() -> {
                if (activeBiometric == session) activeBiometric = null;
                if (epoch == generation) busy = false;
            });
        }
        if (nextSecret == null || nextSecret.length == 0) {
            onDone.accept(false);
            return null;
        }
        busy = true;
        IO.execute(() -> {
            boolean ok;
            try {
                ok = commitLockModeTransition(
                        () -> store.prepareLockSecret(unlocked, nextSecret),
                        () -> unlocked.unlocked() && store.contains(unlocked.id),
                        () -> ProfileLockPolicy.setMode(activity, unlocked.id, nextMode),
                        resealed -> store.commitLockSecret(unlocked, resealed),
                        () -> rollbackPersistedMode(
                                () -> store.contains(unlocked.id),
                                () -> ProfileLockPolicy.setMode(activity, unlocked.id, previousMode),
                                () -> ProfileLockPolicy.forget(activity, unlocked.id)),
                        ProfileLockPolicy.shouldRevokeBiometricOnModeChange(previousMode, nextMode)
                                ? () -> BiometricVault.revoke(activity, unlocked.id) : null,
                        null,
                        "lock_mode_reseal_failed");
            } finally {
                Arrays.fill(nextSecret, '\0');
            }
            boolean finalOk = ok;
            activity.runOnUiThread(() -> {
                if (epoch == generation) busy = false;
                if (epoch == generation && active == unlocked) onDone.accept(finalOk);
            });
        });
        return null;
    }

    /** Opt-in hardware-backed biometric enrollment using the currently unlocked account. */
    public BiometricVault.Session enrollBiometric(Account unlocked, BiometricVault.EnrollmentCallback callback) {
        Objects.requireNonNull(callback);
        if (unlocked == null || unlocked != active) {
            callback.onError();
            return null;
        }
        cancelBiometric();
        int epoch = generation;
        try {
            byte[] payload = store.serializeForBiometric(unlocked);
            BiometricVault.Session signal = BiometricVault.enroll(activity, unlocked.id, unlocked.label, payload,
                    new BiometricVault.EnrollmentCallback() {
                        @Override public void onEnrolled() {
                            activeBiometric = null;
                            if (epoch == generation && active == unlocked) callback.onEnrolled();
                        }

                        @Override public void onCancelled() {
                            activeBiometric = null;
                            if (epoch == generation && active == unlocked) callback.onCancelled();
                        }

                        @Override public void onError() {
                            activeBiometric = null;
                            if (epoch == generation && active == unlocked) callback.onError();
                        }
                    });
            if (signal != null) {
                signal.withOnCancel(() -> {
                    if (activeBiometric == signal) activeBiometric = null;
                });
            }
            activeBiometric = signal;
            return signal;
        } catch (java.io.IOException | RuntimeException error) {
            Diagnostics.warning("biometric", "serialize_failed");
            callback.onError();
            return null;
        }
    }

    private void watchEndpointPreview(ProfileScreen screen, EditText server, EditText serverProfile, TextView preview) {
        boolean[] updating = {false};
        boolean[] pastedServer = {false};
        Runnable refresh = () -> {
            String serverText = server.getText().toString();
            String slugText = serverProfile.getText().toString().trim();
            if (serverText.trim().isEmpty()) {
                screen.setNote(preview, activity.getString(R.string.profile_server_slug_help), R.color.muted);
                return;
            }
            try {
                SplitEndpoint split = splitPastedEndpoint(serverText, slugText);
                if (split.conflict()) {
                    screen.setNote(preview, activity.getString(R.string.profile_server_slug_conflict), R.color.danger);
                    return;
                }
                if (pastedServer[0] && split.splitApplied()) {
                    updating[0] = true;
                    try {
                        server.setText(split.baseUrl());
                        server.setSelection(split.baseUrl().length());
                        serverProfile.setText(split.profileSlug());
                        serverProfile.setSelection(split.profileSlug().length());
                        serverProfile.setError(null);
                    } finally {
                        updating[0] = false;
                    }
                    screen.setNote(preview,
                            activity.getString(R.string.profile_setup_endpoint_detected,
                                    split.profileSlug(), split.composedEndpoint()),
                            R.color.accent);
                    return;
                }
                if (!split.profileSlug().isEmpty()) {
                    String text = split.splitApplied()
                            ? activity.getString(R.string.profile_setup_endpoint_detected,
                                    split.profileSlug(), split.composedEndpoint())
                            : activity.getString(R.string.profile_endpoint_preview, split.composedEndpoint());
                    screen.setNote(preview, text, R.color.muted);
                    return;
                }
                screen.setNote(preview,
                        activity.getString(R.string.profile_endpoint_preview, split.composedEndpoint()),
                        R.color.muted);
                return;
            } catch (IllegalArgumentException ignored) {
                // Fall back to neutral guidance while the user is still typing the URL or profile name.
            }
            screen.setNote(preview, activity.getString(R.string.profile_server_slug_help), R.color.muted);
        };
        server.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!updating[0]) pastedServer[0] = count > 1;
            }
            @Override public void afterTextChanged(Editable s) {
                if (updating[0]) return;
                refresh.run();
                pastedServer[0] = false;
            }
        });
        serverProfile.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (updating[0]) return;
                pastedServer[0] = false;
                refresh.run();
            }
        });
    }

    private String blockerMessage(ProfileLockPolicy.SetupBlocker blocker) {
        return switch (blocker) {
            case READY -> activity.getString(R.string.lock_setup_ready);
            case NAME_MISSING -> activity.getString(R.string.lock_setup_missing_name);
            case NAME_CONTROL -> activity.getString(R.string.lock_setup_invalid_name);
            case NAME_DUPLICATE -> activity.getString(R.string.lock_setup_duplicate_name);
            case SERVER_MISSING -> activity.getString(R.string.lock_setup_missing_server);
            case SERVER_INVALID -> activity.getString(R.string.lock_setup_invalid_server);
            case SLUG_INVALID -> activity.getString(R.string.lock_setup_invalid_slug);
            case SLUG_CONFLICT -> activity.getString(R.string.lock_setup_conflict_slug);
            case KEY_MISSING -> activity.getString(R.string.lock_setup_missing_key);
            case KEY_INVALID -> activity.getString(R.string.lock_setup_invalid_key);
            case BIOMETRIC_UNAVAILABLE -> activity.getString(R.string.lock_mode_biometric_unavailable);
            case PIN_EMPTY, PIN_INVALID, PIN_TOO_SHORT, PIN_CONFIRM_NEEDED, PIN_MISMATCH ->
                    activity.getString(R.string.lock_setup_missing_pin);
            case PASSPHRASE_EMPTY, PASSPHRASE_TOO_SHORT, PASSPHRASE_CONFIRM_NEEDED, PASSPHRASE_MISMATCH ->
                    activity.getString(R.string.lock_setup_missing_passphrase);
        };
    }

    private void watchPinStrength(ProfileScreen screen, EditText pin, EditText repeat, TextView hint, Runnable onChange) {
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                Editable primary = pin.getText();
                Editable confirm = repeat.getText();
                ProfileLockPolicy.CredentialState state = ProfileLockPolicy.evaluatePin(primary, confirm);
                switch (state) {
                    case EMPTY -> screen.setNote(hint, "", R.color.muted);
                    case INVALID_FORMAT -> screen.setNote(hint,
                            activity.getString(R.string.lock_pin_digits_only), R.color.danger);
                    case TOO_SHORT -> screen.setNote(hint,
                            activity.getString(R.string.lock_pin_progress,
                                    primary.length(), ProfileLockPolicy.MIN_PIN_DIGITS), R.color.muted);
                    case AWAITING_CONFIRM -> screen.setNote(hint,
                            activity.getString(R.string.lock_pin_confirm_needed), R.color.muted);
                    case MISMATCH -> screen.setNote(hint,
                            activity.getString(R.string.lock_pin_mismatch), R.color.danger);
                    case READY -> screen.setNote(hint,
                            activity.getString(R.string.lock_pin_ready), R.color.accent);
                }
                if (onChange != null) onChange.run();
            }
        };
        pin.addTextChangedListener(watcher);
        repeat.addTextChangedListener(watcher);
    }

    private void watchPassphraseStrength(ProfileScreen screen, EditText phrase, EditText repeat, TextView hint, Runnable onChange) {
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                Editable primary = phrase.getText();
                Editable confirm = repeat.getText();
                ProfileLockPolicy.CredentialState state = ProfileLockPolicy.evaluatePassphrase(primary, confirm);
                switch (state) {
                    case EMPTY -> screen.setNote(hint, "", R.color.muted);
                    case INVALID_FORMAT, TOO_SHORT -> screen.setNote(hint,
                            activity.getString(R.string.profile_setup_passphrase_progress,
                                    primary.length(), MIN_PASSPHRASE),
                            R.color.muted);
                    case AWAITING_CONFIRM -> screen.setNote(hint,
                            activity.getString(R.string.profile_setup_passphrase_confirm_needed),
                            R.color.muted);
                    case MISMATCH -> screen.setNote(hint,
                            activity.getString(R.string.profile_setup_passphrase_mismatch_live),
                            R.color.danger);
                    case READY -> screen.setNote(hint,
                            activity.getString(R.string.profile_setup_passphrase_ready),
                            R.color.accent);
                }
                if (onChange != null) onChange.run();
            }
        };
        phrase.addTextChangedListener(watcher);
        repeat.addTextChangedListener(watcher);
    }

    /** Non-consuming connection probe for a staged pairing ticket via {@code POST /api/mobile/pairing/inspect}. */
    private void runStagedPairingProbe(
            ProfileScreen screen,
            Button testButton,
            TextView statusView,
            FirstRunWizard.StagedPairing staged,
            Runnable clearStaged) {
        int screenEpoch = generation;
        int probeEpoch = probe.begin(() -> {
            testButton.setEnabled(true);
            screen.setNote(statusView, "", R.color.muted);
        });
        testButton.setEnabled(false);
        screen.setNote(statusView, activity.getString(R.string.profile_setup_testing), R.color.muted);
        HermesConfig cfg = HermesConfig.load(activity);
        HermesApi.Limits probeLimits = new HermesApi.Limits(
                cfg.probeTimeoutMs, cfg.probeTimeoutMs, cfg.httpJsonMaxBytes, cfg.httpEventMaxBytes);
        PROBE_IO.execute(() -> {
            long startMs = SystemClock.elapsedRealtime();
            int outcome = 0; // 0=ok, 1=expired(410), 2=legacy(404), 3=unreachable
            try {
                JSONObject raw = HermesApi.inspectPairing(staged.serverUrl(), staged.pairingCode(), probeLimits);
                OnboardingModels.parsePairingInspect(raw);
            } catch (HermesApi.HttpError http) {
                if (FirstRunWizard.isRejectedPairingError(http.status, http.code)) outcome = 1;
                else if (http.status == 404) outcome = 2;
                else outcome = 3;
            } catch (Exception err) {
                Diagnostics.warning("profile", "staged_pairing_probe_failed");
                outcome = 3;
            }
            long elapsedMs = Math.max(0L, SystemClock.elapsedRealtime() - startMs);
            int finalOutcome = outcome;
            activity.runOnUiThread(() -> {
                if (screenEpoch != generation || activity.isFinishing() || activity.isDestroyed()) return;
                if (!probe.finishUi(probeEpoch)) return;
                testButton.setEnabled(true);
                if (finalOutcome == 0) {
                    screen.setNote(statusView,
                            activity.getString(R.string.onboarding_pairing_probe_ok, elapsedMs), R.color.accent);
                } else if (finalOutcome == 1) {
                    clearStaged.run();
                    screen.setNote(statusView,
                            activity.getString(R.string.onboarding_paste_claim_failed), R.color.danger);
                } else if (finalOutcome == 2) {
                    screen.setNote(statusView,
                            activity.getString(R.string.onboarding_pairing_probe_legacy), R.color.accent);
                } else {
                    screen.setNote(statusView,
                            activity.getString(R.string.profile_setup_test_unreachable), R.color.danger);
                }
            });
        });
    }

    /**
     * Atomically creates a new profile (claiming any staged pairing ticket first), applies smart
     * lock defaults (chosen lock mode + 5-minute default timeout), and runs hardware biometric
     * enrollment when {@link ProfileLockPolicy.Mode#BIOMETRIC} is selected (rolling back fail-closed
     * without wiping form inputs if biometric enrollment is cancelled or fails).
     */
    private void saveProfileWithMode(
            ProfileScreen screen,
            Button action,
            String label,
            String endpoint,
            String manualBearer,
            FirstRunWizard.StagedPairing staged,
            ProfileLockPolicy.Mode lockMode,
            char[] pass,
            Runnable clearStaged,
            Consumer<OnboardingModels.PairingClaim> preserveClaimedOnRollback,
            Runnable refreshReadiness) {
        busy = true;
        int epoch = generation;
        action.setEnabled(false);
        InputMethodManager ime = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (ime != null) ime.hideSoftInputFromWindow(action.getWindowToken(), 0);
        screen.status(activity.getString(staged != null
                ? R.string.onboarding_paste_claiming : R.string.profile_setup_opening), false);
        HermesConfig cfg = HermesConfig.load(activity);
        HermesApi.Limits claimLimits = new HermesApi.Limits(
                cfg.probeTimeoutMs, cfg.probeTimeoutMs, cfg.httpJsonMaxBytes, cfg.httpEventMaxBytes);
        IO.execute(() -> {
            Account account = null;
            OnboardingModels.PairingClaim claimed = null;
            boolean expiredOrUsed = false;
            try {
                String finalEndpoint = endpoint;
                String finalBearer = manualBearer;
                if (staged != null) {
                    JSONObject raw = HermesApi.claimPairing(staged.serverUrl(), staged.pairingCode(), claimLimits);
                    claimed = OnboardingModels.parsePairingClaim(raw);
                    finalEndpoint = claimed.resolvedEndpoint();
                    finalBearer = claimed.apiKey();
                    Diagnostics.event("onboarding", "pairing_claimed");
                }
                account = store.create(label, finalEndpoint, finalBearer, pass);
                if (!ProfileLockPolicy.setMode(activity, account.id, lockMode)) {
                    throw new java.io.IOException("Lock mode commit failed");
                }
                if (!LockGracePolicy.setWindow(activity, account.id, LockGracePolicy.DEFAULT_FOR_NEW_PROFILE)) {
                    throw new java.io.IOException("Lock grace commit failed");
                }
            } catch (HermesApi.HttpError http) {
                if (http.status == 410 || http.status == 404) expiredOrUsed = true;
                Diagnostics.warning("onboarding", "pairing_claim_http_" + http.status);
            } catch (Exception err) {
                Diagnostics.warning("profile", "profile_save_failed");
                if (account != null) {
                    String orphanId = account.id;
                    account.close();
                    account = null;
                    try {
                        store.forget(orphanId);
                    } catch (java.io.IOException | RuntimeException cleanupErr) {
                        Diagnostics.warning("profile", "profile_save_rollback_forget_failed");
                    }
                }
            } finally {
                Arrays.fill(pass, '\0');
            }
            Account created = account;
            OnboardingModels.PairingClaim finalClaim = claimed;
            boolean finalExpired = expiredOrUsed;
            activity.runOnUiThread(() -> {
                if (epoch != generation || activity.isFinishing() || activity.isDestroyed()) {
                    if (created != null) {
                        created.close();
                        if (lockMode == ProfileLockPolicy.Mode.BIOMETRIC) {
                            try {
                                store.forget(created.id);
                            } catch (java.io.IOException ignored) {
                                Diagnostics.warning("profile", "biometric_rollback_forget_failed");
                            }
                        }
                    }
                    return;
                }
                if (created == null) {
                    busy = false;
                    if (resolveRollbackFields(finalClaim) != null) {
                        preserveClaimedOnRollback.accept(finalClaim);
                    } else {
                        if (finalExpired) clearStaged.run();
                        refreshReadiness.run();
                    }
                    screen.status(activity.getString(finalExpired
                            ? R.string.onboarding_paste_claim_failed
                            : R.string.profile_setup_save_failed), true);
                    return;
                }
                if (lockMode != ProfileLockPolicy.Mode.BIOMETRIC) {
                    busy = false;
                    stagedPairing = null;
                    lastPairingClaim = finalClaim;
                    wipeVisibleSecrets();
                    screen.status("", false);
                    opened(created);
                    return;
                }
                // Biometric-primary mode: seal hardware-backed envelope before opening workspace.
                cancelBiometric();
                BiometricEnrollmentGuard guard = new BiometricEnrollmentGuard();
                guard.arm(() -> {
                    if (pendingBiometricGuard == guard) pendingBiometricGuard = null;
                    activeBiometric = null;
                    created.close();
                    try {
                        store.forget(created.id);
                    } catch (java.io.IOException ignored) {
                        Diagnostics.warning("profile", "biometric_rollback_forget_failed");
                    }
                    if (epoch != generation || activity.isFinishing() || activity.isDestroyed()) return;
                    busy = false;
                    if (resolveRollbackFields(finalClaim) != null) {
                        preserveClaimedOnRollback.accept(finalClaim);
                    } else {
                        refreshReadiness.run();
                    }
                    screen.status(activity.getString(R.string.lock_mode_biometric_enroll_failed), true);
                });
                pendingBiometricGuard = guard;
                try {
                    byte[] payload = store.serializeForBiometric(created);
                    activeBiometric = BiometricVault.enroll(activity, created.id, created.label, payload,
                            new BiometricVault.EnrollmentCallback() {
                                @Override public void onEnrolled() {
                                    if (!guard.markEnrolled()) return;
                                    if (pendingBiometricGuard == guard) pendingBiometricGuard = null;
                                    activeBiometric = null;
                                    if (epoch != generation || activity.isFinishing() || activity.isDestroyed()) {
                                        created.close();
                                        return;
                                    }
                                    busy = false;
                                    stagedPairing = null;
                                    lastPairingClaim = finalClaim;
                                    wipeVisibleSecrets();
                                    screen.status("", false);
                                    opened(created);
                                }

                                @Override public void onCancelled() {
                                    guard.rollbackOnce();
                                }

                                @Override public void onError() {
                                    guard.rollbackOnce();
                                }
                            });
                } catch (java.io.IOException | RuntimeException err) {
                    Diagnostics.warning("biometric", "enroll_start_failed", err);
                    guard.rollbackOnce();
                }
            });
        });
    }

    private void runConnectionProbe(ProfileScreen screen, Button testButton, TextView statusView,
                                    String endpoint, String bearer) {
        int screenEpoch = generation;
        int probeEpoch = probe.begin(() -> {
            testButton.setEnabled(true);
            screen.setNote(statusView, "", R.color.muted);
        });
        testButton.setEnabled(false);
        screen.setNote(statusView, activity.getString(R.string.profile_setup_testing), R.color.muted);
        HermesConfig cfg = HermesConfig.load(activity);
        HermesApi.Limits probeLimits = new HermesApi.Limits(
                cfg.probeTimeoutMs, cfg.probeTimeoutMs, cfg.httpJsonMaxBytes, cfg.httpEventMaxBytes);
        PROBE_IO.execute(() -> executeCapabilitiesProbe(
                screenEpoch, probeEpoch, screen, testButton, statusView, endpoint, bearer, probeLimits));
    }

    private void runKeyRotationProbe(ProfileScreen screen, Button testButton, TextView statusView,
                                     String id, String newBearer, char[] pass) {
        int screenEpoch = generation;
        int probeEpoch = probe.begin(() -> {
            testButton.setEnabled(true);
            screen.setNote(statusView, "", R.color.muted);
        });
        testButton.setEnabled(false);
        screen.setNote(statusView, activity.getString(R.string.profile_setup_testing), R.color.muted);
        HermesConfig cfg = HermesConfig.load(activity);
        HermesApi.Limits probeLimits = new HermesApi.Limits(
                cfg.probeTimeoutMs, cfg.probeTimeoutMs, cfg.httpJsonMaxBytes, cfg.httpEventMaxBytes);
        PROBE_IO.execute(() -> {
            String endpoint = null;
            long appliedCooldown = 0L;
            try {
                if (!probe.isCurrent(probeEpoch)) return;
                Account opened = store.open(id, pass);
                try {
                    endpoint = opened.endpoint;
                } finally {
                    opened.close();
                }
                safeResetBackoff(id);
            } catch (Exception ignored) {
                Diagnostics.warning("profile", "probe_unlock_failed");
                appliedCooldown = safeRecordBackoffFailure(id);
            } finally {
                Arrays.fill(pass, '\0');
            }
            if (endpoint == null) {
                long cooldown = appliedCooldown;
                activity.runOnUiThread(() -> {
                    if (screenEpoch != generation || activity.isFinishing() || activity.isDestroyed()) return;
                    if (!probe.finishUi(probeEpoch)) return;
                    testButton.setEnabled(true);
                    String base = activity.getString(R.string.profile_setup_unlock_failed);
                    String msg = cooldown > 0L
                            ? base + " " + activity.getString(R.string.profile_unlock_cooldown,
                                    UnlockBackoff.formatRemaining(cooldown))
                            : base;
                    screen.setNote(statusView, msg, R.color.danger);
                });
                return;
            }
            executeCapabilitiesProbe(
                    screenEpoch, probeEpoch, screen, testButton, statusView, endpoint, newBearer, probeLimits);
        });
    }

    private void executeCapabilitiesProbe(int screenEpoch, int probeEpoch, ProfileScreen screen,
                                          Button testButton, TextView statusView,
                                          String endpoint, String bearer, HermesApi.Limits probeLimits) {
        HermesApi probeApi = null;
        long startMs = SystemClock.elapsedRealtime();
        JSONObject capabilities = null;
        Throwable failure = null;
        try {
            probeApi = new HermesApi(endpoint, bearer, probeLimits);
            if (!probe.attach(probeEpoch, probeApi)) {
                probeApi.revoke();
                return;
            }
            capabilities = probeApi.get("/v1/capabilities");
        } catch (Throwable error) {
            failure = error;
            Diagnostics.warning("profile", "connection_probe_failed");
        } finally {
            if (probeApi != null) {
                probe.detach(probeApi);
                probeApi.revoke();
            }
        }
        ProbeResult result = classifyProbeResult(capabilities, failure, SystemClock.elapsedRealtime() - startMs);
        activity.runOnUiThread(() -> {
            if (screenEpoch != generation || activity.isFinishing() || activity.isDestroyed()) return;
            if (!probe.finishUi(probeEpoch)) return;
            testButton.setEnabled(true);
            presentProbeResult(screen, statusView, result);
        });
    }

    private void presentProbeResult(ProfileScreen screen, TextView statusView, ProbeResult result) {
        switch (result.outcome()) {
            case READY -> screen.setNote(statusView,
                    activity.getString(R.string.profile_setup_test_ready, result.latencyMs()),
                    R.color.accent);
            case OUTDATED_SERVER -> screen.setNote(statusView,
                    activity.getString(R.string.profile_setup_test_outdated, result.latencyMs()),
                    R.color.warning);
            case AUTH_REJECTED -> screen.setNote(statusView,
                    activity.getString(R.string.profile_setup_test_auth_rejected),
                    R.color.danger);
            case NOT_FOUND -> screen.setNote(statusView,
                    activity.getString(R.string.profile_setup_test_not_found),
                    R.color.danger);
            case SERVER_ERROR -> screen.setNote(statusView,
                    activity.getString(R.string.profile_setup_test_server_error, result.httpStatus()),
                    R.color.danger);
            case UNREACHABLE -> screen.setNote(statusView,
                    activity.getString(R.string.profile_setup_test_unreachable),
                    R.color.danger);
        }
    }

    private void unlock(String id, String label) {
        int screenEpoch = beginScreen(true);
        ProfileLockPolicy.Mode lockMode = ProfileLockPolicy.mode(activity, id);
        boolean bioAvailable = BiometricVault.availability(activity) != BiometricVault.Availability.UNAVAILABLE;
        boolean bioEnrolled = bioAvailable && BiometricVault.isEnrolled(activity, id);
        ProfileScreen screen = new ProfileScreen(activity, label, activity.getString(R.string.profile_unlock_subtitle));

        if (lockMode == ProfileLockPolicy.Mode.BIOMETRIC) {
            if (bioEnrolled) {
                Button bioButton = screen.action(activity.getString(R.string.security_biometric_button), true, null);
                bioButton.setOnClickListener(view -> authenticateBiometric(screen, bioButton, id, label, this::opened));
                screen.action(activity.getString(R.string.profile_choose_other), false, view -> { if (!busy) chooser(null); });
                screen.action(activity.getString(R.string.profile_remove_action), false,
                        view -> authenticateBiometric(screen, (Button) view, id, label, account -> {
                            account.close();
                            confirmForget(screen, id, label);
                        }));
                // Auto-trigger OS biometric prompt once the unlock screen renders.
                activity.getWindow().getDecorView().post(() -> {
                    if (screenEpoch == generation && !busy && !activity.isFinishing() && !activity.isDestroyed()) {
                        authenticateBiometric(screen, bioButton, id, label, this::opened);
                    }
                });
            } else {
                screen.status(activity.getString(R.string.lock_mode_biometric_invalidated_recovery), true);
                screen.action(activity.getString(R.string.profile_choose_other), false, view -> { if (!busy) chooser(null); });
                screen.action(activity.getString(R.string.profile_remove_action), true, view -> confirmForget(screen, id, label));
            }
            return;
        }

        boolean pinMode = lockMode == ProfileLockPolicy.Mode.PIN;
        EditText credential = screen.field(
                activity.getString(pinMode ? R.string.lock_pin_label : R.string.profile_passphrase_label),
                pinMode ? activity.getString(R.string.lock_pin_hint) : "",
                pinMode ? PIN_INPUT : SECRET,
                pinMode ? ProfileLockPolicy.MAX_PIN_DIGITS : 512,
                true);
        clearVisibleSecrets = () -> credential.getText().clear();
        screen.action(activity.getString(R.string.profile_unlock_action), true,
                view -> authenticate(screen, credential, (Button) view, id, pinMode, this::opened));
        if (bioEnrolled) {
            Button bioButton = screen.action(activity.getString(R.string.security_biometric_button), false, null);
            bioButton.setOnClickListener(view -> authenticateBiometric(screen, bioButton, id, label, this::opened));
        }
        screen.action(activity.getString(R.string.profile_choose_other), false, view -> { if (!busy) chooser(null); });
        screen.action(activity.getString(R.string.profile_replace_key), false, view -> { if (!busy) rotate(id, label); });
        screen.action(activity.getString(R.string.profile_remove_action), false,
                view -> authenticate(screen, credential, (Button) view, id, pinMode, account -> {
                    account.close();
                    confirmForget(screen, id, label);
                }));
    }

    private void confirmForget(ProfileScreen screen, String id, String label) {
        dismissConfirmation();
        confirmation = Ui.secureDialog(new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.profile_setup_remove_title, label))
                .setMessage(activity.getString(R.string.profile_setup_remove_body))
                .setNegativeButton(R.string.profile_setup_cancel, null)
                .setPositiveButton(R.string.profile_setup_remove_confirm, (dialog, which) -> forget(screen, id)));
    }

    private void authenticateBiometric(
            ProfileScreen screen, Button bioButton, String id, String label, Consumer<Account> onSuccess) {
        if (busy) return;
        cancelBiometric();
        busy = true;
        int epoch = generation;
        bioButton.setEnabled(false);
        BiometricVault.Session session = BiometricVault.authenticate(activity, id, label, new BiometricVault.Callback() {
            @Override public void onSuccess(byte[] payload) {
                activeBiometric = null;
                Account unlocked = null;
                boolean handedOff = false;
                try {
                    if (epoch != generation || activity.isFinishing() || activity.isDestroyed()) {
                        return;
                    }
                    busy = false;
                    bioButton.setEnabled(true);
                    unlocked = store.openBiometric(id, payload);
                    safeResetBackoff(id);
                    wipeVisibleSecrets();
                    screen.status("", false);
                    handedOff = true;
                    onSuccess.accept(unlocked);
                } catch (Exception error) {
                    if (unlocked != null && !handedOff) unlocked.close();
                    if (epoch == generation) {
                        busy = false;
                        bioButton.setEnabled(true);
                    }
                    Diagnostics.warning("biometric", "account_open_failed");
                    screen.status(activity.getString(R.string.security_biometric_failed), true);
                } finally {
                    if (payload != null) Arrays.fill(payload, (byte) 0);
                }
            }

            @Override public void onCancelled() {
                activeBiometric = null;
                if (epoch != generation) return;
                busy = false;
                bioButton.setEnabled(true);
            }

            @Override public void onInvalidated() {
                activeBiometric = null;
                if (epoch != generation) return;
                busy = false;
                if (ProfileLockPolicy.mode(activity, id) == ProfileLockPolicy.Mode.BIOMETRIC) {
                    unlock(id, label);
                    return;
                }
                bioButton.setVisibility(View.GONE);
                screen.status(activity.getString(R.string.security_biometric_invalidated), true);
            }

            @Override public void onError() {
                activeBiometric = null;
                if (epoch != generation) return;
                busy = false;
                bioButton.setEnabled(true);
                screen.status(activity.getString(R.string.security_biometric_failed), true);
            }
        });
        if (session != null) {
            session.withOnCancel(() -> {
                if (activeBiometric == session) activeBiometric = null;
                if (epoch == generation) {
                    busy = false;
                    bioButton.setEnabled(true);
                }
            });
        }
        activeBiometric = session;
    }

    private void rotate(String id, String label) {
        beginScreen(true);
        boolean pinMode = ProfileLockPolicy.mode(activity, id) == ProfileLockPolicy.Mode.PIN;
        ProfileScreen screen = new ProfileScreen(activity,
                activity.getString(R.string.profile_replace_key),
                activity.getString(R.string.profile_setup_rotate_detail, label));
        EditText key = screen.field(activity.getString(R.string.profile_setup_new_key_label), "", SECRET, 8192, true);
        EditText phrase = screen.field(
                activity.getString(pinMode ? R.string.lock_pin_label : R.string.profile_passphrase_label),
                pinMode ? activity.getString(R.string.lock_pin_hint) : "",
                pinMode ? PIN_INPUT : SECRET,
                pinMode ? ProfileLockPolicy.MAX_PIN_DIGITS : 512, true);
        clearVisibleSecrets = () -> {
            key.getText().clear();
            phrase.getText().clear();
        };
        Button testButton = screen.action(activity.getString(R.string.profile_setup_test_key_action), false, null);
        TextView probeStatus = screen.liveNote("");
        testButton.setOnClickListener(view -> {
            if (busy) return;
            long waitMs = backoff.remainingMs(id);
            if (waitMs > 0L) {
                Diagnostics.warning("profile", "unlock_throttled");
                screen.setNote(probeStatus,
                        activity.getString(R.string.profile_unlock_cooldown, UnlockBackoff.formatRemaining(waitMs)),
                        R.color.danger);
                return;
            }
            String bearer = checkedBearer(key);
            if (bearer == null) return;
            char[] pass = password(phrase);
            if (pass.length == 0) {
                invalid(phrase, activity.getString(pinMode
                        ? R.string.lock_pin_required : R.string.profile_setup_passphrase_required));
                return;
            }
            phrase.getText().clear();
            runKeyRotationProbe(screen, testButton, probeStatus, id, bearer, pass);
        });
        screen.action(activity.getString(R.string.profile_setup_rotate_save), true, view -> {
            if (busy) return;
            long waitMs = backoff.remainingMs(id);
            if (waitMs > 0L) {
                Diagnostics.warning("profile", "unlock_throttled");
                screen.status(activity.getString(R.string.profile_unlock_cooldown,
                        UnlockBackoff.formatRemaining(waitMs)), true);
                return;
            }
            String bearer = checkedBearer(key);
            if (bearer == null) return;
            char[] pass = password(phrase);
            if (pass.length == 0) {
                invalid(phrase, activity.getString(pinMode
                        ? R.string.lock_pin_required : R.string.profile_setup_passphrase_required));
                return;
            }
            key.getText().clear();
            phrase.getText().clear();
            cancelProbe(true);
            run(screen, (Button) view, id, pass, () -> store.replaceBearer(id, pass, bearer), this::opened,
                    activity.getString(R.string.profile_setup_rotate_failed));
        });
        screen.action(activity.getString(R.string.profile_setup_cancel), false, view -> {
            if (busy) return;
            wipeVisibleSecrets();
            unlock(id, label);
        });
    }

    private String checkedBearer(EditText field) {
        String bearer = field.getText().toString().trim();
        if (!bearer.isEmpty() && bearer.chars().allMatch(c -> c >= 33 && c <= 126)) return bearer;
        invalid(field, activity.getString(R.string.profile_setup_key_invalid));
        return null;
    }

    private void authenticate(ProfileScreen screen, EditText phrase, Button action, String id,
                              boolean pinMode, Consumer<Account> success) {
        if (busy) return;
        long waitMs = backoff.remainingMs(id);
        if (waitMs > 0L) {
            Diagnostics.warning("profile", "unlock_throttled");
            screen.status(activity.getString(R.string.profile_unlock_cooldown,
                    UnlockBackoff.formatRemaining(waitMs)), true);
            return;
        }
        char[] pass = password(phrase);
        if (pass.length == 0) {
            invalid(phrase, activity.getString(pinMode
                    ? R.string.lock_pin_required : R.string.profile_setup_passphrase_required));
            return;
        }
        phrase.getText().clear();
        run(screen, action, id, pass, () -> store.open(id, pass), success,
                activity.getString(R.string.profile_setup_unlock_failed));
    }

    private void forget(ProfileScreen screen, String id) {
        try {
            // Persist notification revocation, close active account keys, and wipe all profile stores atomically.
            onForget.accept(id);
            if (active != null && id.equals(active.id)) {
                NotificationAllowlist.wipe(active);
                active.close();
                active = null;
            } else {
                NotificationAllowlist.wipe(null);
            }
            store.forget(id);
        } catch (java.io.IOException | RuntimeException ex) {
            Diagnostics.warning("profile", "forget_failed", ex);
            screen.status(activity.getString(R.string.profile_setup_remove_failed), true);
            return;
        }
        chooser(activity.getString(R.string.profile_setup_removed));
    }

    private interface Opening { Account get() throws Exception; }

    private void run(ProfileScreen screen, Button action, char[] pass, Opening work, Consumer<Account> success, String error) {
        run(screen, action, null, pass, work, success, error);
    }

    private void run(ProfileScreen screen, Button action, String throttleProfileId, char[] pass,
                     Opening work, Consumer<Account> success, String error) {
        busy = true;
        int epoch = generation;
        action.setEnabled(false);
        InputMethodManager ime = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (ime != null) ime.hideSoftInputFromWindow(action.getWindowToken(), 0);
        screen.status(activity.getString(R.string.profile_setup_opening), false);
        IO.execute(() -> {
            Account account = null;
            try {
                account = work.get();
            } catch (Exception ignored) {
                // The gate reports a single failure without exposing credentials or ciphertext.
                Diagnostics.warning("profile", "unlock_failed", ignored);
            } finally {
                Arrays.fill(pass, '\0');
            }
            long cooldownMs = 0L;
            if (throttleProfileId != null) {
                if (account != null) safeResetBackoff(throttleProfileId);
                else cooldownMs = safeRecordBackoffFailure(throttleProfileId);
            }
            Account result = account;
            long appliedCooldown = cooldownMs;
            activity.runOnUiThread(() -> {
                if (epoch != generation || activity.isFinishing() || activity.isDestroyed()) {
                    if (result != null) result.close();
                    return;
                }
                busy = false;
                action.setEnabled(true);
                if (result == null) {
                    String msg = appliedCooldown > 0L
                            ? error + " " + activity.getString(R.string.profile_unlock_cooldown,
                                    UnlockBackoff.formatRemaining(appliedCooldown))
                            : error;
                    screen.status(msg, true);
                } else {
                    screen.status("", false);
                    success.accept(result);
                }
            });
        });
    }

    private void safeResetBackoff(String profileId) {
        try {
            backoff.reset(profileId);
        } catch (IllegalStateException failed) {
            Diagnostics.warning("profile", "backoff_reset_failed");
        }
    }

    private long safeRecordBackoffFailure(String profileId) {
        try {
            return backoff.recordFailure(profileId);
        } catch (IllegalStateException failed) {
            Diagnostics.warning("profile", "backoff_record_failed");
            return 0L;
        }
    }

    private void opened(Account account) {
        cancelProbe();
        active = account;
        nested = false;
        onOpen.accept(account);
    }

    private static boolean hasControl(String text) {
        return text.chars().anyMatch(Character::isISOControl);
    }

    private static char[] password(EditText edit) {
        char[] chars = new char[edit.length()];
        edit.getText().getChars(0, chars.length, chars, 0);
        return chars;
    }

    private static void invalid(EditText field, String message) {
        field.setError(message);
        field.requestFocus();
    }
}
