package org.llm4people.hermes.profile;

import android.content.SharedPreferences;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.llm4people.hermes.Diagnostics;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Pure-JVM fail-closed regression tests for corrupt SharedPreferences in IncomingShare and IncomingMediaShare. */
public final class CorruptSharePrefsFailClosedTest {

    @Before
    public void setUp() {
        Diagnostics.clear();
        Diagnostics.setSink((level, tag, message) -> {});
    }

    @After
    public void tearDown() {
        Diagnostics.clear();
        Diagnostics.setSink(null);
    }

    @Test
    public void incomingShareAndMediaShareDiscardCorruptIdPrefsWithoutThrowing() {
        boolean[] textCommitted = {false};
        SharedPreferences corruptTextPrefs = corruptPrefs("id", 42, textCommitted);
        assertNull(IncomingShare.pendingFromPrefs(corruptTextPrefs, 1_000_000L));
        assertTrue("Corrupt IncomingShare record must be cleared fail-closed", textCommitted[0]);
        assertTrue(Diagnostics.exportRedacted().contains("share.text_pref_corrupt"));

        boolean[] mediaCommitted = {false};
        SharedPreferences corruptMediaPrefs = corruptPrefs("id", 99, mediaCommitted);
        assertNull(IncomingMediaShare.pendingIdFromPrefs(corruptMediaPrefs, 1_000_000L, null, null));
        assertTrue("Corrupt IncomingMediaShare record must be cleared fail-closed", mediaCommitted[0]);
        assertTrue(Diagnostics.exportRedacted().contains("share.media_pref_corrupt"));
    }

    @Test
    public void incomingShareAndMediaShareTreatCorruptExpiryAsExpiredAndClearFailClosed() {
        boolean[] textCommitted = {false};
        SharedPreferences badTextExpiry = corruptExpiryPrefs("receipt-1", textCommitted);
        assertNull(IncomingShare.pendingFromPrefs(badTextExpiry, 1_000_000L));
        assertTrue(textCommitted[0]);
        assertTrue(Diagnostics.exportRedacted().contains("share.text_expiry_corrupt"));

        boolean[] mediaCommitted = {false};
        String[] deletedBlobId = {null};
        SharedPreferences badMediaExpiry = corruptExpiryPrefs("receipt-2", mediaCommitted);
        assertNull(IncomingMediaShare.pendingIdFromPrefs(badMediaExpiry, 1_000_000L, id -> deletedBlobId[0] = id, null));
        assertTrue(mediaCommitted[0]);
        assertTrue("Corrupt-expiry media share must delete its sealed .enc blob", "receipt-2".equals(deletedBlobId[0]));
        assertTrue(Diagnostics.exportRedacted().contains("share.media_expiry_corrupt"));

        // Normal expired media share must ALSO delete its sealed .enc blob and clear prefs
        boolean[] expiredCommitted = {false};
        String[] expiredDeletedId = {null};
        SharedPreferences expiredMedia = validPrefs("receipt-expired", "500000", expiredCommitted);
        assertNull(IncomingMediaShare.pendingIdFromPrefs(expiredMedia, 1_000_000L, id -> expiredDeletedId[0] = id, null));
        assertTrue(expiredCommitted[0]);
        assertTrue("Expired media share must delete its sealed .enc blob", "receipt-expired".equals(expiredDeletedId[0]));
    }

