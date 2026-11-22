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
    }

    public static boolean isEnrolled(Context context, String profileId) {
        if (context == null || profileId == null || profileId.isEmpty()) return false;
        SharedPreferences prefs = prefs(context);
        synchronized (prefs) {
            return prefs.contains(ENVELOPE_PREFIX + profileId) && prefs.contains(MODE_PREFIX + profileId);
        }
    }

    public static void revoke(Context context, String profileId) {
        if (context == null || profileId == null || profileId.isEmpty()) return;
        String storedAlias;
        SharedPreferences prefs = prefs(context);
        synchronized (prefs) {
            storedAlias = prefs.getString(ALIAS_PREFIX + profileId, null);
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

    public static CancellationSignal enroll(Activity activity, String profileId, String label, byte[] rawPayload,
                                            EnrollmentCallback callback) {
        Objects.requireNonNull(callback);
        if (activity == null || profileId == null || profileId.isEmpty()
                || rawPayload == null || rawPayload.length == 0) {
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
        AtomicBoolean settled = new AtomicBoolean(false);
        Runnable cleanupCancel = () -> {
            Arrays.fill(payloadCopy, (byte) 0);
            deleteKeyAlias(stagingAlias);
            if (settled.compareAndSet(false, true)) callback.onCancelled();
        };
        try {
            boolean strong = mode == Availability.STRONG;
            SecretKey key = generateKey(stagingAlias, strong);
            BiometricPrompt prompt = buildPrompt(activity,
                    activity.getString(R.string.security_biometric_enroll_title),
                    activity.getString(R.string.security_biometric_enroll_subtitle),
                    strong, cleanupCancel);
            CancellationSignal signal = new CancellationSignal();
            signal.setOnCancelListener(() -> {
                Arrays.fill(payloadCopy, (byte) 0);
                deleteKeyAlias(stagingAlias);
                settled.set(true);
            });
            BiometricPrompt.AuthenticationCallback auth = new BiometricPrompt.AuthenticationCallback() {
                @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                    if (!settled.compareAndSet(false, true)) {
                        Arrays.fill(payloadCopy, (byte) 0);
                        deleteKeyAlias(stagingAlias);
                        return;
                    }
                    try {
                        Cipher cipher = strong && result.getCryptoObject() != null
                                ? result.getCryptoObject().getCipher()
                                : initEncryptCipher(key);
                        byte[] sealed = sealWithCipher(cipher, profileId, label, payloadCopy);
                        String previousAlias = storeEnvelope(activity, profileId,
                                strong ? MODE_STRONG : MODE_CREDENTIAL, stagingAlias, sealed);
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
                    Arrays.fill(payloadCopy, (byte) 0);
                    deleteKeyAlias(stagingAlias);
                    if (!settled.compareAndSet(false, true)) return;
                    if (isUserCancel(errorCode)) callback.onCancelled();
                    else {
                        Diagnostics.warning("biometric", "enroll_auth_error");
                        callback.onError();
                    }
                }
            };
            if (strong) {
                Cipher cipher = initEncryptCipher(key);
                prompt.authenticate(new BiometricPrompt.CryptoObject(cipher), signal, activity.getMainExecutor(), auth);
            } else {
                prompt.authenticate(signal, activity.getMainExecutor(), auth);
            }
            return signal;
        } catch (GeneralSecurityException | IOException | RuntimeException error) {
            Arrays.fill(payloadCopy, (byte) 0);
            deleteKeyAlias(stagingAlias);
            Diagnostics.warning("biometric", "enroll_init_failed");
            if (settled.compareAndSet(false, true)) callback.onError();
            return null;
        }
    }

    public static CancellationSignal authenticate(Activity activity, String profileId, String label, Callback callback) {
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
            encoded = prefs.getString(ENVELOPE_PREFIX + profileId, null);
            storedMode = prefs.getString(MODE_PREFIX + profileId, null);
            alias = prefs.getString(ALIAS_PREFIX + profileId, KEY_PREFIX + profileId);
        }
        if (encoded == null || storedMode == null || alias == null || alias.isEmpty()) {
            callback.onError();
            return null;
        }
        byte[] stored;
        try {
            stored = Base64.decode(encoded, Base64.NO_WRAP);
        } catch (IllegalArgumentException corrupt) {
            revoke(activity, profileId);
            callback.onInvalidated();
            return null;
        }
        boolean strong = MODE_STRONG.equals(storedMode);
        AtomicBoolean settled = new AtomicBoolean(false);
        try {
            SecretKey key = loadKey(alias);
            if (key == null) {
                revoke(activity, profileId);
                callback.onInvalidated();
                return null;
            }
            BiometricPrompt prompt = buildPrompt(activity,
                    activity.getString(R.string.security_biometric_prompt_title, label),
                    activity.getString(R.string.security_biometric_prompt_subtitle),
                    strong, () -> { if (settled.compareAndSet(false, true)) callback.onCancelled(); });
            CancellationSignal signal = new CancellationSignal();
            signal.setOnCancelListener(() -> settled.set(true));
            BiometricPrompt.AuthenticationCallback auth = new BiometricPrompt.AuthenticationCallback() {
                @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                    if (!settled.compareAndSet(false, true)) return;
                    try {
                        Cipher cipher = strong && result.getCryptoObject() != null
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
                    if (isUserCancel(errorCode)) callback.onCancelled();
                    else {
                        Diagnostics.warning("biometric", "unlock_auth_error");
                        callback.onError();
                    }
                }
            };
            if (strong) {
                Cipher cipher = initDecryptCipher(key, extractIv(stored));
                prompt.authenticate(new BiometricPrompt.CryptoObject(cipher), signal, activity.getMainExecutor(), auth);
            } else {
                prompt.authenticate(signal, activity.getMainExecutor(), auth);
            }
            return signal;
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

    private static BiometricPrompt buildPrompt(Activity activity, String title, String subtitle,
                                               boolean strong, Runnable onCancel) {
        BiometricPrompt.Builder builder = new BiometricPrompt.Builder(activity)
                .setTitle(title)
                .setSubtitle(subtitle);
        if (Build.VERSION.SDK_INT >= 30) {
            if (strong) {
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

    private static SecretKey generateKey(String alias, boolean strong) throws GeneralSecurityException, IOException {
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        KeyGenParameterSpec.Builder spec = new KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true);
        if (strong) {
            spec.setInvalidatedByBiometricEnrollment(true);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            if (strong) {
                spec.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG);
            } else {
                spec.setUserAuthenticationParameters(CREDENTIAL_VALIDITY_SECONDS,
                        KeyProperties.AUTH_DEVICE_CREDENTIAL);
            }
        } else {
            @SuppressWarnings("deprecation")
            KeyGenParameterSpec.Builder ignored = spec.setUserAuthenticationValidityDurationSeconds(
                    strong ? -1 : CREDENTIAL_VALIDITY_SECONDS);
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
                                        String alias, byte[] sealed) {
        SharedPreferences prefs = prefs(context);
        synchronized (prefs) {
            String previousAlias = prefs.getString(ALIAS_PREFIX + profileId, KEY_PREFIX + profileId);
            prefs.edit()
                    .putString(ENVELOPE_PREFIX + profileId, Base64.encodeToString(sealed, Base64.NO_WRAP))
                    .putString(MODE_PREFIX + profileId, mode)
                    .putString(ALIAS_PREFIX + profileId, alias)
                    .apply();
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
