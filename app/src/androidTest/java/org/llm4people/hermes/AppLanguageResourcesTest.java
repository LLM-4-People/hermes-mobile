package org.llm4people.hermes;

import android.content.Context;
import android.content.res.Configuration;
import android.test.InstrumentationTestCase;

import org.json.JSONObject;
import org.llm4people.hermes.net.Exchange;
import org.llm4people.hermes.net.HermesApi;

import java.util.Locale;

@SuppressWarnings("deprecation")
public final class AppLanguageResourcesTest extends InstrumentationTestCase {
    public void testFrenchAndEnglishResourcesResolveCleanly() {
        Context base = getInstrumentation().getTargetContext();

        Context fr = localized(base, Locale.FRENCH);
        assertEquals("Atelier", fr.getString(R.string.ws_desk));
        assertEquals("Envoyer", fr.getString(R.string.thread_send));
        assertEquals("Compétences de Hermes", fr.getString(R.string.skills_title));
        assertEquals("Dicter", fr.getString(R.string.voice_dictate));
        assertEquals("Réglages de la discussion", fr.getString(R.string.panel_settings));
        assertEquals("Quelques idées pour commencer", fr.getString(R.string.starter_heading));
        assertEquals("12 caractères minimum", fr.getResources().getQuantityString(R.plurals.profile_passphrase_hint, 12, 12));

        Context en = localized(base, Locale.ENGLISH);
        assertEquals("Workspace", en.getString(R.string.ws_desk));
        assertEquals("Send", en.getString(R.string.thread_send));
        assertEquals("Hermes skills", en.getString(R.string.skills_title));
        assertEquals("Dictate", en.getString(R.string.voice_dictate));
        assertEquals("Discussion settings", en.getString(R.string.panel_settings));
        assertEquals("A few ideas to get started", en.getString(R.string.starter_heading));
        assertEquals("At least 12 characters", en.getResources().getQuantityString(R.plurals.profile_passphrase_hint, 12, 12));
    }

    public void testModeSwitchingUpdatesAcceptLanguageAndCapabilityGatedProfileSync() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        AppLanguage.Mode original = AppLanguage.readMode(context);
        try {
            AppLanguage.apply(context, AppLanguage.Mode.FRENCH);
            assertEquals(AppLanguage.Mode.FRENCH, AppLanguage.readMode(context));
            assertEquals("fr", AppLanguage.resolvedTag(context));
            assertTrue(AppLanguage.currentAcceptLanguage().startsWith("fr"));

            try (Exchange gateway = new Exchange("200 OK", "Content-Type: application/json\r\n",
                    "{\"features\":{\"profile_defaults\":true}}",
                    "{\"preferred_language\":\"fr\"}")) {
                HermesApi api = new HermesApi(gateway.endpoint(), "bearer-token");
                assertTrue(AppLanguage.syncToServer(api, AppLanguage.Mode.FRENCH));
                String wire = gateway.observed();
                assertTrue("Expected French Accept-Language header", wire.contains("Accept-Language: fr"));
                assertTrue("Expected PATCH /api/profile/defaults", wire.contains("PATCH /p/travail/api/profile/defaults"));
                assertTrue("Expected preferred_language=fr", wire.contains("\"preferred_language\":\"fr\""));
            }

            AppLanguage.apply(context, AppLanguage.Mode.ENGLISH);
            assertEquals(AppLanguage.Mode.ENGLISH, AppLanguage.readMode(context));
            assertEquals("en", AppLanguage.resolvedTag(context));
            assertTrue(AppLanguage.currentAcceptLanguage().startsWith("en"));

            try (Exchange unsupported = new Exchange("200 OK", "Content-Type: application/json\r\n",
                    "{\"features\":{\"profile_defaults\":false}}")) {
                HermesApi api = new HermesApi(unsupported.endpoint(), "bearer-token");
                assertFalse(AppLanguage.syncToServer(api, AppLanguage.Mode.ENGLISH));
                String wire = unsupported.observed();
                assertTrue("Expected English Accept-Language header", wire.contains("Accept-Language: en"));
                assertFalse("Must not PATCH when profile_defaults is unsupported", wire.contains("PATCH "));
            }

            AppLanguage.apply(context, AppLanguage.Mode.AUTO);
            assertEquals(AppLanguage.Mode.AUTO, AppLanguage.readMode(context));
            assertEquals("auto", AppLanguage.profileDefaultsPatch(AppLanguage.Mode.AUTO).getString("preferred_language"));
            assertTrue(AppLanguage.supportsProfileLanguageSync(
                    new JSONObject().put("features", new JSONObject().put("profile_defaults", true))));
            assertFalse(AppLanguage.supportsProfileLanguageSync(new JSONObject()));
        } finally {
            AppLanguage.apply(context, original);
        }
    }

    private static Context localized(Context base, Locale locale) {
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.setLocale(locale);
        return base.createConfigurationContext(config);
    }
}
