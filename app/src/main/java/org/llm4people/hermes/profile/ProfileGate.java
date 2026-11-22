package org.llm4people.hermes.profile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Local authentication flow. Its owner must discard gateway clients and account references on lock. */
public final class ProfileGate {
    private static final int TEXT = InputType.TYPE_CLASS_TEXT;
    private static final int SECRET = TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
    private static final int MIN_PASSPHRASE = 12;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private final Activity activity;
    private final AccountStore store;
    private Consumer<Account> onOpen;
    private Consumer<String> onForget = id -> {};
    private Account active;
    private AlertDialog confirmation;
    private int generation;
    private boolean nested;
    private boolean busy;

    public ProfileGate(Activity activity) {
        this.activity = activity;
        this.store = new AccountStore(activity);
    }

    /** Membership check available to notification receivers while profile data is locked. */
    public static boolean contains(Context context, String id) {
        return new AccountStore(context).contains(id);
    }

    public void setOnForget(Consumer<String> callback) {
        onForget = Objects.requireNonNull(callback);
    }

    public void start(Consumer<Account> callback) {
        start(null, callback);
    }

    public void start(String requestedId, Consumer<Account> callback) {
        onOpen = Objects.requireNonNull(callback);
        invalidate();
        Map<String, String> profiles = store.profiles();
        if (requestedId != null && profiles.containsKey(requestedId)) {
            unlock(requestedId, profiles.get(requestedId));
        } else if (requestedId != null) {
            chooser("Ce profil n’est plus enregistré sur cet appareil.");
        } else if (profiles.isEmpty()) {
            add(false);
        } else {
            chooser(null);
        }
    }

    public void lock() {
        invalidate();
        if (onOpen != null && !activity.isDestroyed() && !activity.isFinishing()) {
            if (store.profiles().isEmpty()) add(false);
            else chooser(null);
        }
    }

    /** Returns true only when handling navigation within the gate. */
    public boolean onBack() {
        if (!nested || active != null) return false;
        if (busy) return true;
        chooser(null);
        return true;
    }

    private void invalidate() {
        generation++;
        busy = false;
        nested = false;
        if (confirmation != null) {
            confirmation.dismiss();
            confirmation = null;
        }
        if (active != null) {
            active.close();
            active = null;
        }
    }

    private void chooser(String message) {
        generation++;
        nested = false;
        ProfileScreen screen = new ProfileScreen(activity, "Choisir un profil", null);
        store.profiles().forEach((id, label) -> screen.action(label, false, view -> unlock(id, label)));
        screen.action("Ajouter un profil", true, view -> add(true));
        if (message != null) screen.status(message, false);
    }

    private void add(boolean canGoBack) {
        generation++;
        nested = canGoBack;
        ProfileScreen screen = new ProfileScreen(activity, "Connecter Hermes", null);
        EditText name = screen.field("Nom du profil", "Ex. Personnel", TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, 80, false);
        EditText server = screen.field("Adresse du serveur", "https://hermes.exemple.fr", TEXT | InputType.TYPE_TEXT_VARIATION_URI, 2048, false);
        EditText key = screen.field("Clé d’accès Hermes", "", SECRET, 8192, true);
        screen.section(activity.getString(R.string.profile_lock_section), activity.getString(R.string.profile_lock_detail));
        EditText phrase = screen.field("Phrase secrète du profil",
                activity.getResources().getQuantityString(R.plurals.profile_passphrase_hint, MIN_PASSPHRASE, MIN_PASSPHRASE),
                SECRET, 512, true);
        EditText repeat = screen.field("Confirmer la phrase secrète", "", SECRET, 512, true);
        screen.action("Enregistrer le profil", true, view -> {
            if (busy) return;
            String label = name.getText().toString().trim();
            if (label.isEmpty() || hasControl(label)) {
                invalid(name, "Saisissez un nom de profil."); return;
            }
            if (store.profiles().values().stream().anyMatch(label::equalsIgnoreCase)) {
                invalid(name, "Ce nom est déjà utilisé sur cet appareil."); return;
            }
            String endpoint;
            try {
                endpoint = GatewayAddress.normalize(server.getText().toString());
            } catch (IllegalArgumentException ex) {
                invalid(server, "Saisissez l’adresse HTTPS du serveur, avec son préfixe de profil si nécessaire."); return;
            }
            String bearer = checkedBearer(key);
            if (bearer == null) return;
            char[] pass = password(phrase);
            char[] check = password(repeat);
            boolean matches = Arrays.equals(pass, check);
            Arrays.fill(check, '\0');
            if (pass.length < MIN_PASSPHRASE || !matches) {
                boolean shortPhrase = pass.length < MIN_PASSPHRASE;
                Arrays.fill(pass, '\0');
                invalid(shortPhrase ? phrase : repeat, shortPhrase
                        ? activity.getResources().getQuantityString(R.plurals.profile_passphrase_too_short,
                                MIN_PASSPHRASE, MIN_PASSPHRASE)
                        : "Les phrases secrètes doivent correspondre.");
                return;
            }
            run(screen, (Button) view, pass, () -> store.create(label, endpoint, bearer, pass),
                    this::opened, "Enregistrement impossible. Vérifiez le stockage disponible et réessayez.");
        });
        if (canGoBack) screen.action("Retour aux profils", false, view -> { if (!busy) chooser(null); });
    }

