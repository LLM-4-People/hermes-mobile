package org.llm4people.hermes.profile;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.os.BiometricVault;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** Pure-JVM verification suite for Track 31 multi-mode lock policy and setup form save guard. */
public final class ProfileLockPolicyTest {
    @Before
    public void silenceDiagnostics() {
        Diagnostics.clear();
        Diagnostics.setSink((level, tag, message) -> {});
    }

    @After
    public void restoreDiagnostics() {
        Diagnostics.clear();
        Diagnostics.setSink(null);
    }

    @Test
    public void defaultLockModeAndKeyParsingStayFailClosed() {
        assertEquals(ProfileLockPolicy.Mode.BIOMETRIC,
                ProfileLockPolicy.defaultModeForSetup(BiometricVault.Availability.STRONG));
        assertEquals(ProfileLockPolicy.Mode.BIOMETRIC,
                ProfileLockPolicy.defaultModeForSetup(BiometricVault.Availability.DEVICE_CREDENTIAL));
        assertEquals(ProfileLockPolicy.Mode.PASSPHRASE,
                ProfileLockPolicy.defaultModeForSetup(BiometricVault.Availability.UNAVAILABLE));
        assertEquals(ProfileLockPolicy.Mode.PASSPHRASE,
                ProfileLockPolicy.defaultModeForSetup(null));

        assertEquals(ProfileLockPolicy.Mode.BIOMETRIC, ProfileLockPolicy.Mode.fromKey("biometric"));
        assertEquals(ProfileLockPolicy.Mode.PIN, ProfileLockPolicy.Mode.fromKey("pin"));
        assertEquals(ProfileLockPolicy.Mode.PASSPHRASE, ProfileLockPolicy.Mode.fromKey("passphrase"));
        assertEquals(ProfileLockPolicy.Mode.PASSPHRASE, ProfileLockPolicy.Mode.fromKey(""));
        assertEquals(ProfileLockPolicy.Mode.PASSPHRASE, ProfileLockPolicy.Mode.fromKey(null));
        assertEquals(ProfileLockPolicy.Mode.PASSPHRASE, ProfileLockPolicy.Mode.fromKey("unknown_mode"));
    }

    @Test
    public void pinAndPassphraseValidatorsCoverBoundariesAndNegativeCases() {
        // PIN: 6..12 ASCII digits only
        assertEquals(ProfileLockPolicy.CredentialState.EMPTY, ProfileLockPolicy.evaluatePin("", ""));
        assertEquals(ProfileLockPolicy.CredentialState.INVALID_FORMAT, ProfileLockPolicy.evaluatePin("12345a", "12345a"));
        assertEquals(ProfileLockPolicy.CredentialState.INVALID_FORMAT, ProfileLockPolicy.evaluatePin("1234567890123", "1234567890123"));
        assertEquals(ProfileLockPolicy.CredentialState.TOO_SHORT, ProfileLockPolicy.evaluatePin("12345", ""));
        assertEquals(ProfileLockPolicy.CredentialState.AWAITING_CONFIRM, ProfileLockPolicy.evaluatePin("123456", ""));
        assertEquals(ProfileLockPolicy.CredentialState.MISMATCH, ProfileLockPolicy.evaluatePin("123456", "123457"));
        assertEquals(ProfileLockPolicy.CredentialState.READY, ProfileLockPolicy.evaluatePin("123456", "123456"));
        assertEquals(ProfileLockPolicy.CredentialState.READY, ProfileLockPolicy.evaluatePin("012345678901", "012345678901"));

        // Passphrase: 12+ characters
        assertEquals(ProfileLockPolicy.CredentialState.EMPTY, ProfileLockPolicy.evaluatePassphrase("", ""));
        assertEquals(ProfileLockPolicy.CredentialState.TOO_SHORT, ProfileLockPolicy.evaluatePassphrase("short-pass!", "short-pass!"));
        assertEquals(ProfileLockPolicy.CredentialState.AWAITING_CONFIRM, ProfileLockPolicy.evaluatePassphrase("twelve-chars", ""));
        assertEquals(ProfileLockPolicy.CredentialState.MISMATCH, ProfileLockPolicy.evaluatePassphrase("twelve-chars", "twelve-charS"));
        assertEquals(ProfileLockPolicy.CredentialState.READY, ProfileLockPolicy.evaluatePassphrase("twelve-chars", "twelve-chars"));
    }