    @Test
    public void atomicMediaWriteAndOrphanSweepHandleCommitFailureAndCorruptRecovery() throws Exception {
        java.io.File dir = java.nio.file.Files.createTempDirectory("hermes-media-share-test").toFile();
        try {
            byte[] payload = new byte[]{1, 2, 3, 4, 5};

            // 1. Happy path: writes atomically, removes .tmp, keeps final .enc
            IncomingMediaShare.writeSealedAtomically(dir, "good-1", payload, () -> {}, true);
            java.io.File goodFile = new java.io.File(dir, "good-1.enc");
            java.io.File goodTmp = new java.io.File(dir, "good-1.enc.tmp");
            assertTrue(goodFile.isFile());
            assertTrue(!goodTmp.exists());

            // 2. Commit failure on create(): removes both .tmp and target .enc cleanly
            boolean threw = false;
            try {
                IncomingMediaShare.writeSealedAtomically(dir, "failed-create", payload, () -> {
                    throw new IllegalStateException("commit failed");
                }, true);
            } catch (IllegalStateException expected) {
                threw = true;
            }
            assertTrue(threw);
            assertTrue("New receipt .enc must be deleted when pref commit fails",
                    !new java.io.File(dir, "failed-create.enc").exists());
            assertTrue(!new java.io.File(dir, "failed-create.enc.tmp").exists());

            // 3. Commit failure on existing receipt update (deleteTargetOnCommitFailure=false) preserves target .enc
            boolean updateThrew = false;
            try {
                IncomingMediaShare.writeSealedAtomically(dir, "good-1", payload, () -> {
                    throw new IllegalStateException("update commit failed");
                }, false);
            } catch (IllegalStateException expected) {
                updateThrew = true;
            }
            assertTrue(updateThrew);
            assertTrue(goodFile.isFile());

            // 4. Orphan sweep preserves activeId.enc while deleting stale .enc and .enc.tmp files
            java.nio.file.Files.write(new java.io.File(dir, "stale-1.enc").toPath(), payload);
            java.nio.file.Files.write(new java.io.File(dir, "stale-2.enc.tmp").toPath(), payload);
            int removedKeepActive = IncomingMediaShare.sweepOrphanedBlobs(dir, "good-1");
            assertTrue(removedKeepActive == 2);
            assertTrue(goodFile.isFile());
            assertTrue(!new java.io.File(dir, "stale-1.enc").exists());
            assertTrue(!new java.io.File(dir, "stale-2.enc.tmp").exists());

            // 5. Corrupt-pref sweep deletes everything when activeId is null
            boolean[] corruptSwept = {false};
            boolean[] corruptCommitted = {false};
            SharedPreferences corruptPrefs = corruptPrefs("id", 77, corruptCommitted);
            assertNull(IncomingMediaShare.pendingIdFromPrefs(corruptPrefs, 1_000_000L, null, () -> {
                corruptSwept[0] = true;
                IncomingMediaShare.sweepOrphanedBlobs(dir, null);
            }));
            assertTrue(corruptSwept[0]);
            assertTrue(!goodFile.exists());
        } finally {
            IncomingMediaShare.sweepOrphanedBlobs(dir, null);
            dir.delete();
        }
    }

    private static SharedPreferences validPrefs(String idValue, String expiresValue, boolean[] committed) {
        SharedPreferences.Editor editor = editorProxy(committed);
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("id", idValue);
        snapshot.put("expires", expiresValue);
        return (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getString" -> {
                        if ("id".equals(args[0])) yield idValue;
                        if ("expires".equals(args[0])) yield expiresValue;
                        yield args[1];
                    }
                    case "getAll" -> snapshot;
                    case "edit" -> editor;
                    default -> null;
                });
    }

    private static SharedPreferences corruptPrefs(String key, Object value, boolean[] committed) {
        SharedPreferences.Editor editor = editorProxy(committed);
        return (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getString" -> {
                        if (key.equals(args[0])) throw new ClassCastException("stored non-string");
                        yield args[1];
                    }
                    case "getAll" -> Collections.singletonMap(key, value);
                    case "edit" -> editor;
                    default -> null;
                });
    }

    private static SharedPreferences corruptExpiryPrefs(String idValue, boolean[] committed) {
        SharedPreferences.Editor editor = editorProxy(committed);
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("id", idValue);
        snapshot.put("expires", 12345L);
        return (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getString" -> {
                        if ("id".equals(args[0])) yield idValue;
                        if ("expires".equals(args[0])) throw new ClassCastException("stored long instead of string");
                        yield args[1];
                    }
                    case "getAll" -> snapshot;
                    case "edit" -> editor;
                    default -> null;
                });
    }

    private static SharedPreferences.Editor editorProxy(boolean[] committed) {
        return (SharedPreferences.Editor) Proxy.newProxyInstance(
                SharedPreferences.Editor.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.Editor.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "putString", "putInt", "putLong", "putBoolean", "putFloat", "putStringSet",
                            "remove", "clear" -> proxy;
                    case "commit" -> {
                        committed[0] = true;
                        yield true;
                    }
                    default -> null;
                });
    }
}
