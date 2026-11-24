package org.llm4people.hermes.os;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Pure-JVM regression tests for PermissionCenter SSoT state classification and FR/EN string parity. */
public final class PermissionCenterTest {

    @Test
    public void evaluateRuntimeStateCoversAllFiveStates() {
        assertEquals(PermissionCenter.State.UNAVAILABLE,
                PermissionCenter.evaluateRuntimeState(false, false, false, false));
        assertEquals(PermissionCenter.State.UNAVAILABLE,
                PermissionCenter.evaluateRuntimeState(false, true, true, true));
        assertEquals(PermissionCenter.State.GRANTED,
                PermissionCenter.evaluateRuntimeState(true, true, false, false));
        assertEquals(PermissionCenter.State.GRANTED,
                PermissionCenter.evaluateRuntimeState(true, true, true, false));
        assertEquals(PermissionCenter.State.NOT_REQUESTED,
                PermissionCenter.evaluateRuntimeState(true, false, false, false));
        assertEquals(PermissionCenter.State.NOT_REQUESTED,
                PermissionCenter.evaluateRuntimeState(true, false, false, true));
        assertEquals(PermissionCenter.State.DENIED,
                PermissionCenter.evaluateRuntimeState(true, false, true, true));
        assertEquals(PermissionCenter.State.PERMANENTLY_DENIED,
                PermissionCenter.evaluateRuntimeState(true, false, true, false));
    }

    @Test
    public void evaluateNotificationStateHandlesApi33PlusAndLegacyApis() {
        // API 33+: runtime permission + OS notification toggle
        assertEquals(PermissionCenter.State.GRANTED,
                PermissionCenter.evaluateNotificationState(33, true, true, true, false));
        assertEquals(PermissionCenter.State.PERMANENTLY_DENIED,
                PermissionCenter.evaluateNotificationState(36, false, true, true, false));
        assertEquals(PermissionCenter.State.NOT_REQUESTED,
                PermissionCenter.evaluateNotificationState(34, false, false, false, false));
        assertEquals(PermissionCenter.State.DENIED,
                PermissionCenter.evaluateNotificationState(34, false, false, true, true));
        assertEquals(PermissionCenter.State.PERMANENTLY_DENIED,
                PermissionCenter.evaluateNotificationState(34, false, false, true, false));

        // API 28-32: no POST_NOTIFICATIONS runtime permission, rely on OS toggle
        assertEquals(PermissionCenter.State.GRANTED,
                PermissionCenter.evaluateNotificationState(32, true, false, false, false));
        assertEquals(PermissionCenter.State.PERMANENTLY_DENIED,
                PermissionCenter.evaluateNotificationState(28, false, false, false, false));
    }

    @Test
    public void evaluateSpecialStateAndSettingsRedirect() {
        assertEquals(PermissionCenter.State.GRANTED, PermissionCenter.evaluateSpecialState(true, true));
        assertEquals(PermissionCenter.State.NOT_REQUESTED, PermissionCenter.evaluateSpecialState(true, false));
        assertEquals(PermissionCenter.State.UNAVAILABLE, PermissionCenter.evaluateSpecialState(false, true));

        assertTrue(PermissionCenter.requiresSettingsRedirect(
                PermissionCenter.Key.MICROPHONE, PermissionCenter.State.PERMANENTLY_DENIED));
        assertFalse(PermissionCenter.requiresSettingsRedirect(
                PermissionCenter.Key.MICROPHONE, PermissionCenter.State.DENIED));
        assertFalse(PermissionCenter.requiresSettingsRedirect(
                PermissionCenter.Key.MICROPHONE, PermissionCenter.State.NOT_REQUESTED));
        assertTrue(PermissionCenter.requiresSettingsRedirect(
                PermissionCenter.Key.NOTIFICATION_ACCESS, PermissionCenter.State.NOT_REQUESTED));
        assertTrue(PermissionCenter.requiresSettingsRedirect(
                PermissionCenter.Key.INSTALL_UPDATES, PermissionCenter.State.NOT_REQUESTED));
        assertFalse(PermissionCenter.requiresSettingsRedirect(null, PermissionCenter.State.DENIED));
        assertFalse(PermissionCenter.requiresSettingsRedirect(PermissionCenter.Key.CAMERA, null));
    }

