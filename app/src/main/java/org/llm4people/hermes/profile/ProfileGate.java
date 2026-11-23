package org.llm4people.hermes.profile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import org.llm4people.hermes.AppLanguage;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.ambient.NotificationAllowlist;
import org.llm4people.hermes.os.BiometricVault;
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
    private final UnlockBackoff backoff;
    private Consumer<Account> onOpen;
    private Consumer<String> onForget = id -> {};
    private Account active;
    private AlertDialog confirmation;
    private BiometricVault.Session activeBiometric;
    private int generation;
    private boolean nested;
    private boolean busy;

    public ProfileGate(Activity activity) {
        this.activity = activity;
        this.store = new AccountStore(activity);
        this.backoff = UnlockBackoff.forContext(activity);
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

    /** Permanently tears down the gate on Activity destroy: cancels biometrics, dismisses dialogs, zeroes Account keys. */
    public void close() {
        onOpen = null;
        invalidate();
    }

    /** Returns true only when handling navigation within the gate. */
    public boolean onBack() {
        if (!nested || active != null) return false;
        if (busy) return true;
        chooser(null);
        return true;
    }

    private void cancelBiometric() {
        BiometricVault.Session session = activeBiometric;
        activeBiometric = null;
        if (session != null) session.cancel();
    }

    private void invalidate() {
        generation++;
        busy = false;
        nested = false;
        cancelBiometric();
        if (confirmation != null) {
            confirmation.dismiss();
            confirmation = null;
        }
        if (active != null) {
            active.close();
            active = null;
        }
    }

    /** Tracked like every gate dialog so lock, switch and close dismiss it. */
    private void showLanguagePicker() {
        if (confirmation != null) confirmation.dismiss();
        confirmation = AppLanguage.showPicker(activity, null);
    }

    private void chooser(String message) {
        generation++;
        nested = false;
        ProfileScreen screen = new ProfileScreen(activity, activity.getString(R.string.profile_choose_title), null);
        store.profiles().forEach((id, label) -> screen.action(label, false, view -> unlock(id, label)));
        screen.action(activity.getString(R.string.profile_add_action), true, view -> add(true));
        screen.action(activity.getString(R.string.language_action), false,
                view -> { if (!busy) showLanguagePicker(); });
        if (message != null) screen.status(message, false);
    }

    private void add(boolean canGoBack) {
        generation++;
        nested = canGoBack;
        ProfileScreen screen = new ProfileScreen(
                activity, activity.getString(R.string.profile_connect_title), activity.getString(R.string.profile_connect_detail));
        EditText name = screen.field(activity.getString(R.string.profile_name_label),
                activity.getString(R.string.profile_name_hint), TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, 80, false);
        EditText server = screen.field(activity.getString(R.string.profile_server_label),
                "https://hermes.exemple.fr", TEXT | InputType.TYPE_TEXT_VARIATION_URI, 2048, false);
        EditText serverProfile = screen.field(
                activity.getString(R.string.profile_server_slug_label),
                activity.getString(R.string.profile_server_slug_hint),
                TEXT | InputType.TYPE_TEXT_VARIATION_URI, 64, false);
        TextView endpointPreview = screen.note(activity.getString(R.string.profile_server_slug_help));
        watchEndpointPreview(server, serverProfile, endpointPreview);
        EditText key = screen.field(activity.getString(R.string.profile_key_label), "", SECRET, 8192, true);
        screen.section(activity.getString(R.string.profile_lock_section), activity.getString(R.string.profile_lock_detail));
        EditText phrase = screen.field(activity.getString(R.string.profile_passphrase_label),
                activity.getResources().getQuantityString(R.plurals.profile_passphrase_hint, MIN_PASSPHRASE, MIN_PASSPHRASE),
                SECRET, 512, true);
        EditText repeat = screen.field(activity.getString(R.string.profile_passphrase_confirm_label), "", SECRET, 512, true);
        screen.action(activity.getString(R.string.profile_save_action), true, view -> {
            if (busy) return;
            String label = name.getText().toString().trim();
            if (label.isEmpty() || hasControl(label)) {
                invalid(name, "Saisissez un nom de profil."); return;
            }
            if (store.profiles().values().stream().anyMatch(label::equalsIgnoreCase)) {
                invalid(name, "Ce nom est déjà utilisé sur cet appareil."); return;
            }
            String serverText = server.getText().toString();
            String slugText = serverProfile.getText().toString();
            try {
                GatewayAddress.normalize(serverText);
            } catch (IllegalArgumentException ex) {
                Diagnostics.warning("profile", "invalid_gateway_address", ex);
                invalid(server, activity.getString(R.string.profile_server_url_invalid)); return;
            }
            try {
                GatewayAddress.normalizeProfileSlug(slugText);
            } catch (IllegalArgumentException ex) {
                Diagnostics.warning("profile", "invalid_server_profile", ex);
                invalid(serverProfile, activity.getString(R.string.profile_server_slug_invalid)); return;
            }
            String endpoint;
            try {
                endpoint = GatewayAddress.normalize(serverText, slugText);
            } catch (IllegalArgumentException ex) {
                Diagnostics.warning("profile", "conflicting_server_profile", ex);
                invalid(serverProfile, activity.getString(R.string.profile_server_slug_conflict)); return;
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
        if (canGoBack) screen.action(activity.getString(R.string.profile_back_to_profiles), false, view -> { if (!busy) chooser(null); });
        else screen.action(activity.getString(R.string.language_action), false,
                view -> { if (!busy) showLanguagePicker(); });
    }

    /** Opt-in hardware-backed biometric enrollment using the currently unlocked account. */
    public BiometricVault.Session enrollBiometric(Account unlocked, BiometricVault.EnrollmentCallback callback) {
        Objects.requireNonNull(callback);
        if (unlocked == null || unlocked != active) {
            callback.onError();
            return null;
        }
        cancelBiometric();
        int epoch = generation;
        try {
            byte[] payload = store.serializeForBiometric(unlocked);
            BiometricVault.Session signal = BiometricVault.enroll(activity, unlocked.id, unlocked.label, payload,
                    new BiometricVault.EnrollmentCallback() {
                        @Override public void onEnrolled() {
                            activeBiometric = null;
                            if (epoch == generation && active == unlocked) callback.onEnrolled();
                        }

                        @Override public void onCancelled() {
                            activeBiometric = null;
                            if (epoch == generation && active == unlocked) callback.onCancelled();
                        }

                        @Override public void onError() {
                            activeBiometric = null;
                            if (epoch == generation && active == unlocked) callback.onError();
                        }
                    });
            activeBiometric = signal;
            return signal;
        } catch (java.io.IOException | RuntimeException error) {
            Diagnostics.warning("biometric", "serialize_failed");
            callback.onError();
            return null;
        }
    }

    private void watchEndpointPreview(EditText server, EditText serverProfile, TextView preview) {
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                String slugText = serverProfile.getText().toString().trim();
                if (slugText.isEmpty()) {
                    preview.setText(activity.getString(R.string.profile_server_slug_help));
                    return;
                }
                try {
                    String composed = GatewayAddress.normalize(server.getText().toString(), slugText);
                    preview.setText(activity.getString(R.string.profile_endpoint_preview, composed));
                } catch (IllegalArgumentException ignored) {
                    preview.setText(activity.getString(R.string.profile_server_slug_help));
                }
            }
        };
        server.addTextChangedListener(watcher);
        serverProfile.addTextChangedListener(watcher);
    }

    private void unlock(String id, String label) {
        generation++;
        nested = true;
        ProfileScreen screen = new ProfileScreen(activity, label, activity.getString(R.string.profile_unlock_subtitle));
        EditText phrase = screen.field(activity.getString(R.string.profile_passphrase_label), "", SECRET, 512, true);
        screen.action(activity.getString(R.string.profile_unlock_action), true, view -> authenticate(screen, phrase, (Button) view, id, this::opened));
        if (BiometricVault.isEnrolled(activity, id)
                && BiometricVault.availability(activity) != BiometricVault.Availability.UNAVAILABLE) {
            Button bioButton = screen.action(activity.getString(R.string.security_biometric_button), false, null);
            bioButton.setOnClickListener(view -> authenticateBiometric(screen, bioButton, id, label));
        }
        screen.action(activity.getString(R.string.profile_choose_other), false, view -> { if (!busy) chooser(null); });
        screen.action(activity.getString(R.string.profile_replace_key), false, view -> { if (!busy) rotate(id, label); });
        screen.action(activity.getString(R.string.profile_remove_action), false, view -> authenticate(screen, phrase, (Button) view, id, account -> {
            account.close();
            confirmation = Ui.secureDialog(new AlertDialog.Builder(activity).setTitle("Retirer « " + label + " » ?")
                    .setMessage("Les données locales de ce profil seront effacées de cet appareil. Vos fils restent sur le serveur Hermes.")
                    .setNegativeButton("Annuler", null)
                    .setPositiveButton("Retirer", (dialog, which) -> forget(screen, id)));
        }));
    }

    private void authenticateBiometric(ProfileScreen screen, Button bioButton, String id, String label) {
        if (busy) return;
        cancelBiometric();
        busy = true;
        int epoch = generation;
        bioButton.setEnabled(false);
        activeBiometric = BiometricVault.authenticate(activity, id, label, new BiometricVault.Callback() {
            @Override public void onSuccess(byte[] payload) {
                activeBiometric = null;
                if (epoch != generation || activity.isFinishing() || activity.isDestroyed()) {
                    Arrays.fill(payload, (byte) 0);
                    return;
                }
                busy = false;
                bioButton.setEnabled(true);
                try {
                    Account unlocked = store.openBiometric(id, payload);
                    backoff.reset(id);
                    screen.status("", false);
                    opened(unlocked);
                } catch (Exception error) {
                    Diagnostics.warning("biometric", "account_open_failed");
                    screen.status(activity.getString(R.string.security_biometric_failed), true);
                }
            }

            @Override public void onCancelled() {
                activeBiometric = null;
                if (epoch != generation) return;
                busy = false;
                bioButton.setEnabled(true);
            }

            @Override public void onInvalidated() {
                activeBiometric = null;
                if (epoch != generation) return;
                busy = false;
                bioButton.setVisibility(android.view.View.GONE);
                screen.status(activity.getString(R.string.security_biometric_invalidated), true);
            }

            @Override public void onError() {
                activeBiometric = null;
                if (epoch != generation) return;
                busy = false;
                bioButton.setEnabled(true);
                screen.status(activity.getString(R.string.security_biometric_failed), true);
            }
        });
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
            long waitMs = backoff.remainingMs(id);
            if (waitMs > 0L) {
                Diagnostics.warning("profile", "unlock_throttled");
                screen.status(activity.getString(R.string.profile_unlock_cooldown,
                        UnlockBackoff.formatRemaining(waitMs)), true);
                return;
            }
            String bearer = checkedBearer(key);
            if (bearer == null) return;
            char[] pass = password(phrase);
            if (pass.length == 0) { invalid(phrase, "Saisissez la phrase secrète du profil."); return; }
            phrase.getText().clear();
            run(screen, (Button) view, id, pass, () -> store.replaceBearer(id, pass, bearer), this::opened,
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
        long waitMs = backoff.remainingMs(id);
        if (waitMs > 0L) {
            Diagnostics.warning("profile", "unlock_throttled");
            screen.status(activity.getString(R.string.profile_unlock_cooldown,
                    UnlockBackoff.formatRemaining(waitMs)), true);
            return;
        }
        char[] pass = password(phrase);
        if (pass.length == 0) { invalid(phrase, "Saisissez la phrase secrète du profil."); return; }
        phrase.getText().clear();
        run(screen, action, id, pass, () -> store.open(id, pass), success,
                "Impossible de déverrouiller ce profil. Vérifiez votre phrase secrète.");
    }

    private void forget(ProfileScreen screen, String id) {
        try {
            // Persist notification revocation, close active account keys, and wipe all profile stores atomically.
            onForget.accept(id);
            if (active != null && id.equals(active.id)) {
                NotificationAllowlist.wipe(active);
                active.close();
                active = null;
            } else {
                NotificationAllowlist.wipe(null);
            }
            store.forget(id);
        } catch (java.io.IOException | RuntimeException ex) {
            screen.status("Suppression impossible. Réessayez.", true);
            return;
        }
        chooser("Le profil a été retiré de cet appareil.");
    }

    private interface Opening { Account get() throws Exception; }

    private void run(ProfileScreen screen, Button action, char[] pass, Opening work, Consumer<Account> success, String error) {
        run(screen, action, null, pass, work, success, error);
    }

    private void run(ProfileScreen screen, Button action, String throttleProfileId, char[] pass,
                     Opening work, Consumer<Account> success, String error) {
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
                Diagnostics.warning("profile", "unlock_failed", ignored);
            } finally {
                Arrays.fill(pass, '\0');
            }
            long cooldownMs = 0L;
            if (throttleProfileId != null) {
                if (account != null) backoff.reset(throttleProfileId);
                else cooldownMs = backoff.recordFailure(throttleProfileId);
            }
            Account result = account;
            long appliedCooldown = cooldownMs;
            activity.runOnUiThread(() -> {
                if (epoch != generation || activity.isFinishing() || activity.isDestroyed()) {
                    if (result != null) result.close();
                    return;
                }
                busy = false;
                action.setEnabled(true);
                if (result == null) {
                    String msg = appliedCooldown > 0L
                            ? error + " " + activity.getString(R.string.profile_unlock_cooldown,
                                    UnlockBackoff.formatRemaining(appliedCooldown))
                            : error;
                    screen.status(msg, true);
                } else {
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