    @Test
    public void setupFormGuardBlocksIncompleteFieldsAndUnlocksOnlyWhenValidAcrossAllThreeLockModes() {
        List<String> existing = List.of("Personal");

        // Name validation
        assertEquals(ProfileLockPolicy.SetupBlocker.NAME_MISSING,
                ProfileLockPolicy.evaluateSetupForm("  ", existing, "https://hermes.example.com", "", "sk-valid-key",
                        false, ProfileLockPolicy.Mode.BIOMETRIC, true, "", "", "", ""));
        assertEquals(ProfileLockPolicy.SetupBlocker.NAME_CONTROL,
                ProfileLockPolicy.evaluateSetupForm("Bad\nName", existing, "https://hermes.example.com", "", "sk-valid-key",
                        false, ProfileLockPolicy.Mode.BIOMETRIC, true, "", "", "", ""));
        assertEquals(ProfileLockPolicy.SetupBlocker.NAME_DUPLICATE,
                ProfileLockPolicy.evaluateSetupForm("personal", existing, "https://hermes.example.com", "", "sk-valid-key",
                        false, ProfileLockPolicy.Mode.BIOMETRIC, true, "", "", "", ""));

        // Server + slug validation
        assertEquals(ProfileLockPolicy.SetupBlocker.SERVER_MISSING,
                ProfileLockPolicy.evaluateSetupForm("Work", existing, "", "", "sk-valid-key",
                        false, ProfileLockPolicy.Mode.BIOMETRIC, true, "", "", "", ""));
        assertEquals(ProfileLockPolicy.SetupBlocker.SERVER_INVALID,
                ProfileLockPolicy.evaluateSetupForm("Work", existing, "http://remote.example.com", "", "sk-valid-key",
                        false, ProfileLockPolicy.Mode.BIOMETRIC, true, "", "", "", ""));
        assertEquals(ProfileLockPolicy.SetupBlocker.SLUG_INVALID,
                ProfileLockPolicy.evaluateSetupForm("Work", existing, "https://hermes.example.com", "../bad", "sk-valid-key",
                        false, ProfileLockPolicy.Mode.BIOMETRIC, true, "", "", "", ""));
        assertEquals(ProfileLockPolicy.SetupBlocker.SLUG_CONFLICT,
                ProfileLockPolicy.evaluateSetupForm("Work", existing, "https://hermes.example.com/p/alice", "bob", "sk-valid-key",
                        false, ProfileLockPolicy.Mode.BIOMETRIC, true, "", "", "", ""));

        // Key vs staged pairing validation
        assertEquals(ProfileLockPolicy.SetupBlocker.KEY_MISSING,
                ProfileLockPolicy.evaluateSetupForm("Work", existing, "https://hermes.example.com", "", "",
                        false, ProfileLockPolicy.Mode.BIOMETRIC, true, "", "", "", ""));
        assertEquals(ProfileLockPolicy.SetupBlocker.KEY_INVALID,
                ProfileLockPolicy.evaluateSetupForm("Work", existing, "https://hermes.example.com", "", "bad key with spaces",
                        false, ProfileLockPolicy.Mode.BIOMETRIC, true, "", "", "", ""));

        // Biometric mode: READY with staged pairing (no manual key needed!) when biometric available; blocked if unavailable
        assertEquals(ProfileLockPolicy.SetupBlocker.READY,
                ProfileLockPolicy.evaluateSetupForm("Work", existing, "https://hermes.example.com", "", "",
                        true, ProfileLockPolicy.Mode.BIOMETRIC, true, "", "", "", ""));
        assertEquals(ProfileLockPolicy.SetupBlocker.BIOMETRIC_UNAVAILABLE,
                ProfileLockPolicy.evaluateSetupForm("Work", existing, "https://hermes.example.com", "", "",
                        true, ProfileLockPolicy.Mode.BIOMETRIC, false, "", "", "", ""));

        // PIN mode
        assertEquals(ProfileLockPolicy.SetupBlocker.PIN_TOO_SHORT,
                ProfileLockPolicy.evaluateSetupForm("Work", existing, "https://hermes.example.com", "", "sk-valid-key",
                        false, ProfileLockPolicy.Mode.PIN, false, "1234", "1234", "", ""));
        assertEquals(ProfileLockPolicy.SetupBlocker.PIN_MISMATCH,
                ProfileLockPolicy.evaluateSetupForm("Work", existing, "https://hermes.example.com", "", "sk-valid-key",
                        false, ProfileLockPolicy.Mode.PIN, false, "123456", "654321", "", ""));
        assertEquals(ProfileLockPolicy.SetupBlocker.READY,
                ProfileLockPolicy.evaluateSetupForm("Work", existing, "https://hermes.example.com", "", "sk-valid-key",
                        false, ProfileLockPolicy.Mode.PIN, false, "123456", "123456", "", ""));

        // Passphrase mode
        assertEquals(ProfileLockPolicy.SetupBlocker.PASSPHRASE_TOO_SHORT,
                ProfileLockPolicy.evaluateSetupForm("Work", existing, "https://hermes.example.com", "", "sk-valid-key",
                        false, ProfileLockPolicy.Mode.PASSPHRASE, false, "", "", "too-short", "too-short"));
        assertEquals(ProfileLockPolicy.SetupBlocker.READY,
                ProfileLockPolicy.evaluateSetupForm("Work", existing, "https://hermes.example.com", "", "sk-valid-key",
                        false, ProfileLockPolicy.Mode.PASSPHRASE, false, "", "", "correct-horse-battery", "correct-horse-battery"));
    }

