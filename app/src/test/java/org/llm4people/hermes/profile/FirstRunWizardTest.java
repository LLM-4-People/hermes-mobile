package org.llm4people.hermes.profile;

import org.junit.Test;
import org.llm4people.hermes.onboarding.PairingLinkParser;

import static org.junit.Assert.*;

public final class FirstRunWizardTest {
    @Test public void step1PlansPairingTicketDirectCredentialsAndServerOnlyCleanly() {
        PairingLinkParser.ParsedInput ticket = PairingLinkParser.parse(
                "hermes://pair?host=https%3A%2F%2Fhermes.example.com&code=abcDEF1234567890&profile=alex&name=Alex&mode=invite");
        FirstRunWizard.Step1Plan ticketPlan = FirstRunWizard.planForInput(ticket);
        assertNotNull(ticketPlan);
        assertEquals(FirstRunWizard.ApplyAction.CLAIM_TICKET, ticketPlan.action());
        assertEquals("https://hermes.example.com", ticketPlan.serverUrl());
        assertEquals("alex", ticketPlan.profileSlug());
        assertEquals("abcDEF1234567890", ticketPlan.pairingCode());
        assertEquals("Alex", ticketPlan.suggestedName());

        PairingLinkParser.ParsedInput creds = PairingLinkParser.parse(
                "https://hermes.example.com/p/home\nkey: sk-secret-token-123456");
        FirstRunWizard.Step1Plan credsPlan = FirstRunWizard.planForInput(creds);
        assertNotNull(credsPlan);
        assertEquals(FirstRunWizard.ApplyAction.FILL_CREDENTIALS, credsPlan.action());
        assertEquals("https://hermes.example.com", credsPlan.serverUrl());
        assertEquals("home", credsPlan.profileSlug());
        assertEquals("sk-secret-token-123456", credsPlan.apiKey());
        assertEquals("Home", credsPlan.suggestedName());

        PairingLinkParser.ParsedInput serverOnly = PairingLinkParser.parse("https://hermes.example.com/p/work");
        FirstRunWizard.Step1Plan serverPlan = FirstRunWizard.planForInput(serverOnly);
        assertNotNull(serverPlan);
        assertEquals(FirstRunWizard.ApplyAction.FILL_SERVER_ONLY, serverPlan.action());
        assertEquals("work", serverPlan.profileSlug());
        assertEquals("", serverPlan.apiKey());
        assertEquals("Work", serverPlan.suggestedName());

        assertNull(FirstRunWizard.planForInput(null));
    }

    @Test public void extractsSlugAndFriendlyNameSafelyAcrossEdgeCases() {
        assertEquals("alex", FirstRunWizard.extractSlugFromEndpoint("https://hermes.example.com/p/alex"));
        assertEquals("team-1", FirstRunWizard.extractSlugFromEndpoint("https://hermes.example.com/api/p/team-1/"));
        assertEquals("", FirstRunWizard.extractSlugFromEndpoint("https://hermes.example.com"));
        assertEquals("", FirstRunWizard.extractSlugFromEndpoint("not a valid uri :::"));
        assertEquals("", FirstRunWizard.extractSlugFromEndpoint(null));

        assertEquals("Marie", FirstRunWizard.suggestedName("  Marie  ", "marie", "https://hermes.example.com"));
        assertEquals("Alex", FirstRunWizard.suggestedName("", "alex", "https://hermes.example.com"));
        assertEquals("hermes.example.com", FirstRunWizard.suggestedName("", "", "https://hermes.example.com"));
        assertEquals("Hermes", FirstRunWizard.suggestedName(null, null, "bad://::"));
    }

