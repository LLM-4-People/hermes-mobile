package org.llm4people.hermes.profile;

import android.content.SharedPreferences;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;
import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Credentials and encrypted storage for one unlocked gateway. Keep this out of Bundles and logs. */
public final class Account implements AutoCloseable {
    static final int KEY_BYTES = 64;
    public final String id;
    public final String label;
    public final String endpoint;
    public final String bearer;
    private final SharedPreferences prefs;
    private byte[] keys;

    Account(String id, String label, String endpoint, String bearer, SharedPreferences prefs, byte[] keys) {
        this.id = id;
        this.label = label;
        this.endpoint = endpoint;
        this.bearer = bearer;
        this.prefs = prefs;
        this.keys = keys;
    }

    /** The stored bytes are malformed or failed authentication. A locked profile raises a separate error. */
    public static final class UnreadableRecord extends IllegalStateException {
        private final Object storedValue;
        private UnreadableRecord(Throwable cause, Object value) {
            super("Unreadable account record", cause);
            storedValue = value;
        }

        /** Compare observations without exposing the encrypted preference to the caller. */
        public boolean sameVersion(UnreadableRecord other) {
            return other != null && Objects.equals(storedValue, other.storedValue);
        }
    }

    /** Reads the last committed value, or null for an absent key. Corrupt values fail closed. */
    public synchronized String read(String key) {
        String stored = null;
        try {
            String slot = slot(key);
            synchronized (prefs) {
                try { stored = prefs.getString(slot, null); }
                catch (ClassCastException wrongType) {
                    throw new UnreadableRecord(wrongType, prefs.getAll().get(slot));
                }
            }
            if (stored == null) return null;
            byte[] clear = ProfileEnvelope.decrypt(new SecretKeySpec(keys, 0, 32, "AES"), context(slot),
                    Base64.decode(stored, Base64.NO_WRAP), 0);
            try {
                return new String(clear, StandardCharsets.UTF_8);
            } finally {
                Arrays.fill(clear, (byte) 0);
            }
        } catch (BadPaddingException | IllegalBlockSizeException | IllegalArgumentException ex) {
            throw new UnreadableRecord(ex, stored);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Account record could not be read", ex);
        }
    }

    /** Synchronously commits one encrypted record so a caller may safely dispatch after return. */
    public synchronized void write(String key, String value) {
        try {
            String slot = slot(key);
            byte[] plain = value.getBytes(StandardCharsets.UTF_8);
            try {
                byte[] cipher = ProfileEnvelope.encrypt(new SecretKeySpec(keys, 0, 32, "AES"), context(slot), plain);
                String encoded = Base64.encodeToString(cipher, Base64.NO_WRAP);
                PreferenceTransaction.commit(prefs, Collections.singletonMap(slot, encoded));
            } finally {
                Arrays.fill(plain, (byte) 0);
            }
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Account record encryption failed", ex);
        }
    }

    public synchronized void remove(String key) {
        try {
            PreferenceTransaction.commit(prefs, Collections.singletonMap(slot(key), null));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Account record removal failed", ex);
        }
    }

    private String slot(String key) throws GeneralSecurityException {
        if (keys == null) throw new IllegalStateException("Profile is locked");
        if (key == null || key.isEmpty()) throw new IllegalArgumentException("Missing record key");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(keys, 32, 32, "HmacSHA256"));
        return Base64.encodeToString(mac.doFinal(key.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }

    private byte[] context(String slot) {
        return ("hermes-record-v1\0" + id + "\0" + slot).getBytes(StandardCharsets.UTF_8);
    }

    @Override public synchronized void close() {
        if (keys != null) Arrays.fill(keys, (byte) 0);
        keys = null;
    }
}
