package org.llm4people.hermes.journey;

import android.content.Context;
import android.os.Build;
import android.test.InstrumentationTestCase;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.llm4people.hermes.MainActivity;
import org.llm4people.hermes.net.Exchange;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.panel.SessionPanel;
import org.llm4people.hermes.profile.Account;
import org.llm4people.hermes.profile.WorkspaceAccountFixture;

@SuppressWarnings("deprecation")
public final class SkillBrowserJourneyTest extends InstrumentationTestCase {
    private static final String JSON = "Content-Type: application/json\r\n";
    private static final String CAPS = "{\"features\":{}}";
    private static final String EMPTY = "{\"object\":\"list\",\"data\":[]}";
    private static final String CATALOG = """
            {"object":"list","data":[
              {"name":"travail/revue","description":"Vérifier les changements.","category":"Qualité"}]}
            """;

    public void testRefreshSearchAndSelectionStageOnlyIntoTheOriginalDraft() throws Throwable {
        if (Build.VERSION.SDK_INT < 29) return;
        Context app = getInstrumentation().getTargetContext();
        MainActivity activity = (MainActivity) getInstrumentation().startActivitySync(app.getPackageManager()
                .getLaunchIntentForPackage(app.getPackageName()));
        String transcript = ThreadNoticeContinuityTest.transcript("discussion", "discussion", "Relecture", new JSONArray());
        try (Exchange gateway = new Exchange("200 OK", JSON, transcript, CAPS, EMPTY, EMPTY, CATALOG);
             WorkspaceAccountFixture profile = new WorkspaceAccountFixture(app, gateway.endpoint())) {
            try {
                profile.account.write("last-thread", "discussion");
                String policy = "{\"allowed_toolsets\":[\"skills\"]}";
                profile.account.write("panel/options/discussion", policy);
                Method open = MainActivity.class.getDeclaredMethod("opened", Account.class);
                open.setAccessible(true);
                runTestOnUiThread(() -> {
                    try { open.invoke(activity, profile.account); }
                    catch (ReflectiveOperationException error) { throw new AssertionError(error); }
                });
                JourneyUi ui = new JourneyUi(getInstrumentation(), activity, app.getCacheDir());
                ui.see("Quelques idées pour commencer");
                ui.fill("Message à Hermes", "Mon brouillon");
                runTestOnUiThread(() -> activity.onAction("models"));
                ui.tap("Compétences de Hermes");
                ui.see("La passerelle ne liste aucune compétence pour ce profil.");
                ui.tap("Actualiser les compétences");
                ui.see("travail/revue");
                ui.fill("Rechercher une compétence", "absente");
                ui.see("Aucune compétence ne correspond à cette recherche.");
                ui.fill("Rechercher une compétence", "qualite");
                ui.tap("travail/revue");
                ui.contains("Catégorie · Qualité", "Vérifier les changements.");
                ui.tap("Préparer le brouillon");
                ui.see("Mon brouillon\nUtilise la compétence « travail/revue » pour cette demande.");
                assertEquals(policy, profile.account.read("panel/options/discussion"));
                assertEquals(2, gateway.observed().split("GET /p/travail/v1/skills HTTP/", -1).length - 1);
                assertFalse(gateway.observed().contains("POST "));
                assertTrue(gateway.observed().contains("Authorization: Bearer fixture"));
            } finally { runTestOnUiThread(activity::finish); }
        }
    }

    public void testChangedThreadDiscardsAnOpenSkillAndUnsupportedListingHasRecovery() throws Throwable {
        if (Build.VERSION.SDK_INT < 29) return;
        Context app = getInstrumentation().getTargetContext();
        MainActivity activity = (MainActivity) getInstrumentation().startActivitySync(app.getPackageManager()
                .getLaunchIntentForPackage(app.getPackageName()));
        AtomicBoolean active = new AtomicBoolean(true);
        AtomicReference<String> staged = new AtomicReference<>();
        JourneyUi ui = new JourneyUi(getInstrumentation(), activity, app.getCacheDir());
        try (Exchange gateway = new Exchange("200 OK", JSON, CAPS, EMPTY, CATALOG);
             Exchange missing = new Exchange("404 Not Found", JSON, "{}", "{}");
             WorkspaceAccountFixture profile = new WorkspaceAccountFixture(app)) {
            SessionPanel first = new SessionPanel(activity, new HermesApi(gateway.endpoint(), "test"), profile.account);
            SessionPanel second = new SessionPanel(activity, new HermesApi(missing.endpoint(), "test"), profile.account);
            try {
                runTestOnUiThread(() -> first.show("one", "one", active::get, staged::set));
                ui.tap("Compétences de Hermes");
                ui.tap("travail/revue");
                ui.see("Préparer le brouillon");
                active.set(false);
                ui.tap("Préparer le brouillon");
                assertNull(staged.get());
                runTestOnUiThread(() -> second.show("two", "two", () -> true, staged::set));
                ui.tap("Compétences de Hermes");
                ui.see("Cette passerelle ne propose pas la liste des compétences.");
                ui.see("Actualiser les compétences");
                assertNull(staged.get());
            } finally { runTestOnUiThread(() -> { first.close(); second.close(); activity.finish(); }); }
        }
    }
}
