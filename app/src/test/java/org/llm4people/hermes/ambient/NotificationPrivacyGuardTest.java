package org.llm4people.hermes.ambient;

import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class NotificationPrivacyGuardTest {
    private static final String SIGNAL = "org.thoughtcrime.securesms";
    private static final Set<String> ALLOWLIST = Set.of(SIGNAL, "com.whatsapp", "com.slack");

    @Test
    public void blocksSelfPackageAndSubpackages() {
        assertTrue(NotificationPrivacyGuard.isHardBlockedPackage("org.llm4people.hermes"));
        assertTrue(NotificationPrivacyGuard.isHardBlockedPackage("org.llm4people.hermes.debug"));
        assertEquals(NotificationPrivacyGuard.Decision.BLOCK_SELF,
                NotificationPrivacyGuard.evaluate(candidate("org.llm4people.hermes", "Hermes", "Salut"), Set.of("org.llm4people.hermes")));
    }

    @Test
    public void blocksBankingFinancePasswordManagersAndAuthenticators() {
        for (String pkg : new String[]{
                "com.x8bit.bitwarden",
                "com.agilebits.onepassword",
                "com.keepassdroid.app",
                "com.google.android.apps.authenticator2",
                "com.authy.authy",
                "com.desjardins.mobile",
                "com.chase.sig.android",
                "com.paypal.android.p2pmobile",
                "com.revolut.revolut",
                "com.coinbase.android",
                "com.wealthsimple.trade"
        }) {
            assertTrue("Expected hard-blocked: " + pkg, NotificationPrivacyGuard.isHardBlockedPackage(pkg));
            assertEquals("Expected BLOCK_SENSITIVE_PACKAGE for " + pkg,
                    NotificationPrivacyGuard.Decision.BLOCK_SENSITIVE_PACKAGE,
                    NotificationPrivacyGuard.evaluate(candidate(pkg, "Notice", "Hello"), Set.of(pkg)));
        }
    }

    @Test
    public void blocksOtp2FaAndSecretsInEnglishAndFrenchEvenFromAllowlistedApps() {
        String[][] sensitiveSamples = {
                {"Google", "G-482910 is your Google verification code."},
                {"Banque", "Votre code de vérification est 839201. Ne le partagez pas."},
                {"Login", "Use 492-811 as your one-time passcode (OTP)."},
                {"Sécurité", "Code à usage unique : 551029 pour confirmer la connexion."},
                {"2FA", "Your 2FA security code is 123456"},
                {"Wallet", "Never share your seed phrase with anyone"},
                {"Card", "CVV: 904"},
                {"Auth", "Authorization: Bearer abcdef1234567890abcd"},
                // Alphanumeric secrets / temporary passwords / API tokens without numeric CODE_TOKEN
                {"IT Helpdesk", "Your temporary password is xK9#pQ2m"},
                {"Support", "Votre mot de passe temporaire : AxR9-KmZ"},
                {"Account", "Passcode: K9X-MQZ"},
                {"SSO", "Login code: AB4X-PQ9"},
                {"Connexion", "Code de connexion : Z9K4M2"},
                {"Sécurité", "Code d’authentification : Q8M-X2P"},
                {"Confirm", "Confirmation code: XZQ-LMP"},
                {"Alert", "Do not share this token with anyone"},
                {"Alerte", "Ne partagez pas ce lien privé"},
                {"API", "Rotated key sk-AbCdEf1234567890"},
                {"GitHub", "PAT ghp_AbCdEf1234567890"},
                {"Token", "eyJhbGciOiJIUzI1NiJ9.payload.sig"},
                {"Cert", "-----BEGIN PRIVATE KEY-----"}
        };
        for (String[] pair : sensitiveSamples) {
            assertTrue("Expected OTP/secret detection for: " + pair[1],
                    NotificationPrivacyGuard.containsOtpOrSecret(pair[0], pair[1]));
            assertEquals("Expected BLOCK_OTP_OR_SECRET for: " + pair[1],
                    NotificationPrivacyGuard.Decision.BLOCK_OTP_OR_SECRET,
                    NotificationPrivacyGuard.evaluate(candidate(SIGNAL, pair[0], pair[1]), ALLOWLIST));
        }
    }

    @Test
    public void blocksSecretVisibilityOngoingSystemNoiseAndNonAllowlistedApps() {
        assertEquals(NotificationPrivacyGuard.Decision.BLOCK_PRIVATE_VISIBILITY,
                NotificationPrivacyGuard.evaluate(
                        new NotificationPrivacyGuard.Candidate(SIGNAL, "Alice", "Alice", "Salut",
                                NotificationPrivacyGuard.VISIBILITY_SECRET, false, false, false, "msg"),
                        ALLOWLIST));

        assertEquals(NotificationPrivacyGuard.Decision.BLOCK_ONGOING_OR_SYSTEM,
                NotificationPrivacyGuard.evaluate(
                        new NotificationPrivacyGuard.Candidate(SIGNAL, "Appel", "Appel", "En cours",
                                0, true, false, false, "msg"),
                        ALLOWLIST));

        assertEquals(NotificationPrivacyGuard.Decision.BLOCK_ONGOING_OR_SYSTEM,
                NotificationPrivacyGuard.evaluate(
                        new NotificationPrivacyGuard.Candidate(SIGNAL, "Sync", "Sync", "Uploading",
                                0, false, true, false, "progress"),
                        ALLOWLIST));

        assertEquals(NotificationPrivacyGuard.Decision.BLOCK_ONGOING_OR_SYSTEM,
                NotificationPrivacyGuard.evaluate(
                        new NotificationPrivacyGuard.Candidate("com.android.systemui", "Battery", "Battery", "80%",
                                0, false, false, false, "sys"),
                        Set.of("com.android.systemui")));

        assertEquals(NotificationPrivacyGuard.Decision.BLOCK_NOT_ALLOWLISTED,
                NotificationPrivacyGuard.evaluate(candidate("com.example.chat", "Bob", "Hello"), ALLOWLIST));

        assertEquals(NotificationPrivacyGuard.Decision.BLOCK_NOT_ALLOWLISTED,
                NotificationPrivacyGuard.evaluate(candidate(SIGNAL, "Alice", "Hello"), Set.of()));
    }

    @Test
    public void allowsOrdinaryAllowlistedMessageAndSanitizesControlCharsAndLength() {
        assertFalse(NotificationPrivacyGuard.containsOtpOrSecret("Marie", "On se retrouve à 18 h devant le café ?"));
        assertFalse(NotificationPrivacyGuard.containsOtpOrSecret("Paul", "Réunion à 14h30 salle 402 pour le bilan"));
        assertEquals(NotificationPrivacyGuard.Decision.ALLOW,
                NotificationPrivacyGuard.evaluate(
                        candidate(SIGNAL, "Marie", "On se retrouve à 18 h devant le café ?"), ALLOWLIST));

        String cleaned = NotificationPrivacyGuard.sanitizeText("  Bonjour\n\t\u0007  tout   le monde  ", 16);
        assertEquals("Bonjour tout le…", cleaned);
        assertEquals(16, cleaned.length());

        // R10.2.3: sanitizeText strips format/bidi/Plane 14 tags, normalizes NBSP, and never splits surrogates.
        assertEquals("Bonjour monde", NotificationPrivacyGuard.sanitizeText("Bon\u202E\uDB40\uDC20jour\u00A0monde", 32));
        assertEquals("ab…", NotificationPrivacyGuard.sanitizeText("ab😀cd", 4));
    }

    private static NotificationPrivacyGuard.Candidate candidate(String pkg, String title, String text) {
        return new NotificationPrivacyGuard.Candidate(pkg, title, title, text, 0, false, false, false, "msg");
    }
}
