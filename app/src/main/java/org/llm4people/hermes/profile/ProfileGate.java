package org.llm4people.hermes.profile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
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
import java.net.URI;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Local authentication flow. Its owner must discard gateway clients and account references on lock. */
public final class ProfileGate {
    static final int MIN_PASSPHRASE = 12;
    static final int PROBE_TIMEOUT_MS = 8_000;
    private static final int TEXT = InputType.TYPE_CLASS_TEXT;
    private static final int SECRET = TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
    private static final Pattern TRAILING_PROFILE_PATH = Pattern.compile("^(.*)/p/([^/]+)$");
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

    enum PassphraseState {
        EMPTY,
        TOO_SHORT,
        AWAITING_CONFIRM,
        MISMATCH,
        READY
    }

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

    private final Activity activity;
    private final AccountStore store;
    private final UnlockBackoff backoff;
    private Consumer<Account> onOpen;
    private Consumer<String> onForget = id -> {};
    private Account active;
    private AlertDialog confirmation;
    private BiometricVault.Session activeBiometric;
    private final ProbeController probe = new ProbeController();
    private PairingLinkParser.ParsedInput pendingPairing;
    private OnboardingModels.PairingClaim lastPairingClaim;
    private FirstRunWizard.StagedPairing stagedPairing;
    private int generation;
    private boolean nested;
    private boolean busy;

    /** Queues a {@code hermes://pair} deep link to be confirmed on the connect screen. */
    public void setPendingPairing(PairingLinkParser.ParsedInput parsed) {
        pendingPairing = parsed;
    }

