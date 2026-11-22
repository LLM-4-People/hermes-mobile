package org.llm4people.hermes.profile;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
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
        return id != null && prefs.contains(LABEL + id) && prefs.contains(id);
    }

    Map<String, String> profiles() {
        Map<String, String> result = new LinkedHashMap<>();
        prefs.getAll().entrySet().stream().filter(entry -> entry.getKey().startsWith(LABEL)
                && entry.getValue() instanceof String)
                .sorted(Comparator.comparing(entry -> (String) entry.getValue(), String.CASE_INSENSITIVE_ORDER))
                .forEach(entry -> result.put(entry.getKey().substring(LABEL.length()), (String) entry.getValue()));
        return result;
    }

    Account create(String label, String endpoint, String bearer, char[] pass)
            throws IOException, GeneralSecurityException {
        String id = UUID.randomUUID().toString();
        byte[] keys = new byte[Account.KEY_BYTES];
        new SecureRandom().nextBytes(keys);
        String stored = encode(id, label, endpoint, bearer, keys, pass, deviceKey(true));
        synchronized (AccountStore.class) {
            if (profiles().values().stream().anyMatch(label::equalsIgnoreCase))
                throw new IllegalArgumentException("Duplicate profile name");
            if (!prefs.edit().putString(LABEL + id, label).putString(id, stored).commit())
                throw new IOException("Profile write failed");
        }
        return new Account(id, label, endpoint, bearer, vault(id), keys);
    }

    private String encode(String id, String label, String endpoint, String bearer, byte[] keys,
                          char[] pass, SecretKey device) throws GeneralSecurityException, IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(bytes)) {
            data.writeUTF(endpoint);
            data.writeUTF(bearer);
            data.write(keys);
        }
        byte[] payload = bytes.toByteArray();
        try {
            byte[] envelope = ProfileEnvelope.seal(device, id, label, pass, payload);
            return Base64.encodeToString(envelope, Base64.NO_WRAP);
        } finally {
            Arrays.fill(payload, (byte) 0);
        }
    }

    Account open(String id, char[] pass) throws IOException, GeneralSecurityException {
        return restore(id, pass, null);
    }

    Account replaceBearer(String id, char[] pass, String bearer) throws IOException, GeneralSecurityException {
        return restore(id, pass, Objects.requireNonNull(bearer));
    }

    private Account restore(String id, char[] pass, String replacement) throws IOException, GeneralSecurityException {
        String label = prefs.getString(LABEL + id, null);
        String stored = prefs.getString(id, null);
        if (label == null || stored == null) throw new GeneralSecurityException("Profile unavailable");
        SecretKey device = deviceKey(false);
        byte[] plain = ProfileEnvelope.open(device, id, label, pass, Base64.decode(stored, Base64.NO_WRAP));
        try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(plain))) {
            String endpoint = GatewayAddress.normalize(data.readUTF());
            String bearer = data.readUTF();
            byte[] keys = new byte[Account.KEY_BYTES];
            data.readFully(keys);
            if (replacement != null) {
                String rotated = encode(id, label, endpoint, replacement, keys, pass, device);
                synchronized (AccountStore.class) {
                    if (!stored.equals(prefs.getString(id, null)) || !prefs.edit().putString(id, rotated).commit())
                        throw new IOException("Profile changed during credential update");
                }
                bearer = replacement;
            }
            return new Account(id, label, endpoint, bearer, vault(id), keys);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    private SharedPreferences vault(String id) {
        return context.getSharedPreferences("hermes.vault." + id, Context.MODE_PRIVATE);
    }

    void forget(String id) throws IOException {
        synchronized (AccountStore.class) {
            if (!prefs.edit().remove(LABEL + id).remove(id).commit()) throw new IOException("Profile removal failed");
        }
        context.deleteSharedPreferences("hermes.vault." + id);
    }

    private static synchronized SecretKey deviceKey(boolean allowCreation) throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) return (SecretKey) store.getKey(KEY_ALIAS, null);
        if (!allowCreation) throw new GeneralSecurityException("Device key unavailable");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return generator.generateKey();
    }
}
