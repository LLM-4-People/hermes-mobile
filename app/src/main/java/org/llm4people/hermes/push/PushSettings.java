package org.llm4people.hermes.push;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.database.DataSetObserver;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.net.HermesApi;
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
    private final DeliveryRouter.Actions deliveryActions;
    private AlertDialog dialog;
    private TimePickerDialog timePicker;
    private DeliveryRouter deliveryRouter;
    private volatile boolean closed;

    PushSettings(Activity activity, Account account) {
        this(activity, account, null);
    }

    PushSettings(Activity activity, Account account, DeliveryRouter.Actions deliveryActions) {
        this.activity = activity;
        this.account = account;
        this.deliveryActions = deliveryActions;
    }

    void close() {
        closed = true;
        if (timePicker != null) { timePicker.dismiss(); timePicker = null; }
        if (deliveryRouter != null) { deliveryRouter.dismiss(); deliveryRouter = null; }
        if (dialog != null) dialog.dismiss();
    }

    private boolean current() { return !closed && !activity.isFinishing() && !activity.isDestroyed(); }

    private AlertDialog display(AlertDialog.Builder builder) {
        if (!current()) return null;
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
        if (!current()) return;
        DistributorEnrollment.reconcile(activity);
        PushStore.Device device = PushStore.get(activity).forProfile(account.id);
        if (display(settingsDialog(device, PushNotices.allowed(activity))) != null) observeState(device);
    }

    AlertDialog.Builder settingsDialog(PushStore.Device device, boolean allowed) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity).setTitle(R.string.push_settings_title)
                .setNegativeButton(R.string.ws_close, null)
                .setNeutralButton(R.string.push_settings_history, (d,w) -> history(0, new ArrayDeque<>()));
        if (device != null && PushStore.REMOVING.equals(device.state)) {
            builder.setMessage(R.string.push_settings_removal_pending)
                    .setPositiveButton(R.string.instrument_retry, (d,w) -> remove(device));
        } else if (device != null && (PushStore.ACTIVE.equals(device.state) || pendingRepair(device))) {
            if (!allowed) {
                String android = activity.getString(R.string.push_settings_blocked_message);
                builder.setTitle(R.string.push_settings_blocked_title)
                        .setMessage(device.repair == PushStore.NO_REPAIR ? android : android + "\n\n" + activeMessage(device))
                        .setPositiveButton(R.string.push_settings_android_settings, (d,w) -> systemSettings())
                        .setNegativeButton(R.string.push_settings_disable, (d,w) -> remove(device));
            } else if (device.repair != PushStore.NO_REPAIR) {
                builder.setTitle(R.string.push_settings_distributor_connecting).setMessage(activeMessage(device))
                        .setNegativeButton(R.string.push_settings_disable, (d,w) -> remove(device));
                if (device.repair == PushStore.REPAIR_ACTION)
                    builder.setPositiveButton(R.string.instrument_retry, (d,w) -> retryRepair(device));
            } else {
                builder.setMessage(activeMessage(device)).setPositiveButton(R.string.push_settings_disable, (d,w) -> remove(device));
            }
        } else if (device != null) {
            builder.setTitle(R.string.push_settings_registration_confirm)
                    .setMessage(registrationMessage(device))
                    .setPositiveButton(R.string.instrument_retry, (d,w) -> begin())
                    .setNegativeButton(R.string.push_settings_disable, (d,w) -> remove(device));
        } else {
            builder.setMessage(R.string.push_settings_intro)
                    .setPositiveButton(R.string.push_settings_enable, (d,w) -> begin());
        }
        LinearLayout extras = null;
        if (offersDistributor(device)) {
            extras = Ui.column(activity);
            Ui.pad(extras, 16, 4);
            extras.addView(Ui.button(activity, activity.getString(R.string.push_settings_open_distributor),
                    v -> openDistributor(v, device)));
            if (!allowed && device.repair == PushStore.REPAIR_ACTION) {
                extras.addView(Ui.button(activity, activity.getString(R.string.instrument_retry),
                        v -> retryRepair(device)));
            }
        }
        if (account != null) {
            if (extras == null) {
                extras = Ui.column(activity);
                Ui.pad(extras, 16, 4);
            }
            PushPolicy currentPolicy = PushPolicy.load(activity, account.id);
            String quietLine = currentPolicy.quietHoursEnabled
                    ? activity.getString(R.string.push_policy_quiet_status_on,
                            PushPolicy.formatMinute(activity, currentPolicy.quietStartMinute),
                            PushPolicy.formatMinute(activity, currentPolicy.quietEndMinute))
                    : activity.getString(R.string.push_policy_quiet_status_off);
            TextView summary = Ui.text(activity, quietLine, 14);
            summary.setTextColor(activity.getColor(R.color.muted));
            Ui.pad(summary, 4, 6);
            extras.addView(summary);
            extras.addView(Ui.button(activity, activity.getString(R.string.push_policy_button),
                    v -> showPolicyDialog()));
            extras.addView(Ui.button(activity, activity.getString(R.string.push_inbox_button),
                    v -> openDeliveryInbox()));
        }
        if (extras != null) builder.setView(extras);
        return builder;
    }

    void showPolicyDialog() {
        if (!current() || account == null) return;
        PushPolicy[] state = {PushPolicy.load(activity, account.id)};
        LinearLayout content = Ui.column(activity);
        Ui.pad(content, 20, 12);

        TextView catHeading = Ui.text(activity, activity.getString(R.string.push_policy_categories_heading), 17);
        catHeading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(catHeading);
        TextView catHint = Ui.text(activity, activity.getString(R.string.push_policy_categories_hint), 13);
        catHint.setTextColor(activity.getColor(R.color.muted));
        Ui.pad(catHint, 0, 4);
        content.addView(catHint);

        for (DeliveryItem.Category category : DeliveryItem.Category.values()) {
            Switch toggle = new Switch(activity);
            toggle.setText(category.label(activity));
            toggle.setChecked(state[0].isCategoryEnabled(category));
            toggle.setMinHeight(Ui.dp(activity, 48));
            toggle.setOnCheckedChangeListener((btn, checked) -> {
                state[0] = state[0].withCategory(category, checked);
                PushPolicy.save(activity, account.id, state[0]);
            });
            content.addView(toggle);
            TextView sub = Ui.text(activity, category.hint(activity), 12);
            sub.setTextColor(activity.getColor(R.color.muted));
            Ui.pad(sub, 0, 2);
            content.addView(sub);
        }

        content.addView(Ui.divider(activity));
        TextView quietHeading = Ui.text(activity, activity.getString(R.string.push_policy_quiet_heading), 17);
        quietHeading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        Ui.pad(quietHeading, 0, 10);
        content.addView(quietHeading);

        Button startButton = Ui.button(activity, "", null);
        Button endButton = Ui.button(activity, "", null);
        Switch highToggle = new Switch(activity);

        Runnable refreshQuietViews = () -> {
            startButton.setText(activity.getString(R.string.push_policy_quiet_start,
                    PushPolicy.formatMinute(activity, state[0].quietStartMinute)));
            endButton.setText(activity.getString(R.string.push_policy_quiet_end,
                    PushPolicy.formatMinute(activity, state[0].quietEndMinute)));
            startButton.setEnabled(state[0].quietHoursEnabled);
            endButton.setEnabled(state[0].quietHoursEnabled);
            highToggle.setEnabled(state[0].quietHoursEnabled);
        };

        Switch quietToggle = new Switch(activity);
        quietToggle.setText(activity.getString(R.string.push_policy_quiet_toggle));
        quietToggle.setChecked(state[0].quietHoursEnabled);
        quietToggle.setMinHeight(Ui.dp(activity, 48));
        quietToggle.setOnCheckedChangeListener((btn, checked) -> {
            state[0] = state[0].withQuietHours(checked, state[0].quietStartMinute,
                    state[0].quietEndMinute, state[0].allowHighUrgencyDuringQuietHours);
            PushPolicy.save(activity, account.id, state[0]);
            refreshQuietViews.run();
        });
        content.addView(quietToggle);

        TextView quietHint = Ui.text(activity, activity.getString(R.string.push_policy_quiet_hint), 12);
        quietHint.setTextColor(activity.getColor(R.color.muted));
        content.addView(quietHint);

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
        content.addView(startButton);
        content.addView(endButton);

        highToggle.setText(activity.getString(R.string.push_policy_quiet_high_toggle));
        highToggle.setChecked(state[0].allowHighUrgencyDuringQuietHours);
        highToggle.setMinHeight(Ui.dp(activity, 48));
        highToggle.setOnCheckedChangeListener((btn, checked) -> {
            state[0] = state[0].withQuietHours(state[0].quietHoursEnabled, state[0].quietStartMinute,
                    state[0].quietEndMinute, checked);
            PushPolicy.save(activity, account.id, state[0]);
        });
        content.addView(highToggle);

        TextView highHint = Ui.text(activity, activity.getString(R.string.push_policy_quiet_high_hint), 12);
        highHint.setTextColor(activity.getColor(R.color.muted));
        content.addView(highHint);

        refreshQuietViews.run();

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);
        display(new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.push_policy_title))
                .setView(scroll)
                .setNegativeButton(R.string.push_settings_back, (d, w) -> show())
                .setPositiveButton(R.string.ws_close, null));
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
        if (dialog != null) dialog.dismiss();
        HermesApi api = new HermesApi(account.endpoint, account.bearer);
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
                showPolicyDialog();
            }
        };
        deliveryRouter = new DeliveryRouter(activity, account, api, new DeliveryInbox(), wired);
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
                        if (displayed != updated) show();
                    } else if (!displayed.installation.equals(updated.installation)
                            || !displayed.state.equals(updated.state) || displayed.repair != updated.repair
                            || !Objects.equals(displayed.failure, updated.failure)
                            || displayed.retryScheduled() != updated.retryScheduled()) show();
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
        Locale locale = activity.getResources().getConfiguration().getLocales().get(0);
        PushConnection.WORK.execute(() -> {
            try {
                if (!current()) return;
                PushHistory.Page page = PushHistory.load(activity, account, cursor, locale);
                finishProgress(progress, () -> {
                    AlertDialog.Builder list = new AlertDialog.Builder(activity)
                            .setTitle(R.string.push_settings_history_title)
                            .setNegativeButton(R.string.ws_close, null);
                    if (page.labels.isEmpty()) list.setMessage(R.string.push_settings_history_empty);
                    else list.setItems(page.labels.toArray(new String[0]), (d,index) -> {
                        if (current() && activity instanceof MobilePush.EventHost host) {
                            host.openPushEvent(account, page.events.get(index));
                        }
                    });
                    if (page.more) list.setPositiveButton(R.string.push_settings_next, (d,w) -> {
                        previous.push(cursor);
                        history(page.next, previous);
                    });
                    if (!previous.isEmpty()) list.setNeutralButton(R.string.push_settings_previous,
                            (d,w) -> history(previous.pop(), previous));
                    display(list);
                });
            } catch (Exception error) {
                Diagnostics.warning("push", "history_load_failed");
                finishProgress(progress, () -> display(new AlertDialog.Builder(activity)
                        .setTitle(R.string.push_settings_history_unavailable).setMessage(errorMessage(error))
                        .setPositiveButton(R.string.instrument_retry, (d,w) -> history(cursor, previous))
                        .setNegativeButton(R.string.ws_close, null)));
            }
        });
    }

    private void systemSettings() {
        activity.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName()));
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
            activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, PERMISSION);
        } else permissionResult();
    }

    void permissionResult() {
        if (!current()) return;
        if (!PushNotices.allowed(activity)) {
            display(new AlertDialog.Builder(activity).setTitle(R.string.push_settings_allow_title)
                    .setMessage(R.string.push_settings_allow_body)
                    .setPositiveButton(R.string.push_settings_android_settings, (d,w) -> systemSettings())
                    .setNegativeButton(R.string.ws_close, null));
            return;
        }
        load(activity.getString(R.string.push_settings_searching_services),
                () -> new HermesApi(account.endpoint, account.bearer).get(PushConnection.BASE), this::chooseTransport);
    }

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
                    activity.getApplicationContext(), account, PushConfig.parse(discovery), epoch, this::current)));
        }
        AlertDialog.Builder picker = new AlertDialog.Builder(activity).setTitle(R.string.push_settings_service_title)
                .setNegativeButton(R.string.push_settings_back, (d,w) -> show());
        if (choices.isEmpty()) picker.setMessage(R.string.push_settings_no_service);
        else picker.setItems(labels.toArray(new String[0]), (d,w) -> choices.get(w).run());
        display(picker);
    }

    private void chooseDistributor(JSONObject discovery, long epoch) {
        List<String> packages = DistributorEnrollment.choices(activity);
        AlertDialog.Builder picker = new AlertDialog.Builder(activity).setTitle(R.string.push_settings_choose_distributor)
                .setNegativeButton(R.string.push_settings_back, (d,w) -> show());
        if (packages.isEmpty()) {
            picker.setMessage(R.string.push_settings_install_up);
        } else {
            String[] labels = new String[packages.size()];
            String[] signers = new String[packages.size()];
            for (int i = 0; i < labels.length; i++) {
                String name = packages.get(i);
                signers[i] = DistributorIdentity.current(activity, name);
                try {
                    var manager = activity.getPackageManager();
                    labels[i] = manager.getApplicationLabel(manager.getApplicationInfo(name, 0)) + "\n" + name;
                } catch (PackageManager.NameNotFoundException gone) { labels[i] = name; }
            }
            picker.setItems(labels, (d,w) -> run(activity.getString(R.string.push_settings_registering_distributor), () ->
                    DistributorEnrollment.enroll(activity.getApplicationContext(), account, packages.get(w), signers[w],
                            DistributorEnrollment.vapid(discovery), epoch, this::current)));
        }
        display(picker);
    }

    private void remove(PushStore.Device device) {
        MobilePush.forget(activity, account.id);
        run(activity.getString(R.string.push_settings_disabling), () ->
                PushConnection.remove(activity.getApplicationContext(), device, account.bearer));
    }

    private interface Call { void execute() throws Exception; }

    private void run(String progress, Call action) {
        load(progress, () -> { action.execute(); return null; }, result -> show());
    }

    private <T> void load(String progress, Callable<T> action, Consumer<T> after) {
        AlertDialog waiting = display(new AlertDialog.Builder(activity)
                .setTitle(R.string.push_settings_title).setMessage(progress));
        if (waiting == null) return;
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
