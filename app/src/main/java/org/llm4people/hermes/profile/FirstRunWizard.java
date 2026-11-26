package org.llm4people.hermes.profile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;
import org.llm4people.hermes.AppLanguage;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.HermesConfig;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.artifact.ThemePalette;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.onboarding.OnboardingModels;
import org.llm4people.hermes.onboarding.PairingLinkParser;
import org.llm4people.hermes.os.LockGracePolicy;
import org.llm4people.hermes.settings.AppThemeMode;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Zero-jargon 2-step first-run onboarding wizard integrated cleanly with {@link ProfileGate}:
 * <ul>
 *   <li>Step 1 ("Connect your Hermes"): 1-tap paste (reading the clipboard only on explicit tap) /
 *       {@code hermes://pair} deep-link claim with explicit user confirmation before filling
 *       credentials, while keeping every manual form field accessible and backward-compatible.</li>
 *   <li>Step 2 ("Make it yours"): Display name, language, appearance, quick-lock preference, and
 *       1-tap localized starter prompts from {@code GET /api/mobile/onboarding} (Editable Draft Rule).</li>
 * </ul>
 */
public final class FirstRunWizard {
    public static final String COMPLETED_KEY = "onboarding_v1_done";
    private static final ExecutorService PROFILE_SYNC_IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "hermes-onboarding-sync");
        t.setDaemon(true);
        return t;
    });
    private static final Set<HermesApi> PENDING_SYNCS = ConcurrentHashMap.newKeySet();

    /** Revoke all in-flight detached onboarding sync clients (called on lock, forget, or final destroy). */
    public static void revokePendingSyncs() {
        for (HermesApi client : PENDING_SYNCS) {
            if (PENDING_SYNCS.remove(client)) {
                client.revoke();
            }
        }
    }

    public enum ApplyAction {
        CLAIM_TICKET,
        FILL_CREDENTIALS,
        FILL_SERVER_ONLY
    }

    /** Pure-Java plan produced from a parsed link/clipboard payload (host-JVM testable). */
    public record Step1Plan(
            ApplyAction action,
            String serverUrl,
            String profileSlug,
            String pairingCode,
            String apiKey,
            String displayName,
            String suggestedName) {}

    /**
     * Verified but unconsumed pairing ticket staged on Step 1 until the user completes
     * local profile setup (passphrase + Save). Never burns the single-use code prematurely.
     */
    public record StagedPairing(
            String serverUrl,
            String pairingCode,
            String profileSlug,
            String displayName,
            long expiresAtEpochSec) {
        public StagedPairing {
            serverUrl = GatewayAddress.normalize(Objects.requireNonNull(serverUrl, "serverUrl"));
            pairingCode = pairingCode == null ? "" : pairingCode.trim();
            if (!PairingLinkParser.isValidPairingCode(pairingCode)) {
                throw new IllegalArgumentException("Invalid pairing code");
            }
            profileSlug = profileSlug == null ? "" : profileSlug.trim();
            displayName = displayName == null ? "" : displayName.trim();
        }

        /** Value written into the optional Server profile field (blank for default profile). */
        public String formProfileSlug() {
            return profileSlug.isEmpty() || "default".equalsIgnoreCase(profileSlug) ? "" : profileSlug;
        }

        /** Canonical endpoint combining {@link #serverUrl} and non-default {@link #profileSlug}. */
        public String resolvedEndpoint() {
            String slug = formProfileSlug();
            return slug.isEmpty()
                    ? GatewayAddress.normalize(serverUrl)
                    : GatewayAddress.normalize(serverUrl, slug);
        }

        /** Returns true iff the form's server URL + profile slug still target this staged ticket. */
        public boolean matchesTarget(String rawServerUrl, String rawSlug) {
            if (rawServerUrl == null || rawServerUrl.trim().isEmpty()) return false;
            try {
                String cleanSlug = rawSlug == null ? "" : rawSlug.trim();
                String formEndpoint = cleanSlug.isEmpty() || "default".equalsIgnoreCase(cleanSlug)
                        ? GatewayAddress.normalize(rawServerUrl)
                        : GatewayAddress.normalize(rawServerUrl, cleanSlug);
                return resolvedEndpoint().equalsIgnoreCase(formEndpoint);
            } catch (IllegalArgumentException invalid) {
                return false;
            }
        }
    }

    public interface Step1Targets {
        void fillFields(String suggestedName, String serverUrl, String profileSlug, String apiKey, boolean focusPassphrase);
        void stagePairing(StagedPairing staged);
        void triggerConnectionProbe(String endpoint, String bearer);
        void showInstantStatus(String message, int colorRes);
        void onClaimedMetadata(OnboardingModels.PairingClaim claim);
        void trackDialog(AlertDialog dialog);
        void onScanQrRequested();
    }

    public interface Step2Callbacks {
        void onThemeSelected(String themeMode);
        void onStageStarterDraft(String draftText);
        void onFinished();
    }

    /** Pure-Java commit plan for Step 2 preventing mid-dialog theme recreation and double recreates. */
    record Step2CommitPlan(boolean applyThemeCallback, boolean recreateForLocale, boolean willRecreate) {}

    static Step2CommitPlan planStep2Commit(
            String initialTheme,
            String selectedTheme,
            boolean currentActivityNight,
            boolean systemNight,
            boolean localeChanged,
            boolean needsManualLocaleRecreate) {
        String normalizedInitial = AppThemeMode.normalize(initialTheme);
        String normalizedSelected = AppThemeMode.normalize(selectedTheme);
        boolean themeChanged = !normalizedSelected.equals(normalizedInitial);
        boolean themeWillRecreate = themeChanged
                && AppThemeMode.willRecreateOnModeChange(currentActivityNight, normalizedSelected, systemNight);
        return new Step2CommitPlan(
                themeChanged,
                localeChanged && needsManualLocaleRecreate && !themeWillRecreate,
                themeWillRecreate || localeChanged);
    }

    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** Submit onboarding profile patch, tracking & revoking detached clients so lock/forget aborts them. */
    static Future<Boolean> submitProfileSync(
            HermesApi syncClient,
            boolean detached,
            String slug,
            String editedName,
            String langCode) {
        return submitProfileSync(syncClient, detached, slug, () -> {
            JSONObject patch = new JSONObject();
            if (editedName != null && !editedName.isEmpty()) patch.put("display_name", editedName);
            patch.put("preferred_language", langCode);
            syncClient.updateProfile(slug, patch);
        });
    }

    static Future<Boolean> submitProfileSync(
            HermesApi syncClient,
            boolean detached,
            String slug,
            ThrowingRunnable work) {
        if (syncClient == null || slug == null || slug.isEmpty() || work == null) {
            return CompletableFuture.completedFuture(false);
        }
        if (detached) {
            PENDING_SYNCS.add(syncClient);
        }
        try {
            return PROFILE_SYNC_IO.submit(() -> {
                try {
                    if (syncClient.isRevoked()) return false;
                    work.run();
                    return !syncClient.isRevoked();
                } catch (Exception ignored) {
                    Diagnostics.debug("onboarding", "profile_pref_sync_skipped");
                    return false;
                } finally {
                    if (detached) {
                        PENDING_SYNCS.remove(syncClient);
                        syncClient.revoke();
                    }
                }
            });
        } catch (RuntimeException | Error err) {
            if (detached) {
                PENDING_SYNCS.remove(syncClient);
                syncClient.revoke();
            }
            throw err;
        }
    }

    private FirstRunWizard() {}

    /** Pure-Java classifier converting any parsed onboarding input into an explicit Step-1 UI action. */
    public static Step1Plan planForInput(PairingLinkParser.ParsedInput parsed) {
        if (parsed == null) return null;
        String name = suggestedName(parsed.displayName(), parsed.profileSlug(), parsed.serverUrl());
        return switch (parsed.kind()) {
            case PAIRING_TICKET -> new Step1Plan(
                    ApplyAction.CLAIM_TICKET,
                    parsed.serverUrl(),
                    parsed.profileSlug(),
                    parsed.pairingCode(),
                    "",
                    parsed.displayName(),
                    name);
            case DIRECT_CREDENTIALS -> new Step1Plan(
                    ApplyAction.FILL_CREDENTIALS,
                    parsed.serverUrl(),
                    parsed.profileSlug(),
                    "",
                    parsed.apiKey(),
                    parsed.displayName(),
                    name);
            case SERVER_ONLY -> new Step1Plan(
                    ApplyAction.FILL_SERVER_ONLY,
                    parsed.serverUrl(),
                    parsed.profileSlug(),
                    "",
                    "",
                    parsed.displayName(),
                    name);
        };
    }

    /**
     * Distinct profile label shown in the pairing confirmation dialog.
     * Priority: explicit {@code displayName} -&gt; non-default {@code profileSlug} -&gt; localized
     * {@code defaultLabel}. NEVER falls back to the server URL hostname.
     */
    public static String confirmProfileLabel(String displayName, String profileSlug, String defaultLabel) {
        if (displayName != null && !displayName.trim().isEmpty()) return displayName.trim();
        if (profileSlug != null && !profileSlug.trim().isEmpty()
                && !"default".equalsIgnoreCase(profileSlug.trim())) {
            return profileSlug.trim();
        }
        return (defaultLabel != null && !defaultLabel.trim().isEmpty()) ? defaultLabel.trim() : "";
    }

    /**
     * Friendly profile name pre-filled into the Step-1 "Profile name" field for a pairing ticket.
     * Priority: explicit {@code displayName} -&gt; capitalized non-default {@code profileSlug} -&gt;
     * localized {@code defaultLabel}. NEVER falls back to the server URL hostname or hardcoded English.
     */
    public static String formProfileName(String displayName, String profileSlug, String defaultLabel) {
        if (displayName != null && !displayName.trim().isEmpty()) return displayName.trim();
        if (profileSlug != null && !profileSlug.trim().isEmpty()
                && !"default".equalsIgnoreCase(profileSlug.trim())) {
            String s = profileSlug.trim();
            return Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }
        return (defaultLabel != null && !defaultLabel.trim().isEmpty()) ? defaultLabel.trim() : "";
    }

    /** Derives a friendly profile display name from explicit name, slug, or server host. */
    public static String suggestedName(String displayName, String profileSlug, String serverUrl) {
        if (displayName != null && !displayName.trim().isEmpty()) return displayName.trim();
        if (profileSlug != null && !profileSlug.trim().isEmpty()) {
            String s = profileSlug.trim();
            return Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }
        if (serverUrl != null && !serverUrl.trim().isEmpty()) {
            try {
                String host = URI.create(serverUrl.trim()).getHost();
                if (host != null && !host.isEmpty()) return host;
            } catch (IllegalArgumentException ignored) {}
        }
        return "Hermes";
    }

    /**
     * Returns true when a {@code POST /api/mobile/pairing/inspect} HTTP error proves the ticket
     * itself is expired, already used, revoked, or unknown (as opposed to an older server lacking
     * the {@code /inspect} route, which returns HTTP 404 with an empty error {@code code}).
     */
    public static boolean isRejectedPairingError(int status, String code) {
        if (status == 410) return true;
        return status == 404 && code != null && code.startsWith("pairing_");
    }

    /**
     * Build a clean {@link StagedPairing} from a parsed link plan and a {@code /pairing/inspect}
     * response, always storing the clean base server URL (never duplicating {@code /p/<slug>}).
     */
    public static StagedPairing stagedFromInspect(Step1Plan plan, OnboardingModels.PairingInspect inspected) {
        Objects.requireNonNull(plan, "plan");
        if (inspected == null) {
            return new StagedPairing(
                    GatewayAddress.split(plan.serverUrl()).baseUrl(),
                    plan.pairingCode(),
                    plan.profileSlug(),
                    plan.displayName(),
                    0L);
        }
        String rawBase = !inspected.baseHost().isEmpty()
                ? inspected.baseHost()
                : (!plan.serverUrl().isEmpty() ? plan.serverUrl() : inspected.serverUrl());
        String cleanBase = GatewayAddress.split(rawBase).baseUrl();
        String chosenSlug = inspected.profileSlug().isEmpty() ? plan.profileSlug() : inspected.profileSlug();
        String chosenName = inspected.displayName().isEmpty() ? plan.displayName() : inspected.displayName();
        return new StagedPairing(
                cleanBase, plan.pairingCode(), chosenSlug, chosenName, inspected.expiresAtEpochSec());
    }

    /** Extracts the trailing {@code /p/<slug>} profile slug from a normalized account endpoint, or empty. */
    public static String extractSlugFromEndpoint(String endpoint) {
        if (endpoint == null || endpoint.trim().isEmpty()) return "";
        try {
            return GatewayAddress.split(endpoint).profileSlug();
        } catch (IllegalArgumentException invalid) {
            return "";
        }
    }

    public static boolean isCompleted(Account account) {
        if (account == null) return true;
        try {
            return "1".equals(account.read(COMPLETED_KEY));
        } catch (RuntimeException err) {
            Diagnostics.warning("onboarding", "is_completed_read_failed", err);
            return true;
        }
    }

    public static void markCompleted(Account account) {
        if (account == null) return;
        try {
            account.write(COMPLETED_KEY, "1");
        } catch (RuntimeException ignored) {
            Diagnostics.warning("onboarding", "mark_completed_failed");
        }
    }

    /** Reads the clipboard fail-closed and parses a pairing link only if recognized. */
    public static PairingLinkParser.ParsedInput detectClipboardLink(Activity activity) {
        if (activity == null) return null;
        try {
            ClipboardManager cm = activity.getSystemService(ClipboardManager.class);
            if (cm == null || !cm.hasPrimaryClip()) return null;
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return null;
            CharSequence text = clip.getItemAt(0).coerceToText(activity);
            if (text == null || text.length() == 0) return null;
            return PairingLinkParser.parseOrNull(text.toString());
        } catch (RuntimeException denied) {
            return null;
        }
    }

    /**
     * Attaches the Step-1 "Instant setup" card inside {@link ProfileScreen} above manual fields and
     * returns the handler to invoke once all form {@link EditText} fields are initialized.
     * Never reads clipboard contents during view construction; clipboard access happens strictly
     * inside the explicit paste-button click handler.
     */
    static Consumer<PairingLinkParser.ParsedInput> attachInstantSetupCard(
            Activity activity,
            ProfileScreen screen,
            ExecutorService io,
            BooleanSupplier isCurrent,
            Step1Targets targets) {
        return attachInstantSetupCard(activity, screen, io, isCurrent, targets,
                () -> detectClipboardLink(activity));
    }

    /** Package-private overload for deterministic clipboard-access testing. */
    static Consumer<PairingLinkParser.ParsedInput> attachInstantSetupCard(
            Activity activity,
            ProfileScreen screen,
            ExecutorService io,
            BooleanSupplier isCurrent,
            Step1Targets targets,
            Supplier<PairingLinkParser.ParsedInput> clipboardReader) {
        LinearLayout card = Ui.card(activity);

        TextView heading = Ui.text(activity, activity.getString(R.string.onboarding_paste_card_title), 16);
        heading.setTypeface(Ui.mediumTypeface());
        heading.setAccessibilityHeading(true);
        card.addView(heading);

        TextView subtitle = Ui.text(activity, activity.getString(R.string.onboarding_paste_card_hint), 13);
        subtitle.setTextColor(activity.getColor(R.color.muted));
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = Ui.dp(activity, 4);
        card.addView(subtitle, subLp);

        TextView statusNote = Ui.text(activity, "", 13);
        statusNote.setTextColor(activity.getColor(R.color.muted));
        statusNote.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        statusNote.setVisibility(View.GONE);

        AtomicInteger inspectEpoch = new AtomicInteger(0);
        Consumer<PairingLinkParser.ParsedInput> handleParsed = parsed -> {
            if (!isCurrent.getAsBoolean()) return;
            int requestEpoch = inspectEpoch.incrementAndGet();
            Step1Plan plan = planForInput(parsed);
            if (plan == null) {
                showStatus(activity, statusNote, activity.getString(R.string.onboarding_paste_invalid), R.color.danger);
                return;
            }
            if (plan.action() == ApplyAction.CLAIM_TICKET) {
                BooleanSupplier requestCurrent = () -> isCurrent.getAsBoolean() && inspectEpoch.get() == requestEpoch;
                inspectAndConfirm(activity, plan, statusNote, io, requestCurrent, targets);
            } else if (plan.action() == ApplyAction.FILL_CREDENTIALS) {
                targets.fillFields(plan.suggestedName(), plan.serverUrl(), plan.profileSlug(), plan.apiKey(), false);
                showStatus(activity, statusNote, activity.getString(R.string.onboarding_direct_applied), R.color.accent);
                targets.triggerConnectionProbe(parsed.composedEndpoint(), plan.apiKey());
            } else {
                targets.fillFields(plan.suggestedName(), plan.serverUrl(), plan.profileSlug(), "", false);
                showStatus(activity, statusNote, activity.getString(R.string.onboarding_endpoint_applied), R.color.accent);
            }
        };

        Button scanQrBtn = Ui.filledButton(activity, activity.getString(R.string.onboarding_scan_qr_action), v -> {
            if (!isCurrent.getAsBoolean()) return;
            targets.onScanQrRequested();
        });
        card.addView(scanQrBtn, topMargin(activity, 12));

        Button pasteBtn = Ui.outlinedButton(activity, activity.getString(R.string.onboarding_paste_action), v ->
                handlePasteTap(isCurrent, clipboardReader, handleParsed,
                        () -> showManualPasteDialog(activity, targets, handleParsed, statusNote)));
        card.addView(pasteBtn, topMargin(activity, 8));
        card.addView(statusNote, topMargin(activity, 8));

        screen.card(card, 14);
        return handleParsed;
    }

    /** Executes an explicit paste-button tap without ever reading clipboard contents when stale. */
    static void handlePasteTap(
            BooleanSupplier isCurrent,
            Supplier<PairingLinkParser.ParsedInput> clipboardReader,
            Consumer<PairingLinkParser.ParsedInput> onParsed,
            Runnable onManualFallback) {
        if (isCurrent == null || !isCurrent.getAsBoolean()) return;
        PairingLinkParser.ParsedInput fromClip = clipboardReader == null ? null : clipboardReader.get();
        if (fromClip != null) {
            if (onParsed != null) onParsed.accept(fromClip);
        } else if (onManualFallback != null) {
            onManualFallback.run();
        }
    }

    private static void showManualPasteDialog(
            Activity activity,
            Step1Targets targets,
            Consumer<PairingLinkParser.ParsedInput> onParsed,
            TextView statusNote) {
        LinearLayout box = Ui.column(activity);
        Ui.pad(box, 20, 8);
        EditText input = new EditText(activity);
        input.setHint(R.string.onboarding_paste_card_hint);
        Ui.styleDialogInput(activity, input);
        input.setMinLines(2);
        input.setMaxLines(4);
        input.setSaveEnabled(false);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        input.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        Ui.pad(input, 14, 12);
        box.addView(input, new LinearLayout.LayoutParams(-1, -2));

        AlertDialog dialog = Ui.secureDialog(new AlertDialog.Builder(activity)
                .setTitle(R.string.onboarding_paste_action)
                .setView(box)
                .setNegativeButton(R.string.profile_setup_cancel, null)
                .setPositiveButton(R.string.onboarding_paste_confirm_action, (d, w) -> {
                    String raw = input.getText().toString().trim();
                    input.getText().clear();
                    PairingLinkParser.ParsedInput parsed = PairingLinkParser.parseOrNull(raw);
                    if (parsed == null) {
                        showStatus(activity, statusNote,
                                activity.getString(R.string.onboarding_paste_invalid), R.color.danger);
                    } else {
                        onParsed.accept(parsed);
                    }
                }));
        dialog.setOnDismissListener(d -> input.getText().clear());
        targets.trackDialog(dialog);
    }

    /**
     * Non-consuming ticket check ({@code POST /api/mobile/pairing/inspect}) followed by explicit user
     * confirmation. Continuing stages the ticket on Step 1 without calling {@code /claim} until the
     * user finishes entering their passphrase and taps Save.
     */
    private static void inspectAndConfirm(
            Activity activity,
            Step1Plan plan,
            TextView statusNote,
            ExecutorService io,
            BooleanSupplier isCurrent,
            Step1Targets targets) {
        showStatus(activity, statusNote, activity.getString(R.string.onboarding_paste_checking), R.color.muted);
        HermesConfig cfg = HermesConfig.load(activity);
        HermesApi.Limits limits = new HermesApi.Limits(
                cfg.probeTimeoutMs, cfg.probeTimeoutMs, cfg.httpJsonMaxBytes, cfg.httpEventMaxBytes);
        io.execute(() -> {
            StagedPairing resolved = null;
            boolean expiredOrUsed = false;
            try {
                JSONObject raw = HermesApi.inspectPairing(plan.serverUrl(), plan.pairingCode(), limits);
                OnboardingModels.PairingInspect inspected = OnboardingModels.parsePairingInspect(raw);
                resolved = stagedFromInspect(plan, inspected);
                Diagnostics.event("onboarding", "pairing_inspected");
            } catch (HermesApi.HttpError http) {
                if (isRejectedPairingError(http.status, http.code)) {
                    expiredOrUsed = true;
                    Diagnostics.warning("onboarding", "pairing_inspect_rejected_" + http.status);
                } else {
                    // Older server without /inspect (404 with empty code) or transient error: fall back to parsed QR metadata.
                    Diagnostics.debug("onboarding", "pairing_inspect_fallback_http_" + http.status);
                    resolved = stagedFromInspect(plan, null);
                }
            } catch (Exception err) {
                Diagnostics.debug("onboarding", "pairing_inspect_fallback");
                resolved = stagedFromInspect(plan, null);
            }

            StagedPairing finalStaged = resolved;
            boolean finalExpired = expiredOrUsed;
            activity.runOnUiThread(() -> {
                if (!isCurrent.getAsBoolean() || activity.isFinishing() || activity.isDestroyed()) return;
                if (finalExpired || finalStaged == null) {
                    showStatus(activity, statusNote,
                            activity.getString(R.string.onboarding_paste_claim_failed), R.color.danger);
                    return;
                }
                showStatus(activity, statusNote, "", R.color.muted);
                showConfirmStageDialog(activity, finalStaged, statusNote, isCurrent, targets);
            });
        });
    }

    private static void showConfirmStageDialog(
            Activity activity,
            StagedPairing staged,
            TextView statusNote,
            BooleanSupplier isCurrent,
            Step1Targets targets) {
        String defaultLabel = activity.getString(R.string.onboarding_default_profile_label);
        String profileLabel = confirmProfileLabel(staged.displayName(), staged.profileSlug(), defaultLabel);
        String body = activity.getString(R.string.onboarding_paste_confirm_body, staged.serverUrl(), profileLabel);
        AlertDialog dialog = Ui.secureDialog(new AlertDialog.Builder(activity)
                .setTitle(R.string.onboarding_paste_confirm_title)
                .setMessage(body)
                .setNegativeButton(R.string.profile_setup_cancel, null)
                .setPositiveButton(R.string.onboarding_paste_confirm_action, (d, w) -> {
                    if (!isCurrent.getAsBoolean() || activity.isFinishing() || activity.isDestroyed()) return;
                    targets.stagePairing(staged);
                    showStatus(activity, statusNote,
                            activity.getString(R.string.onboarding_paste_staged, staged.serverUrl(), profileLabel),
                            R.color.accent);
                }));
        targets.trackDialog(dialog);
    }

    private static void showStatus(Activity activity, TextView view, String message, int colorRes) {
        if (view == null) return;
        view.setTextColor(activity.getColor(colorRes));
        view.setText(message);
        view.setVisibility(message == null || message.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /**
     * Step 2 ("Make it yours"): Personalizes language, theme, quick-lock, and offers 1-tap starter
     * prompts fetched from {@code GET /api/mobile/onboarding}. Returns a closeable handle.
     */
    public static AutoCloseable showStep2(
            Activity activity,
            Account account,
            HermesApi api,
            ExecutorService io,
            BooleanSupplier canAct,
            Step2Callbacks callbacks) {
        Objects.requireNonNull(activity, "activity");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(callbacks, "callbacks");
        Handler main = new Handler(Looper.getMainLooper());

        ScrollView scroll = new ScrollView(activity);
        LinearLayout body = Ui.column(activity);
        Ui.pad(body, 20, 14);
        scroll.addView(body);

        TextView subtitle = Ui.text(activity, activity.getString(R.string.onboarding_step2_subtitle), 14);
        subtitle.setTextColor(activity.getColor(R.color.muted));
        body.addView(subtitle);

        // Display name
        EditText nameField = ProfileScreen.styledField(
                activity, body,
                activity.getString(R.string.onboarding_display_name_label),
                activity.getString(R.string.profile_name_hint),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
                80, false, 14);
        nameField.setText(account.label);

        // Language selector
        String[] selectedLang = {AppLanguage.readMode(activity).wire};
        body.addView(sectionLabel(activity, R.string.onboarding_language_label), topMargin(activity, 14));
        body.addView(ProfileScreen.languageChoiceRow(activity, selectedLang[0], val -> selectedLang[0] = val),
                topMargin(activity, 6));

        // Theme selector (staged locally until Finish/Starter commit so tapping chips never recreates mid-dialog)
        String initialTheme = AppThemeMode.readMode(activity);
        String[] selectedTheme = {initialTheme};
        body.addView(sectionLabel(activity, R.string.onboarding_theme_label), topMargin(activity, 14));
        body.addView(ProfileScreen.choiceRow(activity,
                new String[]{
                        activity.getString(R.string.onboarding_theme_auto),
                        activity.getString(R.string.onboarding_theme_light),
                        activity.getString(R.string.onboarding_theme_dark)
                },
                new String[]{"system", "light", "dark"},
                selectedTheme[0],
                val -> selectedTheme[0] = val), topMargin(activity, 6));

        // Quick lock selector
        LockGracePolicy.Window[] selectedLock = {LockGracePolicy.window(activity, account.id)};
        String currentLockChoice = selectedLock[0] == LockGracePolicy.Window.IMMEDIATE
                ? "immediate"
                : (selectedLock[0] == LockGracePolicy.Window.SCREEN_LOCK ? "screen_lock" : "grace_5m");
        body.addView(sectionLabel(activity, R.string.onboarding_lock_label), topMargin(activity, 14));
        body.addView(ProfileScreen.choiceRow(activity,
                new String[]{
                        activity.getString(R.string.onboarding_lock_immediate),
                        activity.getString(R.string.onboarding_lock_5m),
                        activity.getString(R.string.onboarding_lock_screen)
                },
                new String[]{"immediate", "grace_5m", "screen_lock"},
                currentLockChoice,
                val -> {
                    if ("immediate".equals(val)) selectedLock[0] = LockGracePolicy.Window.IMMEDIATE;
                    else if ("screen_lock".equals(val)) selectedLock[0] = LockGracePolicy.Window.SCREEN_LOCK;
                    else selectedLock[0] = LockGracePolicy.Window.MINUTES_5;
                }), topMargin(activity, 6));

        // Live starter prompts from GET /api/mobile/onboarding
        body.addView(sectionLabel(activity, R.string.onboarding_starters_heading), topMargin(activity, 16));
        LinearLayout startersSlot = Ui.column(activity);
        TextView loadingStarters = Ui.text(activity,
                activity.getString(R.string.onboarding_starters_loading), 13);
        loadingStarters.setTextColor(activity.getColor(R.color.muted));
        startersSlot.addView(loadingStarters);
        body.addView(startersSlot, topMargin(activity, 6));

        Runnable applyPrefs = () -> {
            markCompleted(account);
            LockGracePolicy.setWindow(activity, account.id, selectedLock[0]);
            AppLanguage.Mode nextLang = switch (selectedLang[0]) {
                case "en" -> AppLanguage.Mode.ENGLISH;
                case "fr" -> AppLanguage.Mode.FRENCH;
                default -> AppLanguage.Mode.AUTO;
            };
            String previousTag = AppLanguage.resolvedTag(activity);
            boolean localeChanged = false;
            if (nextLang != AppLanguage.readMode(activity)) {
                AppLanguage.apply(activity, nextLang);
                String nextTag = AppLanguage.resolvedTag(activity);
                localeChanged = !previousTag.equals(nextTag);
            }
            String editedName = nameField.getText().toString().trim();
            String slug = extractSlugFromEndpoint(account.endpoint);
            Step2CommitPlan commitPlan = planStep2Commit(
                    initialTheme,
                    selectedTheme[0],
                    ThemePalette.isNight(activity),
                    AppThemeMode.isSystemNight(),
                    localeChanged,
                    Build.VERSION.SDK_INT < 33);
            if (api != null && !api.isRevoked() && !slug.isEmpty()) {
                boolean detached = commitPlan.willRecreate();
                HermesApi syncClient = detached
                        ? new HermesApi(account.endpoint, account.bearer,
                                HermesApi.Limits.from(HermesConfig.load(activity)))
                        : api;
                submitProfileSync(syncClient, detached, slug, editedName, selectedLang[0]);
            }
            if (commitPlan.applyThemeCallback()) {
                callbacks.onThemeSelected(selectedTheme[0]);
            }
            if (commitPlan.recreateForLocale() && !activity.isFinishing() && !activity.isDestroyed()) {
                activity.recreate();
            }
        };

        AlertDialog[] holder = new AlertDialog[1];
        Step2Session session = new Step2Session(
                canAct,
                () -> {
                    if (holder[0] != null) {
                        AlertDialog toDismiss = holder[0];
                        holder[0] = null;
                        try { toDismiss.dismiss(); } catch (RuntimeException ignored) {}
                    }
                },
                () -> holder[0] = null,
                callbacks::onFinished);
        AlertDialog dialog = Ui.secureDialog(new AlertDialog.Builder(activity)
                .setTitle(R.string.onboarding_step2_title)
                .setView(scroll)
                .setNegativeButton(R.string.onboarding_skip_action, (d, w) ->
                        session.onSkip(() -> markCompleted(account)))
                .setPositiveButton(R.string.onboarding_finish_action, (d, w) ->
                        session.onFinish(applyPrefs))
                .setOnDismissListener(d -> session.onDismiss()));
        holder[0] = dialog;

        if (api != null && io != null && !api.isRevoked() && !io.isShutdown()) {
            try {
                io.execute(() -> {
                    OnboardingModels.OnboardingStatus status = null;
                    try {
                        status = OnboardingModels.parseOnboardingStatus(api.onboardingStatus());
                    } catch (Exception ignored) {
                        Diagnostics.debug("onboarding", "status_fetch_skipped");
                    }
                    OnboardingModels.OnboardingStatus loaded = status;
                    main.post(() -> {
                        if (session.isClosed() || !canAct.getAsBoolean()
                                || activity.isFinishing() || activity.isDestroyed()) return;
                        startersSlot.removeAllViews();
                        List<OnboardingModels.StarterPrompt> starters = loaded == null ? List.of() : loaded.starters();
                        if (starters.isEmpty()) {
                            TextView ready = Ui.text(activity,
                                    activity.getString(R.string.onboarding_starters_empty), 13);
                            ready.setTextColor(activity.getColor(R.color.muted));
                            startersSlot.addView(ready);
                            return;
                        }
                        for (OnboardingModels.StarterPrompt starter : starters) {
                            Button chip = Ui.outlinedButton(activity, starter.title(), v ->
                                    session.onStarter(applyPrefs, () -> callbacks.onStageStarterDraft(starter.prompt())));
                            startersSlot.addView(chip, topMargin(activity, 8));
                        }
                    });
                });
            } catch (RejectedExecutionException rejected) {
                Diagnostics.warning("onboarding", "step2_starters_submit_rejected", rejected);
            }
        }

        return session::close;
    }

    /** Pure-JVM testable lifecycle fence for Step 2 dialog actions, dismiss, and close. */
    static final class Step2Session {
        private final BooleanSupplier canAct;
        private final Runnable dismissDialog;
        private final Runnable clearHolder;
        private final Runnable onFinished;
        private boolean closed;
        private boolean finishedNotified;

        Step2Session(
                BooleanSupplier canAct,
                Runnable dismissDialog,
                Runnable clearHolder,
                Runnable onFinished) {
            this.canAct = canAct == null ? () -> true : canAct;
            this.dismissDialog = dismissDialog == null ? () -> {} : dismissDialog;
            this.clearHolder = clearHolder == null ? () -> {} : clearHolder;
            this.onFinished = onFinished == null ? () -> {} : onFinished;
        }

        boolean isClosed() {
            return closed;
        }

        void onSkip(Runnable markCompleted) {
            if (closed || !canAct.getAsBoolean()) return;
            closed = true;
            if (markCompleted != null) markCompleted.run();
            notifyFinishedOnce();
        }

        void onFinish(Runnable applyPrefs) {
            if (closed || !canAct.getAsBoolean()) return;
            closed = true;
            if (applyPrefs != null) applyPrefs.run();
            notifyFinishedOnce();
        }

        void onStarter(Runnable applyPrefs, Runnable stageDraft) {
            if (closed || !canAct.getAsBoolean()) return;
            closed = true;
            if (applyPrefs != null) applyPrefs.run();
            dismissDialog.run();
            if (stageDraft != null) stageDraft.run();
            notifyFinishedOnce();
        }

        void onDismiss() {
            closed = true;
            clearHolder.run();
            notifyFinishedOnce();
        }

        void close() {
            closed = true;
            dismissDialog.run();
        }

        private void notifyFinishedOnce() {
            if (finishedNotified) return;
            finishedNotified = true;
            onFinished.run();
        }
    }

    private static TextView sectionLabel(Activity activity, int resId) {
        TextView label = Ui.text(activity, activity.getString(resId), 14);
        label.setTypeface(Ui.mediumTypeface());
        label.setAccessibilityHeading(true);
        return label;
    }

    private static LinearLayout.LayoutParams topMargin(Activity activity, int dp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(activity, dp);
        return lp;
    }
}