    @Test public void confirmProfileLabelAndStagedPairingNeverUseHostnameAsProfile() {
        // Explicit displayName wins
        assertEquals("Jacob", FirstRunWizard.confirmProfileLabel("Jacob", "default", "Default"));
        assertEquals("Jacob", FirstRunWizard.formProfileName("Jacob", "default", "Default"));

        // Non-default slug beats fallback
        assertEquals("allison", FirstRunWizard.confirmProfileLabel("", "allison", "Default"));
        assertEquals("Allison", FirstRunWizard.formProfileName("", "allison", "Default"));

        // Missing/default slug uses localized Default label — NEVER the server URL hostname!
        assertEquals("Default", FirstRunWizard.confirmProfileLabel("", "default", "Default"));
        assertEquals("Par défaut", FirstRunWizard.confirmProfileLabel("", "", "Par défaut"));
        assertEquals("Par défaut", FirstRunWizard.formProfileName("", "default", "Par défaut"));

        // StagedPairing preserves full subpath URL, blanks default slug, and detects drift cleanly
        FirstRunWizard.StagedPairing stagedDefault = new FirstRunWizard.StagedPairing(
                "https://chat.ai.localserver.ca/mobile", "pair_AbCdEf1234567890", "default", "Jacob", 1_800_000_300L);
        assertEquals("https://chat.ai.localserver.ca/mobile", stagedDefault.serverUrl());
        assertEquals("", stagedDefault.formProfileSlug());
        assertEquals("https://chat.ai.localserver.ca/mobile", stagedDefault.resolvedEndpoint());
        assertTrue(stagedDefault.matchesTarget("https://chat.ai.localserver.ca/mobile", ""));
        assertTrue(stagedDefault.matchesTarget("https://chat.ai.localserver.ca/mobile/", "default"));
        assertFalse(stagedDefault.matchesTarget("https://other.example.com/mobile", ""));
        assertFalse(stagedDefault.matchesTarget("https://chat.ai.localserver.ca/mobile", "allison"));

        FirstRunWizard.StagedPairing stagedNamed = new FirstRunWizard.StagedPairing(
                "https://chat.ai.localserver.ca/mobile", "pair_AbCdEf1234567890", "allison", "Allison", 0L);
        assertEquals("allison", stagedNamed.formProfileSlug());
        assertEquals("https://chat.ai.localserver.ca/mobile/p/allison", stagedNamed.resolvedEndpoint());
        assertTrue(stagedNamed.matchesTarget("https://chat.ai.localserver.ca/mobile", "allison"));
        assertFalse(stagedNamed.matchesTarget("https://chat.ai.localserver.ca/mobile", ""));

        // Named-profile /inspect prefers baseHost and never duplicates /p/<slug> into serverUrl
        PairingLinkParser.ParsedInput namedQr = PairingLinkParser.parse(
                "hermes://pair?host=https%3A%2F%2Fchat.ai.localserver.ca%2Fmobile&code=pair_AbCdEf1234567890&profile=alex");
        FirstRunWizard.Step1Plan namedPlan = FirstRunWizard.planForInput(namedQr);
        org.llm4people.hermes.onboarding.OnboardingModels.PairingInspect namedInspect =
                new org.llm4people.hermes.onboarding.OnboardingModels.PairingInspect(
                        true,
                        "https://chat.ai.localserver.ca/mobile/p/alex",
                        "https://chat.ai.localserver.ca/mobile",
                        "alex", "Alex", "invite", 1_800_000_300L, 250);
        FirstRunWizard.StagedPairing fromInspect = FirstRunWizard.stagedFromInspect(namedPlan, namedInspect);
        assertEquals("https://chat.ai.localserver.ca/mobile", fromInspect.serverUrl());
        assertEquals("alex", fromInspect.formProfileSlug());
        assertEquals("Alex", fromInspect.displayName());
        assertEquals("https://chat.ai.localserver.ca/mobile/p/alex", fromInspect.resolvedEndpoint());

        // Even if inspect omits base_host (falling back to server_url with /p/alex), /p/alex is stripped cleanly
        org.llm4people.hermes.onboarding.OnboardingModels.PairingInspect noBaseInspect =
                new org.llm4people.hermes.onboarding.OnboardingModels.PairingInspect(
                        true,
                        "https://chat.ai.localserver.ca/mobile/p/alex",
                        "https://chat.ai.localserver.ca/mobile/p/alex",
                        "alex", "Alex", "invite", 1_800_000_300L, 250);
        FirstRunWizard.StagedPairing stripped = FirstRunWizard.stagedFromInspect(namedPlan, noBaseInspect);
        assertEquals("https://chat.ai.localserver.ca/mobile", stripped.serverUrl());
        assertEquals("https://chat.ai.localserver.ca/mobile/p/alex", stripped.resolvedEndpoint());

        // Reject 410 and 404 pairing_ticket_not_found; treat bare 404 (empty code) as legacy server without /inspect
        assertTrue(FirstRunWizard.isRejectedPairingError(410, ""));
        assertTrue(FirstRunWizard.isRejectedPairingError(410, "pairing_ticket_expired"));
        assertTrue(FirstRunWizard.isRejectedPairingError(404, "pairing_ticket_not_found"));
        assertFalse(FirstRunWizard.isRejectedPairingError(404, ""));
        assertFalse(FirstRunWizard.isRejectedPairingError(404, null));
        assertFalse(FirstRunWizard.isRejectedPairingError(500, "pairing_internal"));
    }
}