    @Test
    public void requestCodesAreUniqueAndLookupRejectsInvalidCodes() {
        Set<Integer> seen = new HashSet<>();
        for (PermissionCenter.Key key : PermissionCenter.Key.values()) {
            if (key.specialAccess) {
                assertEquals(0, key.requestCode);
            } else {
                assertTrue("Expected positive request code for " + key, key.requestCode > 0);
                assertTrue("Duplicate request code for " + key, seen.add(key.requestCode));
                assertEquals(key, PermissionCenter.fromRequestCode(key.requestCode));
                assertTrue(PermissionCenter.isManagedRequestCode(key.requestCode));
            }
        }
        assertNull(PermissionCenter.fromRequestCode(0));
        assertNull(PermissionCenter.fromRequestCode(-1));
        assertNull(PermissionCenter.fromRequestCode(99999));
        assertFalse(PermissionCenter.isManagedRequestCode(0));
        assertFalse(PermissionCenter.isManagedRequestCode(4201));
    }

    @Test
    public void summarizeStatesCountsAllowedBlockedOffUnavailableAndNotifications() {
        Map<PermissionCenter.Key, PermissionCenter.State> states = new LinkedHashMap<>();
        states.put(PermissionCenter.Key.NOTIFICATIONS, PermissionCenter.State.NOT_REQUESTED);
        states.put(PermissionCenter.Key.CAMERA, PermissionCenter.State.GRANTED);
        states.put(PermissionCenter.Key.MICROPHONE, PermissionCenter.State.PERMANENTLY_DENIED);
        states.put(PermissionCenter.Key.LOCATION, PermissionCenter.State.DENIED);
        states.put(PermissionCenter.Key.CALENDAR, PermissionCenter.State.UNAVAILABLE);
        states.put(PermissionCenter.Key.NOTIFICATION_ACCESS, PermissionCenter.State.GRANTED);

        PermissionCenter.Summary summary = PermissionCenter.summarizeStates(states);
        assertEquals(2, summary.allowed());
        assertEquals(1, summary.blocked());
        assertEquals(2, summary.off());
        assertEquals(1, summary.unavailable());
        assertEquals(6, summary.total());
        assertTrue(summary.notificationsOff());

        states.put(PermissionCenter.Key.NOTIFICATIONS, PermissionCenter.State.GRANTED);
        assertFalse(PermissionCenter.summarizeStates(states).notificationsOff());

        PermissionCenter.Summary empty = PermissionCenter.summarizeStates(null);
        assertEquals(0, empty.total());
        assertFalse(empty.notificationsOff());
    }

    @Test
    public void panelKeysIncludeAllSevenUserFacingPermissions() {
        assertEquals(7, PermissionCenter.PANEL_KEYS.size());
        assertTrue(PermissionCenter.PANEL_KEYS.contains(PermissionCenter.Key.NOTIFICATIONS));
        assertTrue(PermissionCenter.PANEL_KEYS.contains(PermissionCenter.Key.CAMERA));
        assertTrue(PermissionCenter.PANEL_KEYS.contains(PermissionCenter.Key.MICROPHONE));
        assertTrue(PermissionCenter.PANEL_KEYS.contains(PermissionCenter.Key.LOCATION));
        assertTrue(PermissionCenter.PANEL_KEYS.contains(PermissionCenter.Key.CALENDAR));
        assertTrue(PermissionCenter.PANEL_KEYS.contains(PermissionCenter.Key.NOTIFICATION_ACCESS));
        assertTrue(PermissionCenter.PANEL_KEYS.contains(PermissionCenter.Key.INSTALL_UPDATES));
        assertFalse(PermissionCenter.PANEL_KEYS.contains(PermissionCenter.Key.PRECISE_LOCATION));
    }