    private void unlock(String id, String label) {
        generation++;
        nested = true;
        ProfileScreen screen = new ProfileScreen(activity, label, "Déverrouillez ce profil pour retrouver vos fils.");
        EditText phrase = screen.field("Phrase secrète du profil", "", SECRET, 512, true);
        screen.action("Déverrouiller", true, view -> authenticate(screen, phrase, (Button) view, id, this::opened));
        screen.action("Choisir un autre profil", false, view -> { if (!busy) chooser(null); });
        screen.action("Remplacer la clé d’accès", false, view -> { if (!busy) rotate(id, label); });
        screen.action("Retirer ce profil", false, view -> authenticate(screen, phrase, (Button) view, id, account -> {
            account.close();
            confirmation = Ui.secureDialog(new AlertDialog.Builder(activity).setTitle("Retirer « " + label + " » ?")
                    .setMessage("Les données locales de ce profil seront effacées de cet appareil. Vos fils restent sur le serveur Hermes.")
                    .setNegativeButton("Annuler", null)
                    .setPositiveButton("Retirer", (dialog, which) -> forget(screen, id)));
        }));
    }

    private void rotate(String id, String label) {
        generation++;
        nested = true;
        ProfileScreen screen = new ProfileScreen(activity, "Remplacer la clé d’accès",
                "Mettez à jour la clé du serveur pour « " + label + " ». Vos fils et vos brouillons seront conservés.");
        EditText key = screen.field("Nouvelle clé d’accès Hermes", "", SECRET, 8192, true);
        EditText phrase = screen.field("Phrase secrète du profil", "", SECRET, 512, true);
        screen.action("Enregistrer la nouvelle clé", true, view -> {
            if (busy) return;
            String bearer = checkedBearer(key);
            if (bearer == null) return;
            char[] pass = password(phrase);
            if (pass.length == 0) { invalid(phrase, "Saisissez la phrase secrète du profil."); return; }
            phrase.getText().clear();
            run(screen, (Button) view, pass, () -> store.replaceBearer(id, pass, bearer), this::opened,
                    "Mise à jour impossible. Vérifiez votre phrase secrète et réessayez.");
        });
        screen.action("Annuler", false, view -> { if (!busy) unlock(id, label); });
    }

    private static String checkedBearer(EditText field) {
        String bearer = field.getText().toString().trim();
        if (!bearer.isEmpty() && bearer.chars().allMatch(c -> c >= 33 && c <= 126)) return bearer;
        invalid(field, "Vérifiez la clé d’accès fournie par le serveur.");
        return null;
    }

    private void authenticate(ProfileScreen screen, EditText phrase, Button action, String id, Consumer<Account> success) {
        if (busy) return;
        char[] pass = password(phrase);
        if (pass.length == 0) { invalid(phrase, "Saisissez la phrase secrète du profil."); return; }
        phrase.getText().clear();
        run(screen, action, pass, () -> store.open(id, pass), success,
                "Impossible de déverrouiller ce profil. Vérifiez votre phrase secrète.");
    }

    private void forget(ProfileScreen screen, String id) {
        try {
            // Persist notification revocation first so process death cannot leave an orphan listener.
            onForget.accept(id);
            store.forget(id);
        } catch (java.io.IOException | RuntimeException ex) {
            screen.status("Suppression impossible. Réessayez.", true);
            return;
        }
        chooser("Le profil a été retiré de cet appareil.");
    }

    private interface Opening { Account get() throws Exception; }

    private void run(ProfileScreen screen, Button action, char[] pass, Opening work, Consumer<Account> success, String error) {
        busy = true;
        int epoch = generation;
        action.setEnabled(false);
        InputMethodManager ime = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (ime != null) ime.hideSoftInputFromWindow(action.getWindowToken(), 0);
        screen.status("Ouverture du profil…", false);
        IO.execute(() -> {
            Account account = null;
            try {
                account = work.get();
            } catch (Exception ignored) {
                // The gate reports a single failure without exposing credentials or ciphertext.
            } finally {
                Arrays.fill(pass, '\0');
            }
            Account result = account;
            activity.runOnUiThread(() -> {
                if (epoch != generation || activity.isFinishing() || activity.isDestroyed()) {
                    if (result != null) result.close();
                    return;
                }
                busy = false;
                action.setEnabled(true);
                if (result == null) screen.status(error, true);
                else {
                    screen.status("", false);
                    success.accept(result);
                }
            });
        });
    }

    private void opened(Account account) {
        active = account;
        nested = false;
        onOpen.accept(account);
    }

    private static boolean hasControl(String text) {
        return text.chars().anyMatch(Character::isISOControl);
    }

    private static char[] password(EditText edit) {
        char[] chars = new char[edit.length()];
        edit.getText().getChars(0, chars.length, chars, 0);
        return chars;
    }

    private static void invalid(EditText field, String message) {
        field.setError(message);
        field.requestFocus();
    }
}
