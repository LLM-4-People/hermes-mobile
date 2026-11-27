package org.llm4people.hermes.os;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.CancellationSignal;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.profile.PreferenceTransaction;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Hardware-backed opt-in biometric / device-credential wrapper for a profile's credentials.
 * The raw passphrase is never stored; the passphrase envelope in AccountStore always stays authoritative.
 */
public final class BiometricVault {
    private static final String PREFS = "hermes.os.biometric";
    private static final String ENVELOPE_PREFIX = "bio.env.";
    private static final String MODE_PREFIX = "bio.mode.";
    private static final String ALIAS_PREFIX = "bio.alias.";
    private static final String KEY_PREFIX = "hermes.bio.v1.";
    private static final String MODE_STRONG = "strong";
    private static final String MODE_CREDENTIAL = "credential";
    private static final String MODE_COMBINED = "combined";
    private static final byte VERSION = 1;
    private static final int IV_BYTES = 12;
    private static final int TAG_BYTES = 16;
    private static final int CREDENTIAL_VALIDITY_SECONDS = 15;

    public enum Availability {
        STRONG,
        DEVICE_CREDENTIAL,
        UNAVAILABLE
    }

    public interface Callback {
        void onSuccess(byte[] payload);
        void onCancelled();
        void onInvalidated();
        void onError();
    }

    public interface EnrollmentCallback {
        void onEnrolled();
        void onCancelled();
        void onError();
    }

    /**
     * Owner-side handle for one pending prompt. {@link BiometricPrompt#authenticate} replaces the single
     * {@link CancellationSignal} OnCancelListener slot, so vault cleanup (wiping staged secrets, deleting the
     * staging key, settling the attempt) runs here synchronously instead of relying on that listener.
     */
    public static final class Session {
        private static final int STATE_ACTIVE = 0;
        private static final int STATE_CANCELLED = 1;
        private static final int STATE_SETTLED = 2;

        private final Runnable cancelPrompt;
        private final BooleanSupplier promptCanceled;
        private final Runnable cleanup;
        private final AtomicInteger state = new AtomicInteger(STATE_ACTIVE);
        private final AtomicReference<Runnable> onCancelHook = new AtomicReference<>();

        Session(Runnable cancelPrompt, BooleanSupplier promptCanceled, Runnable cleanup) {
            this.cancelPrompt = Objects.requireNonNull(cancelPrompt);
            this.promptCanceled = Objects.requireNonNull(promptCanceled);
            this.cleanup = Objects.requireNonNull(cleanup);
        }

        static Session of(CancellationSignal signal, Runnable cleanup) {
            return new Session(signal::cancel, signal::isCanceled, cleanup);
        }

        /** Registers an owner-side hook invoked once when {@link #cancel()} completes (or immediately if already canceled). */
        public Session withOnCancel(Runnable onCancel) {
            if (onCancel == null) return this;
            int current = state.get();
            if (current == STATE_SETTLED) return this;
            if (current == STATE_CANCELLED) {
                onCancel.run();
                return this;
            }
            onCancelHook.getAndUpdate(prev -> prev == null ? onCancel : () -> {
                try {
                    prev.run();
                } finally {
                    onCancel.run();
                }
            });
            int after = state.get();
            if (after == STATE_SETTLED) {
                onCancelHook.set(null);
            } else if (after == STATE_CANCELLED) {
                Runnable late = onCancelHook.getAndSet(null);
                if (late != null) late.run();
            }
            return this;
        }

        /**
         * Marks the OS prompt as settled (succeeded, errored, or user-canceled via prompt callback)
         * so subsequent owner-side {@link #cancel()} calls become no-ops without firing cancel hooks.
         */
        void markSettled() {
            if (state.compareAndSet(STATE_ACTIVE, STATE_SETTLED)) {
                onCancelHook.set(null);
            }
        }

