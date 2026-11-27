package org.llm4people.hermes.profile;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;
import org.llm4people.hermes.AppLanguage;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.onboarding.OnboardingModels;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Shared package-private helper for profile dialog inputs, existing-profile device pairing, and
 * 1-step "Create &amp; Invite User" flows reused by {@link AdminConsoleSheet} and {@link ProfileHubSheet}.
 */
final class ProfileInviteDialogs {
    record CreateProfileParams(String slug, String displayName, String preferredLanguage, boolean grantAdmin) {}

    private ProfileInviteDialogs() {}

    /** Pure-JVM normalization for creating a profile with an immediate pairing invite. */
    static CreateProfileParams normalizeCreateProfile(
            String slug, String displayName, String preferredLanguage, boolean grantAdmin) {
        String cleanSlug = slug == null ? "" : slug.trim().toLowerCase(Locale.ROOT);
        String cleanName = displayName == null ? "" : displayName.trim();
        String cleanLang = preferredLanguage == null ? "" : preferredLanguage.trim();
        return new CreateProfileParams(
                cleanSlug, cleanName.isEmpty() ? cleanSlug : cleanName, cleanLang, grantAdmin);
    }

    /** Request builder for pairing an additional device onto an existing profile. */
    static JSONObject buildDevicePairingRequest() throws JSONException {
        JSONObject req = new JSONObject();
        req.put("mode", "device");
        return req;
    }

    /** Request builder for creating a profile with an immediate pairing invite. */
    static JSONObject buildCreateProfileRequest(
            String slug, String displayName, String preferredLanguage, boolean grantAdmin) throws JSONException {
        CreateProfileParams params = normalizeCreateProfile(slug, displayName, preferredLanguage, grantAdmin);
        JSONObject req = new JSONObject();
        req.put("slug", params.slug());
        req.put("display_name", params.displayName());
        req.put("preferred_language", params.preferredLanguage());
        req.put("is_admin", params.grantAdmin());
        req.put("create_invite", true);
        return req;
    }

    /** Pure-JVM extractor for a pairing ticket returned from {@code POST /api/admin/profiles}. */
    static OnboardingModels.PairingTicket extractCreatedTicket(JSONObject resp) {
        if (resp == null) return null;
        JSONObject rawTicket = resp.optJSONObject("pairing_ticket");
        if (rawTicket == null) rawTicket = resp.optJSONObject("ticket");
        if (rawTicket == null) rawTicket = resp;
        return OnboardingModels.parsePairingTicket(rawTicket);
    }

    /** Material 3 labeled single-line dialog input backed by {@link Ui#styleDialogInput}. */
    static EditText labeledDialogField(
            Activity activity,
            LinearLayout parent,
            int labelRes,
            String initial,
            int hintRes,
            int inputType,
            int topGapDp) {
        TextView lbl = Ui.text(activity, activity.getString(labelRes), 14);
        lbl.setTypeface(Ui.mediumTypeface());
        if (topGapDp > 0) parent.addView(lbl, topMargin(activity, topGapDp));
        else parent.addView(lbl);

        EditText field = new EditText(activity);
        field.setId(View.generateViewId());
        lbl.setLabelFor(field.getId());
        field.setText(initial == null ? "" : initial);
        if (hintRes != 0) field.setHint(hintRes);
        field.setSingleLine(true);
        field.setInputType(inputType);
        Ui.styleDialogInput(activity, field);
        field.setSaveEnabled(false);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        field.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        parent.addView(field, topMargin(activity, 6));
        return field;
    }

    /**
     * Single-flight gate for async pairing requests: atomically acquires {@code inFlight}, runs
     * {@code onAcquired} before dispatching {@code submitIo}, and rolls back both if submission is rejected.
     */
    static boolean submitSingleFlight(
            AtomicBoolean inFlight,
            Predicate<Runnable> submitIo,
            Runnable onAcquired,
            Runnable onRejected,
            Runnable ioTask) {
        if (!inFlight.compareAndSet(false, true)) return false;
        if (onAcquired != null) onAcquired.run();
        if (!submitIo.test(ioTask)) {
            inFlight.set(false);
            if (onRejected != null) onRejected.run();
            return false;
        }
        return true;
    }

    /** Creates a 5-minute device-pairing ticket for an existing profile slug and delivers it on {@code main}. */
    static void pairExistingProfileDevice(
            Activity activity,
            HermesApi api,
            Handler main,
            Predicate<Runnable> submitIo,
            BooleanSupplier isClosed,
            BooleanSupplier canAct,
            AtomicBoolean inFlight,
            Button trigger,
            String slug,
            String diagTag,
            String diagFailureEvent,
            Consumer<OnboardingModels.PairingTicket> onTicketReady) {
        if (isClosed.getAsBoolean() || api.isRevoked()) return;
        submitSingleFlight(
                inFlight,
                submitIo,
                () -> {
                    if (trigger != null) trigger.setEnabled(false);
                },
                () -> {
                    if (trigger != null) trigger.setEnabled(true);
                },
                () -> {
                    OnboardingModels.PairingTicket ticket = null;
                    try {
                        ticket = OnboardingModels.parsePairingTicket(
                                api.inviteProfile(slug, buildDevicePairingRequest()));
                    } catch (Exception err) {
                        Diagnostics.warning(diagTag, diagFailureEvent);
                    }
                    OnboardingModels.PairingTicket created = ticket;
                    if (!main.post(() -> {
                        inFlight.set(false);
                        if (trigger != null) trigger.setEnabled(true);
                        if (isClosed.getAsBoolean() || !canAct.getAsBoolean()
                                || activity.isFinishing() || activity.isDestroyed()) {
                            return;
                        }
                        if (created == null) {
                            Toast.makeText(activity, R.string.profile_hub_qr_unavailable, Toast.LENGTH_LONG).show();
                            return;
                        }
                        onTicketReady.accept(created);
                    })) {
                        inFlight.set(false);
                    }
                });
    }

