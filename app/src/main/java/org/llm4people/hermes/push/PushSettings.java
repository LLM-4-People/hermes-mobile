package org.llm4people.hermes.push;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.DataSetObserver;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.llm4people.hermes.AppLanguage;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.os.PermissionCenter;
import org.llm4people.hermes.profile.Account;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Objects;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import org.json.JSONObject;

final class PushSettings {
    static final int PERMISSION = 6801;
    private final Activity activity;
    private final Account account;
    private final HermesApi api;
    private final DeliveryRouter.Actions deliveryActions;
    private AlertDialog dialog;
    private TimePickerDialog timePicker;
    private DeliveryRouter deliveryRouter;
    private boolean showingMainSettings;
    private boolean displayedAllowed;
    private boolean showingPermissionRecovery;
    private volatile boolean closed;

    PushSettings(Activity activity, Account account, DeliveryRouter.Actions deliveryActions) {
        this.activity = activity;
        this.account = account;
        this.api = account == null ? null
                : new HermesApi(account.endpoint, account.bearer, PushConnection.apiLimits(activity));
        this.deliveryActions = deliveryActions;
    }

    HermesApi ownedApi() {
        return api;
    }

    DeliveryRouter.Actions deliveryActions() {
        return deliveryActions;
    }

    void close() {
        closed = true;
        if (api != null) api.revoke();
        if (timePicker != null) { timePicker.dismiss(); timePicker = null; }
        dismissDeliveryRouter();
        if (dialog != null) dialog.dismiss();
    }

    private void dismissDeliveryRouter() {
        DeliveryRouter currentRouter = deliveryRouter;
        deliveryRouter = null;
        if (currentRouter != null) currentRouter.dismiss();
    }

    private boolean current() { return !closed && !activity.isFinishing() && !activity.isDestroyed(); }

    private AlertDialog display(AlertDialog.Builder builder) {
        if (!current()) return null;
        showingMainSettings = false;
        showingPermissionRecovery = false;
        dismissDeliveryRouter();
        if (dialog != null) dialog.dismiss();
        dialog = Ui.secureDialog(builder);
        return dialog;
    }

    private boolean showing(AlertDialog expected) {
        return current() && dialog == expected && expected.isShowing();
    }

    private void finishProgress(AlertDialog progress, Runnable update) {
        activity.runOnUiThread(() -> {
            if (showing(progress)) update.run();
        });
    }

    void show() {
        show(true);
    }

    enum ResumeAction { NONE, DISCOVER_TRANSPORT, REFRESH_SETTINGS }

    static ResumeAction resolveResumeAction(
            boolean dialogShowing,
            boolean showingPermissionRecovery,
            boolean showingMainSettings,
            boolean displayedAllowed,
            boolean nowAllowed) {
        if (!dialogShowing) return ResumeAction.NONE;
        if (showingPermissionRecovery && nowAllowed) return ResumeAction.DISCOVER_TRANSPORT;
        if (showingMainSettings && displayedAllowed != nowAllowed) return ResumeAction.REFRESH_SETTINGS;
        return ResumeAction.NONE;
    }

    static boolean isBlockedSettingsDialog(PushStore.Device device, boolean allowed) {
        return device != null
                && !PushStore.REMOVING.equals(device.state)
                && (PushStore.ACTIVE.equals(device.state) || pendingRepair(device))
                && !allowed;
    }

    static boolean shouldRequestRuntimePermissionInRecovery(int sdkInt, PermissionCenter.State state) {
        return sdkInt >= 33
                && (state == PermissionCenter.State.NOT_REQUESTED || state == PermissionCenter.State.DENIED);
    }

    private static void keepOpenOnPositiveClick(AlertDialog target, Runnable action) {
        if (target == null) return;
        Button positive = target.getButton(DialogInterface.BUTTON_POSITIVE);
        if (positive != null) positive.setOnClickListener(v -> action.run());
    }