        /** Idempotent: cancels the OS prompt and always runs cleanup + cancel hooks, even if prompt cancel throws. */
        public void cancel() {
            if (!state.compareAndSet(STATE_ACTIVE, STATE_CANCELLED)) return;
            try {
                if (!promptCanceled.getAsBoolean()) cancelPrompt.run();
            } finally {
                try {
                    cleanup.run();
                } finally {
                    Runnable hook = onCancelHook.getAndSet(null);
                    if (hook != null) hook.run();
                }
            }
        }

        public boolean isCanceled() { return state.get() != STATE_ACTIVE || promptCanceled.getAsBoolean(); }
    }

    private BiometricVault() {}

    /** Pure envelope sealing used by both AndroidKeyStore ciphers and deterministic JVM tests. */
    public static byte[] sealWithCipher(Cipher cipher, String profileId, String label, byte[] payload)
            throws GeneralSecurityException {
        Objects.requireNonNull(cipher);
        if (profileId == null || profileId.isEmpty() || label == null || payload == null || payload.length == 0) {
            throw new IllegalArgumentException("Invalid biometric envelope input");
        }
        byte[] iv = cipher.getIV();
        if (iv == null || iv.length != IV_BYTES) throw new GeneralSecurityException("Unexpected GCM IV");
        cipher.updateAAD(aad(profileId, label));
        byte[] sealed = cipher.doFinal(payload);
        return ByteBuffer.allocate(1 + iv.length + sealed.length)
                .put(VERSION)
                .put(iv)
                .put(sealed)
                .array();
    }

    /** Pure envelope decryption verifying version and profile/label AAD binding. */
    public static byte[] openWithCipher(Cipher cipher, String profileId, String label, byte[] stored)
            throws GeneralSecurityException {
        Objects.requireNonNull(cipher);
        if (profileId == null || profileId.isEmpty() || label == null
                || stored == null || stored.length < 1 + IV_BYTES + TAG_BYTES || stored[0] != VERSION) {
            throw new GeneralSecurityException("Invalid biometric envelope");
        }
        cipher.updateAAD(aad(profileId, label));
        return cipher.doFinal(stored, 1 + IV_BYTES, stored.length - 1 - IV_BYTES);
    }

    public static byte[] extractIv(byte[] stored) throws GeneralSecurityException {
        if (stored == null || stored.length < 1 + IV_BYTES + TAG_BYTES || stored[0] != VERSION) {
            throw new GeneralSecurityException("Invalid biometric envelope");
        }
        return Arrays.copyOfRange(stored, 1, 1 + IV_BYTES);
    }

