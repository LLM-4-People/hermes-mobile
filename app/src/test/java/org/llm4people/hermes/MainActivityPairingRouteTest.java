package org.llm4people.hermes;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pure-JVM regression tests for warm {@code hermes://pair} deep-link routing across locked and
 * unlocked states (A3.1 / A6.1) and {@code MainActivity} singleTask launchMode manifest contract.
 */
public final class MainActivityPairingRouteTest {

    @Test
    public void resolvePairingDeepLinkRouteCoversAllLockedAndUnlockedCombinations() {
        // Without a pending pairing link, routing is always NONE regardless of state.
        assertEquals(MainActivity.PairingDeepLinkRoute.NONE,
                MainActivity.resolvePairingDeepLinkRoute(false, false, false));
        assertEquals(MainActivity.PairingDeepLinkRoute.NONE,
                MainActivity.resolvePairingDeepLinkRoute(false, false, true));
        assertEquals(MainActivity.PairingDeepLinkRoute.NONE,
                MainActivity.resolvePairingDeepLinkRoute(false, true, false));
        assertEquals(MainActivity.PairingDeepLinkRoute.NONE,
                MainActivity.resolvePairingDeepLinkRoute(false, true, true));

        // Locked + active ProfileGate handler delivers directly to the setup screen.
        assertEquals(MainActivity.PairingDeepLinkRoute.DELIVER_TO_LOCKED_HANDLER,
                MainActivity.resolvePairingDeepLinkRoute(true, false, true));

        // Locked without handler opens the setup gate cleanly.
        assertEquals(MainActivity.PairingDeepLinkRoute.OPEN_SETUP_GATE,
                MainActivity.resolvePairingDeepLinkRoute(true, false, false));

        // Unlocked workspace MUST lock & reopen the setup gate for explicit user confirmation,
        // even if a stale pairingLinkHandler reference were still non-null (defense in depth).
        assertEquals(MainActivity.PairingDeepLinkRoute.LOCK_AND_UNLOCK_FOR_CONFIRMATION,
                MainActivity.resolvePairingDeepLinkRoute(true, true, false));
        assertEquals(MainActivity.PairingDeepLinkRoute.LOCK_AND_UNLOCK_FOR_CONFIRMATION,
                MainActivity.resolvePairingDeepLinkRoute(true, true, true));
    }

    @Test
    public void manifestDeclaresSingleTaskOnMainActivityAndPreservesLocationPrivacyInvariant() throws Exception {
        Path manifest = Path.of("src/main/AndroidManifest.xml");
        if (!Files.isRegularFile(manifest)) {
            manifest = Path.of("app/src/main/AndroidManifest.xml");
        }
        String raw = new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8);
        assertFalse("ACCESS_FINE_LOCATION must never be declared", raw.contains("ACCESS_FINE_LOCATION"));
        assertFalse("ACCESS_BACKGROUND_LOCATION must never be declared", raw.contains("ACCESS_BACKGROUND_LOCATION"));

        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifest.toFile());
        NodeList activities = doc.getElementsByTagName("activity");
        boolean foundMain = false;
        for (int i = 0; i < activities.getLength(); i++) {
            Element el = (Element) activities.item(i);
            if (".MainActivity".equals(el.getAttribute("android:name"))) {
                foundMain = true;
                assertEquals("MainActivity must use singleTask so external hermes://pair deep links route via onNewIntent",
                        "singleTask", el.getAttribute("android:launchMode"));
            }
        }
        assertTrue("Expected .MainActivity in AndroidManifest.xml", foundMain);
    }
}
