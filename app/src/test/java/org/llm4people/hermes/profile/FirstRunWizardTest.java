package org.llm4people.hermes.profile;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.HermesConfig;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.onboarding.PairingLinkParser;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public final class FirstRunWizardTest {

    @Before
    public void silenceDiagnostics() {
        Diagnostics.clear();
        Diagnostics.setSink((level, tag, message) -> {});
    }

    @After
    public void resetDiagnosticsAndPendingSyncs() {
        FirstRunWizard.revokePendingSyncs();
        Diagnostics.clear();
        Diagnostics.setSink(null);
    }
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

        // Missing/default slug uses localized Default label — NEVER the server URL hostname or hardcoded English!
        assertEquals("Default", FirstRunWizard.confirmProfileLabel("", "default", "Default"));
        assertEquals("Par défaut", FirstRunWizard.confirmProfileLabel("", "", "Par défaut"));
        assertEquals("Par défaut", FirstRunWizard.formProfileName("", "default", "Par défaut"));
        assertEquals("", FirstRunWizard.confirmProfileLabel("", "default", null));
        assertEquals("", FirstRunWizard.confirmProfileLabel("", "", "   "));
        assertEquals("", FirstRunWizard.formProfileName("", "default", null));
        assertEquals("", FirstRunWizard.formProfileName("", "", "   "));

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

    @Test public void step2CommitPlanDefersThemeAndPreventsDoubleRecreate() {
        // API < 33 unchanged theme + locale change: manual locale recreate + detached sync (willRecreate=true)
        FirstRunWizard.Step2CommitPlan unchangedApi32 = FirstRunWizard.planStep2Commit(
                "system", "system", false, false, true, true);
        assertFalse(unchangedApi32.applyThemeCallback());
        assertTrue(unchangedApi32.recreateForLocale());
        assertTrue(unchangedApi32.willRecreate());

        // API 33+ unchanged theme + locale change: OS LocaleManager recreates -> no manual recreate, but willRecreate=true
        FirstRunWizard.Step2CommitPlan unchangedApi33 = FirstRunWizard.planStep2Commit(
                "system", "system", false, false, true, false);
        assertFalse(unchangedApi33.applyThemeCallback());
        assertFalse(unchangedApi33.recreateForLocale());
        assertTrue(unchangedApi33.willRecreate());

        // Switching light -> dark in day mode triggers theme recreate -> suppress redundant manual locale recreate
        FirstRunWizard.Step2CommitPlan darkRecreates = FirstRunWizard.planStep2Commit(
                "light", "dark", false, false, true, true);
        assertTrue(darkRecreates.applyThemeCallback());
        assertFalse(darkRecreates.recreateForLocale());
        assertTrue(darkRecreates.willRecreate());

        // Switching system -> light when already in day mode does NOT recreate for theme -> keep manual locale recreate
        FirstRunWizard.Step2CommitPlan samePolarity = FirstRunWizard.planStep2Commit(
                "system", "light", false, false, true, true);
        assertTrue(samePolarity.applyThemeCallback());
        assertTrue(samePolarity.recreateForLocale());
        assertTrue(samePolarity.willRecreate());

        // Same polarity with no locale change -> willRecreate is false (session api reused)
        FirstRunWizard.Step2CommitPlan noRecreate = FirstRunWizard.planStep2Commit(
                "system", "light", false, false, false, false);
        assertTrue(noRecreate.applyThemeCallback());
        assertFalse(noRecreate.recreateForLocale());
        assertFalse(noRecreate.willRecreate());

        // Null/garbage theme inputs normalize safely to system
        FirstRunWizard.Step2CommitPlan badInputs = FirstRunWizard.planStep2Commit(
                null, "invalid_theme", false, false, false, false);
        assertFalse(badInputs.applyThemeCallback());
        assertFalse(badInputs.recreateForLocale());
        assertFalse(badInputs.willRecreate());
    }

    @Test public void detachedProfileSyncRevokesOnLockAndCompletionWhileSessionReusePreservesApi() throws Exception {
        // Null / blank inputs fail closed cleanly without throwing.
        assertFalse(FirstRunWizard.submitProfileSync(null, true, "alex", "Alex", "en")
                .get(5, TimeUnit.SECONDS));
        HermesApi blankSlugApi = new HermesApi(
                "http://127.0.0.1:1", "tok", HermesApi.Limits.from(HermesConfig.defaults()));
        assertFalse(FirstRunWizard.submitProfileSync(blankSlugApi, true, "", "Alex", "en")
                .get(5, TimeUnit.SECONDS));

        // Positive-path detached sync returns true and still revokes client in finally.
        AtomicBoolean detachedRan = new AtomicBoolean(false);
        HermesApi detachedOkApi = new HermesApi(
                "http://127.0.0.1:1", "tok", HermesApi.Limits.from(HermesConfig.defaults()));
        assertTrue(FirstRunWizard.submitProfileSync(
                detachedOkApi, true, "alex", () -> detachedRan.set(true))
                .get(5, TimeUnit.SECONDS));
        assertTrue("Detached sync work must execute", detachedRan.get());
        assertTrue("Detached sync client must be revoked in finally on success", detachedOkApi.isRevoked());

        // Positive-path session-reuse sync returns true and preserves the live session API.
        AtomicBoolean sessionRan = new AtomicBoolean(false);
        HermesApi sessionOkApi = new HermesApi(
                "http://127.0.0.1:1", "tok", HermesApi.Limits.from(HermesConfig.defaults()));
        assertTrue(FirstRunWizard.submitProfileSync(
                sessionOkApi, false, "alex", () -> sessionRan.set(true))
                .get(5, TimeUnit.SECONDS));
        assertTrue("Session-reuse sync work must execute", sessionRan.get());
        assertFalse("Session API reuse must not be revoked on success", sessionOkApi.isRevoked());

        // Detached sync always revokes client on completion (even on error).
        HermesApi detachedApi = new HermesApi(
                "http://127.0.0.1:1", "tok", HermesApi.Limits.from(HermesConfig.defaults()));
        assertFalse(FirstRunWizard.submitProfileSync(detachedApi, true, "alex", "Alex", "en")
                .get(5, TimeUnit.SECONDS));
        assertTrue("Detached sync client must be revoked in finally", detachedApi.isRevoked());

        // Non-detached session reuse must NOT revoke the caller's live session API on error.
        HermesApi sessionApi = new HermesApi(
                "http://127.0.0.1:1", "tok", HermesApi.Limits.from(HermesConfig.defaults()));
        assertFalse(FirstRunWizard.submitProfileSync(sessionApi, false, "alex", "Alex", "en")
                .get(5, TimeUnit.SECONDS));
        assertFalse("Session API reuse must not be revoked by FirstRunWizard", sessionApi.isRevoked());

        // In-flight detached sync is deterministically revoked by revokePendingSyncs() while blocked.
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HermesApi inFlight = new HermesApi(
                "http://127.0.0.1:1", "tok", HermesApi.Limits.from(HermesConfig.defaults()));
        Future<Boolean> future = FirstRunWizard.submitProfileSync(
                inFlight, true, "alex", () -> {
                    started.countDown();
                    release.await(5, TimeUnit.SECONDS);
                });
        try {
            assertTrue("Worker must enter sync block before revocation",
                    started.await(5, TimeUnit.SECONDS));
            assertFalse("Client must remain live until revoked", inFlight.isRevoked());
            FirstRunWizard.revokePendingSyncs();
            assertTrue("revokePendingSyncs() must revoke in-flight client while worker is blocked",
                    inFlight.isRevoked());
        } finally {
            release.countDown();
        }
        assertFalse(future.get(5, TimeUnit.SECONDS));

        // Null work fails closed cleanly.
        assertFalse(FirstRunWizard.submitProfileSync(sessionApi, true, "alex", (FirstRunWizard.ThrowingRunnable) null)
                .get(5, TimeUnit.SECONDS));
    }

    @Test public void isCompletedLogsDiagnosticWarningWhenAccountReadFails() {
        // Closed account throws IllegalStateException from account.read(...)
        Account closedAccount = new Account("p1", "Personal", "https://hermes.example.com", "secret",
                null, new byte[Account.KEY_BYTES]);
        closedAccount.close();
        assertTrue(FirstRunWizard.isCompleted(closedAccount));
        assertTrue(Diagnostics.exportRedacted().contains("onboarding.is_completed_read_failed"));
        assertTrue(FirstRunWizard.isCompleted(null));
    }

    @Test public void step2SessionGuardsPostCloseAndPostDismissClicksAndNotifiesFinishedOnce() {
        boolean[] canAct = {true};
        int[] skipped = {0};
        int[] applied = {0};
        int[] staged = {0};
        int[] dismissed = {0};
        int[] cleared = {0};
        int[] finished = {0};

        // 1. Post-close clicks on Skip, Finish, and starter chips are complete no-ops.
        FirstRunWizard.Step2Session afterClose = new FirstRunWizard.Step2Session(
                () -> canAct[0], () -> dismissed[0]++, () -> cleared[0]++, () -> finished[0]++);
        afterClose.close();
        assertTrue(afterClose.isClosed());
        assertEquals(1, dismissed[0]);
        afterClose.onSkip(() -> skipped[0]++);
        afterClose.onFinish(() -> applied[0]++);
        afterClose.onStarter(() -> applied[0]++, () -> staged[0]++);
        assertEquals(0, skipped[0]);
        assertEquals(0, applied[0]);
        assertEquals(0, staged[0]);
        assertEquals(0, finished[0]);

        // 2. canAct=false blocks all actions without closing or firing callbacks.
        canAct[0] = false;
        FirstRunWizard.Step2Session blocked = new FirstRunWizard.Step2Session(
                () -> canAct[0], () -> dismissed[0]++, () -> cleared[0]++, () -> finished[0]++);
        blocked.onSkip(() -> skipped[0]++);
        blocked.onFinish(() -> applied[0]++);
        blocked.onStarter(() -> applied[0]++, () -> staged[0]++);
        assertFalse(blocked.isClosed());
        assertEquals(0, skipped[0]);
        assertEquals(0, applied[0]);
        assertEquals(0, staged[0]);
        assertEquals(0, finished[0]);

        // 3. Back/outside-tap dismiss marks closed, clears holder, notifies onFinished once, and blocks later clicks.
        canAct[0] = true;
        FirstRunWizard.Step2Session backDismissed = new FirstRunWizard.Step2Session(
                () -> canAct[0], () -> dismissed[0]++, () -> cleared[0]++, () -> finished[0]++);
        backDismissed.onDismiss();
        assertTrue(backDismissed.isClosed());
        assertEquals(1, cleared[0]);
        assertEquals(1, finished[0]);
        backDismissed.onSkip(() -> skipped[0]++);
        backDismissed.onFinish(() -> applied[0]++);
        backDismissed.onStarter(() -> applied[0]++, () -> staged[0]++);
        backDismissed.onDismiss();
        assertEquals(0, skipped[0]);
        assertEquals(0, applied[0]);
        assertEquals(0, staged[0]);
        assertEquals(1, finished[0]);

        // 4. Starter chip applies prefs, dismisses dialog, stages draft, and notifies onFinished once even after onDismiss().
        int[] chipFinished = {0};
        FirstRunWizard.Step2Session starterFlow = new FirstRunWizard.Step2Session(
                () -> true, () -> dismissed[0]++, () -> cleared[0]++, () -> chipFinished[0]++);
        starterFlow.onStarter(() -> applied[0]++, () -> staged[0]++);
        starterFlow.onDismiss();
        starterFlow.onFinish(() -> applied[0]++);
        assertEquals(1, applied[0]);
        assertEquals(1, staged[0]);
        assertEquals(1, chipFinished[0]);

        // 5. Skip and Finish each run once and do not double-fire onFinished when onDismiss() follows.
        int[] skipFinished = {0};
        FirstRunWizard.Step2Session skipFlow = new FirstRunWizard.Step2Session(
                () -> true, () -> {}, () -> {}, () -> skipFinished[0]++);
        skipFlow.onSkip(() -> skipped[0]++);
        skipFlow.onDismiss();
        skipFlow.onSkip(() -> skipped[0]++);
        assertEquals(1, skipped[0]);
        assertEquals(1, skipFinished[0]);

        int[] finishCount = {0};
        int[] finishApplied = {0};
        FirstRunWizard.Step2Session finishFlow = new FirstRunWizard.Step2Session(
                () -> true, () -> {}, () -> {}, () -> finishCount[0]++);
        finishFlow.onFinish(() -> finishApplied[0]++);
        finishFlow.onDismiss();
        finishFlow.onFinish(() -> finishApplied[0]++);
        assertEquals(1, finishApplied[0]);
        assertEquals(1, finishCount[0]);
    }
}
