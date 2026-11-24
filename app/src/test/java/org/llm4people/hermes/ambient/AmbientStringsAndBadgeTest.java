package org.llm4people.hermes.ambient;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

/**
 * Pure-JVM regression tests for AmbientAccessPanel status-badge state classification and
 * FR/EN ambient_strings.xml parity + plain-prose onboarding copy.
 */
public final class AmbientStringsAndBadgeTest {

    @Test
    public void resolveStatusBadgeCoversOffActiveAndWaitingStates() {
        // Neither listener feature enabled -> OFF regardless of OS listener state.
        assertEquals(AmbientAccessPanel.StatusBadgeState.OFF,
                AmbientAccessPanel.resolveStatusBadge(false, false, false));
        assertEquals(AmbientAccessPanel.StatusBadgeState.OFF,
                AmbientAccessPanel.resolveStatusBadge(false, false, true));

        // Any listener feature enabled + listener connected -> ACTIVE.
        assertEquals(AmbientAccessPanel.StatusBadgeState.ACTIVE,
                AmbientAccessPanel.resolveStatusBadge(true, false, true));
        assertEquals(AmbientAccessPanel.StatusBadgeState.ACTIVE,
                AmbientAccessPanel.resolveStatusBadge(false, true, true));
        assertEquals(AmbientAccessPanel.StatusBadgeState.ACTIVE,
                AmbientAccessPanel.resolveStatusBadge(true, true, true));

        // Any listener feature enabled + listener disconnected -> WAITING.
        assertEquals(AmbientAccessPanel.StatusBadgeState.WAITING,
                AmbientAccessPanel.resolveStatusBadge(true, false, false));
        assertEquals(AmbientAccessPanel.StatusBadgeState.WAITING,
                AmbientAccessPanel.resolveStatusBadge(false, true, false));
        assertEquals(AmbientAccessPanel.StatusBadgeState.WAITING,
                AmbientAccessPanel.resolveStatusBadge(true, true, false));
    }

    @Test
    public void ambientStringsHaveExactFrenchEnglishParityAndPlainProseOnboarding() throws Exception {
        Path base = Path.of("src/main/res");
        if (!Files.isDirectory(base)) {
            base = Path.of("app/src/main/res");
        }
        Map<String, String> fr = loadStrings(base.resolve("values/ambient_strings.xml"));
        Map<String, String> en = loadStrings(base.resolve("values-en/ambient_strings.xml"));
        assertFalse(fr.isEmpty());
        assertEquals("FR and EN ambient_strings.xml must maintain 1:1 key parity", fr.keySet(), en.keySet());

        // Plain prose only: no Unicode symbol glyphs in ambient_step_restricted.
        String frStep = fr.get("ambient_step_restricted");
        String enStep = en.get("ambient_step_restricted");
        assertNotNull(frStep);
        assertNotNull(enStep);
        assertFalse(frStep.contains("\u22EE") || frStep.contains("\u2192"));
        assertFalse(enStep.contains("\u22EE") || enStep.contains("\u2192"));

        // Loading state copy exists in both locales.
        assertNotNull(fr.get("ambient_allowlist_loading"));
        assertNotNull(en.get("ambient_allowlist_loading"));
        assertFalse(fr.get("ambient_allowlist_loading").trim().isEmpty());
        assertFalse(en.get("ambient_allowlist_loading").trim().isEmpty());
    }

    private static Map<String, String> loadStrings(Path xmlPath) throws Exception {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xmlPath.toFile());
        NodeList nodes = doc.getElementsByTagName("string");
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            String name = el.getAttribute("name");
            assertFalse("Empty string name in " + xmlPath, name.isEmpty());
            assertFalse("Duplicate string key " + name + " in " + xmlPath, out.containsKey(name));
            out.put(name, el.getTextContent());
        }
        return out;
    }
}
