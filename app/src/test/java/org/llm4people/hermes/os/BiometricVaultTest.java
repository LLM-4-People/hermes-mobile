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

    @Test public void sessionWithOnCancelRunsHookOnceAfterCleanupAndHandlesExceptionsAndLateRegistration() {
        java.util.List<String> order = new java.util.ArrayList<>();
        boolean[] promptCanceled = {false};
        BiometricVault.Session session = new BiometricVault.Session(() -> {
            promptCanceled[0] = true;
            order.add("prompt");
            throw new IllegalStateException("prompt error");
        }, () -> promptCanceled[0], () -> order.add("cleanup"));

        assertSame(session, session.withOnCancel(null));
        session.withOnCancel(() -> order.add("hook1")).withOnCancel(() -> order.add("hook2"));

        try {
            session.cancel();
            fail("Expected prompt exception");
        } catch (IllegalStateException expected) {}

        assertEquals(java.util.List.of("prompt", "cleanup", "hook1", "hook2"), order);
        // Repeated cancel must not re-run hooks
        session.cancel();
        assertEquals(4, order.size());

        // Late registration on an already-canceled session fires immediately once
        int[] lateRuns = {0};
        session.withOnCancel(() -> lateRuns[0]++);
        assertEquals(1, lateRuns[0]);
    }

    @Test public void sessionMarkSettledSuppressesCancelCleanupHooksAndLateRegistrations() {
        int[] prompts = {0};
        int[] cleanups = {0};
        int[] hooks = {0};
        boolean[] busy = {true};
        boolean[] promptCanceled = {false};
        BiometricVault.Session session = new BiometricVault.Session(() -> {
            prompts[0]++;
            promptCanceled[0] = true;
        }, () -> promptCanceled[0], () -> cleanups[0]++);

        // Owner attaches a cancel hook that clears busy (mirroring ProfileGate.updateLockMode).
        session.withOnCancel(() -> {
            hooks[0]++;
            busy[0] = false;
        });

        // Prompt settles normally (e.g., onAuthenticationSucceeded) before dialog dismissal cancels the handle.
        session.markSettled();
        assertTrue("Settled session must no longer report as an active prompt", session.isCanceled());

        // Subsequent owner cancel during post-enroll IO reseal must be a complete no-op.
        session.cancel();
        session.cancel();
        assertEquals(0, prompts[0]);
        assertEquals(0, cleanups[0]);
        assertEquals(0, hooks[0]);
        assertTrue("Dismissal after settlement must not clear busy during reseal", busy[0]);

        // Late onCancel registration on a settled session must not fire either.
        int[] lateHooks = {0};
        session.withOnCancel(() -> lateHooks[0]++);
        assertEquals(0, lateHooks[0]);

        // Negative control: cancel BEFORE settlement still cancels prompt, runs cleanup, and invokes hook once.
        int[] prePrompts = {0};
        int[] preCleanups = {0};
        int[] preHooks = {0};
        BiometricVault.Session active = new BiometricVault.Session(
                () -> prePrompts[0]++, () -> false, () -> preCleanups[0]++);
        active.withOnCancel(() -> preHooks[0]++);
        active.cancel();
        active.markSettled();
        active.cancel();
        assertEquals(1, prePrompts[0]);
        assertEquals(1, preCleanups[0]);
        assertEquals(1, preHooks[0]);

        // Race control: markSettled() firing during cancel()'s cleanup must NOT clobber onCancelHook or
        // suppress late withOnCancel() on the already-cancelled session.
        int[] raceHooks = {0};
        int[] raceLateHooks = {0};
        BiometricVault.Session[] holder = new BiometricVault.Session[1];
        holder[0] = new BiometricVault.Session(
                () -> {},
                () -> false,
                () -> holder[0].markSettled());
        holder[0].withOnCancel(() -> raceHooks[0]++);
        holder[0].cancel();
        assertEquals("markSettled during cleanup must not drop pending cancel hook", 1, raceHooks[0]);
        holder[0].withOnCancel(() -> raceLateHooks[0]++);
        assertEquals("Late withOnCancel on cancelled-then-markSettled session must still run once",
                1, raceLateHooks[0]);
    }

    @Test public void enrollmentAttemptTrailingCancelAndErrorAfterSuccessNeverDeleteCommittedAlias() {
        byte[] payloadCopy = new byte[]{10, 20, 30, 40};
        int[] deletes = {0};
        int[] settledMarks = {0};
        BiometricVault.EnrollmentAttempt attempt = new BiometricVault.EnrollmentAttempt(
                payloadCopy, () -> deletes[0]++, () -> settledMarks[0]++);

        // Authentication succeeds first and claims settlement.
        assertTrue(attempt.claimSuccess(false));
        assertEquals(1, settledMarks[0]);
        assertEquals("Winning success must not delete staging alias", 0, deletes[0]);
        assertArrayEquals("Payload remains intact until caller finishes sealing", new byte[]{10, 20, 30, 40}, payloadCopy);

        // Trailing negative-button cancel, framework onAuthenticationError, session.cancel(), and duplicate success
        // must all lose the CAS and leave the committed alias untouched.
        assertFalse(attempt.settleAborted(true));
        assertFalse(attempt.settleAborted(false));
        assertFalse(attempt.claimSuccess(false));
        assertFalse(attempt.claimSuccess(true));
        assertEquals("Trailing cancel/error after commit must never delete committed alias", 0, deletes[0]);
        assertEquals(1, settledMarks[0]);
    }

    @Test public void enrollmentAttemptAbortBeforeSuccessWipesPayloadDeletesStagingKeyOnceAndBlocksLateSuccess() {
        byte[] payloadCopy = new byte[]{1, 2, 3, 4};
        int[] deletes = {0};
        int[] settledMarks = {0};
        BiometricVault.EnrollmentAttempt attempt = new BiometricVault.EnrollmentAttempt(
                payloadCopy, () -> deletes[0]++, () -> settledMarks[0]++);

        assertTrue(attempt.settleAborted(true));
        assertArrayEquals(new byte[4], payloadCopy);
        assertEquals(1, deletes[0]);
        assertEquals(1, settledMarks[0]);

        // Late success or duplicate abort must be ignored without re-deleting.
        assertFalse(attempt.claimSuccess(false));
        assertFalse(attempt.settleAborted(false));
        assertEquals(1, deletes[0]);

        // Externally canceled session arriving in onAuthenticationSucceeded still cleans up staging key once.
        byte[] canceledPayload = new byte[]{9, 8, 7};
        int[] canceledDeletes = {0};
        int[] canceledMarks = {0};
        BiometricVault.EnrollmentAttempt canceledAttempt = new BiometricVault.EnrollmentAttempt(
                canceledPayload, () -> canceledDeletes[0]++, () -> canceledMarks[0]++);
        assertFalse(canceledAttempt.claimSuccess(true));
        assertArrayEquals(new byte[3], canceledPayload);
        assertEquals(1, canceledDeletes[0]);
        assertEquals(0, canceledMarks[0]);
        assertFalse(canceledAttempt.settleAborted(true));
        assertEquals(1, canceledDeletes[0]);
    }
}
