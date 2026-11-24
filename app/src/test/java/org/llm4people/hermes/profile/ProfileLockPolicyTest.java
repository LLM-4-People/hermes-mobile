package org.llm4people.hermes.profile;

import org.junit.Test;
import org.llm4people.hermes.os.BiometricVault;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/** Pure-JVM verification suite for Track 31 multi-mode lock policy and setup form save guard. */
public final class ProfileLockPolicyTest {

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

    private static android.content.Context contextStub(android.content.SharedPreferences prefs) {
        return new android.content.ContextWrapper(null) {
            @Override public android.content.Context getApplicationContext() { return this; }
            @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
                return prefs;
            }
        };
    }
}