    @Test
    public void generatedVaultSecretIsHighEntropyAndUnique() {
        char[] s1 = ProfileLockPolicy.generateVaultSecret();
        char[] s2 = ProfileLockPolicy.generateVaultSecret();
        assertTrue("256-bit Base64 secret must be >= 43 chars", s1.length >= 43);
        assertEquals(s1.length, s2.length);
        assertFalse("Successive secrets must not collide", Arrays.equals(s1, s2));
        for (char c : s1) {
            assertTrue("Must be visible ASCII", c >= 33 && c <= 126);
        }
        Arrays.fill(s1, '\0');
        Arrays.fill(s2, '\0');
    }

    @Test
    public void switchingAwayFromBiometricPrimaryRevokesOldEnvelopeOnlyWhenLeavingBiometric() {
        assertTrue(ProfileLockPolicy.shouldRevokeBiometricOnModeChange(
                ProfileLockPolicy.Mode.BIOMETRIC, ProfileLockPolicy.Mode.PIN));
        assertTrue(ProfileLockPolicy.shouldRevokeBiometricOnModeChange(
                ProfileLockPolicy.Mode.BIOMETRIC, ProfileLockPolicy.Mode.PASSPHRASE));
        assertFalse(ProfileLockPolicy.shouldRevokeBiometricOnModeChange(
                ProfileLockPolicy.Mode.BIOMETRIC, ProfileLockPolicy.Mode.BIOMETRIC));
        assertFalse(ProfileLockPolicy.shouldRevokeBiometricOnModeChange(
                ProfileLockPolicy.Mode.PIN, ProfileLockPolicy.Mode.PASSPHRASE));
        assertFalse(ProfileLockPolicy.shouldRevokeBiometricOnModeChange(
                ProfileLockPolicy.Mode.PASSPHRASE, ProfileLockPolicy.Mode.PIN));
        assertFalse(ProfileLockPolicy.shouldRevokeBiometricOnModeChange(
                ProfileLockPolicy.Mode.PIN, ProfileLockPolicy.Mode.BIOMETRIC));

        // Biometric rollback must NOT wipe the sole biometric vault when re-enrolling BIOMETRIC -> BIOMETRIC
        assertFalse(ProfileGate.shouldRevokeBiometricOnBiometricRollback(ProfileLockPolicy.Mode.BIOMETRIC));
        assertTrue(ProfileGate.shouldRevokeBiometricOnBiometricRollback(ProfileLockPolicy.Mode.PIN));
        assertTrue(ProfileGate.shouldRevokeBiometricOnBiometricRollback(ProfileLockPolicy.Mode.PASSPHRASE));
        assertTrue(ProfileGate.shouldRevokeBiometricOnBiometricRollback(null));
    }

    @Test
    public void biometricCancelBusyGuardAndSerializedTransitionsObserveLatestCommittedMode() {
        // Pre-reseal cancel in the active epoch clears busy.
        assertTrue(ProfileGate.shouldClearBusyOnBiometricCancel(5, 5, false));
        // Dismissal once IO reseal has started must NOT clear busy prematurely.
        assertFalse(ProfileGate.shouldClearBusyOnBiometricCancel(5, 5, true));
        // Stale epoch cancels must never clear busy for a newer generation.
        assertFalse(ProfileGate.shouldClearBusyOnBiometricCancel(4, 5, false));
        assertFalse(ProfileGate.shouldClearBusyOnBiometricCancel(4, 5, true));

        // Serialized transitions reading previousMode inside IO observe the latest committed mode on rollback.
        Map<String, String> modeData = new HashMap<>();
        modeData.put("mode.p1", ProfileLockPolicy.Mode.PASSPHRASE.key);
        boolean[] commitOk = {true};
        android.content.SharedPreferences modePrefs = mapPrefs(modeData, commitOk);
        android.content.Context ctx = contextStub(modePrefs);

        assertTrue(ProfileGate.commitLockModeTransition(
                () -> "env-pin",
                () -> true,
                () -> ProfileLockPolicy.writeMode(modePrefs, "p1", ProfileLockPolicy.Mode.PIN),
                env -> {},
                () -> ProfileLockPolicy.writeMode(modePrefs, "p1", ProfileLockPolicy.Mode.PASSPHRASE),
                null,
                null,
                "lock_mode_reseal_failed"));
        assertEquals(ProfileLockPolicy.Mode.PIN, ProfileLockPolicy.mode(ctx, "p1"));

        // Second serialized transition reads fresh previousMode (PIN) right before commitLockModeTransition.
        ProfileLockPolicy.Mode freshPrevious = ProfileLockPolicy.mode(ctx, "p1");
        assertEquals(ProfileLockPolicy.Mode.PIN, freshPrevious);
        int[] revoked = {0};
        boolean secondOk = ProfileGate.commitLockModeTransition(
                () -> "env-bio",
                () -> true,
                () -> ProfileLockPolicy.writeMode(modePrefs, "p1", ProfileLockPolicy.Mode.BIOMETRIC),
                env -> { throw new IOException("commit failed"); },
                () -> ProfileLockPolicy.writeMode(modePrefs, "p1", freshPrevious),
                null,
                ProfileGate.shouldRevokeBiometricOnBiometricRollback(freshPrevious)
                        ? () -> revoked[0]++ : null,
                "lock_mode_biometric_reseal_failed");
        assertFalse(secondOk);
        assertEquals("Rollback must restore latest committed PIN mode, not stale PASSPHRASE",
                ProfileLockPolicy.Mode.PIN, ProfileLockPolicy.mode(ctx, "p1"));
        assertEquals(1, revoked[0]);
    }

