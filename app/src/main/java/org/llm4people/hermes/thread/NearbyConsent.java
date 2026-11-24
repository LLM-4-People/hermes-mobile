package org.llm4people.hermes.thread;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.text.InputFilter;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.json.JSONObject;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.nearby.NearbyFix;
import org.llm4people.hermes.os.PermissionCenter;
import org.llm4people.hermes.places.NearbyCategory;
import org.llm4people.hermes.profile.Account;

/** Foreground consent belongs to the open protected discussion. No device location is read on render. */
final class NearbyConsent {
    private static final String PREF = "nearby-future-sends-v1";
    private static final String PERMISSION = Manifest.permission.ACCESS_COARSE_LOCATION;
    private final ThreadWorkspace page;
    private final Account account;
    private long generation;
    private long stagedAt;
    private JSONObject staged;
    private boolean skip, remembered, busy, stagedFromPreference;
    private String failure = "";
    private Runnable cancelFix;
    private Runnable onGrant;
    private int permissionTicket = -1;

    NearbyConsent(ThreadWorkspace page, Account account) {
        this.page = page;
        this.account = account;
        refreshPreference();
    }

    private String owner() { return account.selectionBinding(PREF, "profile"); }

    private void refreshPreference() {
        try { remembered = owner().equals(account.read(PREF)); }
        catch (RuntimeException unavailable) {
            remembered = false;
            failure = page.activity.getString(R.string.nearby_setting_unavailable);
        }
        if (stagedFromPreference && !remembered) {
            staged = null;
            stagedFromPreference = false;
            generation++;
            failure = page.activity.getString(R.string.nearby_disabled_profile);
        }
    }

    boolean beforeSend(String text, Runnable exactSend) {
        if (busy) return false;
        if (skip) return true;
        if (!failure.isEmpty() && granted()) {
            failure = "";
            refresh();
        }
        if (staged == null || stagedFromPreference) refreshPreference();
        if (!failure.isEmpty()) { show(exactSend); return false; }
        if (staged != null) {
            if (frozen() != null && (!deviceStaged() || granted())) return true;
            staged = null;
            failure = page.activity.getString(R.string.nearby_expired_or_revoked);
        }
        if (remembered && failure.isEmpty() && granted()) {
            locateTurn(false, remembered, exactSend);
            return false;
        }
        if (remembered || !failure.isEmpty() || NearbyTurn.suggests(text)) {
            show(exactSend);
            return false;
        }
        return true;
    }

    long version() { return generation; }
    JSONObject frozen() { return NearbyTurn.fresh(staged, stagedAt, SystemClock.elapsedRealtime()); }
    boolean stillCurrent(long version, JSONObject snapshot) {
        if (stagedFromPreference) refreshPreference();
        return version == generation && (staged == null || snapshot != null && (!deviceStaged() || granted()));
    }
    private boolean deviceStaged() { return staged != null && "device".equals(staged.optString("source")); }
    boolean granted() { return page.activity.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED; }

    /** Reconciles stale blocked-location failures after the user grants location permission in Android settings. */
    void reconcilePermissionState() {
        if (granted() && !failure.isEmpty()) {
            failure = "";
            refresh();
        }
    }

    void restore() {
        clearTurn();
        JSONObject saved = page.journal == null ? null : page.journal.nearbyDraft();
        if (saved == null) return;
        switch (saved.optString("mode")) {
            case "omit" -> skip = true;
            case "manual" -> staged = NearbyTurn.manual(saved.optString("area"));
            default -> failure = page.activity.getString(R.string.nearby_confirm_message);
        }
        refresh();
    }

    private boolean saveChoice(String mode, String area) {
        try { page.journal.nearbyDraft(mode, area); return true; }
        catch (RuntimeException unavailable) {
            failure = page.activity.getString(R.string.nearby_save_choice_failed);
            refresh();
            return false;
        }
    }

    boolean prepareDeviceChoice(boolean remember) {
        // A replacement retires the old choice even when permission, storage, or acquisition fails.
        staged = null;
        stagedFromPreference = false;
        skip = false;
        if (!remember && remembered && !preference(false)) return false;
        return saveChoice("confirm", null);
    }

    private void locateTurn(boolean allowPermission, boolean remember, Runnable exactSend) {
        if (!prepareDeviceChoice(remember)) return;
        locate(allowPermission, () -> true, area -> {
            if (!allowPermission) {
                refreshPreference();
                if (!remembered) {
                    failure = page.activity.getString(R.string.nearby_disabled_profile);
                    refresh();
                    return;
                }
            } else if (!preference(remember)) return;
            stage(area);
            stagedFromPreference = !allowPermission;
            if (exactSend != null && page.activity.hasWindowFocus()) exactSend.run();
        });
    }

    void clearTurn() {
        generation++;
        cancel();
        staged = null;
        stagedFromPreference = false;
        skip = false;
        failure = "";
        refresh();
    }

