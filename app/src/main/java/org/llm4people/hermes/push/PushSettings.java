package org.llm4people.hermes.push;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;

import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.profile.Account;

import java.util.ArrayDeque;
import java.util.Locale;

final class PushSettings {
    static final int PERMISSION = 6801;
    private final Activity activity;
    private final Account account;
    private AlertDialog dialog;
    private volatile boolean closed;

    PushSettings(Activity activity, Account account) {
        this.activity = activity;
        this.account = account;
    }

    void close() {
        closed = true;
        if (dialog != null) dialog.dismiss();
    }

    private boolean current() { return !closed && !activity.isFinishing() && !activity.isDestroyed(); }

    private void display(AlertDialog.Builder builder) {
        if (!current()) return;
        if (dialog != null) dialog.dismiss();
        dialog = builder.show();
    }

    void show() {
        if (!current()) return;
        PushStore.Device device = PushStore.get(activity).forProfile(account.id);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity).setTitle("Notifications du profil")
                .setNegativeButton("Fermer", null);
        if (device != null && PushStore.REMOVING.equals(device.state)) {
            builder.setMessage("Les alertes sont coupées sur cet appareil. La désactivation sur le serveur reste à confirmer.")
                    .setPositiveButton("Réessayer", (d,w) -> remove(device));
        } else if (device != null && PushStore.ACTIVE.equals(device.state)) {
            if (PushNotices.allowed(activity)) {
                builder.setMessage("Les rappels et l’activité de vos sessions peuvent vous prévenir même après la fermeture de l’application.")
                        .setNeutralButton("Historique", (d,w) -> history(device, 0, new ArrayDeque<>()));
            } else {
                builder.setTitle("Alertes bloquées par Android")
                        .setItems(new String[]{"Historique du profil", "Réglages Android"}, (d,w) -> {
                            if (w == 0) history(device, 0, new ArrayDeque<>());
                            else systemSettings();
                        });
            }
            builder.setPositiveButton("Désactiver", (d,w) -> remove(device));
        } else {
            builder.setMessage(device == null
                    ? "Recevez les rappels, les demandes d’autorisation et la fin des sessions. Les alertes passent par Google, sans le contenu de vos échanges."
                    : "L’inscription de cet appareil auprès du serveur reste à confirmer.")
                    .setPositiveButton(device == null ? "Activer" : "Réessayer", (d,w) -> begin());
            if (device != null) builder.setNeutralButton("Désactiver", (d,w) -> remove(device));
        }
        display(builder);
    }

    private void history(PushStore.Device device, long cursor, ArrayDeque<Long> previous) {
        display(new AlertDialog.Builder(activity).setTitle("Historique du profil").setMessage("Chargement…"));
        Locale locale = activity.getResources().getConfiguration().getLocales().get(0);
        PushConnection.WORK.execute(() -> {
            try {
                PushHistory.Page page = PushHistory.load(account, device, cursor, locale);
                activity.runOnUiThread(() -> {
                    if (!current()) return;
                    AlertDialog.Builder list = new AlertDialog.Builder(activity).setTitle("Historique du profil")
                            .setNegativeButton("Fermer", null);
                    if (page.labels.isEmpty()) list.setMessage("Aucune activité sur cette page.");
                    else list.setItems(page.labels.toArray(new String[0]), (d,index) -> {
                        String id = page.ids.get(index);
                        Context context = activity.getApplicationContext();
                        PushStore.get(context).remember(device.installation, id, page.kinds.get(index));
                        Intent intent = PushNotices.tapIntent(context, device.installation, id);
                        if (MobilePush.target(context, intent) != null) activity.startActivity(intent);
                    });
                    if (page.more) list.setPositiveButton("Suite", (d,w) -> {
                        previous.push(cursor);
                        history(device, page.next, previous);
                    });
                    if (!previous.isEmpty()) list.setNeutralButton("Précédent",
                            (d,w) -> history(device, previous.pop(), previous));
                    display(list);
                });
            } catch (Exception error) {
                Diagnostics.warning("push", "history_load_failed");
                activity.runOnUiThread(() -> display(new AlertDialog.Builder(activity)
                        .setTitle("Historique indisponible").setMessage(errorMessage(error))
                        .setPositiveButton("Réessayer", (d,w) -> history(device, cursor, previous))
                        .setNegativeButton("Fermer", null)));
            }
        });
    }

    private void systemSettings() {
        activity.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName()));
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
            display(new AlertDialog.Builder(activity).setTitle("Autoriser les notifications")
                    .setMessage("Ouvrez les réglages Android pour autoriser les alertes d’Hermès.")
                    .setPositiveButton("Réglages Android", (d,w) -> systemSettings())
                    .setNegativeButton("Fermer", null));
            return;
        }
        long before = PushConnection.version();
        run("Inscription en cours…", () ->
                PushConnection.enroll(activity.getApplicationContext(), account, before, this::current));
    }

    private void remove(PushStore.Device device) {
        MobilePush.forget(activity, account.id);
        run("Désactivation en cours…", () ->
                PushConnection.remove(activity.getApplicationContext(), device, account.bearer));
    }

    private interface Call { void execute() throws Exception; }

    private void run(String progress, Call action) {
        display(new AlertDialog.Builder(activity).setTitle("Notifications du profil").setMessage(progress));
        PushConnection.WORK.execute(() -> {
            try {
                action.execute();
                activity.runOnUiThread(this::show);
            } catch (Exception error) {
                Diagnostics.event("push", "enrollment_action_failed");
                String message = errorMessage(error);
                activity.runOnUiThread(() -> display(new AlertDialog.Builder(activity)
                        .setTitle("Notifications du profil").setMessage(message)
                        .setPositiveButton("Retour", (d,w) -> show())));
            }
        });
    }

    private static String errorMessage(Exception error) {
        if (error instanceof PushConnection.DifferentProject) {
            return "Un autre projet de notifications est déjà utilisé. Désactivez ses profils avant d’en changer.";
        }
        if (error instanceof PushConnection.MissingPlayServices) {
            return "Les services Google Play doivent être installés et à jour pour recevoir ces alertes.";
        }
        if (error instanceof HermesApi.HttpError http) {
            if (http.status == 404 || http.status == 503) return "Le relais de notifications est indisponible sur ce serveur.";
            if (http.status == 401 || http.status == 403) return "Vérifiez la clé d’accès de ce profil.";
            if (http.status == 409) return "Cet appareil est déjà inscrit. Vérifiez ses inscriptions sur le serveur.";
        }
        return "La connexion a échoué. Vérifiez le réseau et la configuration du relais, puis réessayez.";
    }
}