    private void show(boolean triggerReconcile) {
        if (!current()) return;
        PushStore.Device device = PushStore.get(activity).forProfile(account.id);
        boolean allowed = PushNotices.allowed(activity);
        AlertDialog shown = display(settingsDialog(device, allowed));
        if (shown != null) {
            if (isBlockedSettingsDialog(device, allowed)) {
                keepOpenOnPositiveClick(shown, this::systemSettings);
            }
            showingMainSettings = true;
            displayedAllowed = allowed;
            observeState(device);
            if (triggerReconcile) {
                Context app = activity.getApplicationContext();
                PushConnection.WORK.execute(() -> DistributorEnrollment.reconcile(app));
            }
        }
    }

    void refreshPermissionState() {
        if (!current()) return;
        boolean allowed = PushNotices.allowed(activity);
        ResumeAction action = resolveResumeAction(
                dialog != null && dialog.isShowing(),
                showingPermissionRecovery,
                showingMainSettings,
                displayedAllowed,
                allowed);
        if (action == ResumeAction.DISCOVER_TRANSPORT) {
            showingPermissionRecovery = false;
            discoverTransport();
        } else if (action == ResumeAction.REFRESH_SETTINGS) {
            show(false);
        }
    }

    AlertDialog.Builder settingsDialog(PushStore.Device device, boolean allowed) {
        boolean[] handled = {false};
        AlertDialog.Builder builder = new AlertDialog.Builder(activity).setTitle(R.string.push_settings_title)
                .setNegativeButton(R.string.ws_close, null)
                .setNeutralButton(R.string.push_settings_history, (d,w) -> {
                    if (handled[0]) return;
                    handled[0] = true;
                    history(0, new ArrayDeque<>());
                });
        if (device != null && PushStore.REMOVING.equals(device.state)) {
            builder.setMessage(R.string.push_settings_removal_pending)
                    .setPositiveButton(R.string.instrument_retry, (d,w) -> {
                        if (handled[0]) return;
                        handled[0] = true;
                        remove(device);
                    });
        } else if (device != null && (PushStore.ACTIVE.equals(device.state) || pendingRepair(device))) {
            if (!allowed) {
                String android = activity.getString(R.string.push_settings_blocked_message);
                builder.setTitle(R.string.push_settings_blocked_title)
                        .setMessage(device.repair == PushStore.NO_REPAIR ? android : android + "\n\n" + activeMessage(device))
                        .setPositiveButton(R.string.push_settings_android_settings, null)
                        .setNegativeButton(R.string.push_settings_disable, (d,w) -> {
                            if (handled[0]) return;
                            handled[0] = true;
                            remove(device);
                        });
            } else if (device.repair != PushStore.NO_REPAIR) {
                builder.setTitle(R.string.push_settings_distributor_connecting).setMessage(activeMessage(device))
                        .setNegativeButton(R.string.push_settings_disable, (d,w) -> {
                            if (handled[0]) return;
                            handled[0] = true;
                            remove(device);
                        });
                if (device.repair == PushStore.REPAIR_ACTION)
                    builder.setPositiveButton(R.string.instrument_retry, (d,w) -> {
                        if (handled[0]) return;
                        handled[0] = true;
                        retryRepair(device);
                    });
            } else {
                builder.setMessage(activeMessage(device)).setPositiveButton(R.string.push_settings_disable, (d,w) -> {
                    if (handled[0]) return;
                    handled[0] = true;
                    remove(device);
                });
            }
        } else if (device != null) {
            builder.setTitle(R.string.push_settings_registration_confirm)
                    .setMessage(registrationMessage(device))
                    .setPositiveButton(R.string.instrument_retry, (d,w) -> {
                        if (handled[0]) return;
                        handled[0] = true;
                        begin();
                    })
                    .setNegativeButton(R.string.push_settings_disable, (d,w) -> {
                        if (handled[0]) return;
                        handled[0] = true;
                        remove(device);
                    });
        } else {
            builder.setMessage(R.string.push_settings_intro)
                    .setPositiveButton(R.string.push_settings_enable, (d,w) -> {
                        if (handled[0]) return;
                        handled[0] = true;
                        begin();
                    });
        }
        LinearLayout extras = null;
        if (offersDistributor(device)) {
            extras = Ui.column(activity);
            Ui.pad(extras, 18, 8);
            extras.addView(Ui.tonalButton(activity, activity.getString(R.string.push_settings_open_distributor),
                    v -> openDistributor(v, device)), stackGap(0));
            if (!allowed && device.repair == PushStore.REPAIR_ACTION) {
                extras.addView(Ui.outlinedButton(activity, activity.getString(R.string.instrument_retry),
                        v -> retryRepair(device)), stackGap(8));
            }
        }
        if (account != null) {
            if (extras == null) {
                extras = Ui.column(activity);
                Ui.pad(extras, 18, 8);
            }
            PushPolicy currentPolicy = PushPolicy.load(activity, account.id);
            String quietLine = currentPolicy.quietHoursEnabled
                    ? activity.getString(R.string.push_policy_quiet_status_on,
                            PushPolicy.formatMinute(activity, currentPolicy.quietStartMinute),
                            PushPolicy.formatMinute(activity, currentPolicy.quietEndMinute))
                    : activity.getString(R.string.push_policy_quiet_status_off);
            TextView summary = Ui.text(activity, quietLine, 14);
            summary.setTextColor(activity.getColor(R.color.muted));
            Ui.pad(summary, 2, 6);
            extras.addView(summary, stackGap(extras.getChildCount() > 0 ? 8 : 0));
            extras.addView(Ui.tonalButton(activity, activity.getString(R.string.push_policy_button),
                    v -> showPolicyDialog()), stackGap(8));
            extras.addView(Ui.outlinedButton(activity, activity.getString(R.string.push_inbox_button),
                    v -> openDeliveryInbox()), stackGap(8));
        }
        if (extras != null) {
            ScrollView scroll = new ScrollView(activity);
            scroll.addView(extras, new ScrollView.LayoutParams(-1, -2));
            builder.setView(scroll);
        }
        return builder;
    }

