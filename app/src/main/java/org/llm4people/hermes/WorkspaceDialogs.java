package org.llm4people.hermes;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
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
import org.llm4people.hermes.thread.ThreadWorkspace;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Native, FLAG_SECURE dialogs for 1-tap Search, Security/Biometrics/Grace Policy, and Widget Pinning. */
final class WorkspaceDialogs {
    private WorkspaceDialogs() {}

    static String securitySummary(Activity activity, String profileId) {
        LockGracePolicy.Window window = LockGracePolicy.window(activity, profileId);
        String grace = activity.getString(window.labelRes);
        boolean bio = BiometricVault.isEnrolled(activity, profileId);
        return bio ? grace + " · " + activity.getString(R.string.security_biometric_title) : grace;
    }

    static AlertDialog showSearch(Activity activity, ThreadWorkspace threads,
                                  Consumer<String> onAction, Runnable onDismissed) {
        LinearLayout box = Ui.column(activity);
        Ui.pad(box, 18, 12);
        EditText query = new EditText(activity);
        query.setSingleLine(true);
        query.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        query.setSaveEnabled(false);
        query.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        query.setHint(R.string.search_dialog_hint);
        query.setTextColor(activity.getColor(R.color.ink));
        query.setHintTextColor(activity.getColor(R.color.muted));
        query.setBackgroundResource(R.drawable.profile_input);
        query.setBackgroundTintList(null);
        query.setMinHeight(Ui.dp(activity, 48));
        Ui.pad(query, 10, 8);
        box.addView(query, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout results = Ui.column(activity);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(results, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, Ui.dp(activity, 260));
        scrollParams.topMargin = Ui.dp(activity, 10);
        box.addView(scroll, scrollParams);

        AlertDialog[] dialogHolder = new AlertDialog[1];
        List<ThreadWorkspace.ThreadSummary> cachedThreads = threads == null
                ? List.of() : threads.recentThreads();
        record Entry(String label, Runnable run) {}
        List<Entry> entries = new ArrayList<>();
        for (ThreadWorkspace.ThreadSummary item : cachedThreads) {
            entries.add(new Entry(item.title(), () -> {
                if (dialogHolder[0] != null) dialogHolder[0].dismiss();
                onAction.accept("nav_discussions");
                if (threads != null) threads.openSummary(item.id(), item.title());
            }));
        }
        entries.add(new Entry(activity.getString(R.string.search_all_history), () -> {
            if (dialogHolder[0] != null) dialogHolder[0].dismiss();
            onAction.accept(org.llm4people.hermes.panel.BrainStudioRoutes.ROUTE_SEARCH);
        }));
        entries.add(new Entry(activity.getString(R.string.activity_deliveries_title), () -> {
            if (dialogHolder[0] != null) dialogHolder[0].dismiss();
            onAction.accept("deliveries");
        }));
        entries.add(new Entry(activity.getString(R.string.nav_atelier) + " · "
                + activity.getString(R.string.ws_my_screens), () -> {
            if (dialogHolder[0] != null) dialogHolder[0].dismiss();
            onAction.accept("pages");
        }));
        entries.add(new Entry(activity.getString(R.string.brain_skills_title), () -> {
            if (dialogHolder[0] != null) dialogHolder[0].dismiss();
            onAction.accept("skills");
        }));
        entries.add(new Entry(activity.getString(R.string.activity_reminders_title), () -> {
            if (dialogHolder[0] != null) dialogHolder[0].dismiss();
            onAction.accept("reminders");
        }));
        entries.add(new Entry(activity.getString(R.string.activity_security_title), () -> {
            if (dialogHolder[0] != null) dialogHolder[0].dismiss();
            onAction.accept("security");
        }));

        Runnable render = () -> {
            results.removeAllViews();
            String needle = query.getText().toString().trim().toLowerCase(Locale.ROOT);
            int shown = 0;
            for (Entry entry : entries) {
                if (!needle.isEmpty() && !entry.label().toLowerCase(Locale.ROOT).contains(needle)) continue;
                Button row = Ui.button(activity, entry.label(), v -> entry.run().run());
                row.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
                results.addView(row, new LinearLayout.LayoutParams(-1, -2));
                results.addView(Ui.divider(activity));
                shown++;
            }
            if (shown == 0) {
                TextView empty = Ui.text(activity, activity.getString(R.string.search_no_results), 15);
                empty.setTextColor(activity.getColor(R.color.muted));
                Ui.pad(empty, 4, 12);
                results.addView(empty);
            }
        };
        render.run();
        query.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { render.run(); }
            @Override public void afterTextChanged(Editable s) {}
        });