    static byte[] aad(String profileId, String label) {
        return ("hermes-bio-v1\0" + profileId + "\0" + label).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Checks hardware-keystore-compatible authenticators only:
     * Class-3 BIOMETRIC_STRONG first, otherwise DEVICE_CREDENTIAL (never Class-2 BIOMETRIC_WEAK,
     * which cannot unlock AndroidKeyStore auth-bound keys).
     */
    public static Availability availability(Context context) {
        if (context == null || Build.VERSION.SDK_INT < 29) return Availability.UNAVAILABLE;
        try {
            BiometricManager manager = context.getSystemService(BiometricManager.class);
            if (manager == null) return Availability.UNAVAILABLE;
            if (Build.VERSION.SDK_INT >= 30) {
                if (manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                        == BiometricManager.BIOMETRIC_SUCCESS) {
                    return Availability.STRONG;
                }
                if (manager.canAuthenticate(BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                        == BiometricManager.BIOMETRIC_SUCCESS) {
                    return Availability.DEVICE_CREDENTIAL;
                }
                return Availability.UNAVAILABLE;
            }
            @SuppressWarnings("deprecation")
            int legacy = manager.canAuthenticate();
            return legacy == BiometricManager.BIOMETRIC_SUCCESS ? Availability.STRONG : Availability.UNAVAILABLE;
        } catch (RuntimeException err) {
            Diagnostics.warning("biometric", "availability_check_failed", err);
            return Availability.UNAVAILABLE;
        }
    }

    public static boolean isEnrolled(Context context, String profileId) {
        if (context == null || profileId == null || profileId.isEmpty()) return false;
        SharedPreferences prefs = prefs(context);
        synchronized (prefs) {
            return hasValidEnrollment(prefs, profileId);
        }
    }

    static boolean hasValidEnrollment(SharedPreferences prefs, String profileId) {
        String env = readStringOrNull(prefs, ENVELOPE_PREFIX + profileId);
        String mode = readStringOrNull(prefs, MODE_PREFIX + profileId);
        return env != null && !env.isEmpty() && mode != null && !mode.isEmpty();
    }

    private static String readStringOrNull(SharedPreferences prefs, String key) {
        try {
            return prefs.getString(key, null);
        } catch (ClassCastException corrupt) {
            Diagnostics.warning("biometric", "prefs_read_corrupt");
            return null;
        }
    }

    public static void revoke(Context context, String profileId) {
        if (context == null || profileId == null || profileId.isEmpty()) return;
        String storedAlias;
        SharedPreferences prefs = prefs(context);
        synchronized (prefs) {
            storedAlias = readStringOrNull(prefs, ALIAS_PREFIX + profileId);
            Map<String, String> removals = new HashMap<>();
            removals.put(ENVELOPE_PREFIX + profileId, null);
            removals.put(MODE_PREFIX + profileId, null);
            removals.put(ALIAS_PREFIX + profileId, null);
            try {
                PreferenceTransaction.commit(prefs, removals);
            } catch (IllegalStateException fallback) {
                Diagnostics.warning("biometric", "revoke_commit_failed");
            }
        }
        if (storedAlias != null && !storedAlias.isEmpty()) deleteKeyAlias(storedAlias);
        deleteKeyAlias(KEY_PREFIX + profileId);
        Diagnostics.event("biometric", "revoked");
    }

    public static Session enroll(Activity activity, String profileId, String label, byte[] rawPayload,
                                 EnrollmentCallback callback) {
        Objects.requireNonNull(callback);
        if (activity == null || profileId == null || profileId.isEmpty()
                || rawPayload == null || rawPayload.length == 0) {
            if (rawPayload != null) Arrays.fill(rawPayload, (byte) 0);
            callback.onError();
            return null;
        }
        Availability mode = availability(activity);
        if (mode == Availability.UNAVAILABLE) {
            Arrays.fill(rawPayload, (byte) 0);
            callback.onError();
            return null;
        }
        byte[] payloadCopy = Arrays.copyOf(rawPayload, rawPayload.length);
        Arrays.fill(rawPayload, (byte) 0);
        // Stage enrollment under a fresh keystore alias so cancel/failure never corrupts an existing enrollment.
        String stagingAlias = KEY_PREFIX + profileId + "." + UUID.randomUUID();
        AtomicReference<Session> sessionRef = new AtomicReference<>();
        EnrollmentAttempt attempt = new EnrollmentAttempt(
                payloadCopy,
                () -> deleteKeyAlias(stagingAlias),
                () -> {
                    Session active = sessionRef.get();
                    if (active != null) active.markSettled();
                });
        try {
            boolean combined = Build.VERSION.SDK_INT >= 30
                    && mode == Availability.STRONG
                    && supportsDeviceCredential(activity);
            boolean strongCrypto = mode == Availability.STRONG && !combined;
            String modeKey = combined ? MODE_COMBINED : (strongCrypto ? MODE_STRONG : MODE_CREDENTIAL);
            SecretKey key = generateKey(stagingAlias, modeKey);
            CancellationSignal signal = new CancellationSignal();
            Session session = Session.of(signal, () -> attempt.settleAborted(false));
            sessionRef.set(session);
            Runnable cleanupCancel = () -> {
                if (attempt.settleAborted(true)) {
                    callback.onCancelled();
                }
            };
            BiometricPrompt prompt = buildPrompt(activity,
                    activity.getString(R.string.security_biometric_enroll_title),
                    activity.getString(R.string.security_biometric_enroll_subtitle),
                    modeKey, cleanupCancel);
            BiometricPrompt.AuthenticationCallback auth = new BiometricPrompt.AuthenticationCallback() {
                @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                    // A success already queued on the executor must not seal secrets after lock/cancel,
                    // and a losing callback must never delete a committed alias.
                    if (!attempt.claimSuccess(session.isCanceled())) {
                        return;
                    }
                    try {
                        Cipher cipher = strongCrypto && result.getCryptoObject() != null
                                ? result.getCryptoObject().getCipher()
                                : initEncryptCipher(key);
                        byte[] sealed = sealWithCipher(cipher, profileId, label, payloadCopy);
                        String previousAlias = storeEnvelope(activity, profileId,
                                modeKey, stagingAlias, sealed);
                        if (previousAlias != null && !previousAlias.equals(stagingAlias)) {
                            deleteKeyAlias(previousAlias);
                        }
                        Diagnostics.event("biometric", "enrolled");
                        callback.onEnrolled();
                    } catch (GeneralSecurityException | RuntimeException error) {
                        Diagnostics.warning("biometric", "enroll_seal_failed");
                        deleteKeyAlias(stagingAlias);
                        callback.onError();
                    } finally {
                        Arrays.fill(payloadCopy, (byte) 0);
                    }
                }

                @Override public void onAuthenticationError(int errorCode, CharSequence errString) {
                    if (!attempt.settleAborted(true)) return;
                    if (isUserCancel(errorCode)) callback.onCancelled();
                    else {
                        Diagnostics.warning("biometric", "enroll_auth_error");
                        callback.onError();
                    }
                }
            };
            if (strongCrypto) {
                Cipher cipher = initEncryptCipher(key);
                prompt.authenticate(new BiometricPrompt.CryptoObject(cipher), signal, activity.getMainExecutor(), auth);
            } else {
                prompt.authenticate(signal, activity.getMainExecutor(), auth);
            }
            return session;
        } catch (GeneralSecurityException | IOException | RuntimeException error) {
            Diagnostics.warning("biometric", "enroll_init_failed");
            if (attempt.settleAborted(false)) callback.onError();
            return null;
        }
    }

    /**
     * Single-settlement guard for an enrollment attempt so trailing cancel/error callbacks after
     * {@code onAuthenticationSucceeded} commits can never wipe or delete the committed keystore alias.
     */
    static final class EnrollmentAttempt {
        private final AtomicBoolean settled = new AtomicBoolean(false);
        private final byte[] payloadCopy;
        private final Runnable deleteStagingKey;
        private final Runnable markSessionSettled;

        EnrollmentAttempt(byte[] payloadCopy, Runnable deleteStagingKey, Runnable markSessionSettled) {
            this.payloadCopy = payloadCopy;
            this.deleteStagingKey = deleteStagingKey;
            this.markSessionSettled = markSessionSettled;
        }

        boolean settleAborted(boolean markSettled) {
            if (!settled.compareAndSet(false, true)) return false;
            Arrays.fill(payloadCopy, (byte) 0);
            deleteStagingKey.run();
            if (markSettled && markSessionSettled != null) markSessionSettled.run();
            return true;
        }

        boolean claimSuccess(boolean sessionCanceled) {
            if (sessionCanceled) {
                settleAborted(false);
                return false;
            }
            if (!settled.compareAndSet(false, true)) return false;
            if (markSessionSettled != null) markSessionSettled.run();
            return true;
        }
    }

    public static Session authenticate(Activity activity, String profileId, String label, Callback callback) {
        Objects.requireNonNull(callback);
        if (activity == null || profileId == null || profileId.isEmpty() || !isEnrolled(activity, profileId)) {
            callback.onError();
            return null;
        }
        String encoded;
        String storedMode;
        String alias;
        SharedPreferences prefs = prefs(activity);
        synchronized (prefs) {
            encoded = readStringOrNull(prefs, ENVELOPE_PREFIX + profileId);
            storedMode = readStringOrNull(prefs, MODE_PREFIX + profileId);
            String storedAlias = readStringOrNull(prefs, ALIAS_PREFIX + profileId);
            alias = storedAlias != null && !storedAlias.isEmpty() ? storedAlias : KEY_PREFIX + profileId;
        }
        if (encoded == null || storedMode == null || alias == null || alias.isEmpty()) {
            callback.onError();
            return null;
        }
        byte[] stored;
        try {
            stored = Base64.decode(encoded, Base64.NO_WRAP);
        } catch (IllegalArgumentException corrupt) {
            Diagnostics.warning("biometric", "envelope_decode_corrupt");
            revoke(activity, profileId);
            callback.onInvalidated();
            return null;
        }
        boolean strongCrypto = MODE_STRONG.equals(storedMode);
        AtomicBoolean settled = new AtomicBoolean(false);
        try {
            SecretKey key = loadKey(alias);
            if (key == null) {
                revoke(activity, profileId);
                callback.onInvalidated();
                return null;
            }
            CancellationSignal signal = new CancellationSignal();
            Session session = Session.of(signal, () -> settled.set(true));
            BiometricPrompt prompt = buildPrompt(activity,
                    activity.getString(R.string.security_biometric_prompt_title, label),
                    activity.getString(R.string.security_biometric_prompt_subtitle),
                    storedMode, () -> {
                        if (settled.compareAndSet(false, true)) {
                            session.markSettled();
                            callback.onCancelled();
                        }
                    });
            BiometricPrompt.AuthenticationCallback auth = new BiometricPrompt.AuthenticationCallback() {
                @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                    // Never decrypt key material for a prompt the owner has already cancelled.
                    if (session.isCanceled() || !settled.compareAndSet(false, true)) return;
                    session.markSettled();
                    try {
                        Cipher cipher = strongCrypto && result.getCryptoObject() != null
                                ? result.getCryptoObject().getCipher()
                                : initDecryptCipher(key, extractIv(stored));
                        byte[] plain = openWithCipher(cipher, profileId, label, stored);
                        Diagnostics.event("biometric", "unlock_succeeded");
                        callback.onSuccess(plain);
                    } catch (KeyPermanentlyInvalidatedException invalidated) {
                        Diagnostics.warning("biometric", "key_invalidated");
                        revoke(activity, profileId);
                        callback.onInvalidated();
                    } catch (GeneralSecurityException | RuntimeException error) {
                        Diagnostics.warning("biometric", "unlock_decrypt_failed");
                        callback.onError();
                    }
                }

                @Override public void onAuthenticationError(int errorCode, CharSequence errString) {
                    if (!settled.compareAndSet(false, true)) return;
                    session.markSettled();
                    if (isUserCancel(errorCode)) callback.onCancelled();
                    else {
                        Diagnostics.warning("biometric", "unlock_auth_error");
                        callback.onError();
                    }
                }
            };
            if (strongCrypto) {
                Cipher cipher = initDecryptCipher(key, extractIv(stored));
                prompt.authenticate(new BiometricPrompt.CryptoObject(cipher), signal, activity.getMainExecutor(), auth);
            } else {
                prompt.authenticate(signal, activity.getMainExecutor(), auth);
            }
            return session;
        } catch (KeyPermanentlyInvalidatedException invalidated) {
            Diagnostics.warning("biometric", "key_invalidated");
            revoke(activity, profileId);
            if (settled.compareAndSet(false, true)) callback.onInvalidated();
            return null;
        } catch (GeneralSecurityException | IOException | RuntimeException error) {
            Diagnostics.warning("biometric", "unlock_init_failed");
            if (settled.compareAndSet(false, true)) callback.onError();
            return null;
        }
    }

