package org.llm4people.hermes.journey;

import android.content.Context;
import android.os.Build;
import android.test.InstrumentationTestCase;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.MainActivity;
import org.llm4people.hermes.net.Exchange;
import org.llm4people.hermes.profile.Account;
import org.llm4people.hermes.profile.WorkspaceAccountFixture;
import org.llm4people.hermes.thread.ThreadWorkspace;

@SuppressWarnings("deprecation")
public final class NearbyGuideJourneyTest extends InstrumentationTestCase {
    public void testHistoricalGuideWaitsForVerifiedTurnThenFiltersAndComparesWithoutSending() throws Throwable {
        if (Build.VERSION.SDK_INT < 29) return;
        Context context = getInstrumentation().getTargetContext();
        MainActivity activity = (MainActivity) getInstrumentation().startActivitySync(context.getPackageManager()
                .getLaunchIntentForPackage(context.getPackageName()));
        String page = "{\"version\":1,\"surface\":\"page/restaurants\",\"mode\":\"replace\",\"title\":\"À table\","
                + "\"blocks\":[{\"type\":\"nearby_places\",\"category\":\"restaurant\"}]}";
        JSONObject assistant = new JSONObject().put("role","assistant").put("id",12).put("session_id","root")
                .put("content","Voici le guide.\n```hermes-workspace\n" + page + "\n```");
        String transcript = ThreadNoticeContinuityTest.transcript("root", "root", "Restaurants", new JSONArray().put(assistant));
        JSONObject changed = new JSONObject(assistant.toString()).put("content", assistant.getString("content").replace("À table", "Autre page"));
        JSONObject area = new JSONObject().put("source","device").put("latitude",45.51)
                .put("longitude",-73.57).put("accuracy_m",3000).put("age_s",12);
        String data = """
                {"source":"OpenStreetMap","attribution":"© OpenStreetMap contributors","source_date":"2026-11-22T07:27:46Z",
                 "radius_m":1500,"places":[
                 {"name":"Yamu Yamu Sushi","osm_type":"node","osm_id":11280151110,"latitude":45.5105406,"longitude":-73.5725473,"distance_m":207,"cuisine":"sushi"},
                 {"name":"Mae Sri Thai","osm_type":"node","osm_id":6732094290,"latitude":45.5104122,"longitude":-73.5726866,"distance_m":214,"cuisine":"asian","address":"224 Milton Street"},
                 {"name":"Tiamo","osm_type":"node","osm_id":12194959181,"latitude":45.5083743,"longitude":-73.5716569,"distance_m":222,"cuisine":"italian"}]}
                """;
        try (Exchange server = new Exchange("200 OK", "Content-Type: application/json\r\n", transcript, data);
             WorkspaceAccountFixture fixture = new WorkspaceAccountFixture(context, server.endpoint())) {
            String instrumentKey = "thread.instrument.v1.root";
            android.content.SharedPreferences encrypted = context.getSharedPreferences(
                    "workspace.test." + fixture.account.id, Context.MODE_PRIVATE);
            fixture.account.write(instrumentKey, "{}");
            assertEquals(1, encrypted.getAll().size());
            String instrumentSlot = encrypted.getAll().keySet().iterator().next();
            fixture.account.remove(instrumentKey);
            fixture.account.write("last-thread", "root");
            Method open = MainActivity.class.getDeclaredMethod("opened", Account.class);
            open.setAccessible(true);
            Field workspace = MainActivity.class.getDeclaredField("threads");
            workspace.setAccessible(true);
            try {
                runTestOnUiThread(() -> {
                    try { open.invoke(activity, fixture.account); }
                    catch (ReflectiveOperationException error) { throw new AssertionError(error); }
                });
                JourneyUi ui = new JourneyUi(getInstrumentation(), activity, context.getCacheDir());
                ui.see("Utiliser ma zone approximative");
                ThreadWorkspace origin = (ThreadWorkspace) workspace.get(activity);
                runTestOnUiThread(() -> {
                    activity.nearbyGuide(null, "root", "run", assistant, area);
                    activity.nearbyGuide(origin, "other", "run", assistant, area);
                    activity.nearbyGuide(origin, "root", "run", changed, area);
                });
                ui.see("Utiliser ma zone approximative");
                runTestOnUiThread(() -> {
                    activity.nearbyGuide(origin, "root", "run", assistant, area);
                    activity.nearbyGuide(origin, "root", "run", assistant, area);
                });
                ui.see("Mae Sri Thai");
                ui.tap("Mae Sri Thai");
                ui.see("224 Milton Street");
                ui.tap("Comparer");
                ui.tap("Tiamo · 222 m");
                ui.see("Écart des distances au centre : 8 m");
                ui.tap("Cuisine");
                ui.tap("Asiatique");
                ui.contains("1 adresse sur 3");
                ui.see("Repère 2, Mae Sri Thai, environ 214 m du centre. Replier les détails");
                ui.absent("Écart des distances au centre : 8 m");
                ui.absent("Modifier la comparaison");
                // The persisted instrument can become unreadable between transcript hydration and run completion.
                assertTrue(encrypted.edit().putInt(instrumentSlot, 3).commit());
                runTestOnUiThread(() -> activity.nearbyGuide(origin, "root", "later-run", assistant, area));
                ui.see(activity.getString(org.llm4people.hermes.R.string.instrument_repair));
                assertEquals(3, encrypted.getInt(instrumentSlot, 0));
                String observed = server.observed();
                assertEquals(1, observed.split("POST /p/travail/api/mobile/places/nearby ",-1).length - 1);
                assertTrue(observed.contains("Authorization: Bearer fixture"));
                assertFalse(observed.contains("accuracy_m"));
                assertFalse(observed.contains("/v1/runs"));
            } finally { runTestOnUiThread(activity::finish); }
        }
    }
}
