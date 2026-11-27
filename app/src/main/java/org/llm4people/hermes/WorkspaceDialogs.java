package org.llm4people.hermes;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.llm4people.hermes.os.BiometricVault;
import org.llm4people.hermes.os.HermesWidgetPinning;
import org.llm4people.hermes.os.HermesWidgetSpec;
import org.llm4people.hermes.os.HermesWidgetStore;
import org.llm4people.hermes.os.LockGracePolicy;
import org.llm4people.hermes.profile.Account;
import org.llm4people.hermes.profile.ProfileGate;
import org.llm4people.hermes.profile.ProfileLockPolicy;
import org.llm4people.hermes.settings.SettingsWrapLayout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Native, FLAG_SECURE dialogs for Security/Biometrics/Grace Policy and Widget Pinning. */
final class WorkspaceDialogs {
    private WorkspaceDialogs() {}

    static String securitySummary(Activity activity, String profileId) {
        LockGracePolicy.Window window = LockGracePolicy.window(activity, profileId);
        String grace = activity.getString(window.labelRes);
        ProfileLockPolicy.Mode lockMode = ProfileLockPolicy.mode(activity, profileId);
        return activity.getString(lockMode.labelRes) + " · " + grace;
    }

    static AutoCloseable showSecurity(Activity activity, Account account, ProfileGate gate,
                                      Runnable onLockNow, Runnable onPolicyChanged, Runnable onDismissed) {
        LinearLayout content = Ui.column(activity);
        Ui.pad(content, 16, 12);

        // Profile identity + immediate lock card
        LinearLayout profileCard = Ui.card(activity);
        LinearLayout profileHeader = Ui.row(activity);
        profileHeader.addView(Ui.avatarBadge(activity, Ui.initials(account.label), 36, true));
        TextView profileTitle = Ui.text(activity, account.label, 17);
        profileTitle.setTypeface(Ui.mediumTypeface());
        profileTitle.setPaddingRelative(Ui.dp(activity, 10), 0, 0, 0);
        profileHeader.addView(profileTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        profileCard.addView(profileHeader);

        Button lockNow = Ui.outlinedButton(activity, activity.getString(R.string.security_lock_now), null);
        lockNow.setTextColor(Ui.dangerInk(activity));
        LinearLayout.LayoutParams lockLp = new LinearLayout.LayoutParams(-1, -2);
        lockLp.topMargin = Ui.dp(activity, 12);
        profileCard.addView(lockNow, lockLp);
        content.addView(profileCard, new LinearLayout.LayoutParams(-1, -2));

        // Lock method & biometric card
        LinearLayout authCard = Ui.card(activity);
        LinearLayout.LayoutParams cardGap = new LinearLayout.LayoutParams(-1, -2);
        cardGap.topMargin = Ui.dp(activity, 12);

        TextView modeHeading = Ui.text(activity, activity.getString(R.string.security_lock_mode_title), 16);
        modeHeading.setTypeface(Ui.mediumTypeface());
        authCard.addView(modeHeading);

        TextView modeStatus = Ui.pill(activity, "", R.color.accent_surface, R.color.accent);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-2, -2);
        statusLp.topMargin = Ui.dp(activity, 6);
        authCard.addView(modeStatus, statusLp);

        Button changeModeBtn = Ui.tonalButton(activity, activity.getString(R.string.security_lock_mode_change), null);
        LinearLayout.LayoutParams changeLp = new LinearLayout.LayoutParams(-1, -2);
        changeLp.topMargin = Ui.dp(activity, 10);
        authCard.addView(changeModeBtn, changeLp);

        View authDiv = Ui.divider(activity);
        LinearLayout.LayoutParams authDivLp = new LinearLayout.LayoutParams(-1, Math.max(1, Ui.dp(activity, 1)));
        authDivLp.topMargin = Ui.dp(activity, 12);
        authDivLp.bottomMargin = Ui.dp(activity, 10);
        authCard.addView(authDiv, authDivLp);

        TextView bioHeading = Ui.text(activity, activity.getString(R.string.security_biometric_title), 16);
        bioHeading.setTypeface(Ui.mediumTypeface());
        authCard.addView(bioHeading);

        TextView bioStatus = Ui.text(activity, "", 14);
        bioStatus.setTextColor(activity.getColor(R.color.muted));
        bioStatus.setPaddingRelative(0, Ui.dp(activity, 4), 0, 0);
        authCard.addView(bioStatus);

        Button bioAction = Ui.outlinedButton(activity, "", null);
        LinearLayout.LayoutParams bioBtnLp = new LinearLayout.LayoutParams(-1, -2);
        bioBtnLp.topMargin = Ui.dp(activity, 10);
        authCard.addView(bioAction, bioBtnLp);
        content.addView(authCard, cardGap);

        Runnable refreshBio = () -> {
            ProfileLockPolicy.Mode currentMode = ProfileLockPolicy.mode(activity, account.id);
            modeStatus.setText(currentMode.labelRes);
            BiometricVault.Availability available = BiometricVault.availability(activity);
            boolean enrolled = BiometricVault.isEnrolled(activity, account.id);
            if (currentMode == ProfileLockPolicy.Mode.BIOMETRIC) {
                bioStatus.setText(R.string.security_lock_mode_biometric_primary_note);
                bioAction.setVisibility(View.GONE);
            } else if (available == BiometricVault.Availability.UNAVAILABLE) {
                bioStatus.setText(R.string.security_biometric_unavailable_note);
                bioAction.setVisibility(View.GONE);
            } else if (enrolled) {
                bioStatus.setText(R.string.security_biometric_enabled_note);
                bioAction.setVisibility(View.VISIBLE);
                bioAction.setText(R.string.security_biometric_disable);
            } else {
                bioStatus.setText(R.string.security_biometric_disabled_note);
                bioAction.setVisibility(View.VISIBLE);
                bioAction.setText(R.string.security_biometric_enable);
            }
        };
        refreshBio.run();

        final AlertDialog[] dialogHolder = new AlertDialog[1];
        final AlertDialog[] childDialog = new AlertDialog[1];
        final BiometricVault.Session[] activeEnroll = new BiometricVault.Session[1];
        changeModeBtn.setOnClickListener(v -> {
            if (childDialog[0] != null) {
                childDialog[0].dismiss();
                childDialog[0] = null;
            }
            childDialog[0] = showChangeLockModeDialog(
                    activity, account, gate, activeEnroll, childDialog, () -> {
                        refreshBio.run();
                        if (onPolicyChanged != null) onPolicyChanged.run();
                    });
        });
        bioAction.setOnClickListener(v -> {
            if (BiometricVault.isEnrolled(activity, account.id)) {
                BiometricVault.revoke(activity, account.id);
                refreshBio.run();
                if (onPolicyChanged != null) onPolicyChanged.run();
                Toast.makeText(activity, R.string.security_biometric_disabled_toast, Toast.LENGTH_SHORT).show();
            } else {
                bioAction.setEnabled(false);
                if (activeEnroll[0] != null) activeEnroll[0].cancel();
                activeEnroll[0] = gate.enrollBiometric(account, new BiometricVault.EnrollmentCallback() {
                    @Override public void onEnrolled() {
                        activeEnroll[0] = null;
                        if (dialogHolder[0] == null || !dialogHolder[0].isShowing()
                                || activity.isFinishing() || activity.isDestroyed()) return;
                        bioAction.setEnabled(true);
                        refreshBio.run();
                        if (onPolicyChanged != null) onPolicyChanged.run();
                        Toast.makeText(activity, R.string.security_biometric_enabled_toast, Toast.LENGTH_SHORT).show();
                    }

                    @Override public void onCancelled() {
                        activeEnroll[0] = null;
                        if (dialogHolder[0] == null || !dialogHolder[0].isShowing()
                                || activity.isFinishing() || activity.isDestroyed()) return;
                        bioAction.setEnabled(true);
                    }

                    @Override public void onError() {
                        activeEnroll[0] = null;
                        if (dialogHolder[0] == null || !dialogHolder[0].isShowing()
                                || activity.isFinishing() || activity.isDestroyed()) return;
                        bioAction.setEnabled(true);
                        Toast.makeText(activity, R.string.security_biometric_failed, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });

        // Auto-lock timeout card with wrap-safe Material 3 choice chips
        LinearLayout graceCard = Ui.card(activity);
        TextView graceHeading = Ui.text(activity, activity.getString(R.string.security_grace_title), 16);
        graceHeading.setTypeface(Ui.mediumTypeface());
        graceCard.addView(graceHeading);

        TextView graceHint = Ui.text(activity, activity.getString(R.string.security_grace_hint), 14);
        graceHint.setTextColor(activity.getColor(R.color.muted));
        graceHint.setPaddingRelative(0, Ui.dp(activity, 4), 0, Ui.dp(activity, 10));
        graceCard.addView(graceHint);

        LockGracePolicy.Window[] selectedWindow = {LockGracePolicy.window(activity, account.id)};
        List<LockGracePolicy.Window> options = new ArrayList<>(Arrays.asList(LockGracePolicy.selectableWindows()));
        if (!options.contains(selectedWindow[0])) options.add(selectedWindow[0]);

        SettingsWrapLayout graceChips = new SettingsWrapLayout(activity, 8, 8);
        List<Button> chipButtons = new ArrayList<>(options.size());
        Runnable syncGraceChips = () -> {
            for (int i = 0; i < options.size(); i++) {
                Ui.styleChoiceChip(chipButtons.get(i), options.get(i) == selectedWindow[0]);
            }
        };
        for (LockGracePolicy.Window option : options) {
            Button chip = Ui.choiceChip(activity, activity.getString(option.labelRes), v -> {
                if (selectedWindow[0] == option) return;
                if (!LockGracePolicy.setWindow(activity, account.id, option)) return;
                selectedWindow[0] = option;
                syncGraceChips.run();
                if (onPolicyChanged != null) onPolicyChanged.run();
            });
            chip.setTag(option);
            chipButtons.add(chip);
            graceChips.addView(chip);
        }
        syncGraceChips.run();
        graceCard.addView(graceChips, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout.LayoutParams graceCardLp = new LinearLayout.LayoutParams(-1, -2);
        graceCardLp.topMargin = Ui.dp(activity, 12);
        content.addView(graceCard, graceCardLp);

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        AlertDialog dialog = Ui.secureDialog(new AlertDialog.Builder(activity)
                .setTitle(R.string.security_dialog_title)
                .setView(scroll)
                .setPositiveButton(R.string.ws_close, null));
        dialogHolder[0] = dialog;
        Runnable cleanup = () -> {
            if (childDialog[0] != null) {
                childDialog[0].dismiss();
                childDialog[0] = null;
            }
            if (activeEnroll[0] != null) {
                activeEnroll[0].cancel();
                activeEnroll[0] = null;
            }
        };
        lockNow.setOnClickListener(v -> {
            cleanup.run();
            dialog.dismiss();
            onLockNow.run();
        });
        dialog.setOnDismissListener(d -> {
            cleanup.run();
            if (onDismissed != null) onDismissed.run();
        });
        return () -> {
            cleanup.run();
            dialog.dismiss();
        };
    }

    private static AlertDialog showChangeLockModeDialog(
            Activity activity,
            Account account,
            ProfileGate gate,
            BiometricVault.Session[] activeEnroll,
            AlertDialog[] childHolder,
            Runnable onUpdated) {
        LinearLayout form = Ui.column(activity);
        Ui.pad(form, 18, 12);

        boolean bioAvailable = BiometricVault.availability(activity) != BiometricVault.Availability.UNAVAILABLE;
        ProfileLockPolicy.Mode current = ProfileLockPolicy.mode(activity, account.id);
        ProfileLockPolicy.Mode[] chosen = {current};

        RadioGroup modeGroup = new RadioGroup(activity);
        modeGroup.setOrientation(RadioGroup.VERTICAL);
        for (ProfileLockPolicy.Mode m : ProfileLockPolicy.Mode.values()) {
            RadioButton rb = new RadioButton(activity);
            rb.setId(View.generateViewId());
            rb.setText(m.labelRes);
            rb.setTextColor(Ui.enabledDisabledInk(activity, R.color.ink));
            rb.setMinHeight(Ui.dp(activity, 48));
            rb.setTag(m);
            if (m == ProfileLockPolicy.Mode.BIOMETRIC && !bioAvailable) rb.setEnabled(false);
            modeGroup.addView(rb);
            if (m == current) modeGroup.check(rb.getId());
        }
        form.addView(modeGroup);

        EditText first = new EditText(activity);
        first.setSingleLine(true);
        first.setSaveEnabled(false);
        first.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        first.setImeOptions(EditorInfo.IME_ACTION_NEXT | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        Ui.styleDialogInput(activity, first);
        LinearLayout.LayoutParams fLp = new LinearLayout.LayoutParams(-1, -2);
        fLp.topMargin = Ui.dp(activity, 10);
        form.addView(first, fLp);

        EditText confirm = new EditText(activity);
        confirm.setSingleLine(true);
        confirm.setSaveEnabled(false);
        confirm.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        confirm.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        Ui.styleDialogInput(activity, confirm);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(-1, -2);
        cLp.topMargin = Ui.dp(activity, 8);
        form.addView(confirm, cLp);

        TextView note = Ui.text(activity, "", 13);
        note.setTextColor(activity.getColor(R.color.muted));
        LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(-1, -2);
        nLp.topMargin = Ui.dp(activity, 8);
        form.addView(note, nLp);

        Runnable syncInputs = () -> {
            first.getText().clear();
            confirm.getText().clear();
            note.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);
            note.setTextColor(activity.getColor(R.color.muted));
            if (chosen[0] == ProfileLockPolicy.Mode.BIOMETRIC) {
                first.setVisibility(View.GONE);
                confirm.setVisibility(View.GONE);
                note.setText(bioAvailable ? R.string.lock_mode_biometric_detail : R.string.lock_mode_biometric_unavailable);
            } else if (chosen[0] == ProfileLockPolicy.Mode.PIN) {
                first.setVisibility(View.VISIBLE);
                confirm.setVisibility(View.VISIBLE);
                int pinType = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD;
                first.setInputType(pinType);
                confirm.setInputType(pinType);
                InputFilter[] pinFilter = {new InputFilter.LengthFilter(ProfileLockPolicy.MAX_PIN_DIGITS)};
                first.setFilters(pinFilter);
                confirm.setFilters(pinFilter);
                first.setHint(R.string.lock_pin_hint);
                confirm.setHint(R.string.lock_pin_confirm_label);
                note.setText(R.string.lock_setup_missing_pin);
            } else {
                first.setVisibility(View.VISIBLE);
                confirm.setVisibility(View.VISIBLE);
                int passType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
                first.setInputType(passType);
                confirm.setInputType(passType);
                InputFilter[] passFilter = {new InputFilter.LengthFilter(512)};
                first.setFilters(passFilter);
                confirm.setFilters(passFilter);
                first.setHint(R.string.profile_passphrase_label);
                confirm.setHint(R.string.profile_passphrase_confirm_label);
                note.setText(R.string.lock_setup_missing_passphrase);
            }
        };
        modeGroup.setOnCheckedChangeListener((g, id) -> {
            View v = g.findViewById(id);
            if (v != null && v.getTag() instanceof ProfileLockPolicy.Mode m) {
                chosen[0] = m;
                syncInputs.run();
            }
        });
        syncInputs.run();

        ScrollView formScroll = new ScrollView(activity);
        formScroll.addView(form, new ScrollView.LayoutParams(-1, -2));

        AlertDialog modeDialog = Ui.secureDialog(new AlertDialog.Builder(activity)
                .setTitle(R.string.security_lock_mode_title)
                .setView(formScroll)
                .setNegativeButton(R.string.profile_setup_cancel, null)
                .setPositiveButton(R.string.security_lock_mode_save, null));
        Button saveBtn = modeDialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (saveBtn != null) {
            saveBtn.setOnClickListener(v -> {
                ProfileLockPolicy.Mode target = chosen[0];
                int errRes = lockModeValidationErrorRes(target, first.getText(), confirm.getText(), bioAvailable);
                if (errRes != 0) {
                    note.setTextColor(activity.getColor(R.color.danger));
                    if (note.getAccessibilityLiveRegion() != View.ACCESSIBILITY_LIVE_REGION_POLITE) {
                        note.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
                    }
                    String errMsg = activity.getString(errRes);
                    if (errMsg.contentEquals(note.getText())) {
                        note.setText("");
                    }
                    note.setText(errMsg);
                    return;
                }
                char[] secret = null;
                if (target == ProfileLockPolicy.Mode.PIN || target == ProfileLockPolicy.Mode.PASSPHRASE) {
                    secret = new char[first.length()];
                    first.getText().getChars(0, secret.length, secret, 0);
                    first.getText().clear();
                    confirm.getText().clear();
                }
                modeDialog.dismiss();
                if (activeEnroll[0] != null) activeEnroll[0].cancel();
                activeEnroll[0] = gate.updateLockMode(account, target, secret, ok -> {
                    activeEnroll[0] = null;
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    Toast.makeText(activity,
                            ok ? R.string.security_lock_mode_updated : R.string.security_lock_mode_update_failed,
                            Toast.LENGTH_SHORT).show();
                    if (ok && onUpdated != null) onUpdated.run();
                });
            });
        }
        modeDialog.setOnDismissListener(d -> {
            first.getText().clear();
            confirm.getText().clear();
            if (childHolder != null && childHolder[0] == modeDialog) childHolder[0] = null;
        });
        return modeDialog;
    }

    /** Pure-JVM validation error message resource for the Change Lock Mode dialog (0 when READY). */
    static int lockModeValidationErrorRes(
            ProfileLockPolicy.Mode target,
            CharSequence first,
            CharSequence confirm,
            boolean bioAvailable) {
        if (target == ProfileLockPolicy.Mode.PIN) {
            return switch (ProfileLockPolicy.evaluatePin(first, confirm)) {
                case READY -> 0;
                case INVALID_FORMAT -> R.string.lock_pin_digits_only;
                case MISMATCH -> R.string.lock_pin_mismatch;
                case AWAITING_CONFIRM -> R.string.lock_pin_confirm_needed;
                case EMPTY, TOO_SHORT -> R.string.lock_setup_missing_pin;
            };
        }
        if (target == ProfileLockPolicy.Mode.PASSPHRASE) {
            return switch (ProfileLockPolicy.evaluatePassphrase(first, confirm)) {
                case READY -> 0;
                case MISMATCH -> R.string.profile_setup_passphrase_mismatch_live;
                case AWAITING_CONFIRM -> R.string.profile_setup_passphrase_confirm_needed;
                case EMPTY, INVALID_FORMAT, TOO_SHORT -> R.string.lock_setup_missing_passphrase;
            };
        }
        return bioAvailable ? 0 : R.string.lock_mode_biometric_unavailable;
    }

    /** Each profile pins its own quick-actions widget, so profiles never overwrite or purge each other's. */
    static void pinQuickWidget(Activity activity, String ownerProfileId) {
        if (ownerProfileId == null || ownerProfileId.isEmpty()) return;
        HermesWidgetSpec spec = new HermesWidgetSpec(
                HermesWidgetStore.profileScopedId("hermes_quick_actions", ownerProfileId),
                activity.getString(R.string.widget_title),
                activity.getString(R.string.widget_quick_subtitle),
                HermesWidgetSpec.Template.QUICK_ACTIONS,
                List.of(
                        new HermesWidgetSpec.Item(activity.getString(R.string.widget_quick_ask), "", ""),
                        new HermesWidgetSpec.Item(activity.getString(R.string.widget_quick_plan), "",
                                activity.getString(R.string.widget_quick_plan_draft))
                ),
                null,
                true,
                ownerProfileId);
        HermesWidgetPinning.PinResult result = HermesWidgetPinning.requestPinWidget(activity, spec);
        Toast.makeText(activity, HermesWidgetPinning.guidanceMessage(activity, result), Toast.LENGTH_LONG).show();
    }
}