    private static boolean supportsDeviceCredential(Context context) {
        if (context == null || Build.VERSION.SDK_INT < 30) return false;
        try {
            BiometricManager manager = context.getSystemService(BiometricManager.class);
            return manager != null
                    && manager.canAuthenticate(BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                    == BiometricManager.BIOMETRIC_SUCCESS;
        } catch (RuntimeException err) {
            Diagnostics.warning("biometric", "availability_check_failed", err);
            return false;
        }
    }

    private static BiometricPrompt buildPrompt(Activity activity, String title, String subtitle,
                                               String modeKey, Runnable onCancel) {
        BiometricPrompt.Builder builder = new BiometricPrompt.Builder(activity)
                .setTitle(title)
                .setSubtitle(subtitle);
        if (Build.VERSION.SDK_INT >= 30) {
            if (MODE_COMBINED.equals(modeKey)) {
                builder.setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG
                                | BiometricManager.Authenticators.DEVICE_CREDENTIAL);
            } else if (MODE_STRONG.equals(modeKey)) {
                builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                        .setNegativeButton(activity.getString(R.string.security_biometric_use_passphrase),
                                activity.getMainExecutor(), (dialog, which) -> onCancel.run());
            } else {
                builder.setAllowedAuthenticators(BiometricManager.Authenticators.DEVICE_CREDENTIAL);
            }
        } else {
            builder.setNegativeButton(activity.getString(R.string.security_biometric_use_passphrase),
                    activity.getMainExecutor(), (dialog, which) -> onCancel.run());
        }
        return builder.build();
    }