    private void cancel() {
        permissionTicket = -1;
        onGrant = null;
        if (cancelFix != null) cancelFix.run();
        cancelFix = null;
        busy = false;
    }

    void pauseFix() {
        if (cancelFix == null) return;
        generation++;
        cancel();
        failure = page.activity.getString(R.string.nearby_lookup_interrupted);
        refresh();
    }

    void refresh() {
        String status = busy ? page.activity.getString(R.string.nearby_locating) : !failure.isEmpty() ? failure
                : deviceStaged() ? page.activity.getString(R.string.nearby_approx_attached)
                : staged != null ? page.activity.getString(R.string.nearby_neighborhood_attached, staged.optString("area"))
                : skip ? page.activity.getString(R.string.nearby_none_for_message)
                : remembered ? page.activity.getString(R.string.nearby_enabled_profile) : "";
        if (page.screen != null) page.screen.nearbyStatus(status, () -> show(null));
    }

    void show(Runnable sendWithout) {
        if (page.journal == null || page.screen == null) return;
        refreshPreference();
        generation++;
        cancel();
        refresh();
        long ticket = generation;
        boolean editable = page.journal.pending() == null && !page.admitting();
        LinearLayout items = Ui.column(page.activity);
        Ui.pad(items, 20, 6);
        String disclosure = page.activity.getString(R.string.nearby_consent_explain);
        items.addView(Ui.text(page.activity, disclosure, 15));
        CheckBox remember = new CheckBox(page.activity);
        remember.setText(R.string.nearby_always_profile);
        remember.setTextColor(page.activity.getColor(R.color.ink));
        int touchMin = Ui.dp(page.activity, 48);
        remember.setMinHeight(touchMin);
        remember.setMinimumHeight(touchMin);
        remember.setChecked(remembered);
        if (editable) {
            items.addView(remember);
            option(items, page.activity.getString(R.string.nearby_use_approx), ticket,
                    () -> locateTurn(true, remember.isChecked(), null));
            if (!granted() && PermissionCenter.state(page.activity, PermissionCenter.Key.LOCATION)
                    == PermissionCenter.State.PERMANENTLY_DENIED) {
                option(items, page.activity.getString(R.string.perm_action_open_settings), ticket,
                        () -> PermissionCenter.openAppSettings(page.activity));
            }
            option(items, page.activity.getString(R.string.nearby_enter_neighborhood), ticket, () -> {
                if (!remembered || remember.isChecked() || preference(false)) manual();
            });
            option(items, page.activity.getString(sendWithout == null
                    ? R.string.nearby_none_for_message : R.string.nearby_send_without), ticket, () -> {
                if (remembered && !remember.isChecked() && !preference(false)) return;
                omit(sendWithout);
            });
        }
        if (remembered) option(items, page.activity.getString(R.string.nearby_disable_profile), ticket, () -> {
            if (preference(false) && (!editable || saveChoice("omit", null))) { clearTurn(); skip = editable; refresh(); }
        });
        if (!editable) items.addView(Ui.text(page.activity, page.activity.getString(R.string.nearby_locked_in_flight), 15));
        if (!failure.isEmpty()) items.addView(Ui.text(page.activity, failure, 15));
        ScrollView scroll = new ScrollView(page.activity);
        scroll.addView(items);
        page.screen.show(new AlertDialog.Builder(page.activity).setTitle(R.string.nearby_add_area_title)
                .setView(scroll).setNegativeButton(R.string.ws_cancel, null));
    }

    void omit(Runnable sendWithout) {
        if (!saveChoice("omit", null)) return;
        clearTurn();
        skip = true;
        refresh();
        if (sendWithout != null) sendWithout.run();
    }

    private boolean preference(boolean enable) {
        if (enable == remembered) return true;
        try {
            if (enable) account.write(PREF, owner()); else account.remove(PREF);
            remembered = enable;
            return true;
        } catch (RuntimeException unavailable) {
            failure = page.activity.getString(R.string.nearby_save_profile_failed);
            refresh();
            return false;
        }
    }

    private void option(LinearLayout items, String label, long ticket, Runnable action) {
        Button button = Ui.button(page.activity, label, v -> {
            if (ticket != generation) return;
            page.screen.dismiss();
            action.run();
        });
        button.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        items.addView(button);
    }

