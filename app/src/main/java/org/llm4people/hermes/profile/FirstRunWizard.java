package org.llm4people.hermes.profile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
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
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.onboarding.OnboardingModels;
import org.llm4people.hermes.onboarding.PairingLinkParser;
import org.llm4people.hermes.os.LockGracePolicy;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Zero-jargon 2-step first-run onboarding wizard integrated cleanly with {@link ProfileGate}:
 * <ul>
 *   <li>Step 1 ("Connect your Hermes"): 1-tap paste / clipboard auto-detection / {@code hermes://pair}
 *       deep-link claim with explicit user confirmation before filling credentials, while keeping
 *       every manual form field accessible and backward-compatible.</li>
 *   <li>Step 2 ("Make it yours"): Display name, language, appearance, quick-lock preference, and
 *       1-tap localized starter prompts from {@code GET /api/mobile/onboarding} (Editable Draft Rule).</li>
 * </ul>
 */
public final class FirstRunWizard {
    public static final String COMPLETED_KEY = "onboarding_v1_done";
    private static volatile Typeface mediumTypeface;

    private static Typeface mediumTypeface() {
        Typeface cached = mediumTypeface;
        if (cached == null) {
            cached = Typeface.create("sans-serif-medium", Typeface.NORMAL);
            mediumTypeface = cached;
        }
        return cached;
    }
    private static final Pattern ENDPOINT_SLUG = Pattern.compile("^.*/p/([^/]+)/?$");

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
        return (defaultLabel != null && !defaultLabel.trim().isEmpty()) ? defaultLabel.trim() : "Default";
    }

    /**
     * Friendly profile name pre-filled into the Step-1 "Profile name" field for a pairing ticket.
     * Priority: explicit {@code displayName} -&gt; capitalized non-default {@code profileSlug} -&gt;
     * localized {@code defaultLabel}. NEVER falls back to the server URL hostname.
     */
    public static String formProfileName(String displayName, String profileSlug, String defaultLabel) {
        if (displayName != null && !displayName.trim().isEmpty()) return displayName.trim();
        if (profileSlug != null && !profileSlug.trim().isEmpty()
                && !"default".equalsIgnoreCase(profileSlug.trim())) {
            String s = profileSlug.trim();
            return Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }
        return (defaultLabel != null && !defaultLabel.trim().isEmpty()) ? defaultLabel.trim() : "Default";
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
                    stripTrailingProfilePath(plan.serverUrl()),
                    plan.pairingCode(),
                    plan.profileSlug(),
                    plan.displayName(),
                    0L);
        }
        String rawBase = !inspected.baseHost().isEmpty()
                ? inspected.baseHost()
                : (!plan.serverUrl().isEmpty() ? plan.serverUrl() : inspected.serverUrl());
        String cleanBase = stripTrailingProfilePath(rawBase);
        String chosenSlug = inspected.profileSlug().isEmpty() ? plan.profileSlug() : inspected.profileSlug();
        String chosenName = inspected.displayName().isEmpty() ? plan.displayName() : inspected.displayName();
        return new StagedPairing(
                cleanBase, plan.pairingCode(), chosenSlug, chosenName, inspected.expiresAtEpochSec());
    }

    private static String stripTrailingProfilePath(String serverUrl) {
        String normalized = GatewayAddress.normalize(serverUrl);
        try {
            URI uri = URI.create(normalized);
            String path = uri.getRawPath();
            if (path != null) {
                int idx = path.toLowerCase(java.util.Locale.ROOT).lastIndexOf("/p/");
                if (idx >= 0 && idx + 3 < path.length() && path.indexOf('/', idx + 3) < 0) {
                    return uri.getScheme() + "://" + uri.getRawAuthority() + path.substring(0, idx);
                }
            }
        } catch (IllegalArgumentException ignored) {}
        return normalized;
    }

    /** Extracts the trailing {@code /p/<slug>} profile slug from a normalized account endpoint, or empty. */
    public static String extractSlugFromEndpoint(String endpoint) {
        if (endpoint == null || endpoint.trim().isEmpty()) return "";
        try {
            String path = URI.create(endpoint.trim()).getRawPath();
            if (path == null) return "";
            Matcher m = ENDPOINT_SLUG.matcher(path);
            return m.matches() ? GatewayAddress.normalizeProfileSlug(m.group(1)) : "";
        } catch (IllegalArgumentException invalid) {
            return "";
        }
    }

    public static boolean isCompleted(Account account) {
        if (account == null) return true;
        try {
            return "1".equals(account.read(COMPLETED_KEY));
        } catch (RuntimeException ignored) {
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
     * Preserves every manual form control so existing flows &amp; tests stay 100% compatible.
     */
    static Consumer<PairingLinkParser.ParsedInput> attachInstantSetupCard(
            Activity activity,
            ProfileScreen screen,
            boolean hasIncomingLink,
            ExecutorService io,
            BooleanSupplier isCurrent,
            Step1Targets targets) {
        LinearLayout card = Ui.column(activity);
        Ui.pad(card, 14, 12);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Ui.dp(activity, 14));
        bg.setColor(activity.getColor(R.color.surface));
        bg.setStroke(Math.max(1, Ui.dp(activity, 1)), activity.getColor(R.color.line));
        card.setBackground(bg);

        TextView heading = Ui.text(activity, activity.getString(R.string.onboarding_paste_card_title), 16);
        heading.setTypeface(mediumTypeface());
        heading.setAccessibilityHeading(true);
        card.addView(heading);

        TextView subtitle = Ui.text(activity, activity.getString(R.string.onboarding_paste_card_hint), 13);
        subtitle.setTextColor(activity.getColor(R.color.muted));
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = Ui.dp(activity, 4);
        card.addView(subtitle, subLp);

        int touchMin = Ui.dp(activity, 48);
        TextView statusNote = Ui.text(activity, "", 13);
        statusNote.setTextColor(activity.getColor(R.color.muted));
        statusNote.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        statusNote.setVisibility(View.GONE);

        Consumer<PairingLinkParser.ParsedInput> handleParsed = parsed -> {
            if (!isCurrent.getAsBoolean()) return;
            Step1Plan plan = planForInput(parsed);
            if (plan == null) {
                showStatus(activity, statusNote, activity.getString(R.string.onboarding_paste_invalid), R.color.danger);
                return;
            }
            if (plan.action() == ApplyAction.CLAIM_TICKET) {
                inspectAndConfirm(activity, plan, statusNote, io, isCurrent, targets);
            } else if (plan.action() == ApplyAction.FILL_CREDENTIALS) {
                targets.fillFields(plan.suggestedName(), plan.serverUrl(), plan.profileSlug(), plan.apiKey(), false);
                showStatus(activity, statusNote, activity.getString(R.string.onboarding_direct_applied), R.color.accent);
                targets.triggerConnectionProbe(parsed.composedEndpoint(), plan.apiKey());
            } else {
                targets.fillFields(plan.suggestedName(), plan.serverUrl(), plan.profileSlug(), "", false);
                showStatus(activity, statusNote, activity.getString(R.string.onboarding_endpoint_applied), R.color.accent);
            }
        };

        PairingLinkParser.ParsedInput detectedClip = hasIncomingLink ? null : detectClipboardLink(activity);
        if (detectedClip != null) {
            LinearLayout banner = Ui.column(activity);
            Ui.pad(banner, 12, 10);
            GradientDrawable bannerBg = new GradientDrawable();
            bannerBg.setCornerRadius(Ui.dp(activity, 10));
            bannerBg.setColor(activity.getColor(R.color.accent_surface));
            banner.setBackground(bannerBg);

            TextView bannerText = Ui.text(activity,
                    activity.getString(R.string.onboarding_paste_clipboard_detected), 13);
            bannerText.setTypeface(mediumTypeface());
            bannerText.setTextColor(activity.getColor(R.color.accent));
            banner.addView(bannerText);

            Button useBtn = Ui.button(activity, activity.getString(R.string.onboarding_paste_clipboard_use),
                    v -> handleParsed.accept(detectedClip));
            useBtn.setMinHeight(touchMin);
            useBtn.setMinimumHeight(touchMin);
            banner.addView(useBtn);

            LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            bLp.topMargin = Ui.dp(activity, 10);
            card.addView(banner, bLp);
        }

        Button scanQrBtn = Ui.button(activity, activity.getString(R.string.onboarding_scan_qr_action),
                v -> targets.onScanQrRequested());
        scanQrBtn.setMinHeight(touchMin);
        scanQrBtn.setMinimumHeight(touchMin);
        LinearLayout.LayoutParams scanLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        scanLp.topMargin = Ui.dp(activity, 10);
        card.addView(scanQrBtn, scanLp);

        Button pasteBtn = Ui.button(activity, activity.getString(R.string.onboarding_paste_action), v -> {
            PairingLinkParser.ParsedInput fromClip = detectClipboardLink(activity);
            if (fromClip != null) {
                handleParsed.accept(fromClip);
            } else {
                showManualPasteDialog(activity, targets, handleParsed, statusNote);
            }
        });
        pasteBtn.setMinHeight(touchMin);
        pasteBtn.setMinimumHeight(touchMin);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnLp.topMargin = Ui.dp(activity, 8);
        card.addView(pasteBtn, btnLp);

        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        statusLp.topMargin = Ui.dp(activity, 6);
        card.addView(statusNote, statusLp);

        screen.card(card, 14);
        return handleParsed;
    }

    private static void showManualPasteDialog(
            Activity activity,
            Step1Targets targets,
            Consumer<PairingLinkParser.ParsedInput> onParsed,
            TextView statusNote) {
        EditText input = new EditText(activity);
        input.setHint(R.string.onboarding_paste_card_hint);
        input.setMinLines(2);
        input.setMaxLines(4);
        input.setSaveEnabled(false);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        Ui.pad(input, 14, 12);

        AlertDialog dialog = Ui.secureDialog(new AlertDialog.Builder(activity)
                .setTitle(R.string.onboarding_paste_action)
                .setView(input)
                .setNegativeButton(R.string.profile_setup_cancel, null)
                .setPositiveButton(R.string.onboarding_paste_confirm_action, (d, w) -> {
                    String raw = input.getText().toString().trim();
                    PairingLinkParser.ParsedInput parsed = PairingLinkParser.parseOrNull(raw);
                    if (parsed == null) {
                        showStatus(activity, statusNote,
                                activity.getString(R.string.onboarding_paste_invalid), R.color.danger);
                    } else {
                        onParsed.accept(parsed);
                    }
                }));
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
        int inspectTimeoutMs = Math.min(ProfileGate.PROBE_TIMEOUT_MS, 4_000);
        HermesApi.Limits limits = new HermesApi.Limits(
                inspectTimeoutMs, inspectTimeoutMs, cfg.httpJsonMaxBytes, cfg.httpEventMaxBytes);
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
        boolean[] closed = {false};

        ScrollView scroll = new ScrollView(activity);
        LinearLayout body = Ui.column(activity);
        Ui.pad(body, 20, 14);
        scroll.addView(body);

        TextView subtitle = Ui.text(activity, activity.getString(R.string.onboarding_step2_subtitle), 14);
        subtitle.setTextColor(activity.getColor(R.color.muted));
        body.addView(subtitle);

        int touchMin = Ui.dp(activity, 48);

        // Display name
        TextView nameLabel = sectionLabel(activity, R.string.onboarding_display_name_label);
        body.addView(nameLabel, topMargin(activity, 14));
        EditText nameField = new EditText(activity);
        nameField.setText(account.label);
        nameField.setSingleLine(true);
        nameField.setMinHeight(touchMin);
        nameField.setSaveEnabled(false);
        body.addView(nameField);

        // Language selector
        String[] selectedLang = {AppLanguage.readMode(activity).wire};
        body.addView(sectionLabel(activity, R.string.onboarding_language_label), topMargin(activity, 14));
        body.addView(choiceRow(activity,
                new String[]{
                        activity.getString(R.string.onboarding_language_auto),
                        activity.getString(R.string.onboarding_language_en),
                        activity.getString(R.string.onboarding_language_fr)
                },
                new String[]{"auto", "en", "fr"},
                selectedLang[0],
                val -> selectedLang[0] = val));

        // Theme selector
        String[] selectedTheme = {"system"};
        body.addView(sectionLabel(activity, R.string.onboarding_theme_label), topMargin(activity, 14));
        body.addView(choiceRow(activity,
                new String[]{
                        activity.getString(R.string.onboarding_theme_auto),
                        activity.getString(R.string.onboarding_theme_light),
                        activity.getString(R.string.onboarding_theme_dark)
                },
                new String[]{"system", "light", "dark"},
                selectedTheme[0],
                val -> {
                    selectedTheme[0] = val;
                    callbacks.onThemeSelected(val);
                }));

        // Quick lock selector
        LockGracePolicy.Window[] selectedLock = {LockGracePolicy.window(activity, account.id)};
        body.addView(sectionLabel(activity, R.string.onboarding_lock_label), topMargin(activity, 14));
        body.addView(choiceRow(activity,
                new String[]{
                        activity.getString(R.string.onboarding_lock_immediate),
                        activity.getString(R.string.onboarding_lock_grace)
                },
                new String[]{"immediate", "grace_2m"},
                selectedLock[0] == LockGracePolicy.Window.IMMEDIATE ? "immediate" : "grace_2m",
                val -> selectedLock[0] = "grace_2m".equals(val)
                        ? LockGracePolicy.Window.MINUTES_2 : LockGracePolicy.Window.IMMEDIATE));

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
            if (nextLang != AppLanguage.readMode(activity)) {
                AppLanguage.apply(activity, nextLang);
            }
            String editedName = nameField.getText().toString().trim();
            String slug = extractSlugFromEndpoint(account.endpoint);
            if (api != null && io != null && !api.isRevoked() && !slug.isEmpty()) {
                io.execute(() -> {
                    try {
                        JSONObject patch = new JSONObject();
                        if (!editedName.isEmpty()) patch.put("display_name", editedName);
                        patch.put("preferred_language", selectedLang[0]);
                        api.updateProfile(slug, patch);
                    } catch (Exception ignored) {
                        Diagnostics.debug("onboarding", "profile_pref_sync_skipped");
                    }
                });
            }
        };

        AlertDialog[] holder = new AlertDialog[1];
        AlertDialog dialog = Ui.secureDialog(new AlertDialog.Builder(activity)
                .setTitle(R.string.onboarding_step2_title)
                .setView(scroll)
                .setNegativeButton(R.string.onboarding_skip_action, (d, w) -> {
                    markCompleted(account);
                    callbacks.onFinished();
                })
                .setPositiveButton(R.string.onboarding_finish_action, (d, w) -> {
                    applyPrefs.run();
                    callbacks.onFinished();
                }));
        holder[0] = dialog;

        if (api != null && io != null && !api.isRevoked()) {
            io.execute(() -> {
                OnboardingModels.OnboardingStatus status = null;
                try {
                    status = OnboardingModels.parseOnboardingStatus(api.onboardingStatus());
                } catch (Exception ignored) {
                    Diagnostics.debug("onboarding", "status_fetch_skipped");
                }
                OnboardingModels.OnboardingStatus loaded = status;
                main.post(() -> {
                    if (closed[0] || !canAct.getAsBoolean() || activity.isFinishing() || activity.isDestroyed()) return;
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
                        Button chip = Ui.button(activity, starter.title(), v -> {
                            applyPrefs.run();
                            if (holder[0] != null) holder[0].dismiss();
                            callbacks.onStageStarterDraft(starter.prompt());
                            callbacks.onFinished();
                        });
                        chip.setMinHeight(touchMin);
                        chip.setMinimumHeight(touchMin);
                        startersSlot.addView(chip, topMargin(activity, 6));
                    }
                });
            });
        }

        return () -> {
            closed[0] = true;
            if (holder[0] != null) {
                try { holder[0].dismiss(); } catch (RuntimeException ignored) {}
                holder[0] = null;
            }
        };
    }

    private static TextView sectionLabel(Activity activity, int resId) {
        TextView label = Ui.text(activity, activity.getString(resId), 14);
        label.setTypeface(mediumTypeface());
        return label;
    }

    private static LinearLayout.LayoutParams topMargin(Activity activity, int dp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(activity, dp);
        return lp;
    }

    private static LinearLayout choiceRow(
            Activity activity,
            String[] labels,
            String[] values,
            String initial,
            Consumer<String> onSelected) {
        LinearLayout row = Ui.row(activity);
        int touchMin = Ui.dp(activity, 48);
        Button[] buttons = new Button[labels.length];
        String[] active = {initial};

        Runnable paint = () -> {
            for (int i = 0; i < buttons.length; i++) {
                boolean selected = values[i].equals(active[0]);
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(Ui.dp(activity, 10));
                bg.setColor(activity.getColor(selected ? R.color.accent_surface : R.color.surface));
                bg.setStroke(Math.max(1, Ui.dp(activity, 1)),
                        activity.getColor(selected ? R.color.accent : R.color.line));
                buttons[i].setBackground(bg);
                buttons[i].setTextColor(activity.getColor(selected ? R.color.accent : R.color.ink));
                buttons[i].setSelected(selected);
            }
        };

        for (int i = 0; i < labels.length; i++) {
            final String value = values[i];
            Button btn = new Button(activity, null, android.R.attr.borderlessButtonStyle);
            btn.setText(labels[i]);
            btn.setAllCaps(false);
            btn.setTextSize(13);
            btn.setTypeface(mediumTypeface());
            btn.setMinHeight(touchMin);
            btn.setMinimumHeight(touchMin);
            btn.setOnClickListener(v -> {
                active[0] = value;
                paint.run();
                onSelected.accept(value);
            });
            buttons[i] = btn;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.setMarginStart(Ui.dp(activity, 6));
            row.addView(btn, lp);
        }
        paint.run();
        return row;
    }
}