    /** Shows the shared "Create &amp; Invite User" dialog and delivers the resulting pairing ticket on {@code main}. */
    static AlertDialog showCreateAndInviteDialog(
            Activity activity,
            HermesApi api,
            Handler main,
            Predicate<Runnable> submitIo,
            BooleanSupplier isClosed,
            BooleanSupplier canAct,
            int titleRes,
            boolean allowAdminToggle,
            String diagTag,
            String diagSuccessEvent,
            String diagFailureEvent,
            Consumer<AlertDialog> onDismissed,
            Runnable onAbortedOrFailed,
            Consumer<OnboardingModels.PairingTicket> onTicketReady) {
        ScrollView scroll = new ScrollView(activity);
        LinearLayout form = Ui.column(activity);
        Ui.pad(form, 18, 12);
        scroll.addView(form);

        TextView detail = Ui.text(activity, activity.getString(R.string.profile_hub_invite_detail), 13);
        detail.setTextColor(activity.getColor(R.color.muted));
        form.addView(detail);

        EditText nameField = labeledDialogField(activity, form,
                R.string.profile_hub_edit_name_hint, "", R.string.profile_hub_invite_name_hint,
                InputType.TYPE_CLASS_TEXT, 10);
        EditText slugField = labeledDialogField(activity, form,
                R.string.profile_hub_slug_label, "", R.string.profile_hub_invite_slug_hint,
                InputType.TYPE_CLASS_TEXT, 10);

        TextView inlineError = Ui.text(activity, "", 13);
        inlineError.setTextColor(activity.getColor(R.color.danger));
        inlineError.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        inlineError.setVisibility(View.GONE);
        form.addView(inlineError, topMargin(activity, 8));

        boolean[] slugManuallyEdited = {false};
        nameField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (!slugManuallyEdited[0]) {
                    slugField.setText(ProfileHubSheet.suggestSlug(s.toString()));
                }
            }
        });
        slugField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (inlineError.getVisibility() == View.VISIBLE) {
                    inlineError.setText("");
                    inlineError.setVisibility(View.GONE);
                }
            }
        });
        slugField.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) slugManuallyEdited[0] = true;
        });

        Switch adminToggle = new Switch(activity);
        adminToggle.setText(R.string.admin_console_grant_admin_toggle);
        adminToggle.setTextColor(activity.getColor(R.color.ink));
        adminToggle.setTextSize(15);
        adminToggle.setMinHeight(Ui.dp(activity, 48));
        if (allowAdminToggle) form.addView(adminToggle, topMargin(activity, 10));

        boolean[] submitted = {false};
        AlertDialog dialog = Ui.secureDialog(new AlertDialog.Builder(activity)
                .setTitle(titleRes)
                .setView(scroll)
                .setNegativeButton(R.string.profile_setup_cancel, null)
                .setPositiveButton(R.string.profile_hub_invite_create_action, null));
        dialog.setOnDismissListener(d -> {
            if (onDismissed != null) onDismissed.accept(dialog);
            if (!submitted[0] && onAbortedOrFailed != null) onAbortedOrFailed.run();
        });
        Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (positive != null) {
            positive.setOnClickListener(v -> {
                String displayName = nameField.getText().toString().trim();
                String slug = slugField.getText().toString().trim().toLowerCase(Locale.ROOT);
                if (!ProfileHubSheet.isValidNewSlug(slug)) {
                    Ui.showInlineError(inlineError, activity.getString(R.string.profile_hub_invite_invalid_slug));
                    slugField.requestFocus();
                    return;
                }
                inlineError.setText("");
                inlineError.setVisibility(View.GONE);
                if (isClosed.getAsBoolean()) return;
                boolean grantAdmin = allowAdminToggle && adminToggle.isChecked();
                submitted[0] = true;
                dialog.dismiss();
                if (!submitIo.test(() -> {
                    OnboardingModels.PairingTicket ticket = null;
                    try {
                        JSONObject req = buildCreateProfileRequest(
                                slug, displayName, AppLanguage.resolvedTag(activity), grantAdmin);
                        ticket = extractCreatedTicket(api.createProfile(req));
                        Diagnostics.event(diagTag, diagSuccessEvent);
                    } catch (Exception err) {
                        Diagnostics.warning(diagTag, diagFailureEvent);
                    }
                    OnboardingModels.PairingTicket created = ticket;
                    main.post(() -> {
                        if (isClosed.getAsBoolean()) return;
                        if (!canAct.getAsBoolean() || activity.isFinishing() || activity.isDestroyed()) {
                            if (onAbortedOrFailed != null) onAbortedOrFailed.run();
                            return;
                        }
                        if (created == null) {
                            Toast.makeText(activity, R.string.profile_hub_invite_failed, Toast.LENGTH_LONG).show();
                            if (onAbortedOrFailed != null) onAbortedOrFailed.run();
                            return;
                        }
                        onTicketReady.accept(created);
                    });
                }) && onAbortedOrFailed != null) {
                    onAbortedOrFailed.run();
                }
            });
        }
        return dialog;
    }

    private static LinearLayout.LayoutParams topMargin(Activity activity, int dp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(activity, dp);
        return lp;
    }
}
