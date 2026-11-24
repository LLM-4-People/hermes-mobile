package org.llm4people.hermes;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class AppLanguageTest {
    private static final Pattern PLACEHOLDER = Pattern.compile("%[0-9]+\\$[sd]");
    private static final Pattern AI_JARGON = Pattern.compile(
            "\\b(json|sse|prompt|endpoint|token|tokens|toolset|toolsets|cron|yaml|ttft|tps)\\b",
            Pattern.CASE_INSENSITIVE);

    @Test
    public void autoDetectsFrenchAndEnglishFromDeviceLocalePriorityList() {
        assertEquals("fr", AppLanguage.resolveTag(AppLanguage.Mode.AUTO, Collections.singletonList("fr-CA")));
        assertEquals("fr", AppLanguage.resolveTag(AppLanguage.Mode.AUTO, Collections.singletonList("fr-FR")));
        assertEquals("en", AppLanguage.resolveTag(AppLanguage.Mode.AUTO, Collections.singletonList("en-US")));
        assertEquals("en", AppLanguage.resolveTag(AppLanguage.Mode.AUTO, Collections.singletonList("en-GB")));
        assertEquals("en", AppLanguage.resolveTag(AppLanguage.Mode.AUTO, Arrays.asList("es-ES", "en-CA")));
        assertEquals("fr", AppLanguage.resolveTag(AppLanguage.Mode.AUTO, Arrays.asList("de-DE", "fr-CH")));
        // Unsupported locales fall back cleanly to French (matching default res/values/).
        assertEquals("fr", AppLanguage.resolveTag(AppLanguage.Mode.AUTO, Collections.singletonList("es-ES")));
        assertEquals("fr", AppLanguage.resolveTag(AppLanguage.Mode.AUTO, Collections.emptyList()));
        assertEquals("fr", AppLanguage.resolveTag(AppLanguage.Mode.AUTO, null));
    }

    @Test
    public void manualOverridesBeatDeviceLocaleAndNormalizeWireValues() {
        assertEquals("fr", AppLanguage.resolveTag(AppLanguage.Mode.FRENCH, Collections.singletonList("en-US")));
        assertEquals("en", AppLanguage.resolveTag(AppLanguage.Mode.ENGLISH, Collections.singletonList("fr-CA")));

        assertEquals(AppLanguage.Mode.AUTO, AppLanguage.Mode.fromWire(null));
        assertEquals(AppLanguage.Mode.AUTO, AppLanguage.Mode.fromWire("auto"));
        assertEquals(AppLanguage.Mode.AUTO, AppLanguage.Mode.fromWire("es-ES"));
        assertEquals(AppLanguage.Mode.FRENCH, AppLanguage.Mode.fromWire("fr"));
        assertEquals(AppLanguage.Mode.FRENCH, AppLanguage.Mode.fromWire("FR-CA"));
        assertEquals(AppLanguage.Mode.ENGLISH, AppLanguage.Mode.fromWire("en"));
        assertEquals(AppLanguage.Mode.ENGLISH, AppLanguage.Mode.fromWire("en_GB"));
    }

    @Test
    public void preservesRegionalLocaleFormatsAcceptLanguageAndResolvesVoiceTags() {
        Locale frCa = AppLanguage.resolveLocale(AppLanguage.Mode.AUTO, Collections.singletonList("fr-CA"));
        assertEquals("fr-CA", frCa.toLanguageTag());
        assertEquals("fr-CA, fr;q=0.9", AppLanguage.formatAcceptLanguage(frCa));
        assertEquals("fr-CA", AppLanguage.resolvedVoiceTag(AppLanguage.Mode.AUTO, Collections.singletonList("fr-CA")));

        Locale enGb = AppLanguage.resolveLocale(AppLanguage.Mode.AUTO, Collections.singletonList("en-GB"));
        assertEquals("en-GB", enGb.toLanguageTag());
        assertEquals("en-GB, en;q=0.9", AppLanguage.formatAcceptLanguage(enGb));
        assertEquals("en-GB", AppLanguage.resolvedVoiceTag(AppLanguage.Mode.AUTO, Collections.singletonList("en-GB")));

        assertEquals("fr", AppLanguage.formatAcceptLanguage(Locale.FRENCH));
        assertEquals("en", AppLanguage.formatAcceptLanguage(Locale.ENGLISH));
        assertEquals("fr-FR", AppLanguage.resolvedVoiceTag(AppLanguage.Mode.FRENCH, Collections.singletonList("en-US")));
        assertEquals("en-US", AppLanguage.resolvedVoiceTag(AppLanguage.Mode.ENGLISH, Collections.singletonList("fr-CA")));
    }

    @Test
    public void englishAndFrenchStringResourcesHaveFullKeyPlaceholderParityAndZeroAiJargon() throws Exception {
        File resRoot = locateResRoot();
        File defaultValues = new File(resRoot, "values");
        File englishValues = new File(resRoot, "values-en");
        assertTrue("Missing res/values", defaultValues.isDirectory());
        assertTrue("Missing res/values-en", englishValues.isDirectory());

        Map<String, String> frStrings = new LinkedHashMap<>();
        Map<String, String> enStrings = new LinkedHashMap<>();
        Set<String> frPlurals = new TreeSet<>();
        Set<String> enPlurals = new TreeSet<>();

        readStrings(defaultValues, frStrings, frPlurals);
        readStrings(englishValues, enStrings, enPlurals);

        assertFalse("Expected French strings", frStrings.isEmpty());
        for (Map.Entry<String, String> entry : frStrings.entrySet()) {
            String key = entry.getKey();
            assertTrue("Missing English translation for string: " + key, enStrings.containsKey(key));
            String enValue = enStrings.get(key);
            assertFalse("Empty English translation for string: " + key, enValue.trim().isEmpty());
            assertEquals("Format placeholder mismatch for string: " + key,
                    placeholders(entry.getValue()), placeholders(enValue));
            assertFalse("AI jargon found in English string " + key + ": " + enValue,
                    AI_JARGON.matcher(enValue).find());
        }
        assertEquals("English and French plural keys must match", frPlurals, enPlurals);
        assertEquals("Extra untranslated keys in values-en", frStrings.keySet(), enStrings.keySet());
    }

    @Test
    public void syncToServerAsyncRevokesClientsOnCompletionAndOnRevokePendingSyncs() throws Exception {
        Diagnostics.clear();
        Diagnostics.setSink((level, tag, msg) -> {});
        try {
            // Null API returns false cleanly.
            assertFalse(AppLanguage.syncToServerAsync(null, AppLanguage.Mode.FRENCH)
                    .get(5, java.util.concurrent.TimeUnit.SECONDS));

            // Pre-revoked API does not touch the server.
            java.util.concurrent.atomic.AtomicInteger hits = new java.util.concurrent.atomic.AtomicInteger();
            try (java.net.ServerSocket server = new java.net.ServerSocket(
                    0, 4, java.net.InetAddress.getByName("127.0.0.1"))) {
                java.util.concurrent.CountDownLatch holdOpen = new java.util.concurrent.CountDownLatch(1);
                java.util.concurrent.CountDownLatch clientConnected = new java.util.concurrent.CountDownLatch(1);
                Thread responder = new Thread(() -> {
                    while (!server.isClosed()) {
                        try (java.net.Socket s = server.accept()) {
                            java.io.BufferedReader reader = new java.io.BufferedReader(
                                    new java.io.InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
                            String headerLine;
                            while ((headerLine = reader.readLine()) != null && !headerLine.isEmpty()) {
                                // Drain HTTP request line and headers before responding or releasing latch.
                            }
                            int n = hits.incrementAndGet();
                            if (n == 1) {
                                // First real request: immediate empty 503 response.
                                java.io.OutputStream out = s.getOutputStream();
                                out.write("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                        .getBytes(StandardCharsets.US_ASCII));
                                out.flush();
                            } else {
                                // Second request: hold socket open until revoked.
                                clientConnected.countDown();
                                holdOpen.await(5, java.util.concurrent.TimeUnit.SECONDS);
                            }
                        } catch (Exception ignored) {
                            return;
                        }
                    }
                }, "test-lang-sync-server");
                responder.setDaemon(true);
                responder.start();

                String endpoint = "http://127.0.0.1:" + server.getLocalPort();
                org.llm4people.hermes.net.HermesApi.Limits limits =
                        org.llm4people.hermes.net.HermesApi.Limits.from(HermesConfig.defaults());

                org.llm4people.hermes.net.HermesApi preRevoked =
                        new org.llm4people.hermes.net.HermesApi(endpoint, "tok", limits);
                preRevoked.revoke();
                assertFalse(AppLanguage.syncToServerAsync(preRevoked, AppLanguage.Mode.ENGLISH)
                        .get(5, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(0, hits.get());

                // Completed detached sync revokes its HermesApi in finally.
                org.llm4people.hermes.net.HermesApi completed =
                        new org.llm4people.hermes.net.HermesApi(endpoint, "tok", limits);
                assertFalse(AppLanguage.syncToServerAsync(completed, AppLanguage.Mode.ENGLISH)
                        .get(5, java.util.concurrent.TimeUnit.SECONDS));
                assertTrue("Completed sync API must be revoked in finally", completed.isRevoked());
                assertEquals(1, hits.get());

                // In-flight detached sync is revoked immediately by revokePendingSyncs().
                org.llm4people.hermes.net.HermesApi inFlight =
                        new org.llm4people.hermes.net.HermesApi(endpoint, "tok", limits);
                java.util.concurrent.Future<Boolean> future =
                        AppLanguage.syncToServerAsync(inFlight, AppLanguage.Mode.FRENCH);
                assertTrue(clientConnected.await(5, java.util.concurrent.TimeUnit.SECONDS));
                AppLanguage.revokePendingSyncs();
                assertTrue("revokePendingSyncs() must revoke active sync API", inFlight.isRevoked());
                holdOpen.countDown();
                assertFalse(future.get(5, java.util.concurrent.TimeUnit.SECONDS));
            }
        } finally {
            AppLanguage.revokePendingSyncs();
            Diagnostics.clear();
            Diagnostics.setSink(null);
        }
    }

    @Test
    public void refreshRuntimeUpdatesModeAutoAcceptLanguageAndLifecycleHooksExist() throws Exception {
        String previousRaw = AppLanguage.rawActiveAcceptLanguageForTest();
        try {
            // Cold-start bootstrap from empty cache updates header without falsely reporting a change.
            AppLanguage.setActiveAcceptLanguageForTest("");
            assertFalse("Cold-start bootstrap must not report locale change",
                    AppLanguage.refreshRuntime(AppLanguage.Mode.AUTO, Collections.singletonList("en-CA")));
            assertEquals("en-CA, en;q=0.9", AppLanguage.currentAcceptLanguage());

            // Same effective language tag (en-CA -> en-US) is a no-op for OS surface refresh.
            assertFalse("Unchanged effective tag must return false",
                    AppLanguage.refreshRuntime(AppLanguage.Mode.AUTO, Collections.singletonList("en-US")));
            assertEquals("en-US, en;q=0.9", AppLanguage.currentAcceptLanguage());

            // Effective language switch (en -> fr) returns true so PushApplication refreshes widgets/shortcuts.
            assertTrue("Effective language switch en -> fr must return true",
                    AppLanguage.refreshRuntime(AppLanguage.Mode.AUTO, Collections.singletonList("fr-CA")));
            assertEquals("fr-CA, fr;q=0.9", AppLanguage.currentAcceptLanguage());

            // Unsupported system locale falls back to fr (same effective tag -> false).
            assertFalse("fr-CA -> es-ES fallback keeps fr tag",
                    AppLanguage.refreshRuntime(AppLanguage.Mode.AUTO, Collections.singletonList("es-ES")));
            assertEquals("fr", AppLanguage.currentAcceptLanguage());

            // Explicit mode overrides report change only when effective tag changes.
            assertTrue("fr -> ENGLISH override must return true",
                    AppLanguage.refreshRuntime(AppLanguage.Mode.ENGLISH, Collections.singletonList("fr-CA")));
            assertEquals("en", AppLanguage.currentAcceptLanguage());
            assertFalse("Repeated ENGLISH override must return false",
                    AppLanguage.refreshRuntime(AppLanguage.Mode.ENGLISH, Collections.singletonList("fr-CA")));

            assertTrue("en -> FRENCH override must return true",
                    AppLanguage.refreshRuntime(AppLanguage.Mode.FRENCH, Collections.singletonList("en-US")));
            assertEquals("fr", AppLanguage.currentAcceptLanguage());
        } finally {
            AppLanguage.setActiveAcceptLanguageForTest(previousRaw);
        }
        assertEquals("R16.8.3: raw activeAcceptLanguage must be restored without freezing Locale.getDefault()",
                previousRaw, AppLanguage.rawActiveAcceptLanguageForTest());

        // R16.1.1 & R16.1.2: localizedContext, PushApplication.onConfigurationChanged, widget & shortcut overloads.
        assertEquals(null, AppLanguage.localizedContext(null, AppLanguage.Mode.FRENCH));
        java.lang.reflect.Method localizedContext = AppLanguage.class
                .getMethod("localizedContext", android.content.Context.class, AppLanguage.Mode.class);
        assertEquals(android.content.Context.class, localizedContext.getReturnType());

        java.lang.reflect.Method onConfig = org.llm4people.hermes.push.PushApplication.class
                .getDeclaredMethod("onConfigurationChanged", android.content.res.Configuration.class);
        assertEquals(void.class, onConfig.getReturnType());

        java.lang.reflect.Method refresh = org.llm4people.hermes.os.HermesWidgetProvider.class
                .getMethod("refresh", android.content.Context.class);
        assertEquals(void.class, refresh.getReturnType());
        java.lang.reflect.Method refreshWithMode = org.llm4people.hermes.os.HermesWidgetProvider.class
                .getMethod("refresh", android.content.Context.class, AppLanguage.Mode.class);
        assertEquals(void.class, refreshWithMode.getReturnType());
        org.llm4people.hermes.os.HermesWidgetProvider.refresh(null);
        org.llm4people.hermes.os.HermesWidgetProvider.refresh(null, AppLanguage.Mode.ENGLISH);

        java.lang.reflect.Method publishWithMode = org.llm4people.hermes.os.HermesShortcuts.class
                .getMethod("publish", android.content.Context.class, AppLanguage.Mode.class);
        assertEquals(void.class, publishWithMode.getReturnType());
        org.llm4people.hermes.os.HermesShortcuts.publish(null, AppLanguage.Mode.ENGLISH);
    }

    private static File locateResRoot() {
        File direct = new File("src/main/res");
        if (direct.isDirectory()) return direct;
        return new File("app/src/main/res");
    }

    private static void readStrings(File dir, Map<String, String> strings, Set<String> plurals) throws Exception {
        File[] files = dir.listFiles((d, name) -> name.endsWith(".xml"));
        assertTrue("No XML files in " + dir, files != null && files.length > 0);
        Arrays.sort(files);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        for (File file : files) {
            byte[] raw = Files.readAllBytes(file.toPath());
            Document doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(raw));
            NodeList strNodes = doc.getElementsByTagName("string");
            for (int i = 0; i < strNodes.getLength(); i++) {
                Element el = (Element) strNodes.item(i);
                if ("false".equals(el.getAttribute("translatable"))) continue;
                String name = el.getAttribute("name");
                if (!name.isEmpty()) strings.put(name, el.getTextContent());
            }
            NodeList pluralNodes = doc.getElementsByTagName("plurals");
            for (int i = 0; i < pluralNodes.getLength(); i++) {
                Element el = (Element) pluralNodes.item(i);
                String name = el.getAttribute("name");
                if (!name.isEmpty()) plurals.add(name);
            }
        }
    }

    private static List<String> placeholders(String text) {
        List<String> out = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) out.add(matcher.group());
        Collections.sort(out);
        return out;
    }
}