    @Test
    public void biometricEnrollmentGuardRollsBackExactlyOnceOnCancelOrInterruptAndNeverAfterEnroll() {
        ProfileGate.BiometricEnrollmentGuard guard = new ProfileGate.BiometricEnrollmentGuard();
        int[] rollbacks = {0};

        // Interruption (e.g., cancelBiometric/invalidate) before enrollment triggers rollback once
        guard.arm(() -> rollbacks[0]++);
        assertTrue(guard.rollbackOnce());
        assertFalse("Second rollback (e.g., late onCancelled) must be a no-op", guard.rollbackOnce());
        assertFalse("Late onEnrolled after rollback must be rejected", guard.markEnrolled());
        assertEquals(1, rollbacks[0]);

        // Successful enrollment disarms rollback permanently
        guard.arm(() -> rollbacks[0]++);
        assertTrue(guard.markEnrolled());
        assertFalse("Double onEnrolled must be ignored", guard.markEnrolled());
        assertFalse("Cancel after successful enroll must not roll back", guard.rollbackOnce());
        assertEquals(1, rollbacks[0]);
    }

    @Test
    public void nullInputsNonAsciiDigitsAndAllRemainingSetupBlockersFailClosed() {
        // Null & non-ASCII digit rejection
        assertFalse(ProfileLockPolicy.isDigitsOnly(null));
        assertFalse(ProfileLockPolicy.isDigitsOnly(""));
        assertFalse(ProfileLockPolicy.isDigitsOnly("\u0661\u0662\u0663\u0664\u0665\u0666")); // Arabic-Indic digits
        assertFalse(ProfileLockPolicy.isDigitsOnly("\uFF11\uFF12\uFF13\uFF14\uFF15\uFF16")); // Fullwidth digits
        assertFalse(ProfileLockPolicy.isDigitsOnly("12345\u0666"));

        assertEquals(ProfileLockPolicy.CredentialState.EMPTY, ProfileLockPolicy.evaluatePin(null, null));
        assertEquals(ProfileLockPolicy.CredentialState.INVALID_FORMAT,
                ProfileLockPolicy.evaluatePin("\u0661\u0662\u0663\u0664\u0665\u0666", "\u0661\u0662\u0663\u0664\u0665\u0666"));
        assertEquals(ProfileLockPolicy.CredentialState.INVALID_FORMAT,
                ProfileLockPolicy.evaluatePin("123456", "12345a"));
        assertEquals(ProfileLockPolicy.CredentialState.EMPTY, ProfileLockPolicy.evaluatePassphrase(null, null));
        assertEquals(ProfileLockPolicy.CredentialState.TOO_SHORT, ProfileLockPolicy.evaluatePassphrase("short", null));

        // Null label/server/key/mode handling
        assertEquals(ProfileLockPolicy.SetupBlocker.NAME_MISSING,
                ProfileLockPolicy.evaluateSetupForm(null, null, null, null, null, false, null, false, null, null, null, null));

        // All 6 remaining SetupBlocker variants explicitly verified
        List<String> existing = List.of();
        assertEquals(ProfileLockPolicy.SetupBlocker.PIN_EMPTY,
                ProfileLockPolicy.evaluateSetupForm("Work", existing, "https://hermes.example.com", "", "sk-valid-key",
                        false, ProfileLockPolicy.Mode.PIN, false, "", "", "", ""));
        assertEquals(ProfileLockPolicy.SetupBlocker.PIN_INVALID,
                ProfileLockPolicy.evaluateSetupForm("Work", existing, "https://hermes.example.com", "", "sk-valid-key",
                        false, ProfileLockPolicy.Mode.PIN, false, "12345a", "12345a", "", ""));
        assertEquals(ProfileLockPolicy.SetupBlocker.PIN_CONFIRM_NEEDED,
                ProfileLockPolicy.evaluateSetupForm("Work", existing, "https://hermes.example.com", "", "sk-valid-key",
                        false, ProfileLockPolicy.Mode.PIN, false, "123456", "", "", ""));
        assertEquals(ProfileLockPolicy.SetupBlocker.PASSPHRASE_EMPTY,
                ProfileLockPolicy.evaluateSetupForm("Work", existing, "https://hermes.example.com", "", "sk-valid-key",
                        false, ProfileLockPolicy.Mode.PASSPHRASE, false, "", "", "", ""));
        assertEquals(ProfileLockPolicy.SetupBlocker.PASSPHRASE_CONFIRM_NEEDED,
                ProfileLockPolicy.evaluateSetupForm("Work", existing, "https://hermes.example.com", "", "sk-valid-key",
                        false, ProfileLockPolicy.Mode.PASSPHRASE, false, "", "", "twelve-chars", ""));
        assertEquals(ProfileLockPolicy.SetupBlocker.PASSPHRASE_MISMATCH,
                ProfileLockPolicy.evaluateSetupForm("Work", existing, "https://hermes.example.com", "", "sk-valid-key",
                        false, ProfileLockPolicy.Mode.PASSPHRASE, false, "", "", "twelve-chars", "twelve-charX"));
    }

