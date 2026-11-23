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
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.nearby.NearbyFix;
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
        catch (RuntimeException unavailable) { remembered = false; failure = "Réglage de position indisponible"; }
        if (stagedFromPreference && !remembered) {
            staged = null;
            stagedFromPreference = false;
            generation++;
            failure = "Partage de position désactivé pour ce profil";
        }
    }

    boolean beforeSend(String text, Runnable exactSend) {
        if (busy) return false;
        if (skip) return true;
        if (staged == null || stagedFromPreference) refreshPreference();
        if (!failure.isEmpty()) { show(exactSend); return false; }
        if (staged != null) {
            if (frozen() != null && (!deviceStaged() || granted())) return true;
            staged = null;
            failure = "Position expirée ou autorisation retirée";
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

    void restore() {
        clearTurn();
        JSONObject saved = page.journal == null ? null : page.journal.nearbyDraft();
        if (saved == null) return;
        switch (saved.optString("mode")) {
            case "omit" -> skip = true;
            case "manual" -> staged = NearbyTurn.manual(saved.optString("area"));
            default -> failure = "Position à confirmer pour ce message";
        }
        refresh();
    }

    private boolean saveChoice(String mode, String area) {
        try { page.journal.nearbyDraft(mode, area); return true; }
        catch (RuntimeException unavailable) {
            failure = "Impossible d’enregistrer le choix de zone";
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
                if (!remembered) { failure = "Partage de position désactivé pour ce profil"; refresh(); return; }
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
        failure = "Recherche de position interrompue";
        refresh();
    }

    void refresh() {
        String status = busy ? "Recherche de la position…" : !failure.isEmpty() ? failure
                : deviceStaged() ? "Position approximative jointe" : staged != null ? "Quartier joint · " + staged.optString("area")
                : skip ? "Sans position pour ce message" : remembered ? "Position activée pour ce profil" : "";
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
        String disclosure = "Hermes reçoit la zone avec votre message. Pour un guide de restaurants, votre passerelle peut aussi transmettre la zone approximative au fournisseur de données OpenStreetMap configuré.";
        items.addView(Ui.text(page.activity, disclosure, 15));
        CheckBox remember = new CheckBox(page.activity);
        remember.setText("Utiliser ma position pour mes prochains messages sur ce profil");
        remember.setChecked(remembered);
        if (editable) {
            items.addView(remember);
            option(items, "Utiliser ma position approximative", ticket, () -> locateTurn(true, remember.isChecked(), null));
            option(items, "Saisir un quartier", ticket, () -> {
                if (!remembered || remember.isChecked() || preference(false)) manual();
            });
            option(items, sendWithout == null ? "Sans position pour ce message" : "Envoyer sans position", ticket, () -> {
                if (remembered && !remember.isChecked() && !preference(false)) return;
                omit(sendWithout);
            });
        }
        if (remembered) option(items, "Désactiver pour ce profil", ticket, () -> {
            if (preference(false) && (!editable || saveChoice("omit", null))) { clearTurn(); skip = editable; refresh(); }
        });
        if (!editable) items.addView(Ui.text(page.activity, "Le message en cours a déjà ses réglages.", 15));
        if (!failure.isEmpty()) items.addView(Ui.text(page.activity, failure, 15));
        ScrollView scroll = new ScrollView(page.activity);
        scroll.addView(items);
        page.screen.show(new AlertDialog.Builder(page.activity).setTitle("Ajouter une zone à ce message ?")
                .setView(scroll).setNegativeButton("Annuler", null));
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
            failure = "Impossible d’enregistrer ce choix sur le profil";
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
        input.setHint("Quartier et ville");
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(100)});
        LinearLayout box = Ui.column(page.activity);
        Ui.pad(box, 20, 8);
        box.addView(input);
        AlertDialog dialog = page.screen.show(new AlertDialog.Builder(page.activity).setTitle("Indiquer un quartier")
                .setView(box).setNegativeButton("Annuler", null).setPositiveButton("Utiliser ce quartier", null));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (generation != ticket) return;
            JSONObject area = NearbyTurn.manual(input.getText().toString());
            if (area == null) { input.setError("Indiquez un lieu en quelques mots, par exemple Rosemont, Montréal."); return; }
            if (!saveChoice("manual", area.optString("area"))) return;
            page.screen.dismiss(dialog);
            stage(area);
        });
    }

    void chooseGuide(BooleanSupplier current, Consumer<JSONObject> chosen) {
        if (!current.getAsBoolean() || page.screen == null) return;
        generation++;
        cancel();
        long ticket = generation;
        page.screen.show(new AlertDialog.Builder(page.activity).setTitle("Rechercher autour de moi ?")
                .setMessage("Votre passerelle et son fournisseur de données OpenStreetMap recevront votre position approximative pour rechercher des restaurants.")
                .setNegativeButton("Annuler", null).setPositiveButton("Utiliser ma position", (d,w) -> {
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
        if (permissions.length == 1 && PERMISSION.equals(permissions[0]) && results.length == 1
                && results[0] == PackageManager.PERMISSION_GRANTED && granted() && ready != null) ready.run();
        else clearFailure();
        return true;
    }

    private void clearFailure() {
        cancel();
        failure = "Position indisponible · choisir un quartier ou continuer sans position";
        refresh();
    }
}