    private void manual() {
        long ticket = generation;
        EditText input = new EditText(page.activity);
        input.setSingleLine();
        input.setSaveEnabled(false);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setHint(R.string.nearby_neighborhood_city_hint);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(100)});
        LinearLayout box = Ui.column(page.activity);
        Ui.pad(box, 20, 8);
        box.addView(input);
        AlertDialog dialog = page.screen.show(new AlertDialog.Builder(page.activity)
                .setTitle(R.string.nearby_specify_neighborhood_title)
                .setView(box).setNegativeButton(R.string.ws_cancel, null)
                .setPositiveButton(R.string.nearby_use_neighborhood, null));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (generation != ticket) return;
            JSONObject area = NearbyTurn.manual(input.getText().toString());
            if (area == null) { input.setError(page.activity.getString(R.string.nearby_neighborhood_example)); return; }
            if (!saveChoice("manual", area.optString("area"))) return;
            page.screen.dismiss(dialog);
            stage(area);
        });
    }

    void chooseGuide(BooleanSupplier current, Consumer<JSONObject> chosen) {
        chooseGuide(current, NearbyCategory.RESTAURANT, chosen);
    }

    void chooseGuide(BooleanSupplier current, NearbyCategory category, Consumer<JSONObject> chosen) {
        if (!current.getAsBoolean() || page.screen == null) return;
        NearbyCategory activeCat = category == null ? NearbyCategory.RESTAURANT : category;
        generation++;
        cancel();
        long ticket = generation;
        page.screen.show(new AlertDialog.Builder(page.activity).setTitle(R.string.nearby_around_me_title)
                .setMessage(page.activity.getString(R.string.nearby_around_me_body, activeCat.draftPlural(page.activity)))
                .setNegativeButton(R.string.ws_cancel, null)
                .setPositiveButton(R.string.nearby_use_location, (d,w) -> {
                    if (generation == ticket && current.getAsBoolean()) locate(true, current, chosen);
                }));
    }

    private void stage(JSONObject area) {
        generation++;
        stagedAt = SystemClock.elapsedRealtime();
        stagedFromPreference = false;
        staged = area;
        skip = false;
        failure = "";
        refresh();
    }

    private void locate(boolean allowPermission, BooleanSupplier current, Consumer<JSONObject> selected) {
        long ticket = ++generation;
        cancel();
        busy = true;
        failure = "";
        refresh();
        Runnable start = () -> {
            if (ticket != generation || !current.getAsBoolean()) { if (ticket == generation) clearFailure(); return; }
            cancelFix = NearbyFix.request(page.activity, fix -> {
                if (ticket != generation) return;
                cancelFix = null;
                busy = false;
                if (!current.getAsBoolean()) { refresh(); return; }
                if (!granted() || !NearbyTurn.validDevice(fix)) { clearFailure(); return; }
                ThreadData.put(fix, "source", "device");
                refresh();
                selected.accept(fix);
            });
        };
        if (granted()) start.run();
        else if (allowPermission) {
            if (PermissionCenter.state(page.activity, PermissionCenter.Key.LOCATION)
                    == PermissionCenter.State.PERMANENTLY_DENIED) {
                showBlockedRecovery();
                return;
            }
            try {
                permissionTicket = nextPermission();
                onGrant = start;
                page.activity.requestPermissions(new String[]{PERMISSION}, permissionTicket);
            } catch (RuntimeException unavailable) { clearFailure(); }
        } else clearFailure();
    }

    private int nextPermission() {
        // An old Activity or profile can still receive a delayed result. Allocate a non-private, persistent ticket.
        SharedPreferences values = page.activity.getSharedPreferences("nearby-permission", Context.MODE_PRIVATE);
        synchronized (values) {
            int next = values.getInt("next", 8000);
            if (next >= 19000 || !values.edit().putInt("next", next + 1).commit()) throw new IllegalStateException();
            return next;
        }
    }

    boolean permissionResult(int code, String[] permissions, int[] results) {
        if (code < 8000 || code >= 19000) return false;
        if (code != permissionTicket) return false;
        Runnable ready = onGrant;
        permissionTicket = -1;
        onGrant = null;
        PermissionCenter.recordCompletedPrompt(page.activity, PermissionCenter.Key.LOCATION, results);
        if (permissions != null && results != null
                && permissions.length == 1 && PERMISSION.equals(permissions[0]) && results.length == 1
                && results[0] == PackageManager.PERMISSION_GRANTED && granted() && ready != null) {
            ready.run();
        } else if (PermissionCenter.state(page.activity, PermissionCenter.Key.LOCATION)
                == PermissionCenter.State.PERMANENTLY_DENIED) {
            showBlockedRecovery();
        } else {
            clearFailure();
        }
        return true;
    }

    private void showBlockedRecovery() {
        cancel();
        failure = page.activity.getString(R.string.perm_location_blocked);
        refresh();
        if (page.screen != null) {
            page.screen.show(new AlertDialog.Builder(page.activity)
                    .setTitle(R.string.nearby_add_area_title)
                    .setMessage(R.string.perm_location_blocked)
                    .setPositiveButton(R.string.perm_action_open_settings,
                            (d, w) -> PermissionCenter.openAppSettings(page.activity))
                    .setNegativeButton(R.string.ws_cancel, null));
        }
    }

    private void clearFailure() {
        cancel();
        failure = page.activity.getString(R.string.nearby_unavailable_hint);
        refresh();
    }
}
