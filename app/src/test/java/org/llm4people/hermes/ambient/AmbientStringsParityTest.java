package org.llm4people.hermes.ambient;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class AmbientStringsParityTest {
    private static final Pattern FORMAT_SPEC = Pattern.compile("%\\d+\\$[sd]");
    private static final Pattern AI_JARGON = Pattern.compile("(?i)\\b(?:llm|prompt|tokens?|agentic|telemetry)\\b");

    @Test
    public void frenchAndEnglishAmbientStringsHaveExactParityAndZeroAiJargon() throws Exception {
        Path resDir = Path.of("src/main/res");
        if (!Files.isDirectory(resDir)) resDir = Path.of("app/src/main/res");
        Path frFile = resDir.resolve("values/ambient_strings.xml");
        Path enFile = resDir.resolve("values-en/ambient_strings.xml");
        assertTrue("Missing French ambient_strings.xml", Files.isRegularFile(frFile));
        assertTrue("Missing English ambient_strings.xml", Files.isRegularFile(enFile));

        Map<String, String> fr = loadStrings(frFile);
        Map<String, String> en = loadStrings(enFile);
        assertFalse(fr.isEmpty());
        assertEquals("FR and EN ambient_strings.xml keys must match exactly", fr.keySet(), en.keySet());

        for (String key : fr.keySet()) {
            String frVal = fr.get(key);
            String enVal = en.get(key);
            assertFalse("Empty FR value for " + key, frVal.isEmpty());
            assertFalse("Empty EN value for " + key, enVal.isEmpty());
            assertEquals("Format placeholder mismatch for " + key,
                    countPlaceholders(frVal), countPlaceholders(enVal));
            assertFalse("AI jargon in FR " + key + ": " + frVal,
                    AI_JARGON.matcher(frVal.toLowerCase(Locale.ROOT)).find());
            assertFalse("AI jargon in EN " + key + ": " + enVal,
                    AI_JARGON.matcher(enVal.toLowerCase(Locale.ROOT)).find());
        }
    }

    private static Map<String, String> loadStrings(Path path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document doc = factory.newDocumentBuilder().parse(path.toFile());
        NodeList nodes = doc.getElementsByTagName("string");
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            map.put(el.getAttribute("name"), el.getTextContent().trim());
        }
        return map;
    }

    private static int countPlaceholders(String value) {
        Matcher m = FORMAT_SPEC.matcher(value);
        int count = 0;
        while (m.find()) count++;
        return count;
    }
}