    /** Opens the connect screen directly with an incoming pairing link requiring explicit confirmation. */
    public void openAddWithPairing(PairingLinkParser.ParsedInput parsed) {
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

    /** Pure passphrase readiness classifier (takes lengths + match flag so no String copies are required). */
    static PassphraseState evaluatePassphrase(int length, int confirmLength, boolean matches) {
        int safeLen = Math.max(0, length);
        int safeConfirm = Math.max(0, confirmLength);
        if (safeLen == 0 && safeConfirm == 0) return PassphraseState.EMPTY;
        if (safeLen < MIN_PASSPHRASE) return PassphraseState.TOO_SHORT;
        if (safeConfirm == 0) return PassphraseState.AWAITING_CONFIRM;
        return matches ? PassphraseState.READY : PassphraseState.MISMATCH;
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
        String normalizedBase = GatewayAddress.normalize(serverInput);
        String trimmedSlug = currentSlug == null ? "" : currentSlug.trim();
        URI uri = URI.create(normalizedBase);
        String origin = uri.getScheme() + "://" + uri.getRawAuthority();
        String path = uri.getRawPath();
        Matcher matcher = TRAILING_PROFILE_PATH.matcher(path);
        if (!matcher.matches()) {
            if (trimmedSlug.isEmpty()) {
                return new SplitEndpoint(normalizedBase, "", normalizedBase, false, false);
            }
            String slug = GatewayAddress.normalizeProfileSlug(trimmedSlug);
            String composed = GatewayAddress.normalize(normalizedBase, slug);
            return new SplitEndpoint(normalizedBase, slug, composed, false, false);
        }
        String rawExtracted = matcher.group(2);
        String extractedSlug = GatewayAddress.normalizeProfileSlug(rawExtracted);
        String baseWithoutProfile = origin + matcher.group(1);
        String composed = origin + matcher.group(1) + "/p/" + extractedSlug;
        if (trimmedSlug.isEmpty()) {
            return new SplitEndpoint(baseWithoutProfile, extractedSlug, composed, true, false);
        }
        String normalizedCurrent = GatewayAddress.normalizeProfileSlug(trimmedSlug);
        if (normalizedCurrent.equalsIgnoreCase(extractedSlug)) {
            return new SplitEndpoint(baseWithoutProfile, extractedSlug, composed, true, false);
        }
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
        invalidate();
        if (onOpen != null && !activity.isDestroyed() && !activity.isFinishing()) {
            if (store.profiles().isEmpty()) add(false);
            else chooser(null);
        }
    }

    /** Permanently tears down the gate on Activity destroy: cancels biometrics, revokes probes, dismisses dialogs, zeroes Account keys. */
    public void close() {
        onOpen = null;
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
    }

    private void cancelProbe() {
        cancelProbe(false);
    }

    private void cancelProbe(boolean resetUi) {
        probe.cancel(resetUi);
    }

    private void invalidate() {
        generation++;
        busy = false;
        nested = false;
        stagedPairing = null;
        cancelBiometric();
        cancelProbe();
        if (confirmation != null) {
            confirmation.dismiss();
            confirmation = null;
        }
        if (active != null) {
            active.close();
            active = null;
        }
    }

    /** Tracked like every gate dialog so lock, switch and close dismiss it. */
    private void showLanguagePicker() {
        if (confirmation != null) confirmation.dismiss();
        confirmation = AppLanguage.showPicker(activity, null);
    }

    private void chooser(String message) {
        generation++;
        cancelProbe();
        nested = false;
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
        generation++;
        int screenEpoch = generation;
        cancelProbe();
        nested = canGoBack;
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
                                stagedSummaryRef[0].setVisibility(android.view.View.GONE);
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
                        if (focusPassphrase && fields[4] != null) {
                            fields[4].requestFocus();
                        }
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
                            stagedSummaryRef[0].setVisibility(android.view.View.VISIBLE);
                        }
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
                stagedSummaryRef[0].setVisibility(android.view.View.GONE);
            }
        };
        key.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (!programmaticUpdate[0] && stagedPairing != null && s.length() > 0) {
                    clearStaged.run();
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
        stagedSummary.setVisibility(android.view.View.GONE);
        stagedSummaryRef[0] = stagedSummary;
        EditText phrase = screen.field(activity.getString(R.string.profile_passphrase_label),
                activity.getResources().getQuantityString(R.plurals.profile_passphrase_hint, MIN_PASSPHRASE, MIN_PASSPHRASE),
                SECRET, 512, true);
        fields[0] = name;
        fields[1] = server;
        fields[2] = serverProfile;
        fields[3] = key;
        fields[4] = phrase;
        EditText repeat = screen.field(activity.getString(R.string.profile_passphrase_confirm_label), "", SECRET, 512, true);
        TextView passphraseHint = screen.liveNote("");
        watchPassphraseStrength(screen, phrase, repeat, passphraseHint);
        screen.action(activity.getString(R.string.profile_save_action), true, view -> {
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
            char[] pass = password(phrase);
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
            cancelProbe(true);
            if (useStaged) {
                claimAndCreate(screen, (Button) view, label, activeStaged, pass, clearStaged);
            } else {
                run(screen, (Button) view, pass, () -> store.create(label, endpoint, bearer, pass),
                        this::opened, activity.getString(R.string.profile_setup_save_failed));
            }
        });
        if (canGoBack) screen.action(activity.getString(R.string.profile_back_to_profiles), false, view -> { if (!busy) chooser(null); });
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

    private void watchPassphraseStrength(ProfileScreen screen, EditText phrase, EditText repeat, TextView hint) {
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                Editable primary = phrase.getText();
                Editable confirm = repeat.getText();
                PassphraseState state = evaluatePassphrase(primary.length(), confirm.length(), sameChars(primary, confirm));
                switch (state) {
                    case EMPTY -> screen.setNote(hint, "", R.color.muted);
                    case TOO_SHORT -> screen.setNote(hint,
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
                PROBE_TIMEOUT_MS, PROBE_TIMEOUT_MS, cfg.httpJsonMaxBytes, cfg.httpEventMaxBytes);
        PROBE_IO.execute(() -> {
            long startMs = SystemClock.elapsedRealtime();
            int outcome = 0; // 0=ok, 1=expired(410), 2=legacy(404), 3=unreachable
            try {
                JSONObject raw = HermesApi.inspectPairing(staged.serverUrl(), staged.pairingCode(), probeLimits);
                OnboardingModels.parsePairingInspect(raw);
            } catch (HermesApi.HttpError http) {
                if (http.status == 410) outcome = 1;
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

    /** Atomically claims a staged pairing ticket and creates the encrypted local profile only after Save. */
    private void claimAndCreate(
            ProfileScreen screen,
            Button action,
            String label,
            FirstRunWizard.StagedPairing staged,
            char[] pass,
            Runnable clearStaged) {
        busy = true;
        int epoch = generation;
        action.setEnabled(false);
        InputMethodManager ime = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (ime != null) ime.hideSoftInputFromWindow(action.getWindowToken(), 0);
        screen.status(activity.getString(R.string.onboarding_paste_claiming), false);
        HermesConfig cfg = HermesConfig.load(activity);
        HermesApi.Limits claimLimits = new HermesApi.Limits(
                PROBE_TIMEOUT_MS, PROBE_TIMEOUT_MS, cfg.httpJsonMaxBytes, cfg.httpEventMaxBytes);
        IO.execute(() -> {
            Account account = null;
            OnboardingModels.PairingClaim claimed = null;
            boolean expiredOrUsed = false;
            try {
                JSONObject raw = HermesApi.claimPairing(staged.serverUrl(), staged.pairingCode(), claimLimits);
                claimed = OnboardingModels.parsePairingClaim(raw);
                account = store.create(label, claimed.resolvedEndpoint(), claimed.apiKey(), pass);
                Diagnostics.event("onboarding", "pairing_claimed");
            } catch (HermesApi.HttpError http) {
                if (http.status == 410 || http.status == 404) expiredOrUsed = true;
                Diagnostics.warning("onboarding", "pairing_claim_http_" + http.status);
            } catch (Exception err) {
                Diagnostics.warning("onboarding", "pairing_claim_save_failed");
            } finally {
                Arrays.fill(pass, '\0');
            }
            Account result = account;
            OnboardingModels.PairingClaim finalClaim = claimed;
            boolean finalExpired = expiredOrUsed;
            activity.runOnUiThread(() -> {
                if (epoch != generation || activity.isFinishing() || activity.isDestroyed()) {
                    if (result != null) result.close();
                    return;
                }
                busy = false;
                action.setEnabled(true);
                if (result == null) {
                    if (finalExpired) clearStaged.run();
                    screen.status(activity.getString(finalExpired
                            ? R.string.onboarding_paste_claim_failed
                            : R.string.profile_setup_save_failed), true);
                } else {
                    stagedPairing = null;
                    lastPairingClaim = finalClaim;
                    screen.status("", false);
                    opened(result);
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
                PROBE_TIMEOUT_MS, PROBE_TIMEOUT_MS, cfg.httpJsonMaxBytes, cfg.httpEventMaxBytes);
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
                PROBE_TIMEOUT_MS, PROBE_TIMEOUT_MS, cfg.httpJsonMaxBytes, cfg.httpEventMaxBytes);
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
                backoff.reset(id);
            } catch (Exception ignored) {
                Diagnostics.warning("profile", "probe_unlock_failed");
                appliedCooldown = backoff.recordFailure(id);
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
        generation++;
        cancelProbe();
        nested = true;
        ProfileScreen screen = new ProfileScreen(activity, label, activity.getString(R.string.profile_unlock_subtitle));
        EditText phrase = screen.field(activity.getString(R.string.profile_passphrase_label), "", SECRET, 512, true);
        screen.action(activity.getString(R.string.profile_unlock_action), true, view -> authenticate(screen, phrase, (Button) view, id, this::opened));
        if (BiometricVault.isEnrolled(activity, id)
                && BiometricVault.availability(activity) != BiometricVault.Availability.UNAVAILABLE) {
            Button bioButton = screen.action(activity.getString(R.string.security_biometric_button), false, null);
            bioButton.setOnClickListener(view -> authenticateBiometric(screen, bioButton, id, label));
        }
        screen.action(activity.getString(R.string.profile_choose_other), false, view -> { if (!busy) chooser(null); });
        screen.action(activity.getString(R.string.profile_replace_key), false, view -> { if (!busy) rotate(id, label); });
        screen.action(activity.getString(R.string.profile_remove_action), false, view -> authenticate(screen, phrase, (Button) view, id, account -> {
            account.close();
            confirmation = Ui.secureDialog(new AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.profile_setup_remove_title, label))
                    .setMessage(activity.getString(R.string.profile_setup_remove_body))
                    .setNegativeButton(R.string.profile_setup_cancel, null)
                    .setPositiveButton(R.string.profile_setup_remove_confirm, (dialog, which) -> forget(screen, id)));
        }));
    }

    private void authenticateBiometric(ProfileScreen screen, Button bioButton, String id, String label) {
        if (busy) return;
        cancelBiometric();
        busy = true;
        int epoch = generation;
        bioButton.setEnabled(false);
        activeBiometric = BiometricVault.authenticate(activity, id, label, new BiometricVault.Callback() {
            @Override public void onSuccess(byte[] payload) {
                activeBiometric = null;
                if (epoch != generation || activity.isFinishing() || activity.isDestroyed()) {
                    Arrays.fill(payload, (byte) 0);
                    return;
                }
                busy = false;
                bioButton.setEnabled(true);
                try {
                    Account unlocked = store.openBiometric(id, payload);
                    backoff.reset(id);
                    screen.status("", false);
                    opened(unlocked);
                } catch (Exception error) {
                    Diagnostics.warning("biometric", "account_open_failed");
                    screen.status(activity.getString(R.string.security_biometric_failed), true);
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
                bioButton.setVisibility(android.view.View.GONE);
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
    }

    private void rotate(String id, String label) {
        generation++;
        cancelProbe();
        nested = true;
        ProfileScreen screen = new ProfileScreen(activity,
                activity.getString(R.string.profile_replace_key),
                activity.getString(R.string.profile_setup_rotate_detail, label));
        EditText key = screen.field(activity.getString(R.string.profile_setup_new_key_label), "", SECRET, 8192, true);
        EditText phrase = screen.field(activity.getString(R.string.profile_passphrase_label), "", SECRET, 512, true);
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
                invalid(phrase, activity.getString(R.string.profile_setup_passphrase_required));
                return;
            }
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
                invalid(phrase, activity.getString(R.string.profile_setup_passphrase_required));
                return;
            }
            phrase.getText().clear();
            cancelProbe(true);
            run(screen, (Button) view, id, pass, () -> store.replaceBearer(id, pass, bearer), this::opened,
                    activity.getString(R.string.profile_setup_rotate_failed));
        });
        screen.action(activity.getString(R.string.profile_setup_cancel), false, view -> { if (!busy) unlock(id, label); });
    }

    private String checkedBearer(EditText field) {
        String bearer = field.getText().toString().trim();
        if (!bearer.isEmpty() && bearer.chars().allMatch(c -> c >= 33 && c <= 126)) return bearer;
        invalid(field, activity.getString(R.string.profile_setup_key_invalid));
        return null;
    }

    private void authenticate(ProfileScreen screen, EditText phrase, Button action, String id, Consumer<Account> success) {
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
            invalid(phrase, activity.getString(R.string.profile_setup_passphrase_required));
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
                if (account != null) backoff.reset(throttleProfileId);
                else cooldownMs = backoff.recordFailure(throttleProfileId);
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
