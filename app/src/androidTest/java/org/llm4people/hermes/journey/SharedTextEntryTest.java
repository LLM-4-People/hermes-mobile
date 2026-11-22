package org.llm4people.hermes.journey;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.llm4people.hermes.MainActivity;
import org.llm4people.hermes.share.ShareTextActivity;
import org.llm4people.hermes.net.Exchange;
import org.llm4people.hermes.profile.IncomingShare;
import org.llm4people.hermes.profile.RegisteredShareProfile;

/** Resolves the system text actions, unlocks through the real gate and checks the local journal. */
public final class SharedTextEntryTest extends InstrumentationTestCase {
    public void testSystemShareUrlStagesOnlyAfterReview() throws Throwable {
        share(Intent.ACTION_SEND, Intent.EXTRA_TEXT, "https://example.invalid/article", false);
    }

    public void testSelectedCodeSurvivesRecreationAndRequiresOwnerAgain() throws Throwable {
        share(Intent.ACTION_PROCESS_TEXT, Intent.EXTRA_PROCESS_TEXT, "  if (ok) {\n\treturn 2;\n}", true);
    }

    public void testCommittedReceiptReopensTheEditedDraftAfterInterruptedCleanup() throws Throwable {
        if (Build.VERSION.SDK_INT < 29) return;
        Instrumentation driver = getInstrumentation();
        Context context = driver.getTargetContext();
        String thread = "share-recovery";
        String response = ThreadNoticeContinuityTest.transcript(thread, thread, "Relecture", new JSONArray());
        try (Exchange gateway = new Exchange("200 OK", "Content-Type: application/json\r\n", response);
             RegisteredShareProfile profile = new RegisteredShareProfile(context, gateway.endpoint())) {
            String receipt = IncomingShare.create(context, "déjà ajouté");
            assertNotNull(receipt);
            Activity activity = null;
            try {
                assertNotNull(IncomingShare.open(context, receipt, profile.account));
                assertTrue(IncomingShare.target(context, receipt, profile.account, thread));
                profile.account.write("last-thread", thread);
                profile.account.write("thread:" + thread, new JSONObject().put("draft", "ma version modifiée")
                        .put("imported_share", receipt).toString());
                Intent resume = new Intent(context, MainActivity.class).setAction("org.llm4people.hermes.REVIEW_SHARED_TEXT")
                        .putExtra("org.llm4people.hermes.SHARE_RECEIPT", receipt).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity = driver.startActivitySync(resume);
                JourneyUi ui = new JourneyUi(driver, activity, context.getCacheDir());
                ui.see("Déverrouiller");
                ui.fill("Phrase secrète du profil", profile.passphrase);
                ui.tap("Déverrouiller");
                ui.see("Envoyer");
                assertEquals("ma version modifiée", ui.fieldText("Message à Hermes"));
                assertNull(IncomingShare.pending(context));
                assertFalse(gateway.observed().contains("POST "));
            } finally {
                if (activity != null) runTestOnUiThread(activity::finish);
                IncomingShare.discard(context, receipt);
            }
        }
    }

