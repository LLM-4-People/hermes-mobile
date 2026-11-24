package org.llm4people.hermes;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.llm4people.hermes.ambient.NotificationPrivacyGuard;
import org.llm4people.hermes.customization.AppCustomization;
import org.llm4people.hermes.os.HermesWidgetSpec;
import org.llm4people.hermes.presence.AppPresenceTracker;
import org.llm4people.hermes.presence.ContinuousLocationTracker;
import org.llm4people.hermes.templates.WorkflowTemplate;

import java.io.IOException;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class SecretPatternsTest {
    @Before
    public void setUpDiagnostics() {
        Diagnostics.clear();
        Diagnostics.setSink((level, tag, message) -> {});
    }

    @After
    public void tearDownDiagnostics() {
        Diagnostics.clear();
        Diagnostics.setSink(null);
    }

    private static final String[] OBFUSCATED_AND_DRIFTED_SECRETS = {
            "sk-\u200B1234567890abcdef",
            "sk-\u00001234567890abcdef",
            "sk-\n1234567890abcdef",
            "sk-\uDB40\uDC201234567890abcdef",
            "sk-proj-1234567890abcdef",
            "sk-ant-api03-1234567890abcdef",
            "ghp_\u0000AbCdEf1234567890",
            "gho_AbCdEf1234567890",
            "ghu_AbCdEf1234567890",
            "ghs_AbCdEf1234567890",
            "ghr_AbCdEf1234567890",
            "Bearer\u3000abcdefgh12345678",
            "Bea\u200Brer\nabcdefgh12345678",
            "eyJ\uFEFFhbGciOiJIUzI1NiJ9.payload",
            "-----BEGIN\u202E PRIVATE KEY-----",
            "safe prefix ".repeat(20) + "sk-proj-1234567890abcdef"
    };

    @Test
    public void detectsRawObfuscatedDriftedAndPastBoundCredentials() {
        assertFalse(SecretPatterns.containsCredential(null));
        assertFalse(SecretPatterns.containsCredential(""));
        assertFalse(SecretPatterns.containsCredential("Bonjour Montréal, réunion à 14h30"));
        assertFalse(SecretPatterns.containsCredential("Ask about task-1234567890abcdef"));

        for (String secret : OBFUSCATED_AND_DRIFTED_SECRETS) {
            assertTrue("SecretPatterns missed: " + secret, SecretPatterns.containsCredential(secret));
            assertTrue("HermesWidgetSpec missed: " + secret, HermesWidgetSpec.containsSecretLikePattern(secret));
            assertEquals("HermesWidgetSpec.sanitizeHomeText leaked: " + secret,
                    "", HermesWidgetSpec.sanitizeHomeText(secret, 48));
            assertEquals("HermesWidgetSpec.sanitizeDraft leaked: " + secret,
                    "", HermesWidgetSpec.sanitizeDraft(secret));
            assertTrue("WorkflowTemplate missed: " + secret, WorkflowTemplate.looksLikeSecret(secret));
            assertTrue("NotificationPrivacyGuard missed: " + secret,
                    NotificationPrivacyGuard.containsOtpOrSecret("Notice", secret));
            assertEquals("NotificationPrivacyGuard.evaluate allowed: " + secret,
                    NotificationPrivacyGuard.Decision.BLOCK_OTP_OR_SECRET,
                    NotificationPrivacyGuard.evaluate(
                            new NotificationPrivacyGuard.Candidate(
                                    "com.slack", "Notice", secret, 0, false, false, false, "msg"),
                            Set.of("com.slack")));
            assertEquals("ContinuousLocationTracker leaked: " + secret,
                    "", ContinuousLocationTracker.sanitizeAreaLabel(secret));
            try {
                AppCustomization.sanitizePlainText(secret, 48);
                fail("AppCustomization accepted secret: " + secret);
            } catch (IllegalArgumentException expected) {}
        }
    }

    @Test
    public void presenceTrackerAndTemplatesRejectObfuscatedSecrets() {
        AppPresenceTracker tracker = new AppPresenceTracker();
        long epoch = tracker.bindProfile("alex", 1L);
        tracker.updateDraft(epoch, "sk-\u200B1234567890abcdef");
        String wire = tracker.toWireContextJson(
                new org.llm4people.hermes.presence.PresencePolicy(
                        true, false, true, false, false, false, false, 45, 3),
                null, 1000L);
        assertFalse("Draft preview must not leak obfuscated secret", wire != null && wire.contains("1234567890abcdef"));

        try {
            new WorkflowTemplate("tpl_sec", "Safe title", "", "Use sk-\u200B1234567890abcdef", false);
            fail("WorkflowTemplate accepted obfuscated secret in draft");
        } catch (WorkflowTemplate.SecretRejectedException expected) {
            // expected
        } catch (IOException unexpected) {
            fail("Expected SecretRejectedException, got: " + unexpected);
        }
    }

    @Test
    public void widgetSanitizersStripFormatAndSurrogatesWithoutSplittingEmoji() {
        assertEquals("Bon\uD83D\uDE00", HermesWidgetSpec.sanitizeHomeText("Bon\u200B\uFEFF\uDB40\uDC20\uD83D\uDE00", 5));
        assertEquals("Bon", HermesWidgetSpec.sanitizeHomeText("Bon\uD83D\uDE00", 4));
        assertEquals("ab", HermesWidgetSpec.sanitizeHomeText("a\uD83Db", 10));
        assertEquals("ab\ncd", HermesWidgetSpec.sanitizeDraft("a\u200Bb\nc\uDB40\uDC20d"));
    }

    @Test
    public void notificationPrivacyGuardDetectsObfuscatedOtpAndSecretLabelsWithoutTitleFusion() {
        String[][] obfuscatedCases = {
                {"Alert", "G-\u200B123456"},
                {"Notice", "c\u200Bvv 123"},
                {"Alert", "o\u200Btp 123456"},
                {"Notice", "card\u200B\npin 1234"},
                {"Alert", "c\nvv 123"},
                {"card", "pin 1234"}
        };
        for (String[] pair : obfuscatedCases) {
            assertTrue("containsOtpOrSecret missed: " + pair[0] + " / " + pair[1],
                    NotificationPrivacyGuard.containsOtpOrSecret(pair[0], pair[1]));
            assertEquals("evaluate allowed: " + pair[0] + " / " + pair[1],
                    NotificationPrivacyGuard.Decision.BLOCK_OTP_OR_SECRET,
                    NotificationPrivacyGuard.evaluate(
                            new NotificationPrivacyGuard.Candidate(
                                    "com.slack", pair[0], pair[1], 0, false, false, false, "msg"),
                            Set.of("com.slack")));
        }
    }
}