    @Test
    public void writeModeCommitsSynchronouslyAndReportsCommitFailure() {
        boolean[] committed = {false};
        boolean[] applied = {false};
        boolean[] commitReturns = {true};
        android.content.SharedPreferences.Editor editor = (android.content.SharedPreferences.Editor)
                java.lang.reflect.Proxy.newProxyInstance(
                        android.content.SharedPreferences.Editor.class.getClassLoader(),
                        new Class<?>[]{android.content.SharedPreferences.Editor.class},
                        (proxy, method, args) -> switch (method.getName()) {
                            case "putString", "remove", "clear" -> proxy;
                            case "commit" -> {
                                committed[0] = true;
                                yield commitReturns[0];
                            }
                            case "apply" -> {
                                applied[0] = true;
                                yield null;
                            }
                            default -> null;
                        });
        android.content.SharedPreferences prefs = (android.content.SharedPreferences)
                java.lang.reflect.Proxy.newProxyInstance(
                        android.content.SharedPreferences.class.getClassLoader(),
                        new Class<?>[]{android.content.SharedPreferences.class},
                        (proxy, method, args) -> "edit".equals(method.getName()) ? editor : null);

        assertTrue(ProfileLockPolicy.writeMode(prefs, "p1", ProfileLockPolicy.Mode.PIN));
        assertTrue("writeMode must commit synchronously", committed[0]);
        assertFalse("writeMode must not use async apply()", applied[0]);

        commitReturns[0] = false;
        assertFalse(ProfileLockPolicy.writeMode(prefs, "p1", ProfileLockPolicy.Mode.BIOMETRIC));

        // Verify setMode(Context, ...) forwards writeMode commit result and fails closed on null args
        assertFalse(ProfileLockPolicy.setMode(null, "p1", ProfileLockPolicy.Mode.PIN));
        assertFalse(ProfileLockPolicy.setMode(contextStub(prefs), "", ProfileLockPolicy.Mode.PIN));
        commitReturns[0] = true;
        assertTrue(ProfileLockPolicy.setMode(contextStub(prefs), "p1", ProfileLockPolicy.Mode.PIN));
        commitReturns[0] = false;
        assertFalse(ProfileLockPolicy.setMode(contextStub(prefs), "p1", ProfileLockPolicy.Mode.BIOMETRIC));
    }

    @Test
    public void rollbackFieldsPreserveServerUrlSlugAndApiKeyAcrossNamedDefaultAndNullClaims() {
        org.llm4people.hermes.onboarding.OnboardingModels.PairingClaim named =
                new org.llm4people.hermes.onboarding.OnboardingModels.PairingClaim(
                        "https://chat.example.com/mobile", "allison", "Allison",
                        "sk-claimed-123", "fr", "", 0, null);
        ProfileGate.RollbackFields namedFields = ProfileGate.resolveRollbackFields(named);
        assertNotNull(namedFields);
        assertEquals("https://chat.example.com/mobile", namedFields.serverUrl());
        assertEquals("allison", namedFields.profileSlug());
        assertEquals("sk-claimed-123", namedFields.apiKey());

        org.llm4people.hermes.onboarding.OnboardingModels.PairingClaim embedded =
                new org.llm4people.hermes.onboarding.OnboardingModels.PairingClaim(
                        "https://chat.example.com/mobile/p/alex", "", "Alex",
                        "sk-claimed-456", "en", "", 0, null);
        ProfileGate.RollbackFields embeddedFields = ProfileGate.resolveRollbackFields(embedded);
        assertNotNull(embeddedFields);
        assertEquals("https://chat.example.com/mobile", embeddedFields.serverUrl());
        assertEquals("alex", embeddedFields.profileSlug());
        assertEquals("sk-claimed-456", embeddedFields.apiKey());

        org.llm4people.hermes.onboarding.OnboardingModels.PairingClaim defaultClaim =
                new org.llm4people.hermes.onboarding.OnboardingModels.PairingClaim(
                        "https://chat.example.com/mobile", "default", "Jacob",
                        "sk-claimed-789", "fr", "", 0, null);
        ProfileGate.RollbackFields defaultFields = ProfileGate.resolveRollbackFields(defaultClaim);
        assertNotNull(defaultFields);
        assertEquals("https://chat.example.com/mobile", defaultFields.serverUrl());
        assertEquals("", defaultFields.profileSlug());
        assertEquals("sk-claimed-789", defaultFields.apiKey());

        assertNull(ProfileGate.resolveRollbackFields(null));
        org.llm4people.hermes.onboarding.OnboardingModels.PairingClaim blankKey =
                new org.llm4people.hermes.onboarding.OnboardingModels.PairingClaim(
                        "https://chat.example.com/mobile", "allison", "Allison",
                        "   ", "fr", "", 0, null);
        assertNull(ProfileGate.resolveRollbackFields(blankKey));
    }