        AlertDialog dialog = Ui.secureDialog(new AlertDialog.Builder(activity)
                .setTitle(R.string.search_dialog_title)
                .setView(box)
                .setNegativeButton(R.string.ws_close, null));
        dialogHolder[0] = dialog;
        dialog.setOnDismissListener(d -> { if (onDismissed != null) onDismissed.run(); });
        return dialog;
    }

    static AlertDialog showSecurity(Activity activity, Account account, ProfileGate gate,
                                    Runnable onLockNow, Runnable onPolicyChanged, Runnable onDismissed) {
        LinearLayout content = Ui.column(activity);
        Ui.pad(content, 18, 12);

        TextView profileTitle = Ui.text(activity, account.label, 17);
        profileTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(profileTitle);

        Button lockNow = Ui.button(activity, activity.getString(R.string.security_lock_now), null);
        lockNow.setTextColor(activity.getColor(R.color.danger));
        lockNow.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        content.addView(lockNow, new LinearLayout.LayoutParams(-1, -2));
        content.addView(Ui.divider(activity));

        TextView bioHeading = Ui.text(activity, activity.getString(R.string.security_biometric_title), 16);
        bioHeading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        bioHeading.setPaddingRelative(0, Ui.dp(activity, 12), 0, Ui.dp(activity, 4));
        content.addView(bioHeading);

        TextView bioStatus = Ui.text(activity, "", 14);
        bioStatus.setTextColor(activity.getColor(R.color.muted));
        content.addView(bioStatus);

        Button bioAction = Ui.button(activity, "", null);
        bioAction.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        content.addView(bioAction, new LinearLayout.LayoutParams(-1, -2));

        Runnable refreshBio = () -> {
            BiometricVault.Availability available = BiometricVault.availability(activity);
            boolean enrolled = BiometricVault.isEnrolled(activity, account.id);
            if (available == BiometricVault.Availability.UNAVAILABLE) {
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
        final BiometricVault.Session[] activeEnroll = new BiometricVault.Session[1];
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

        content.addView(Ui.divider(activity));

        TextView graceHeading = Ui.text(activity, activity.getString(R.string.security_grace_title), 16);
        graceHeading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        graceHeading.setPaddingRelative(0, Ui.dp(activity, 12), 0, Ui.dp(activity, 4));
        content.addView(graceHeading);

        TextView graceHint = Ui.text(activity, activity.getString(R.string.security_grace_hint), 14);
        graceHint.setTextColor(activity.getColor(R.color.muted));
        content.addView(graceHint);

        RadioGroup group = new RadioGroup(activity);
        group.setOrientation(RadioGroup.VERTICAL);
        LockGracePolicy.Window current = LockGracePolicy.window(activity, account.id);
        for (LockGracePolicy.Window option : LockGracePolicy.Window.values()) {
            RadioButton button = new RadioButton(activity);
            button.setId(View.generateViewId());
            button.setText(option.labelRes);
            button.setTextColor(activity.getColor(R.color.ink));
            button.setMinHeight(Ui.dp(activity, 48));
            button.setTag(option);
            group.addView(button);
            if (option == current) group.check(button.getId());
        }
        group.setOnCheckedChangeListener((g, checkedId) -> {
            View selected = g.findViewById(checkedId);
            if (selected != null && selected.getTag() instanceof LockGracePolicy.Window chosen) {
                LockGracePolicy.setWindow(activity, account.id, chosen);
                if (onPolicyChanged != null) onPolicyChanged.run();
            }
        });
        content.addView(group);

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        AlertDialog dialog = Ui.secureDialog(new AlertDialog.Builder(activity)
                .setTitle(R.string.security_dialog_title)
                .setView(scroll)
                .setPositiveButton(R.string.ws_close, null));
        dialogHolder[0] = dialog;
        lockNow.setOnClickListener(v -> {
            dialog.dismiss();
            onLockNow.run();
        });
        dialog.setOnDismissListener(d -> {
            if (activeEnroll[0] != null) {
                activeEnroll[0].cancel();
                activeEnroll[0] = null;
            }
            if (onDismissed != null) onDismissed.run();
        });
        return dialog;
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
