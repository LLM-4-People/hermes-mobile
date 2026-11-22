package org.llm4people.hermes.push;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.DataSetObserver;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;

import org.llm4people.hermes.Diagnostics;
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
        AlertDialog.Builder builder = new AlertDialog.Builder(activity).setTitle("Notifications du profil")
                .setNegativeButton("Fermer", null)
                .setNeutralButton("Historique", (d,w) -> history(0, new ArrayDeque<>()));
        if (device != null && PushStore.REMOVING.equals(device.state)) {
            builder.setMessage("Les alertes sont coupées sur cet appareil. La désactivation sur le serveur reste à confirmer.")
                    .setPositiveButton("Réessayer", (d,w) -> remove(device));
        } else if (device != null && PushStore.ACTIVE.equals(device.state)) {
            if (PushNotices.allowed(activity)) {
                builder.setMessage(device.unified()
                                ? "Le relais a enregistré ce profil. Ouvrez le distributeur pour vérifier sa connexion et terminer la configuration des alertes."
                                : "Les rappels et l’activité de vos sessions peuvent vous prévenir même après la fermeture de l’application.")
                        .setPositiveButton("Désactiver", (d,w) -> remove(device));
            } else {
                builder.setTitle("Alertes bloquées par Android")
                        .setMessage("Autorisez les notifications dans les réglages Android pour recevoir les alertes de ce profil.")
                        .setPositiveButton("Réglages Android", (d,w) -> systemSettings())
                        .setNegativeButton("Désactiver", (d,w) -> remove(device));
            }
        } else if (device != null) {
            builder.setTitle("Inscription à confirmer")
                    .setMessage(registrationMessage(device))
                    .setPositiveButton("Réessayer", (d,w) -> begin())
                    .setNegativeButton("Désactiver", (d,w) -> remove(device));
        } else {
            builder.setMessage("Recevez les rappels, les demandes d’autorisation et la fin des sessions. Le contenu de vos échanges reste privé.")
                    .setPositiveButton("Activer", (d,w) -> begin());
        }
        if (device != null && device.unified() && (PushStore.ACTIVE.equals(device.state)
                || "ACTION_REQUIRED".equals(device.failure))) {
            builder.setView(Ui.button(activity, "Ouvrir le distributeur", v -> openDistributor(device)));
        }
        if (display(builder) != null) observeState(device);
    }

    private static String registrationMessage(PushStore.Device device) {
        if (device.retryScheduled()) return "Hermes réessaiera automatiquement lorsque le réseau sera disponible.";
        if (!PushStore.FAILED.equals(device.state)) return "L’inscription de cet appareil auprès du serveur reste à confirmer.";
        return switch (device.failure == null ? "" : device.failure) {
            case "ACTION_REQUIRED" -> "Ouvrez le distributeur pour terminer sa configuration, puis réessayez.";
            case "VAPID_REQUIRED" -> "Le distributeur demande une clé VAPID. Vérifiez la configuration du relais, puis réessayez.";
            case PushStore.ENDPOINT_REJECTED -> "Le relais refuse cette adresse de notification. Vérifiez le serveur du distributeur, puis réessayez.";
            case PushStore.ACCOUNT_REQUIRED -> "Le relais demande de confirmer l’inscription avec ce profil. Vérifiez sa clé d’accès, puis réessayez.";
            default -> "Le distributeur n’a pas pu terminer l’inscription. Vérifiez sa configuration, puis réessayez.";
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
                            || !displayed.state.equals(updated.state)
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
        AlertDialog progress = display(new AlertDialog.Builder(activity).setTitle("Historique du profil").setMessage("Chargement…"));
        if (progress == null) return;
        Locale locale = activity.getResources().getConfiguration().getLocales().get(0);
        PushConnection.WORK.execute(() -> {
            try {
                if (!current()) return;
                PushHistory.Page page = PushHistory.load(account, cursor, locale);
                finishProgress(progress, () -> {
                    AlertDialog.Builder list = new AlertDialog.Builder(activity).setTitle("Historique du profil")
                            .setNegativeButton("Fermer", null);
                    if (page.labels.isEmpty()) list.setMessage("Aucune activité sur cette page.");
                    else list.setItems(page.labels.toArray(new String[0]), (d,index) -> {
                        if (current() && activity instanceof MobilePush.EventHost host) {
                            host.openPushEvent(account, page.events.get(index));
                        }
                    });
                    if (page.more) list.setPositiveButton("Suite", (d,w) -> {
                        previous.push(cursor);
                        history(page.next, previous);
                    });
                    if (!previous.isEmpty()) list.setNeutralButton("Précédent",
                            (d,w) -> history(previous.pop(), previous));
                    display(list);
                });
            } catch (Exception error) {
                Diagnostics.warning("push", "history_load_failed");
                finishProgress(progress, () -> display(new AlertDialog.Builder(activity)
                        .setTitle("Historique indisponible").setMessage(errorMessage(error))
                        .setPositiveButton("Réessayer", (d,w) -> history(cursor, previous))
                        .setNegativeButton("Fermer", null)));
            }
        });
    }

    private void systemSettings() {
        activity.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName()));
    }

    private void openDistributor(PushStore.Device device) {
        Intent launch = activity.getPackageManager().getLaunchIntentForPackage(device.distributor);
        if (launch != null) {
            try {
                activity.startActivity(launch);
                return;
            } catch (ActivityNotFoundException | SecurityException gone) {
                Diagnostics.warning("push", "distributor_launcher_unavailable");
            }
        }
        display(new AlertDialog.Builder(activity).setTitle("Distributeur indisponible")
                .setMessage("Vérifiez que le distributeur choisi est installé et activé sur ce téléphone.")
                .setPositiveButton("Retour", (d,w) -> show()));
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
                    .setMessage("Ouvrez les réglages Android pour autoriser les alertes d’Hermes.")
                    .setPositiveButton("Réglages Android", (d,w) -> systemSettings())
                    .setNegativeButton("Fermer", null));
            return;
        }
        load("Recherche des services de notification…",
                () -> new HermesApi(account.endpoint, account.bearer).get(PushConnection.BASE), this::chooseTransport);
    }

    private void chooseTransport(JSONObject discovery) {
        PushStore.Device existing = PushStore.get(activity).forProfile(account.id);
        List<String> labels = new ArrayList<>();
        List<Runnable> choices = new ArrayList<>();
        long epoch = PushConnection.version();
        if (PushConfig.offers(discovery, DistributorEnrollment.PROVIDER) && (existing == null || existing.unified())) {
            labels.add("Activer avec UnifiedPush");
            choices.add(() -> chooseDistributor(discovery, epoch));
        }
        if (PushConfig.offers(discovery, "fcm") && (existing == null || !existing.unified())) {
            labels.add("Activer avec Google Play");
            choices.add(() -> run("Inscription en cours…", () -> PushConnection.enroll(
                    activity.getApplicationContext(), account, PushConfig.parse(discovery), epoch, this::current)));
        }
        AlertDialog.Builder picker = new AlertDialog.Builder(activity).setTitle("Service de notification")
                .setNegativeButton("Retour", (d,w) -> show());
        if (choices.isEmpty()) picker.setMessage("Aucun service de notification disponible pour ce profil.");
        else picker.setItems(labels.toArray(new String[0]), (d,w) -> choices.get(w).run());
        display(picker);
    }

    private void chooseDistributor(JSONObject discovery, long epoch) {
        List<String> packages = DistributorEnrollment.choices(activity);
        AlertDialog.Builder picker = new AlertDialog.Builder(activity).setTitle("Choisir un distributeur")
                .setNegativeButton("Retour", (d,w) -> show());
        if (packages.isEmpty()) {
            picker.setMessage("Installez ou configurez un distributeur UnifiedPush, par exemple ntfy, puis réessayez.");
        } else {
            String[] labels = new String[packages.size()];
            for (int i = 0; i < labels.length; i++) {
                String name = packages.get(i);
                try {
                    var manager = activity.getPackageManager();
                    labels[i] = manager.getApplicationLabel(manager.getApplicationInfo(name, 0)) + "\n" + name;
                } catch (PackageManager.NameNotFoundException gone) { labels[i] = name; }
            }
            picker.setItems(labels, (d,w) -> run("Inscription au distributeur…", () ->
                    DistributorEnrollment.enroll(activity.getApplicationContext(), account, packages.get(w),
                            DistributorEnrollment.vapid(discovery), epoch, this::current)));
        }
        display(picker);
    }

    private void remove(PushStore.Device device) {
        MobilePush.forget(activity, account.id);
        run("Désactivation en cours…", () ->
                PushConnection.remove(activity.getApplicationContext(), device, account.bearer));
    }

    private interface Call { void execute() throws Exception; }

    private void run(String progress, Call action) {
        load(progress, () -> { action.execute(); return null; }, result -> show());
    }

    private <T> void load(String progress, Callable<T> action, Consumer<T> after) {
        AlertDialog waiting = display(new AlertDialog.Builder(activity).setTitle("Notifications du profil").setMessage(progress));
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