    @Test
    public void completedPromptHelperDistinguishesInterruptedFromCompletedOutcomes() {
        // Interrupted/cancelled Android permission prompts deliver null or empty grantResults.
        assertFalse(PermissionCenter.isCompletedResult(null));
        assertFalse(PermissionCenter.isCompletedResult(new int[0]));

        // Completed prompts deliver non-empty grantResults (either GRANTED=0 or DENIED=-1).
        assertTrue(PermissionCenter.isCompletedResult(new int[]{0}));
        assertTrue(PermissionCenter.isCompletedResult(new int[]{-1}));

        // Interrupted prompt MUST NOT mark requestedBefore=true, keeping NOT_REQUESTED intact
        // even when shouldShowRequestPermissionRationale returns false.
        boolean recordedInterrupted = PermissionCenter.isCompletedResult(new int[0]);
        assertEquals(PermissionCenter.State.NOT_REQUESTED,
                PermissionCenter.evaluateRuntimeState(true, false, recordedInterrupted, false));

        // Completed non-empty denial with rationale=false transitions to PERMANENTLY_DENIED.
        boolean recordedCompleted = PermissionCenter.isCompletedResult(new int[]{-1});
        assertEquals(PermissionCenter.State.PERMANENTLY_DENIED,
                PermissionCenter.evaluateRuntimeState(true, false, recordedCompleted, false));
        assertEquals(PermissionCenter.State.DENIED,
                PermissionCenter.evaluateRuntimeState(true, false, recordedCompleted, true));

        // GRANTED always wins regardless of requestedBefore or rationale.
        assertEquals(PermissionCenter.State.GRANTED,
                PermissionCenter.evaluateRuntimeState(true, true, recordedCompleted, false));
        assertEquals(PermissionCenter.State.GRANTED,
                PermissionCenter.evaluateRuntimeState(true, true, false, false));

        // Null/special-access guards return false without throwing.
        assertFalse(PermissionCenter.recordCompletedPrompt(null, PermissionCenter.Key.CAMERA, new int[]{-1}));
        assertFalse(PermissionCenter.recordCompletedPrompt(null, null, new int[]{-1}));
        assertFalse(PermissionCenter.recordCompletedPrompt(null, PermissionCenter.Key.NOTIFICATION_ACCESS, new int[]{0}));
        assertFalse(PermissionCenter.recordCompletedPrompt(null, PermissionCenter.Key.INSTALL_UPDATES, new int[]{0}));
        PermissionCenter.recordCompletedPrompts(null, new String[]{"android.permission.CAMERA"}, new int[]{-1});
        PermissionCenter.recordCompletedPrompts(null, null, null);
    }

    @Test
    public void fromManifestPermissionMapsAllRuntimeKeysAndRejectsInvalidInputs() {
        for (PermissionCenter.Key key : PermissionCenter.Key.values()) {
            if (!key.specialAccess) {
                assertEquals(key, PermissionCenter.fromManifestPermission(key.manifestPermission));
            }
        }
        assertNull(PermissionCenter.fromManifestPermission(null));
        assertNull(PermissionCenter.fromManifestPermission(""));
        assertNull(PermissionCenter.fromManifestPermission("android.permission.UNKNOWN_FAKE"));
    }

    @Test
    public void permissionsStringsHaveExactFrenchEnglishKeyParity() throws Exception {
        Path base = Path.of("src/main/res");
        if (!Files.isDirectory(base)) {
            base = Path.of("app/src/main/res");
        }
        Set<String> frKeys = loadStringNames(base.resolve("values/permissions_strings.xml"));
        Set<String> enKeys = loadStringNames(base.resolve("values-en/permissions_strings.xml"));
        assertFalse("Expected non-empty permissions_strings.xml", frKeys.isEmpty());
        assertEquals("FR and EN permissions_strings.xml must have 1:1 key parity", frKeys, enKeys);
    }

    private static Set<String> loadStringNames(Path xmlPath) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        Document doc = factory.newDocumentBuilder().parse(xmlPath.toFile());
        NodeList nodes = doc.getElementsByTagName("string");
        Set<String> names = new HashSet<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            String name = el.getAttribute("name");
            assertFalse("Empty string name in " + xmlPath, name.isEmpty());
            assertTrue("Duplicate string key " + name + " in " + xmlPath, names.add(name));
            assertFalse("Empty value for " + name + " in " + xmlPath, el.getTextContent().trim().isEmpty());
        }
        return names;
    }
}
