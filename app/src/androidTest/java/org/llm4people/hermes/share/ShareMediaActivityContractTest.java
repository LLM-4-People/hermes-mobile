package org.llm4people.hermes.share;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.test.InstrumentationTestCase;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@SuppressWarnings("deprecation")
public final class ShareMediaActivityContractTest extends InstrumentationTestCase {
    public void testExtractUrisAndInboundFilterEnforceFailClosedAuthorityAndSchemeBoundaries() {
        Context context = getInstrumentation().getTargetContext();
        String pkg = context.getPackageName();

        Uri selfCapture = Uri.parse("content://" + pkg + ".capture/capture/photo.jpg");
        Uri selfUpdates = Uri.parse("content://" + pkg + ".updates/updates/app.apk");
        Uri selfMultiUser = Uri.parse("content://0@" + pkg + ".capture/capture/photo.jpg");
        Uri fileUri = Uri.parse("file:///data/data/" + pkg + "/shared_prefs/secrets.xml");
        Uri emptyAuthority = Uri.parse("content:///foo");
        Uri externalOne = Uri.parse("content://com.android.providers.media.documents/document/image:1");
        Uri externalTwo = Uri.parse("content://com.google.android.apps.photos.contentprovider/0/1/content");

        // 1. ACTION_SEND with self-owned Hermes authorities -> empty list & rejected.
        for (Uri forbidden : new Uri[]{selfCapture, selfUpdates, selfMultiUser}) {
            assertFalse(ShareMediaActivity.isAllowedInboundUri(context, forbidden));
            Intent send = new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, forbidden);
            assertTrue(ShareMediaActivity.extractUris(context, send).isEmpty());
        }

        // 2. ACTION_SEND with file://, empty-authority content://, and null intent -> empty list.
        for (Uri invalid : new Uri[]{fileUri, emptyAuthority}) {
            assertFalse(ShareMediaActivity.isAllowedInboundUri(context, invalid));
            Intent send = new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, invalid);
            assertTrue(ShareMediaActivity.extractUris(context, send).isEmpty());
        }
        assertTrue(ShareMediaActivity.extractUris(context, null).isEmpty());

        // 3. ACTION_SEND with external content:// URI -> retained.
        assertTrue(ShareMediaActivity.isAllowedInboundUri(context, externalOne));
        Intent validSend = new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, externalOne);
        assertEquals(List.of(externalOne), ShareMediaActivity.extractUris(context, validSend));

        // 4. ACTION_SEND_MULTIPLE mixing self-owned, file://, and external content:// -> retains only external.
        ArrayList<Uri> mixed = new ArrayList<>(Arrays.asList(
                selfCapture, selfUpdates, selfMultiUser, fileUri, emptyAuthority, externalOne, externalTwo));
        Intent multiSend = new Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, mixed);
        assertEquals(List.of(externalOne, externalTwo), ShareMediaActivity.extractUris(context, multiSend));

        // 5. Null context must fail closed for both isAllowedInboundUri and extractUris.
        assertFalse(ShareMediaActivity.isAllowedInboundUri(null, externalOne));
        assertTrue(ShareMediaActivity.extractUris(null, validSend).isEmpty());
    }
}
