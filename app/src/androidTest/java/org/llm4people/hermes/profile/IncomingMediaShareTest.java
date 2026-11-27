package org.llm4people.hermes.profile;

import android.content.Context;
import android.test.InstrumentationTestCase;

import org.llm4people.hermes.Digests;
import org.llm4people.hermes.camera.CapturedMedia;

import java.nio.charset.StandardCharsets;
import java.util.List;

@SuppressWarnings("deprecation")
public final class IncomingMediaShareTest extends InstrumentationTestCase {
    public void testEncryptedStagingIsolatesProfilesAndCleansUp() {
        Context context = getInstrumentation().getTargetContext();
        byte[] content = "données confidentielles".getBytes(StandardCharsets.UTF_8);
        CapturedMedia.Item doc = new CapturedMedia.Item(
                CapturedMedia.Kind.DOCUMENT, "secret.txt", "text/plain", content, Digests.sha256Hex(content), null);

        try (WorkspaceAccountFixture first = new WorkspaceAccountFixture(context);
             WorkspaceAccountFixture second = new WorkspaceAccountFixture(context)) {
            String receipt = IncomingMediaShare.create(context, List.of(doc));
            assertNotNull(receipt);
            try {
                assertNull("Busy staging should not overwrite pending receipt",
                        IncomingMediaShare.create(context, List.of(doc)));
                IncomingMediaShare.Batch opened = IncomingMediaShare.open(context, receipt, first.account);
                assertNotNull(opened);
                assertEquals(1, opened.items().size());
                assertEquals("secret.txt", opened.items().get(0).name());
                assertEquals("données confidentielles",
                        new String(opened.items().get(0).bytes(), StandardCharsets.UTF_8));

                // Second profile must never open or target first profile's share.
                assertNull(IncomingMediaShare.open(context, receipt, second.account));
                assertFalse(IncomingMediaShare.target(context, receipt, second.account, "s1"));
                assertTrue(IncomingMediaShare.target(context, receipt, first.account, "s1"));
                assertFalse(IncomingMediaShare.target(context, receipt, first.account, "s2"));

                // SharedPreferences must hold only opaque metadata, zero plaintext.
                assertFalse(context.getSharedPreferences("hermes.share.media.v1", 0)
                        .getAll().toString().contains("confidentielles"));
            } finally {
                IncomingMediaShare.discard(context, receipt);
            }
            assertNull(IncomingMediaShare.pending(context));
        }
    }
}
