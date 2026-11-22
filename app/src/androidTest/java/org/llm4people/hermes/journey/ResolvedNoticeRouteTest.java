package org.llm4people.hermes.journey;

import android.content.Context;
import android.test.InstrumentationTestCase;
import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.MainActivity;
import org.llm4people.hermes.artifact.ThreadInstruments;
import org.llm4people.hermes.net.Exchange;
import org.llm4people.hermes.profile.Account;
import org.llm4people.hermes.profile.WorkspaceAccountFixture;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercise the Activity entry point reached after a notification's opaque event ID is resolved. */
@SuppressWarnings("deprecation")
public class ResolvedNoticeRouteTest extends InstrumentationTestCase {
    public void testVerifiedRemoteClarificationKeepsBothFormsUntilExplicitReveal() throws Throwable { promptRoute(true); }
    public void testVerifiedRemoteApprovalKeepsBothFormsUntilExplicitReveal() throws Throwable { promptRoute(false); }

    private void promptRoute(boolean question) throws Throwable {
        Context app = getInstrumentation().getTargetContext();
        MainActivity activity = launch(app);
        String kind = question ? "question_requested" : "approval_requested";
        JSONObject event = event("intermediate", kind);
        JSONObject prompt = new JSONObject().put("prompt_id", "confirmation").put("execution_generation", 3)
                .put("kind", question ? "clarify" : "approval").put("question", "Quelle date ?")
                .put("description", "Consulter le dossier").put("choices", new JSONArray().put("Demain"));
        JSONArray rows = new JSONArray().put(ThreadNoticeContinuityTest.proposal(11, "Relire."))
                .put(ThreadNoticeContinuityTest.proposal(12, "Vérifier."));
        String before = ThreadNoticeContinuityTest.transcript("root", "root", "Relecture", rows);
        // Status and transcript GETs can race after admission. This authenticated fixture supplies
        // both projections, then holds the event response while the user inspects the pending control.
        String live = new JSONObject(ThreadNoticeContinuityTest.transcript("root", "intermediate", "Relecture", rows))
                .put("run_id", "remote").put("execution_generation", 3)
                .put("status", question ? "waiting_for_input" : "waiting_for_approval")
                .put("pending_controls", new JSONArray().put(prompt)).toString();
        CountDownLatch releaseEvents = new CountDownLatch(1);
        AtomicInteger requestCount = new AtomicInteger();
        try (Exchange gateway = new Exchange("200 OK", "Content-Type: application/json\r\n", () -> {
                    if (requestCount.incrementAndGet() == 6) await(releaseEvents);
                }, before, live, live, live, live, live);
             WorkspaceAccountFixture profile = new WorkspaceAccountFixture(app, gateway.endpoint())) {
            profile.account.write("last-thread", "root");
            ThreadInstruments store = new ThreadInstruments(profile.account, "root");
            store.pin(store.state(), store.proposals(rows).get(0), rows);
            runTestOnUiThread(() -> opened(activity, profile.account));
            JourneyUi ui = new JourneyUi(getInstrumentation(), activity, app.getCacheDir());
            ui.see("Comparer les versions");
            ui.fill("Message à Hermes", "Demain à midi");
            ui.tap("Comparer les versions");
            ui.tap("Version proposée");
            ui.fill("Fichier", "proposition.py");
            ui.tap("Version épinglée");
            ui.fill("Fichier", "origine.py");
            runTestOnUiThread(() -> activity.openPushEvent(profile.account, event));
            ui.see("Répondre à Hermes");
            assertEquals("origine.py", ui.fieldText("Fichier"));
            ui.tap("Version proposée");
            assertEquals("proposition.py", ui.fieldText("Fichier"));
            ui.tap("Répondre à Hermes");
            ui.see(question ? "Quelle date ?" : "Autoriser cette action ?");
            assertEquals("Demain à midi", ui.fieldText("Message à Hermes"));
            releaseEvents.countDown();
            String wire = gateway.observed();
            runTestOnUiThread(() -> activity.onAction("profiles"));
            assertTrue(wire.contains("/intermediate/messages?order=latest&limit=0"));
            assertTrue(wire.contains("/v1/runs/remote "));
            assertTrue(wire.contains("/v1/runs/remote/events"));
            assertTrue(wire.contains("Authorization: Bearer " + profile.account.bearer));
            assertFalse(wire.contains("POST "));
        } finally {
            releaseEvents.countDown();
            runTestOnUiThread(activity::finish);
        }
    }

    public void testResolvedNoticeFromDepartedAccountCannotSettleIntoTheNewProfile() throws Throwable {
        Context app = getInstrumentation().getTargetContext();
        MainActivity activity = launch(app);
        String first = ThreadNoticeContinuityTest.transcript("root", "root", "Premier profil", new JSONArray());
        String second = ThreadNoticeContinuityTest.transcript("root", "root", "Deuxième profil", new JSONArray());
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        try (Exchange oldGateway = new Exchange("200 OK", "", () -> {
                    if (requests.incrementAndGet() == 2) { entered.countDown(); await(release); }
                }, first, first);
             Exchange newGateway = new Exchange("200 OK", "", second);
             WorkspaceAccountFixture old = new WorkspaceAccountFixture(app, oldGateway.endpoint());
             WorkspaceAccountFixture next = new WorkspaceAccountFixture(app, newGateway.endpoint())) {
            // Each account has a discussion with the same root ID.
            old.account.write("last-thread", "root");
            next.account.write("last-thread", "root");
            runTestOnUiThread(() -> opened(activity, old.account));
            JourneyUi ui = new JourneyUi(getInstrumentation(), activity, app.getCacheDir());
            ui.see("Premier profil");
            ui.fill("Message à Hermes", "Privé au premier profil");
            JSONObject late = event("intermediate", "run_completed");
            runTestOnUiThread(() -> activity.openPushEvent(old.account, late));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            runTestOnUiThread(() -> { activity.onAction("profiles"); opened(activity, next.account); });
            ui.see("Deuxième profil");
            assertEquals("", ui.fieldText("Message à Hermes"));
            ui.fill("Message à Hermes", "Texte du deuxième profil");
            release.countDown();
            oldGateway.observed();
            runTestOnUiThread(() -> activity.openPushEvent(old.account, late));
            getInstrumentation().waitForIdleSync();
            ui.see("Deuxième profil");
            assertEquals("Texte du deuxième profil", ui.fieldText("Message à Hermes"));
            assertEquals(1, newGateway.observed().split("GET ", -1).length - 1);
        } finally {
            release.countDown();
            runTestOnUiThread(activity::finish);
        }
    }

    private MainActivity launch(Context app) {
        return (MainActivity)getInstrumentation().startActivitySync(
                app.getPackageManager().getLaunchIntentForPackage(app.getPackageName()));
    }

    private static JSONObject event(String session, String kind) throws Exception {
        return new JSONObject().put("session_id", session).put("run_id", "remote").put("kind", kind);
    }

    private static void opened(MainActivity activity, Account account) {
        try {
            Method unlock = MainActivity.class.getDeclaredMethod("opened", Account.class);
            unlock.setAccessible(true);
            unlock.invoke(activity, account);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static void await(CountDownLatch release) {
        try { release.await(45, TimeUnit.SECONDS); }
        catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
    }
}
