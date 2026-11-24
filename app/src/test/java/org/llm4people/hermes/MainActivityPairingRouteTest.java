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

    @Test
    public void clearCompetingWarmIntentsClearsAllCompetingSlotsAcrossEveryTransition() {
        class PendingSlots {
            boolean pairing = true;
            boolean delivery = true;
            boolean osEntry = true;
            boolean pinnedShortcut = true;
            boolean sharedText = true;
            boolean sharedMedia = true;

            void accept(MainActivity.WarmIntentKind kind) {
                MainActivity.clearCompetingWarmIntents(
                        kind,
                        () -> pairing = false,
                        () -> delivery = false,
                        () -> osEntry = false,
                        () -> pinnedShortcut = false,
                        () -> sharedText = false,
                        () -> sharedMedia = false);
            }
        }

        // text -> media: sharedText and all other pending intents cleared; sharedMedia preserved.
        PendingSlots textToMedia = new PendingSlots();
        textToMedia.accept(MainActivity.WarmIntentKind.SHARED_MEDIA);
        assertFalse(textToMedia.sharedText);
        assertTrue(textToMedia.sharedMedia);
        assertFalse(textToMedia.pairing);
        assertFalse(textToMedia.delivery);
        assertFalse(textToMedia.osEntry);
        assertFalse(textToMedia.pinnedShortcut);

        // media -> text: sharedMedia and all other pending intents cleared; sharedText preserved.
        PendingSlots mediaToText = new PendingSlots();
        mediaToText.accept(MainActivity.WarmIntentKind.SHARED_TEXT);
        assertTrue(mediaToText.sharedText);
        assertFalse(mediaToText.sharedMedia);
        assertFalse(mediaToText.pairing);
        assertFalse(mediaToText.delivery);
        assertFalse(mediaToText.osEntry);
        assertFalse(mediaToText.pinnedShortcut);

        // share/delivery -> pairing: all competing intents cleared; pairing preserved.
        PendingSlots toPairing = new PendingSlots();
        toPairing.accept(MainActivity.WarmIntentKind.PAIRING);
        assertTrue(toPairing.pairing);
        assertFalse(toPairing.delivery);
        assertFalse(toPairing.osEntry);
        assertFalse(toPairing.pinnedShortcut);
        assertFalse(toPairing.sharedText);
        assertFalse(toPairing.sharedMedia);

        // delivery/share -> widget/shortcut (OS_ENTRY and PINNED_SHORTCUT).
        PendingSlots toOsEntry = new PendingSlots();
        toOsEntry.accept(MainActivity.WarmIntentKind.OS_ENTRY);
        assertTrue(toOsEntry.osEntry);
        assertFalse(toOsEntry.pinnedShortcut);
        assertFalse(toOsEntry.delivery);
        assertFalse(toOsEntry.pairing);
        assertFalse(toOsEntry.sharedText);
        assertFalse(toOsEntry.sharedMedia);

        PendingSlots toPinned = new PendingSlots();
        toPinned.accept(MainActivity.WarmIntentKind.PINNED_SHORTCUT);
        assertTrue(toPinned.pinnedShortcut);
        assertFalse(toPinned.osEntry);
        assertFalse(toPinned.delivery);
        assertFalse(toPinned.pairing);
        assertFalse(toPinned.sharedText);
        assertFalse(toPinned.sharedMedia);

        // text/media/widget/pairing -> push: all competing intents cleared; delivery preserved.
        PendingSlots toPush = new PendingSlots();
        toPush.accept(MainActivity.WarmIntentKind.PUSH_DELIVERY);
        assertTrue(toPush.delivery);
        assertFalse(toPush.pairing);
        assertFalse(toPush.osEntry);
        assertFalse(toPush.pinnedShortcut);
        assertFalse(toPush.sharedText);
        assertFalse(toPush.sharedMedia);

        // Null callbacks are tolerated cleanly; null kind fails closed.
        MainActivity.clearCompetingWarmIntents(
                MainActivity.WarmIntentKind.PAIRING, null, null, null, null, null, null);
        org.junit.Assert.assertThrows(NullPointerException.class, () ->
                MainActivity.clearCompetingWarmIntents(null, null, null, null, null, null, null));
    }

    @Test
    public void qrPairingAcceptanceClearsAllCompetingWarmIntentSlotsAndLocksOnlyWhenUnlocked() {
        boolean[] pairing = {true};
        boolean[] delivery = {true};
        boolean[] osEntry = {true};
        boolean[] pinnedShortcut = {true};
        boolean[] sharedText = {true};
        boolean[] sharedMedia = {true};
        java.util.List<String> steps = new java.util.ArrayList<>();

        MainActivity.handleAcceptedQrPairing(
                () -> {
                    steps.add("clearCompeting");
                    MainActivity.clearCompetingWarmIntents(
                            MainActivity.WarmIntentKind.PAIRING,
                            () -> pairing[0] = false,
                            () -> delivery[0] = false,
                            () -> osEntry[0] = false,
                            () -> pinnedShortcut[0] = false,
                            () -> sharedText[0] = false,
                            () -> sharedMedia[0] = false);
                },
                () -> steps.add("resetIntent"),
                true,
                () -> steps.add("lock"),
                () -> steps.add("openAddWithPairing"));

        assertEquals(java.util.List.of("clearCompeting", "resetIntent", "lock", "openAddWithPairing"), steps);
        assertTrue(pairing[0]);
        assertFalse(delivery[0]);
        assertFalse(osEntry[0]);
        assertFalse(pinnedShortcut[0]);
        assertFalse(sharedText[0]);
        assertFalse(sharedMedia[0]);

        // When already locked, handleAcceptedQrPairing skips lock() while still clearing competing intents and resetting intent.
        java.util.List<String> lockedSteps = new java.util.ArrayList<>();
        MainActivity.handleAcceptedQrPairing(
                () -> lockedSteps.add("clearCompeting"),
                () -> lockedSteps.add("resetIntent"),
                false,
                () -> lockedSteps.add("lock"),
                () -> lockedSteps.add("openAddWithPairing"));
        assertEquals(java.util.List.of("clearCompeting", "resetIntent", "openAddWithPairing"), lockedSteps);
    }
}
