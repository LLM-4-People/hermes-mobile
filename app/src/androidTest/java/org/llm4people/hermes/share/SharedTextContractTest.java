package org.llm4people.hermes.share;

import android.content.Context;
import android.content.Intent;
import android.test.InstrumentationTestCase;
import android.text.SpannableString;
import android.text.style.URLSpan;
import org.llm4people.hermes.profile.IncomingShare;
import org.llm4people.hermes.profile.WorkspaceAccountFixture;

public final class SharedTextContractTest extends InstrumentationTestCase {
    public void testShareAndProcessSelectionReadOnlyAndEditableReturnOnlyPlainText() {
        String code = "  if (prêt) {\n\treturn \"🙂\";\n}\n";
        SpannableString styled = new SpannableString(code);
        styled.setSpan(new URLSpan("https://example.invalid"), 2, 4, 0);
        for (boolean readOnly : new boolean[]{true, false}) {
            Intent selection = new Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
                    .putExtra(Intent.EXTRA_PROCESS_TEXT, styled).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, readOnly)
                    .putExtra(Intent.EXTRA_TEXT, "wrong extra");
            assertEquals(code, ShareTextActivity.incoming(selection));
        }
        Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "https://example.invalid/a");
        assertEquals("https://example.invalid/a", ShareTextActivity.incoming(share));
        for (Intent rejected : new Intent[]{new Intent(), share.cloneFilter(), new Intent(share).setType("text/html"),
                new Intent(share).setAction(Intent.ACTION_SEND_MULTIPLE), new Intent(share).putExtra(Intent.EXTRA_TEXT, " \u200b"),
                new Intent(share).putExtra(Intent.EXTRA_TEXT, "a\u0000b"),
                new Intent(share).putExtra(Intent.EXTRA_TEXT, new String(new char[IncomingShare.MAX_TEXT + 1]))}) {
            try { ShareTextActivity.incoming(rejected); fail("Rejected input produced a draft"); }
            catch (IllegalArgumentException expected) { }
        }
    }

    public void testExpiredReceiptDoesNotConsumeANewerShare() {
        Context context = getInstrumentation().getTargetContext();
        String expired = IncomingShare.create(context, "Premier");
        assertNotNull(expired);
        String next = null;
        try {
            assertTrue(context.getSharedPreferences("hermes.share.text.v1", 0).edit().putString("expires", "0").commit());
            next = IncomingShare.create(context, "Nouveau");
            assertNotNull(next);
            assertFalse(next.equals(expired));
            IncomingShare.discard(context, expired);
            assertEquals(next, IncomingShare.pending(context));
        } finally {
            IncomingShare.discard(context, expired);
            IncomingShare.discard(context, next);
        }
    }

    public void testReceiptBindsOwnerAndThreadThroughRelock() {
        Context context = getInstrumentation().getTargetContext();
        try (WorkspaceAccountFixture first = new WorkspaceAccountFixture(context);
             WorkspaceAccountFixture second = new WorkspaceAccountFixture(context)) {
            String id = IncomingShare.create(context, "Texte confidentiel");
            assertNotNull(id);
            try {
                assertNull(IncomingShare.create(context, "Autre partage"));
                assertEquals("Texte confidentiel", IncomingShare.open(context, id, first.account).text());
                assertNull(IncomingShare.open(context, id, first.account).confirmedThread());
                assertNull(IncomingShare.open(context, id, second.account));
                assertFalse(IncomingShare.target(context, id, second.account, "même fil"));
                assertTrue(IncomingShare.target(context, id, first.account, "même fil"));
                assertFalse(IncomingShare.target(context, id, first.account, "autre fil"));
                first.account.close();
                assertNull(IncomingShare.open(context, id, first.account));
                try (var unlocked = first.reopen()) {
                    assertEquals("Texte confidentiel", IncomingShare.open(context, id, unlocked).text());
                    assertEquals("même fil", IncomingShare.open(context, id, unlocked).confirmedThread());
                    assertTrue(IncomingShare.target(context, id, unlocked, "même fil"));
                }
                assertFalse(context.getSharedPreferences("hermes.share.text.v1", 0).getAll().toString().contains("confidentiel"));
                IncomingShare.discard(context, "incorrect");
                assertEquals(id, IncomingShare.pending(context));
            } finally { IncomingShare.discard(context, id); }
            assertNull(IncomingShare.open(context, id, second.account));
        }
    }


    public void testPlainTextSendHasExactlyOneHermesTargetAndDocumentsReachMediaShare() {
        Context context = getInstrumentation().getTargetContext();
        android.content.pm.PackageManager pm = context.getPackageManager();
        String pkg = context.getPackageName();
        java.util.List<android.content.pm.ResolveInfo> text = pm.queryIntentActivities(
                new Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(pkg), 0);
        assertEquals(1, text.size());
        assertEquals(ShareTextActivity.class.getName(), text.get(0).activityInfo.name);
        for (String mime : new String[]{"text/markdown", "text/csv", "text/x-python", "application/json",
                "application/pdf", "image/jpeg"}) {
            java.util.List<android.content.pm.ResolveInfo> media = pm.queryIntentActivities(
                    new Intent(Intent.ACTION_SEND).setType(mime).setPackage(pkg), 0);
            assertEquals(mime, 1, media.size());
            assertEquals(mime, ShareMediaActivity.class.getName(), media.get(0).activityInfo.name);
        }
        for (String mime : new String[]{"application/json", "application/yaml", "text/plain"}) {
            java.util.List<android.content.pm.ResolveInfo> many = pm.queryIntentActivities(
                    new Intent(Intent.ACTION_SEND_MULTIPLE).setType(mime).setPackage(pkg), 0);
            assertEquals(mime, 1, many.size());
            assertEquals(mime, ShareMediaActivity.class.getName(), many.get(0).activityInfo.name);
        }
    }
}