    private LinearLayout.LayoutParams stackGap(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        if (topDp > 0) lp.topMargin = Ui.dp(activity, topDp);
        return lp;
    }

    private Switch styledSwitch(String label, boolean checked) {
        Switch toggle = new Switch(activity);
        toggle.setText(label);
        toggle.setTextSize(15);
        toggle.setTextColor(Ui.enabledDisabledInk(activity, R.color.ink));
        toggle.setChecked(checked);
        toggle.setMinHeight(Ui.dp(activity, 48));
        return toggle;
    }

    void showPolicyDialog() {
        if (!current() || account == null) return;
        PushPolicy[] state = {PushPolicy.load(activity, account.id)};
        LinearLayout content = Ui.column(activity);
        Ui.pad(content, 18, 12);

        TextView catHeading = Ui.text(activity, activity.getString(R.string.push_policy_categories_heading), 17);
        catHeading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        catHeading.setAccessibilityHeading(true);
        content.addView(catHeading);
        TextView catHint = Ui.text(activity, activity.getString(R.string.push_policy_categories_hint), 13);
        catHint.setTextColor(activity.getColor(R.color.muted));
        content.addView(catHint, stackGap(4));

        LinearLayout categoriesCard = Ui.card(activity);
        DeliveryItem.Category[] categories = DeliveryItem.Category.values();
        for (int i = 0; i < categories.length; i++) {
            DeliveryItem.Category category = categories[i];
            if (i > 0) {
                addInsetDivider(categoriesCard, 8);
            }
            Switch toggle = styledSwitch(category.label(activity), state[0].isCategoryEnabled(category));
            toggle.setOnCheckedChangeListener((btn, checked) -> {
                state[0] = state[0].withCategory(category, checked);
                PushPolicy.save(activity, account.id, state[0]);
            });
            categoriesCard.addView(toggle, stackGap(i > 0 ? 4 : 0));
            TextView sub = Ui.text(activity, category.hint(activity), 12);
            sub.setTextColor(activity.getColor(R.color.muted));
            categoriesCard.addView(sub, stackGap(2));
        }
        content.addView(categoriesCard, stackGap(10));

        TextView quietHeading = Ui.text(activity, activity.getString(R.string.push_policy_quiet_heading), 17);
        quietHeading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        quietHeading.setAccessibilityHeading(true);
        content.addView(quietHeading, stackGap(16));

        LinearLayout quietCard = Ui.card(activity);
        Button startButton = Ui.outlinedButton(activity, "", null);
        Button endButton = Ui.outlinedButton(activity, "", null);
        Switch highToggle = styledSwitch(activity.getString(R.string.push_policy_quiet_high_toggle),
                state[0].allowHighUrgencyDuringQuietHours);
        TextView highHint = Ui.text(activity, activity.getString(R.string.push_policy_quiet_high_hint), 12);

        Runnable refreshQuietViews = () -> {
            boolean enabled = state[0].quietHoursEnabled;
            startButton.setText(activity.getString(R.string.push_policy_quiet_start,
                    PushPolicy.formatMinute(activity, state[0].quietStartMinute)));
            endButton.setText(activity.getString(R.string.push_policy_quiet_end,
                    PushPolicy.formatMinute(activity, state[0].quietEndMinute)));
            startButton.setEnabled(enabled);
            endButton.setEnabled(enabled);
            highToggle.setEnabled(enabled);
            highHint.setTextColor(activity.getColor(enabled ? R.color.muted : R.color.disabled_ink));
        };

        Switch quietToggle = styledSwitch(activity.getString(R.string.push_policy_quiet_toggle),
                state[0].quietHoursEnabled);
        quietToggle.setOnCheckedChangeListener((btn, checked) -> {
            state[0] = state[0].withQuietHours(checked, state[0].quietStartMinute,
                    state[0].quietEndMinute, state[0].allowHighUrgencyDuringQuietHours);
            PushPolicy.save(activity, account.id, state[0]);
            refreshQuietViews.run();
        });
        quietCard.addView(quietToggle);

        TextView quietHint = Ui.text(activity, activity.getString(R.string.push_policy_quiet_hint), 12);
        quietHint.setTextColor(activity.getColor(R.color.muted));
        quietCard.addView(quietHint, stackGap(2));

        addInsetDivider(quietCard, 10);
        startButton.setOnClickListener(v -> openQuietTimePicker(state[0].quietStartMinute, picked -> {
            state[0] = state[0].withQuietHours(state[0].quietHoursEnabled, picked,
                    state[0].quietEndMinute, state[0].allowHighUrgencyDuringQuietHours);
            PushPolicy.save(activity, account.id, state[0]);
            refreshQuietViews.run();
        }));
        endButton.setOnClickListener(v -> openQuietTimePicker(state[0].quietEndMinute, picked -> {
            state[0] = state[0].withQuietHours(state[0].quietHoursEnabled, state[0].quietStartMinute,
                    picked, state[0].allowHighUrgencyDuringQuietHours);
            PushPolicy.save(activity, account.id, state[0]);
            refreshQuietViews.run();
        }));
        quietCard.addView(startButton, stackGap(10));
        quietCard.addView(endButton, stackGap(8));

        addInsetDivider(quietCard, 10);
        highToggle.setOnCheckedChangeListener((btn, checked) -> {
            state[0] = state[0].withQuietHours(state[0].quietHoursEnabled, state[0].quietStartMinute,
                    state[0].quietEndMinute, checked);
            PushPolicy.save(activity, account.id, state[0]);
        });
        quietCard.addView(highToggle, stackGap(6));
        quietCard.addView(highHint, stackGap(2));
        content.addView(quietCard, stackGap(8));

        refreshQuietViews.run();

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);
        display(new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.push_policy_title))
                .setView(scroll)
                .setNegativeButton(R.string.push_settings_back, (d, w) -> show())
                .setPositiveButton(R.string.ws_close, null));
    }

    private void addInsetDivider(LinearLayout parent, int topDp) {
        View divider = Ui.insetDivider(activity, 16);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) divider.getLayoutParams();
        lp.topMargin = Ui.dp(activity, topDp);
        parent.addView(divider, lp);
    }

    private void openQuietTimePicker(int initialMinute, java.util.function.IntConsumer onPicked) {
        if (!current()) return;
        if (timePicker != null) timePicker.dismiss();
        timePicker = new TimePickerDialog(activity, (picker, hour, minute) -> {
            if (current()) onPicked.accept(hour * 60 + minute);
        }, initialMinute / 60, initialMinute % 60, true);
        timePicker.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        timePicker.show();
    }

    private void openDeliveryInbox() {
        if (!current() || account == null) return;
        dismissDeliveryRouter();
        if (dialog != null) dialog.dismiss();
        HermesApi routerApi = new HermesApi(account.endpoint, account.bearer, PushConnection.apiLimits(activity));
        DeliveryRouter.Actions wired = new DeliveryRouter.Actions() {
            @Override public void openDiscussion(String sessionId, String runId) {
                if (deliveryActions != null) deliveryActions.openDiscussion(sessionId, runId);
            }
            @Override public void openPublishedFile(String sessionId, String fileId, String filename) {
                if (deliveryActions != null) deliveryActions.openPublishedFile(sessionId, fileId, filename);
            }
            @Override public void openCodeSnippet(String title, String language, String content) {
                if (deliveryActions != null) deliveryActions.openCodeSnippet(title, language, content);
            }
            @Override public void previewUiPatch(DeliveryItem item) {
                if (deliveryActions != null) deliveryActions.previewUiPatch(item);
            }
            @Override public void refreshWidget(DeliveryItem item) {
                if (deliveryActions != null) deliveryActions.refreshWidget(item);
            }
            @Override public void reviewDeviceAction(DeliveryItem item) {
                if (deliveryActions != null) deliveryActions.reviewDeviceAction(item);
            }
            @Override public void openReminder(String jobId, String sessionId) {
                if (deliveryActions != null) deliveryActions.openReminder(jobId, sessionId);
            }
            @Override public void openPushPolicy() {
                dismissDeliveryRouter();
                showPolicyDialog();
            }
        };
        deliveryRouter = new DeliveryRouter(activity, account, routerApi, new DeliveryInbox(), wired);
        deliveryRouter.showInbox();
    }

    private void retryRepair(PushStore.Device device) {
        run(activity.getString(R.string.push_settings_reconnecting),
                () -> DistributorEnrollment.repair(activity.getApplicationContext(), device, true));
    }

    private String activeMessage(PushStore.Device device) {
        if (!device.unified()) return activity.getString(R.string.push_settings_active_summary);
        String status = activity.getString(
                pendingRepair(device) ? R.string.push_settings_relay_pending : R.string.push_settings_relay_registered);
        return status + " " + switch (device.repair) {
            case PushStore.WAITING_ENDPOINT -> activity.getString(R.string.push_settings_waiting_distributor);
            case PushStore.RETRY_REPAIR -> activity.getString(R.string.push_settings_retry_distributor_auto);
            case PushStore.REPAIR_ACTION -> registrationMessage(device);
            default -> activity.getString(R.string.push_settings_open_distributor_check);
        };
    }

    private String registrationMessage(PushStore.Device device) {
        if (device.retryScheduled()) return activity.getString(R.string.push_settings_retry_network_auto);
        if (!PushStore.FAILED.equals(device.state) && device.repair != PushStore.REPAIR_ACTION) {
            return activity.getString(R.string.push_settings_device_registration_pending);
        }
        return switch (device.failure == null ? "" : device.failure) {
            case "ACTION_REQUIRED" -> activity.getString(R.string.push_settings_distributor_finish);
            case "VAPID_REQUIRED" -> activity.getString(R.string.push_settings_distributor_vapid);
            case PushStore.ENDPOINT_REJECTED -> activity.getString(R.string.push_settings_relay_refused_endpoint);
            case PushStore.ACCOUNT_REQUIRED -> activity.getString(R.string.push_settings_relay_confirm_profile);
            default -> activity.getString(device.repair == PushStore.REPAIR_ACTION
                    ? R.string.push_settings_distributor_reconnect_failed
                    : R.string.push_settings_distributor_register_failed);
        };
    }

    private void observeState(PushStore.Device displayed) {
        AlertDialog watching = dialog;
        PushStore store = PushStore.get(activity);
        DataSetObserver observer = new DataSetObserver() {
            @Override public void onChanged() {
                // Always post. A read or dismissal from inside notifyChanged must not unregister mid-iteration.
                activity.getMainExecutor().execute(() -> {
                    if (!showing(watching)) return;
                    PushStore.Device updated = store.forProfile(account.id);
                    if (displayed == null || updated == null) {
                        if (displayed != updated) show(false);
                    } else if (!displayed.installation.equals(updated.installation)
                            || !displayed.state.equals(updated.state) || displayed.repair != updated.repair
                            || !Objects.equals(displayed.failure, updated.failure)
                            || displayed.retryScheduled() != updated.retryScheduled()) show(false);
                });
            }
        };
        store.observe(observer);
        watching.setOnDismissListener(d -> store.stopObserving(observer));
        observer.onChanged();
    }

    private void history(long cursor, ArrayDeque<Long> previous) {
        AlertDialog progress = display(new AlertDialog.Builder(activity)
                .setTitle(R.string.push_settings_history_title)
                .setMessage(R.string.push_settings_loading));
        if (progress == null) return;
        Locale locale = AppLanguage.resolvedLocale(activity);
        PushConnection.WORK.execute(() -> {
            try {
                if (!current()) return;
                PushHistory.Page page = PushHistory.load(activity, api, cursor, locale);
                finishProgress(progress, () -> {
                    boolean[] handled = {false};
                    AlertDialog.Builder list = new AlertDialog.Builder(activity)
                            .setTitle(R.string.push_settings_history_title)
                            .setNegativeButton(R.string.ws_close, null);
                    if (page.labels.isEmpty()) list.setMessage(R.string.push_settings_history_empty);
                    else list.setItems(page.labels.toArray(new String[0]), (d,index) -> {
                        if (handled[0]) return;
                        handled[0] = true;
                        if (current() && activity instanceof MobilePush.EventHost host) {
                            host.openPushEvent(account, page.events.get(index));
                        }
                    });
                    if (page.more) list.setPositiveButton(R.string.push_settings_next, (d,w) -> {
                        if (handled[0]) return;
                        handled[0] = true;
                        previous.push(cursor);
                        history(page.next, previous);
                    });
                    if (!previous.isEmpty()) list.setNeutralButton(R.string.push_settings_previous, (d,w) -> {
                        if (handled[0] || previous.isEmpty()) return;
                        handled[0] = true;
                        history(previous.pop(), previous);
                    });
                    display(list);
                });
            } catch (Exception error) {
                Diagnostics.warning("push", "history_load_failed");
                finishProgress(progress, () -> {
                    boolean[] handled = {false};
                    display(new AlertDialog.Builder(activity)
                            .setTitle(R.string.push_settings_history_unavailable).setMessage(errorMessage(error))
                            .setPositiveButton(R.string.instrument_retry, (d,w) -> {
                                if (handled[0]) return;
                                handled[0] = true;
                                history(cursor, previous);
                            })
                            .setNegativeButton(R.string.ws_close, null));
                });
            }
        });
    }

    private void systemSettings() {
        PermissionCenter.openNotificationSettings(activity);
    }

    private static boolean pendingRepair(PushStore.Device device) {
        return PushStore.PENDING.equals(device.state) && device.repair != PushStore.NO_REPAIR;
    }

    private static boolean offersDistributor(PushStore.Device device) {
        return device != null && device.unified() && (PushStore.ACTIVE.equals(device.state) || pendingRepair(device)
                || PushStore.FAILED.equals(device.state) && "ACTION_REQUIRED".equals(device.failure));
    }

    static boolean mayOpenDistributor(Context context, String profile, PushStore.Device displayed) {
        PushStore.Device enrolled = PushStore.get(context).forProfile(profile);
        return profile.equals(displayed.profile) && offersDistributor(enrolled)
                && enrolled.installation.equals(displayed.installation) && enrolled.distributor.equals(displayed.distributor)
                && Objects.equals(enrolled.signer, displayed.signer)
                && DistributorIdentity.matches(context, enrolled.distributor, enrolled.signer);
    }

    private void openDistributor(View button, PushStore.Device displayed) {
        if (!current() || dialog == null || !dialog.isShowing()
                || button.getRootView() != dialog.getWindow().getDecorView()) return;
        String message = activity.getString(R.string.push_settings_distributor_no_screen);
        try {
            Intent launch = activity.getPackageManager().getLaunchIntentForPackage(displayed.distributor);
            synchronized (PushConnection.class) {
                if (!mayOpenDistributor(activity, account.id, displayed)) {
                    message = activity.getString(R.string.push_settings_distributor_unauthorized);
                } else if (launch != null) {
                    activity.startActivity(launch);
                    return;
                }
            }
        } catch (ActivityNotFoundException | SecurityException gone) {
            Diagnostics.warning("push", "distributor_launcher_unavailable");
        }
        display(new AlertDialog.Builder(activity).setTitle(R.string.push_settings_distributor_unavailable)
                .setMessage(message)
                .setPositiveButton(R.string.push_settings_back, (d,w) -> show()));
    }

    private void begin() {
        PushNotices.channel(activity);
        if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            PermissionCenter.State state = PermissionCenter.state(activity, PermissionCenter.Key.NOTIFICATIONS);
            if (state == PermissionCenter.State.PERMANENTLY_DENIED) {
                showNotificationPermissionRecovery();
                return;
            }
            activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, PERMISSION);
        } else {
            proceedAfterPermissionCheck();
        }
    }

    void permissionResult(int[] grantResults) {
        if (!current()) return;
        if (grantResults != null && grantResults.length == 0) {
            return;
        }
        PermissionCenter.recordCompletedPrompt(activity, PermissionCenter.Key.NOTIFICATIONS, grantResults);
        proceedAfterPermissionCheck();
    }

    private void proceedAfterPermissionCheck() {
        if (!current()) return;
        if (!PushNotices.allowed(activity)) {
            showNotificationPermissionRecovery();
            return;
        }
        discoverTransport();
    }

    private void showNotificationPermissionRecovery() {
        PermissionCenter.State state = PermissionCenter.state(activity, PermissionCenter.Key.NOTIFICATIONS);
        boolean requestRuntime = shouldRequestRuntimePermissionInRecovery(Build.VERSION.SDK_INT, state);
        AlertDialog.Builder recovery = new AlertDialog.Builder(activity)
                .setTitle(R.string.push_settings_allow_title)
                .setMessage(R.string.push_settings_allow_body)
                .setNegativeButton(R.string.ws_close, null);
        if (requestRuntime) {
            recovery.setPositiveButton(R.string.perm_action_allow, (d, w) -> begin());
        } else {
            recovery.setPositiveButton(R.string.perm_action_open_notification_settings, null);
        }
        AlertDialog shown = display(recovery);
        if (shown != null) {
            if (!requestRuntime) {
                keepOpenOnPositiveClick(shown, this::systemSettings);
            }
            showingPermissionRecovery = true;
        }
    }

    private void discoverTransport() {
        load(activity.getString(R.string.push_settings_searching_services),
                () -> {
                    if (api == null || api.isRevoked()) throw new java.io.IOException("Push settings closed");
                    return api.get(PushConnection.BASE);
                }, this::chooseTransport);
    }

    private record DistributorChoice(String packageName, String label, String signer) {}

    private void chooseTransport(JSONObject discovery) {
        PushStore.Device existing = PushStore.get(activity).forProfile(account.id);
        List<String> labels = new ArrayList<>();
        List<Runnable> choices = new ArrayList<>();
        long epoch = PushConnection.version();
        if (PushConfig.offers(discovery, DistributorEnrollment.PROVIDER) && (existing == null || existing.unified())) {
            labels.add(activity.getString(R.string.push_settings_enable_up));
            choices.add(() -> chooseDistributor(discovery, epoch));
        }
        if (PushConfig.offers(discovery, "fcm") && (existing == null || !existing.unified())) {
            labels.add(activity.getString(R.string.push_settings_enable_fcm));
            choices.add(() -> run(activity.getString(R.string.push_settings_registering), () -> PushConnection.enroll(
                    activity.getApplicationContext(), account, api, PushConfig.parse(discovery), epoch, this::current)));
        }
        boolean[] handled = {false};
        AlertDialog.Builder picker = new AlertDialog.Builder(activity).setTitle(R.string.push_settings_service_title)
                .setNegativeButton(R.string.push_settings_back, (d,w) -> {
                    if (handled[0]) return;
                    handled[0] = true;
                    show();
                });
        if (choices.isEmpty()) picker.setMessage(R.string.push_settings_no_service);
        else picker.setItems(labels.toArray(new String[0]), (d,w) -> {
            if (handled[0]) return;
            handled[0] = true;
            choices.get(w).run();
        });
        display(picker);
    }

    private void chooseDistributor(JSONObject discovery, long epoch) {
        Context app = activity.getApplicationContext();
        load(activity.getString(R.string.push_settings_searching_services),
                () -> loadDistributorChoices(app),
                choices -> showDistributorPicker(discovery, epoch, choices));
    }

    private static List<DistributorChoice> loadDistributorChoices(Context app) {
        List<String> packages = DistributorEnrollment.choices(app);
        List<DistributorChoice> entries = new ArrayList<>(packages.size());
        PackageManager manager = app.getPackageManager();
        for (String name : packages) {
            String signer = DistributorIdentity.current(app, name);
            String label;
            try {
                label = manager.getApplicationLabel(manager.getApplicationInfo(name, 0)) + "\n" + name;
            } catch (PackageManager.NameNotFoundException gone) {
                label = name;
            }
            entries.add(new DistributorChoice(name, label, signer));
        }
        return entries;
    }

    private void showDistributorPicker(JSONObject discovery, long epoch, List<DistributorChoice> choices) {
        boolean[] handled = {false};
        AlertDialog.Builder picker = new AlertDialog.Builder(activity).setTitle(R.string.push_settings_choose_distributor)
                .setNegativeButton(R.string.push_settings_back, (d,w) -> {
                    if (handled[0]) return;
                    handled[0] = true;
                    show();
                });
        if (choices == null || choices.isEmpty()) {
            picker.setMessage(R.string.push_settings_install_up);
        } else {
            String[] labels = new String[choices.size()];
            for (int i = 0; i < choices.size(); i++) labels[i] = choices.get(i).label();
            picker.setItems(labels, (d,w) -> {
                if (handled[0]) return;
                handled[0] = true;
                DistributorChoice chosen = choices.get(w);
                run(activity.getString(R.string.push_settings_registering_distributor), () ->
                        DistributorEnrollment.enroll(activity.getApplicationContext(), account, api,
                                chosen.packageName(), chosen.signer(),
                                DistributorEnrollment.vapid(discovery), epoch, this::current));
            });
        }
        display(picker);
    }

    private void remove(PushStore.Device device) {
        MobilePush.forget(activity, account.id);
        run(activity.getString(R.string.push_settings_disabling), () ->
                PushConnection.remove(activity.getApplicationContext(), device, api));
    }

    private interface Call { void execute() throws Exception; }

    private void run(String progress, Call action) {
        load(progress, () -> { action.execute(); return null; }, result -> show());
    }

    private <T> void load(String progress, Callable<T> action, Consumer<T> after) {
        AlertDialog waiting = display(new AlertDialog.Builder(activity)
                .setTitle(R.string.push_settings_title).setMessage(progress));
        if (waiting == null) return;
        waiting.setCanceledOnTouchOutside(false);
        PushConnection.WORK.execute(() -> {
            try {
                if (!current()) return;
                T result = action.call();
                finishProgress(waiting, () -> after.accept(result));
            } catch (Exception error) {
                Diagnostics.event("push", "enrollment_action_failed");
                String message = errorMessage(error);
                finishProgress(waiting, () -> display(new AlertDialog.Builder(activity)
                        .setTitle(R.string.push_settings_title).setMessage(message)
                        .setPositiveButton(R.string.push_settings_back, (d,w) -> show())));
            }
        });
    }

    private String errorMessage(Exception error) {
        if (error instanceof PushConnection.DifferentProject) {
            return activity.getString(R.string.push_settings_other_project);
        }
        if (error instanceof PushConnection.MissingPlayServices) {
            return activity.getString(R.string.push_settings_play_required);
        }
        if (error instanceof HermesApi.HttpError http) {
            if (http.status == 404 || http.status == 503) return activity.getString(R.string.push_settings_relay_unavailable);
            if (http.status == 401 || http.status == 403) return activity.getString(R.string.push_settings_check_profile_key);
            if (http.status == 409) return activity.getString(R.string.push_settings_device_already_registered);
        }
        return activity.getString(R.string.push_settings_connection_failed);
    }
}
