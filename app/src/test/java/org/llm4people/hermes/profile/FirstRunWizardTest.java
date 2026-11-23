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
}
