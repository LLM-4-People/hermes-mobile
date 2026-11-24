package org.llm4people.hermes.profile;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.os.BiometricVault;
import org.llm4people.hermes.os.LockGracePolicy;

import java.io.*;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

final class AccountStore {
    private static final String KEY_ALIAS = "hermes.profile.envelopes.v1";
    private static final String LABEL = "label.";
    private final SharedPreferences prefs;
    private final Context context;

    AccountStore(Context context) {
        this.context = context.getApplicationContext();
        prefs = context.getSharedPreferences("hermes.profiles", Context.MODE_PRIVATE);
    }

    boolean contains(String id) {
        synchronized (prefs) {
            return id != null && prefs.contains(LABEL + id) && prefs.contains(id);
        }
    }

    Map<String, String> profiles() {
        Map<String, String> result = new LinkedHashMap<>();
        Map<String, ?> entries;
        synchronized (prefs) { entries = prefs.getAll(); }
        entries.entrySet().stream().filter(entry -> entry.getKey().startsWith(LABEL)
                && entry.getValue() instanceof String)
                .sorted(Comparator.comparing(entry -> (String) entry.getValue(), String.CASE_INSENSITIVE_ORDER))
                .forEach(entry -> result.put(entry.getKey().substring(LABEL.length()), (String) entry.getValue()));
        return result;
    }

    Account create(String label, String endpoint, String bearer, char[] pass)
            throws IOException, GeneralSecurityException {
        String id = UUID.randomUUID().toString();
        byte[] keys = new byte[Account.KEY_BYTES];
        boolean transferred = false;
        try {
            new SecureRandom().nextBytes(keys);
            String stored = encode(id, label, endpoint, bearer, keys, pass, deviceKey(true));
            synchronized (prefs) {
                if (profiles().values().stream().anyMatch(label::equalsIgnoreCase))
                    throw new IllegalArgumentException("Duplicate profile name");
                commitMembership(id, label, stored);
            }
            Account created = new Account(id, label, endpoint, bearer, vault(id), keys);
            transferred = true;
            return created;
        } finally {
            if (!transferred) Arrays.fill(keys, (byte) 0);
        }
    }

