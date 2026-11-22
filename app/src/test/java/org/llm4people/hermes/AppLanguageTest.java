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
