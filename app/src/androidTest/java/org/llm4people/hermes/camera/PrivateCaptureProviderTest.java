package org.llm4people.hermes.camera;

import android.content.Context;
import android.net.Uri;
import android.test.InstrumentationTestCase;

import java.io.File;

@SuppressWarnings("deprecation")
public final class PrivateCaptureProviderTest extends InstrumentationTestCase {
    public void testCreatesValidUuidSlotAndRejectsTraversalUris() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        Uri slot = PrivateCaptureProvider.createSlot(context);
        try {
            File file = PrivateCaptureProvider.fileForSlot(context, slot);
            assertNotNull(file);
            assertTrue(file.isFile());

            assertNull(PrivateCaptureProvider.fileForSlot(context,
                    Uri.parse("content://" + PrivateCaptureProvider.authority(context) + "/../escape.jpg")));
            assertNull(PrivateCaptureProvider.fileForSlot(context,
                    Uri.parse("content://other.authority/00000000-0000-0000-0000-000000000000.jpg")));
            assertNull(PrivateCaptureProvider.fileForSlot(context, Uri.parse("file:///etc/passwd")));
        } finally {
            PrivateCaptureProvider.deleteSlot(context, slot);
        }
        assertFalse(PrivateCaptureProvider.fileForSlot(context, slot).exists());
    }
}