    private static SecretKey generateKey(String alias, String modeKey) throws GeneralSecurityException, IOException {
        boolean strongOnly = MODE_STRONG.equals(modeKey);
        boolean combined = MODE_COMBINED.equals(modeKey);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        KeyGenParameterSpec.Builder spec = new KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true);
        if (strongOnly) {
            spec.setInvalidatedByBiometricEnrollment(true);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            if (combined) {
                spec.setUserAuthenticationParameters(CREDENTIAL_VALIDITY_SECONDS,
                        KeyProperties.AUTH_BIOMETRIC_STRONG | KeyProperties.AUTH_DEVICE_CREDENTIAL);
            } else if (strongOnly) {
                spec.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG);
            } else {
                spec.setUserAuthenticationParameters(CREDENTIAL_VALIDITY_SECONDS,
                        KeyProperties.AUTH_DEVICE_CREDENTIAL);
            }
        } else {
            @SuppressWarnings("deprecation")
            KeyGenParameterSpec.Builder ignored = spec.setUserAuthenticationValidityDurationSeconds(
                    strongOnly ? -1 : CREDENTIAL_VALIDITY_SECONDS);
        }
        generator.init(spec.build());
        return generator.generateKey();
    }

    private static SecretKey loadKey(String alias) throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(alias)) return null;
        return (SecretKey) store.getKey(alias, null);
    }

    private static void deleteKeyAlias(String alias) {
        if (alias == null || alias.isEmpty()) return;
        try {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            if (store.containsAlias(alias)) store.deleteEntry(alias);
        } catch (GeneralSecurityException | IOException | RuntimeException ignored) {
            Diagnostics.warning("biometric", "key_delete_failed");
        }
    }

    private static Cipher initEncryptCipher(SecretKey key) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        return cipher;
    }

    private static Cipher initDecryptCipher(SecretKey key, byte[] iv) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BYTES * 8, iv));
        return cipher;
    }

    private static String storeEnvelope(Context context, String profileId, String mode,
                                        String alias, byte[] sealed) throws GeneralSecurityException {
        SharedPreferences prefs = prefs(context);
        synchronized (prefs) {
            String storedAlias = readStringOrNull(prefs, ALIAS_PREFIX + profileId);
            String previousAlias = storedAlias != null ? storedAlias : KEY_PREFIX + profileId;
            Map<String, String> updates = new HashMap<>();
            updates.put(ENVELOPE_PREFIX + profileId, Base64.encodeToString(sealed, Base64.NO_WRAP));
            updates.put(MODE_PREFIX + profileId, mode);
            updates.put(ALIAS_PREFIX + profileId, alias);
            try {
                PreferenceTransaction.commit(prefs, updates);
            } catch (IllegalStateException commitFailed) {
                Diagnostics.warning("biometric", "enroll_commit_failed");
                throw new GeneralSecurityException("Biometric envelope commit failed", commitFailed);
            }
            return previousAlias;
        }
    }

    private static boolean isUserCancel(int code) {
        return code == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED
                || code == BiometricPrompt.BIOMETRIC_ERROR_CANCELED;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
