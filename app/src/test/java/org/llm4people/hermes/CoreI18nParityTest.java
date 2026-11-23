package org.llm4people.hermes;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.*;

/** Verifies exact FR/EN parity, placeholder alignment, and zero hardcoded French UI literals across Track 30A packages. */
public final class CoreI18nParityTest {
    private static final Pattern FORMAT_SPEC = Pattern.compile("%(\\d+\\$)?[\\-#+ 0,(]*\\d*(?:\\.\\d+)?[sdfoxegcb]");
    private static final Pattern STRING_LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern FRENCH_ACCENT = Pattern.compile("[àâäçéèêëîïôöùûüÿœæÀÂÄÇÉÈÊËÎÏÔÖÙÛÜŸŒÆ«»]");
    private static final Set<String> EXCLUDED_FILES = Set.of(
            "ThreadViews.java",
            "ThreadStarters.java",
            "BiometricVault.java",
            "LockGracePolicy.java");

    private static Path resolveAppRoot() {
        File cwd = new File("").getAbsoluteFile();
        if (new File(cwd, "src/main/res").isDirectory()) return cwd.toPath();
        if (new File(cwd, "app/src/main/res").isDirectory()) return cwd.toPath().resolve("app");
        throw new AssertionError("Cannot locate app module root from " + cwd);
    }

    private static Map<String, String> parseResources(Path xmlPath) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        Document doc = factory.newDocumentBuilder().parse(xmlPath.toFile());
        Map<String, String> entries = new LinkedHashMap<>();
        NodeList strings = doc.getElementsByTagName("string");
        for (int i = 0; i < strings.getLength(); i++) {
            Element el = (Element) strings.item(i);
            entries.put("string:" + el.getAttribute("name"), el.getTextContent());
        }
        NodeList plurals = doc.getElementsByTagName("plurals");
        for (int i = 0; i < plurals.getLength(); i++) {
            Element el = (Element) plurals.item(i);
            String name = el.getAttribute("name");
            NodeList items = el.getElementsByTagName("item");
            for (int j = 0; j < items.getLength(); j++) {
                Element item = (Element) items.item(j);
                entries.put("plurals:" + name + ":" + item.getAttribute("quantity"), item.getTextContent());
            }
        }
        return entries;
    }

    private static List<String> placeholders(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = FORMAT_SPEC.matcher(text);
        while (m.find()) out.add(m.group());
        return out;
    }

    @Test
    public void coreI18nXmlHasExactFrenchEnglishParityAndMatchingPlaceholders() throws Exception {
        Path res = resolveAppRoot().resolve("src/main/res");
        Map<String, String> fr = parseResources(res.resolve("values/i18n_core_strings.xml"));
        Map<String, String> en = parseResources(res.resolve("values-en/i18n_core_strings.xml"));

        assertFalse("Expected non-empty FR string catalog", fr.isEmpty());
        assertEquals("FR and EN key sets must match 1:1", fr.keySet(), en.keySet());
        for (String key : fr.keySet()) {
            String frVal = fr.get(key);
            String enVal = en.get(key);
            assertFalse("Empty FR value for " + key, frVal == null || frVal.trim().isEmpty());
            assertFalse("Empty EN value for " + key, enVal == null || enVal.trim().isEmpty());
            assertEquals("Format placeholders mismatch for " + key, placeholders(frVal), placeholders(enVal));
        }
    }

    @Test
    public void ownedCorePackagesContainZeroHardcodedFrenchStringLiterals() throws Exception {
        Path javaRoot = resolveAppRoot().resolve("src/main/java/org/llm4people/hermes");
        List<String> violations = new ArrayList<>();
        for (String pkg : new String[]{"os", "files", "places", "push", "share", "thread"}) {
            Path dir = javaRoot.resolve(pkg);
            try (var stream = Files.list(dir)) {
                for (Path file : stream.toList()) {
                    String name = file.getFileName().toString();
                    if (!name.endsWith(".java") || EXCLUDED_FILES.contains(name)) continue;
                    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    boolean inBlock = false;
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i);
                        if (inBlock) {
                            int end = line.indexOf("*/");
                            if (end >= 0) { line = line.substring(end + 2); inBlock = false; }
                            else continue;
                        }
                        while (line.contains("/*")) {
                            int start = line.indexOf("/*");
                            int end = line.indexOf("*/", start + 2);
                            if (end >= 0) line = line.substring(0, start) + line.substring(end + 2);
                            else { line = line.substring(0, start); inBlock = true; break; }
                        }
                        int slash = line.indexOf("//");
                        if (slash >= 0) line = line.substring(0, slash);
                        Matcher m = STRING_LITERAL.matcher(line);
                        while (m.find()) {
                            String lit = m.group(1);
                            if (FRENCH_ACCENT.matcher(lit).find()) {
                                violations.add(pkg + "/" + name + ":" + (i + 1) + " -> \"" + lit + "\"");
                            }
                        }
                    }
                }
            }
        }
        assertTrue("Hardcoded French literals remain:\n" + String.join("\n", violations), violations.isEmpty());
    }
}
