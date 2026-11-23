package org.llm4people.hermes.os;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.*;

public class BiometricVaultTest {
    private static SecretKey randomKey() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        return new SecretKeySpec(raw, "AES");
    }

    @Test public void envelopeRoundTripSucceedsAndRejectsTamperedAadOrCiphertext() throws Exception {
        SecretKey key = randomKey();
        byte[] secretPayload = "https://hermes.exemple.fr|bearer|keys".getBytes(StandardCharsets.UTF_8);

        Cipher encrypt = Cipher.getInstance("AES/GCM/NoPadding");
        encrypt.init(Cipher.ENCRYPT_MODE, key);
        byte[] sealed = BiometricVault.sealWithCipher(encrypt, "prof-1", "Personnel", secretPayload);

        byte[] iv = BiometricVault.extractIv(sealed);
        assertEquals(12, iv.length);

        Cipher decrypt = Cipher.getInstance("AES/GCM/NoPadding");
        decrypt.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        byte[] opened = BiometricVault.openWithCipher(decrypt, "prof-1", "Personnel", sealed);
        assertArrayEquals(secretPayload, opened);

        // Wrong profile ID must fail GCM authentication.
        Cipher wrongProfile = Cipher.getInstance("AES/GCM/NoPadding");
        wrongProfile.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        try {
            BiometricVault.openWithCipher(wrongProfile, "prof-2", "Personnel", sealed);
            fail("Wrong profile ID accepted");
        } catch (GeneralSecurityException expected) {}

        // Wrong label must fail GCM authentication.
        Cipher wrongLabel = Cipher.getInstance("AES/GCM/NoPadding");
        wrongLabel.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        try {
            BiometricVault.openWithCipher(wrongLabel, "prof-1", "Travail", sealed);
            fail("Wrong label accepted");
        } catch (GeneralSecurityException expected) {}

        // Mutated ciphertext byte must fail GCM authentication.
        byte[] tampered = Arrays.copyOf(sealed, sealed.length);
        tampered[tampered.length - 1] ^= 0x01;
        Cipher tamperedDecrypt = Cipher.getInstance("AES/GCM/NoPadding");
        tamperedDecrypt.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        try {
            BiometricVault.openWithCipher(tamperedDecrypt, "prof-1", "Personnel", tampered);
            fail("Tampered ciphertext accepted");
        } catch (GeneralSecurityException expected) {}
    }

    @Test public void malformedVersionOrTruncatedEnvelopesFailClosed() throws Exception {
        SecretKey key = randomKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);

        try {
            BiometricVault.extractIv(null);
            fail("Null envelope accepted");
        } catch (GeneralSecurityException expected) {}

        try {
            BiometricVault.extractIv(new byte[]{1, 2, 3});
            fail("Truncated envelope accepted");
        } catch (GeneralSecurityException expected) {}

        byte[] wrongVersion = new byte[40];
        wrongVersion[0] = 2;
        try {
            BiometricVault.extractIv(wrongVersion);
            fail("Unsupported version accepted");
        } catch (GeneralSecurityException expected) {}

        try {
            BiometricVault.sealWithCipher(cipher, "", "Personnel", new byte[]{1, 2});
            fail("Empty profile ID accepted");
        } catch (IllegalArgumentException expected) {}
    }


    @Test public void sessionCancelAlwaysRunsCleanupOnceEvenWhenPromptCancelThrows() {
        int[] prompts = {0};
        int[] cleanups = {0};
        boolean[] promptCanceled = {false};
        BiometricVault.Session session = new BiometricVault.Session(() -> {
            prompts[0]++;
            promptCanceled[0] = true;
            throw new IllegalStateException("prompt service disconnected");
        }, () -> promptCanceled[0], () -> cleanups[0]++);
        assertFalse(session.isCanceled());
        try {
            session.cancel();
            fail("Prompt failure should surface");
        } catch (IllegalStateException expected) { }
        assertEquals(1, cleanups[0]);
        assertTrue(session.isCanceled());
        session.cancel();
        assertEquals(1, prompts[0]);
        assertEquals(1, cleanups[0]);
    }

    @Test public void sessionReportsExternalPromptCancelAndStillCleansUpWithoutRecancelling() {
        int[] prompts = {0};
        int[] cleanups = {0};
        boolean[] promptCanceled = {true};
        BiometricVault.Session session = new BiometricVault.Session(
                () -> prompts[0]++, () -> promptCanceled[0], () -> cleanups[0]++);
        // A success callback checks this before sealing or decrypting anything.
        assertTrue(session.isCanceled());
        session.cancel();
        assertEquals(0, prompts[0]);
        assertEquals(1, cleanups[0]);
    }

    @Test public void enrollEarlyErrorWipesRawPayloadAndCorruptPrefsFailClosed() {
        byte[] secret = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
        boolean[] errored = {false};
        assertNull(BiometricVault.enroll(null, "prof-1", "Personal", secret, new BiometricVault.EnrollmentCallback() {
            @Override public void onEnrolled() { fail("Must not enroll with null activity"); }
            @Override public void onCancelled() { fail("Must not cancel with null activity"); }
            @Override public void onError() { errored[0] = true; }
        }));
        assertTrue(errored[0]);
        assertArrayEquals(new byte[8], secret);

        android.content.SharedPreferences corruptPrefs = (android.content.SharedPreferences)
                java.lang.reflect.Proxy.newProxyInstance(
                        android.content.SharedPreferences.class.getClassLoader(),
                        new Class<?>[]{android.content.SharedPreferences.class},
                        (proxy, method, args) -> {
                            if ("getString".equals(method.getName())) {
                                throw new ClassCastException("non-string stored");
                            }
                            return null;
                        });
        assertFalse(BiometricVault.hasValidEnrollment(corruptPrefs, "prof-1"));
    }
}