    private void share(String action, String key, String text, boolean recreate) throws Throwable {
        if (Build.VERSION.SDK_INT < 29) return;
        Instrumentation driver = getInstrumentation();
        Context context = driver.getTargetContext();
        String thread = "share-root";
        String response = ThreadNoticeContinuityTest.transcript(thread, thread, "Notes de lecture", new JSONArray());
        try (Exchange gateway = new Exchange("200 OK", "Content-Type: application/json\r\n", recreate
                    ? new String[]{response, response} : new String[]{response});
             RegisteredShareProfile profile = new RegisteredShareProfile(context, gateway.endpoint())) {
            profile.account.write("last-thread", thread);
            profile.account.write("thread:" + thread, new JSONObject().put("draft", "Mon texte initial").toString());
            Activity first = driver.startActivitySync(context.getPackageManager().getLaunchIntentForPackage(context.getPackageName()));
            Activity[] current = {first};
            String receipt = null;
            try {
                Intent external = new Intent(action).setType("text/plain").setPackage(context.getPackageName())
                        .putExtra(key, text).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false);
                assertEquals("org.llm4people.hermes.share.ShareTextActivity", external.resolveActivity(context.getPackageManager()).getClassName());
                assertNull(IncomingShare.pending(context));
                ShareHandoff handoff = new ShareHandoff();
                Application application = first.getApplication();
                application.registerActivityLifecycleCallbacks(handoff);
                try {
                    runTestOnUiThread(() -> first.startActivity(external));
                    long end = SystemClock.uptimeMillis() + 10_000;
                    while ((receipt = IncomingShare.pending(context)) == null && SystemClock.uptimeMillis() < end) SystemClock.sleep(30);
                    assertNotNull(receipt);
                    assertTrue("The share entry did not return to the protected workspace",
                            handoff.resumed.await(15, TimeUnit.SECONDS));
                    current[0] = handoff.workspace;
                } finally { application.unregisterActivityLifecycleCallbacks(handoff); }
                View chooser = current[0].getWindow().getDecorView();
                boolean[] focused = {false};
                long focusDeadline = SystemClock.uptimeMillis() + 10_000;
                do {
                    runTestOnUiThread(() -> focused[0] = chooser.hasWindowFocus() && chooser.isShown());
                    if (!focused[0]) SystemClock.sleep(30);
                } while (!focused[0] && SystemClock.uptimeMillis() < focusDeadline);
                assertTrue("The receiving profile chooser did not acquire input focus", focused[0]);
                JourneyUi ui = new JourneyUi(driver, current[0], context.getCacheDir());
                ui.tap(profile.label);
                ui.fill("Phrase secrète du profil", profile.passphrase);
                ui.tap("Déverrouiller");
                ui.see("Ajouter au brouillon ?");
                ui.contains(profile.label, "Identifiant : " + thread);
                if (recreate) {
                    Instrumentation.ActivityMonitor monitor = driver.addMonitor(MainActivity.class.getName(), null, false);
                    try {
                        runTestOnUiThread(current[0]::recreate);
                        Activity restored = monitor.waitForActivityWithTimeout(10_000);
                        assertNotNull(restored);
                        current[0] = restored;
                    } finally { driver.removeMonitor(monitor); }
                    ui = new JourneyUi(driver, current[0], context.getCacheDir());
                    ui.see("Déverrouiller");
                    assertFalse(ui.has("Ajouter au brouillon ?"));
                    ui.fill("Phrase secrète du profil", profile.passphrase);
                    ui.tap("Déverrouiller");
                    ui.see("Ajouter au brouillon ?");
                    ui.contains(profile.label, "Identifiant : " + thread);
                }
                assertEquals("Mon texte initial", new JSONObject(profile.account.read("thread:" + thread)).getString("draft"));
                ui.tap("Ajouter au brouillon");
                ui.see("Envoyer");
                String expected = "Mon texte initial\n" + text;
                assertEquals(expected, ui.fieldText("Message à Hermes"));
                JSONObject saved = new JSONObject(profile.account.read("thread:" + thread));
                assertEquals(expected, saved.getString("draft"));
                assertFalse(saved.has("pending"));
                assertNull(IncomingShare.pending(context));
                assertFalse(gateway.observed().contains("POST "));
            } finally {
                if (receipt != null) IncomingShare.discard(context, receipt);
                runTestOnUiThread(current[0]::finish);
            }
        }
    }

    /** A persisted receipt exists before the receiving MainActivity replaces the previous profile chooser. */
    private static final class ShareHandoff implements Application.ActivityLifecycleCallbacks {
        final CountDownLatch resumed = new CountDownLatch(1);
        private boolean entryCreated;
        MainActivity workspace;

        @Override public void onActivityCreated(Activity activity, Bundle state) {
            if (activity instanceof ShareTextActivity) entryCreated = true;
        }

        @Override public void onActivityResumed(Activity activity) {
            if (entryCreated && activity instanceof MainActivity main) {
                workspace = main;
                resumed.countDown();
            }
        }

        @Override public void onActivityStarted(Activity activity) {}
        @Override public void onActivityPaused(Activity activity) {}
        @Override public void onActivityStopped(Activity activity) {}
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle out) {}
        @Override public void onActivityDestroyed(Activity activity) {}
    }
}