    private static byte[] packPayload(String endpoint, String bearer, byte[] keys) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(bytes)) {
            data.writeUTF(endpoint);
            data.writeUTF(bearer);
            data.write(keys);
        }
        return bytes.toByteArray();
    }

    private String encode(String id, String label, String endpoint, String bearer, byte[] keys,
                          char[] pass, SecretKey device) throws GeneralSecurityException, IOException {
        byte[] payload = packPayload(endpoint, bearer, keys);
        try {
            byte[] envelope = ProfileEnvelope.seal(device, id, label, pass, payload);
            return Base64.encodeToString(envelope, Base64.NO_WRAP);
        } finally {
            Arrays.fill(payload, (byte) 0);
        }
    }

    byte[] serializeForBiometric(Account account) throws IOException {
        Objects.requireNonNull(account);
        byte[] keys = account.copyKeys();
        try {
            return packPayload(account.endpoint, account.bearer, keys);
        } finally {
            Arrays.fill(keys, (byte) 0);
        }
    }

    Account openBiometric(String id, byte[] plain) throws IOException, GeneralSecurityException {
        if (plain == null) throw new GeneralSecurityException("Biometric payload unavailable");
        try {
            String label;
            synchronized (prefs) {
                label = prefs.getString(LABEL + id, null);
                if (label == null || !prefs.contains(id)) {
                    throw new GeneralSecurityException("Profile unavailable");
                }
            }
            try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(plain))) {
                String endpoint = GatewayAddress.normalize(data.readUTF());
                String bearer = data.readUTF();
                byte[] keys = new byte[Account.KEY_BYTES];
                boolean transferred = false;
                try {
                    data.readFully(keys);
                    synchronized (prefs) {
                        if (!label.equals(prefs.getString(LABEL + id, null)) || !prefs.contains(id)) {
                            throw new IOException("Profile changed while opening");
                        }
                        Account opened = new Account(id, label, endpoint, bearer, vault(id), keys);
                        transferred = true;
                        return opened;
                    }
                } finally {
                    if (!transferred) Arrays.fill(keys, (byte) 0);
                }
            }
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    Account open(String id, char[] pass) throws IOException, GeneralSecurityException {
        return restore(id, pass, null);
    }

    Account replaceBearer(String id, char[] pass, String bearer) throws IOException, GeneralSecurityException {
        Account updated = restore(id, pass, Objects.requireNonNull(bearer));
        BiometricVault.revoke(context, id);
        return updated;
    }

    /** Re-seals an already unlocked account under a new PIN, passphrase, or generated biometric secret. */
    void changeLockSecret(Account unlocked, char[] newSecret) throws IOException, GeneralSecurityException {
        Objects.requireNonNull(unlocked, "unlocked");
        Objects.requireNonNull(newSecret, "newSecret");
        byte[] keys = unlocked.copyKeys();
        try {
            SecretKey device = deviceKey(true);
            String resealed = encode(unlocked.id, unlocked.label, unlocked.endpoint, unlocked.bearer, keys, newSecret, device);
            synchronized (prefs) {
                if (!unlocked.label.equals(prefs.getString(LABEL + unlocked.id, null)) || !prefs.contains(unlocked.id)) {
                    throw new IOException("Profile unavailable");
                }
                commit(Collections.singletonMap(unlocked.id, resealed));
            }
        } finally {
            Arrays.fill(keys, (byte) 0);
        }
    }

    private Account restore(String id, char[] pass, String replacement) throws IOException, GeneralSecurityException {
        String label, stored;
        synchronized (prefs) {
            label = prefs.getString(LABEL + id, null);
            stored = prefs.getString(id, null);
        }
        if (label == null || stored == null) throw new GeneralSecurityException("Profile unavailable");
        SecretKey device = deviceKey(false);
        byte[] plain = ProfileEnvelope.open(device, id, label, pass, Base64.decode(stored, Base64.NO_WRAP));
        try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(plain))) {
            String endpoint = GatewayAddress.normalize(data.readUTF());
            String bearer = data.readUTF();
            byte[] keys = new byte[Account.KEY_BYTES];
            boolean transferred = false;
            try {
                data.readFully(keys);
                String rotated = replacement == null ? null : encode(id, label, endpoint, replacement, keys, pass, device);
                synchronized (prefs) {
                    if (!stored.equals(prefs.getString(id, null))) throw new IOException("Profile changed while opening");
                    if (rotated != null) {
                        commit(Collections.singletonMap(id, rotated));
                        bearer = replacement;
                    }
                    Account opened = new Account(id, label, endpoint, bearer, vault(id), keys);
                    transferred = true;
                    return opened;
                }
            } finally {
                if (!transferred) Arrays.fill(keys, (byte) 0);
            }
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    private SharedPreferences vault(String id) {
        return context.getSharedPreferences("hermes.vault." + id, Context.MODE_PRIVATE);
    }

    void forget(String id) throws IOException {
        runForgetCleanup("biometric", () -> BiometricVault.revoke(context, id));
        runForgetCleanup("grace", () -> LockGracePolicy.forget(context, id));
        runForgetCleanup("lock_mode", () -> ProfileLockPolicy.forget(context, id));
        runForgetCleanup("backoff", () -> UnlockBackoff.forget(context, id));
        runForgetCleanup("widgets", () -> org.llm4people.hermes.os.HermesWidgetStore.forgetProfile(context, id));
        runForgetCleanup("share_text", () -> IncomingShare.forgetProfile(context, id));
        runForgetCleanup("share_media", () -> IncomingMediaShare.forgetProfile(context, id));
        runForgetCleanup("captures", () -> org.llm4people.hermes.camera.PrivateCaptureProvider.purgeAll(context));
        runForgetCleanup("ambient", org.llm4people.hermes.ambient.HermesNotificationListener::clearSession);
        runForgetCleanup("memory", org.llm4people.hermes.perf.MemoryPressure::clearAll);
        commitMembership(id, null, null);
        SharedPreferences profileVault = vault(id);
        synchronized (profileVault) {
            profileVault.edit().clear().commit();
        }
        context.deleteSharedPreferences("hermes.vault." + id);
    }

    private static void runForgetCleanup(String step, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failed) {
            Diagnostics.warning("profile", "forget_cleanup_failed_" + step, failed);
        }
    }

    private void commitMembership(String id, String label, String envelope) throws IOException {
        Map<String, String> changes = new HashMap<>();
        changes.put(LABEL + id, label);
        changes.put(id, envelope);
        commit(changes);
    }

    private void commit(Map<String, String> changes) throws IOException {
        try {
            PreferenceTransaction.commit(prefs, changes);
        } catch (IllegalStateException ex) {
            throw new IOException("Profile index write failed", ex);
        }
    }

    private static SecretKey deviceKey(boolean allowCreation) throws GeneralSecurityException, IOException {
        return deviceKey(KEY_ALIAS, allowCreation);
    }

    static synchronized SecretKey deviceKey(String alias, boolean allowCreation) throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(alias)) return (SecretKey) store.getKey(alias, null);
        if (!allowCreation) throw new GeneralSecurityException("Device key unavailable");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return generator.generateKey();
    }
}