    @Test
    public void twoPhaseLockSecretPrepareAndCommitPreventsModeCorruptionAndRollsBackOnFailure() throws Exception {
        Map<String, String> indexData = new HashMap<>();
        indexData.put("label.p1", "Personal");
        indexData.put("p1", "initial-envelope");
        boolean[] indexCommitOk = {true};
        android.content.SharedPreferences indexPrefs = mapPrefs(indexData, indexCommitOk);

        Map<String, String> modeData = new HashMap<>();
        modeData.put("mode.p1", ProfileLockPolicy.Mode.PASSPHRASE.key);
        boolean[] modeCommitOk = {true};
        android.content.SharedPreferences modePrefs = mapPrefs(modeData, modeCommitOk);

        AccountStore store = new AccountStore(null, indexPrefs);
        SecretKey deviceKey = new SecretKeySpec(new byte[32], "AES");

        byte[] rawKeys = new byte[Account.KEY_BYTES];
        for (int i = 0; i < rawKeys.length; i++) rawKeys[i] = (byte) (i + 1);

        // (a) Locked/closed Account throws inside prepareLockSecret BEFORE ProfileLockPolicy.setMode is touched.
        Account lockedAccount = new Account(
                "p1", "Personal", "https://hermes.example.com", "sk-live-secret", null, Arrays.copyOf(rawKeys, rawKeys.length));
        lockedAccount.close();
        int[] modeSetCalls = {0};
        boolean lockedResult = ProfileGate.commitLockModeTransition(
                () -> store.prepareLockSecret(lockedAccount, "123456".toCharArray(), deviceKey),
                () -> lockedAccount.unlocked() && store.contains(lockedAccount.id),
                () -> {
                    modeSetCalls[0]++;
                    return ProfileLockPolicy.writeMode(modePrefs, "p1", ProfileLockPolicy.Mode.PIN);
                },
                resealed -> store.commitLockSecret(lockedAccount, resealed),
                () -> ProfileGate.rollbackPersistedMode(
                        () -> store.contains(lockedAccount.id),
                        () -> ProfileLockPolicy.writeMode(modePrefs, "p1", ProfileLockPolicy.Mode.PASSPHRASE),
                        () -> modeData.remove("mode.p1")),
                null,
                null,
                "lock_mode_reseal_failed");
        assertFalse(lockedResult);
        assertEquals("ProfileLockPolicy.setMode must never be called when prepareLockSecret fails", 0, modeSetCalls[0]);
        assertEquals(ProfileLockPolicy.Mode.PASSPHRASE.key, modeData.get("mode.p1"));
        assertEquals("initial-envelope", indexData.get("p1"));

        // Missing profile membership also fails inside prepareLockSecret before setMode is touched.
        Account orphan = new Account(
                "missing", "Orphan", "https://hermes.example.com", "sk-live-secret", null, Arrays.copyOf(rawKeys, rawKeys.length));
        try {
            store.prepareLockSecret(orphan, "123456".toCharArray(), deviceKey);
            fail("Expected IOException for missing profile membership");
        } catch (IOException expected) {
            // ok
        } finally {
            orphan.close();
        }

        // (b) Unlocked Account prepares resealed envelope, commits mode + envelope in order, and decrypts cleanly.
        Account unlocked = new Account(
                "p1", "Personal", "https://hermes.example.com", "sk-live-secret", null, Arrays.copyOf(rawKeys, rawKeys.length));
        List<String> steps = new ArrayList<>();
        char[] newPin = "654321".toCharArray();
        boolean ok = ProfileGate.commitLockModeTransition(
                () -> {
                    steps.add("prepare");
                    return store.prepareLockSecret(unlocked, newPin, deviceKey);
                },
                () -> unlocked.unlocked() && store.contains(unlocked.id),
                () -> {
                    steps.add("setMode");
                    return ProfileLockPolicy.writeMode(modePrefs, "p1", ProfileLockPolicy.Mode.PIN);
                },
                resealed -> {
                    steps.add("commitEnvelope");
                    store.commitLockSecret(unlocked, resealed);
                },
                () -> {
                    steps.add("rollbackMode");
                    ProfileGate.rollbackPersistedMode(
                            () -> store.contains(unlocked.id),
                            () -> ProfileLockPolicy.writeMode(modePrefs, "p1", ProfileLockPolicy.Mode.PASSPHRASE),
                            () -> modeData.remove("mode.p1"));
                },
                () -> steps.add("onSuccess"),
                () -> steps.add("onRollback"),
                "lock_mode_reseal_failed");
        assertTrue(ok);
        assertEquals(List.of("prepare", "setMode", "commitEnvelope", "onSuccess"), steps);
        assertEquals(ProfileLockPolicy.Mode.PIN.key, modeData.get("mode.p1"));
        String storedEnvelope = indexData.get("p1");
        assertNotNull(storedEnvelope);
        assertNotEquals("initial-envelope", storedEnvelope);

        byte[] opened = ProfileEnvelope.open(
                deviceKey, "p1", "Personal", newPin, Base64.getDecoder().decode(storedEnvelope));
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(opened))) {
            assertEquals("https://hermes.example.com", in.readUTF());
            assertEquals("sk-live-secret", in.readUTF());
            byte[] recoveredKeys = new byte[Account.KEY_BYTES];
            in.readFully(recoveredKeys);
            assertArrayEquals(rawKeys, recoveredKeys);
        }

        // (c) Commit failure rolls back persisted mode cleanly to previousMode (and triggers onRollback).
        indexCommitOk[0] = false;
        int[] biometricRevoked = {0};
        boolean commitFailed = ProfileGate.commitLockModeTransition(
                () -> store.prepareLockSecret(unlocked, "999999".toCharArray(), deviceKey),
                () -> unlocked.unlocked() && store.contains(unlocked.id),
                () -> ProfileLockPolicy.writeMode(modePrefs, "p1", ProfileLockPolicy.Mode.BIOMETRIC),
                resealed -> store.commitLockSecret(unlocked, resealed),
                () -> ProfileGate.rollbackPersistedMode(
                        () -> store.contains(unlocked.id),
                        () -> ProfileLockPolicy.writeMode(modePrefs, "p1", ProfileLockPolicy.Mode.PIN),
                        () -> modeData.remove("mode.p1")),
                null,
                ProfileGate.shouldRevokeBiometricOnBiometricRollback(ProfileLockPolicy.Mode.PIN)
                        ? () -> biometricRevoked[0]++ : null,
                "lock_mode_biometric_reseal_failed");
        assertFalse(commitFailed);
        assertEquals("Mode must roll back to PIN on envelope commit failure",
                ProfileLockPolicy.Mode.PIN.key, modeData.get("mode.p1"));
        assertEquals(1, biometricRevoked[0]);
        assertEquals("Stored envelope must stay unchanged on commit failure", storedEnvelope, indexData.get("p1"));

        // (d) commitMode == false branch aborts without calling commitEnvelope or rollbackMode, invokes onRollback once, logs warning.
        indexCommitOk[0] = true;
        Diagnostics.clear();
        int[] envCalls = {0};
        int[] rbModeCalls = {0};
        int[] onRbCalls = {0};
        boolean modeCommitFailed = ProfileGate.commitLockModeTransition(
                () -> store.prepareLockSecret(unlocked, "111222".toCharArray(), deviceKey),
                () -> unlocked.unlocked() && store.contains(unlocked.id),
                () -> false,
                resealed -> {
                    envCalls[0]++;
                    store.commitLockSecret(unlocked, resealed);
                },
                () -> rbModeCalls[0]++,
                null,
                () -> onRbCalls[0]++,
                "lock_mode_commit_branch_failed");
        assertFalse(modeCommitFailed);
        assertEquals(0, envCalls[0]);
        assertEquals(0, rbModeCalls[0]);
        assertEquals(1, onRbCalls[0]);
        assertTrue(Diagnostics.exportRedacted().contains("security.lock_mode_commit_branch_failed"));

        // (e) Post-PBKDF2 lock race: Account closes during prepareLockSecret -> stillEligible aborts BEFORE setMode is touched,
        //     and direct commitLockSecret on a locked account also throws IOException without writing prefs.
        Account racedLock = new Account(
                "p1", "Personal", "https://hermes.example.com", "sk-live-secret", null, Arrays.copyOf(rawKeys, rawKeys.length));
        int[] racedModeCalls = {0};
        String[] preparedForRace = {null};
        boolean racedLockResult = ProfileGate.commitLockModeTransition(
                () -> {
                    String env = store.prepareLockSecret(racedLock, "777888".toCharArray(), deviceKey);
                    preparedForRace[0] = env;
                    racedLock.close();
                    return env;
                },
                () -> racedLock.unlocked() && store.contains(racedLock.id),
                () -> {
                    racedModeCalls[0]++;
                    return ProfileLockPolicy.writeMode(modePrefs, "p1", ProfileLockPolicy.Mode.BIOMETRIC);
                },
                resealed -> store.commitLockSecret(racedLock, resealed),
                () -> ProfileGate.rollbackPersistedMode(
                        () -> store.contains(racedLock.id),
                        () -> ProfileLockPolicy.writeMode(modePrefs, "p1", ProfileLockPolicy.Mode.PIN),
                        () -> modeData.remove("mode.p1")),
                null,
                null,
                "lock_mode_reseal_failed");
        assertFalse(racedLockResult);
        assertEquals("setMode must not run if account locked during PBKDF2", 0, racedModeCalls[0]);
        assertEquals(ProfileLockPolicy.Mode.PIN.key, modeData.get("mode.p1"));
        assertEquals(storedEnvelope, indexData.get("p1"));
        try {
            store.commitLockSecret(racedLock, preparedForRace[0]);
            fail("Expected IOException when committing lock secret for an already-locked account");
        } catch (IOException expected) {
            // ok
        }
        assertEquals(storedEnvelope, indexData.get("p1"));

        // (f) Concurrent store.forget(id) race between commitMode and commitLockSecret:
        //     commitLockSecret rejects non-member with IOException, and rollback forgets mode.<id> instead of leaving an orphan.
        int[] forgetModeCalls = {0};
        int[] restoreModeCalls = {0};
        boolean forgetRaceResult = ProfileGate.commitLockModeTransition(
                () -> store.prepareLockSecret(unlocked, "333444".toCharArray(), deviceKey),
                () -> unlocked.unlocked() && store.contains(unlocked.id),
                () -> {
                    boolean written = ProfileLockPolicy.writeMode(modePrefs, "p1", ProfileLockPolicy.Mode.BIOMETRIC);
                    // Simulate concurrent store.forget("p1") wiping membership right after setMode succeeded.
                    indexData.remove("label.p1");
                    indexData.remove("p1");
                    return written;
                },
                resealed -> store.commitLockSecret(unlocked, resealed),
                () -> ProfileGate.rollbackPersistedMode(
                        () -> store.contains(unlocked.id),
                        () -> {
                            restoreModeCalls[0]++;
                            ProfileLockPolicy.writeMode(modePrefs, "p1", ProfileLockPolicy.Mode.PIN);
                        },
                        () -> {
                            forgetModeCalls[0]++;
                            modeData.remove("mode.p1");
                        }),
                null,
                null,
                "lock_mode_reseal_failed");
        assertFalse(forgetRaceResult);
        assertEquals("Must not restore previousMode after profile was forgotten", 0, restoreModeCalls[0]);
        assertEquals("Must forget orphaned mode.<id> entry when profile no longer exists", 1, forgetModeCalls[0]);
        assertFalse("Orphaned mode.<id> must not remain in prefs", modeData.containsKey("mode.p1"));

        // (g) Direct TOCTOU commitLockSecret with valid non-empty envelope on non-member throws IOException,
        //     and null/empty envelopes throw IllegalArgumentException.
        try {
            store.commitLockSecret(unlocked, preparedForRace[0]);
            fail("Expected IOException for non-member commitLockSecret with valid envelope");
        } catch (IOException expected) {
            // ok
        }
        try {
            store.commitLockSecret(unlocked, "");
            fail("Expected IllegalArgumentException for empty resealed envelope");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            store.commitLockSecret(unlocked, null);
            fail("Expected IllegalArgumentException for null resealed envelope");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        unlocked.close();
    }

    private static android.content.SharedPreferences mapPrefs(Map<String, String> backing, boolean[] commitOk) {
        return (android.content.SharedPreferences) java.lang.reflect.Proxy.newProxyInstance(
                android.content.SharedPreferences.class.getClassLoader(),
                new Class<?>[]{android.content.SharedPreferences.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getString" -> backing.getOrDefault((String) args[0], (String) args[1]);
                    case "contains" -> backing.containsKey((String) args[0]);
                    case "getAll" -> new HashMap<>(backing);
                    case "edit" -> {
                        Map<String, String> pending = new HashMap<>();
                        List<String> removals = new ArrayList<>();
                        yield java.lang.reflect.Proxy.newProxyInstance(
                                android.content.SharedPreferences.Editor.class.getClassLoader(),
                                new Class<?>[]{android.content.SharedPreferences.Editor.class},
                                (edProxy, edMethod, edArgs) -> switch (edMethod.getName()) {
                                    case "putString" -> {
                                        pending.put((String) edArgs[0], (String) edArgs[1]);
                                        yield edProxy;
                                    }
                                    case "remove" -> {
                                        removals.add((String) edArgs[0]);
                                        yield edProxy;
                                    }
                                    case "commit" -> {
                                        if (!commitOk[0]) yield false;
                                        for (String r : removals) backing.remove(r);
                                        backing.putAll(pending);
                                        yield true;
                                    }
                                    default -> edProxy;
                                });
                    }
                    default -> null;
                });
    }

    private static android.content.Context contextStub(android.content.SharedPreferences prefs) {
        return new android.content.ContextWrapper(null) {
            @Override public android.content.Context getApplicationContext() { return this; }
            @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
                return prefs;
            }
        };
    }
}
