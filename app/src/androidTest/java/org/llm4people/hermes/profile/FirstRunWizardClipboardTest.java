package org.llm4people.hermes.profile;

import android.app.AlertDialog;
import android.content.Context;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import org.llm4people.hermes.ActivityTestLifecycle;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.MainActivity;
import org.llm4people.hermes.R;
import org.llm4people.hermes.onboarding.OnboardingModels;
import org.llm4people.hermes.onboarding.PairingLinkParser;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Instrumentation regression test for R13.2.1: rendering {@link FirstRunWizard#attachInstantSetupCard}
 * must NEVER read clipboard contents prior to an explicit tap on the paste button.
 */
@SuppressWarnings("deprecation")
public final class FirstRunWizardClipboardTest extends InstrumentationTestCase {

    @Override
    protected void tearDown() throws Exception {
        try {
            Diagnostics.clear();
        } finally {
            super.tearDown();
        }
    }

    public void testInstantSetupCardNeverReadsClipboardBeforeExplicitPasteTap() throws Throwable {
        Context context = getInstrumentation().getTargetContext();
        MainActivity activity = launchActivity(context.getPackageName(), MainActivity.class, null);
        ExecutorService io = Executors.newSingleThreadExecutor();
        AtomicReference<AlertDialog> dialogRef = new AtomicReference<>();
        AtomicReference<android.widget.EditText> manualInputRef = new AtomicReference<>();
        try {
            runTestOnUiThread(() -> {
                ProfileScreen screen = new ProfileScreen(
                        activity,
                        activity.getString(R.string.profile_connect_title),
                        activity.getString(R.string.profile_connect_detail));

                AtomicInteger clipboardReads = new AtomicInteger(0);
                AtomicBoolean current = new AtomicBoolean(true);
                AtomicReference<String> filledServer = new AtomicReference<>();
                AtomicReference<String> filledSlug = new AtomicReference<>();
                AtomicReference<String> filledKey = new AtomicReference<>();
                PairingLinkParser.ParsedInput sampleCreds = PairingLinkParser.parse(
                        "https://hermes.example.com/p/home\nkey: sk-secret-token-123456");
                AtomicReference<PairingLinkParser.ParsedInput> nextClip = new AtomicReference<>(sampleCreds);

                FirstRunWizard.Step1Targets targets = new FirstRunWizard.Step1Targets() {
                    @Override public void fillFields(String suggestedName, String serverUrl, String profileSlug,
                                                     String apiKey, boolean focusPassphrase) {
                        filledServer.set(serverUrl);
                        filledSlug.set(profileSlug);
                        filledKey.set(apiKey);
                    }
                    @Override public void stagePairing(FirstRunWizard.StagedPairing staged) {}
                    @Override public void triggerConnectionProbe(String endpoint, String bearer) {}
                    @Override public void showInstantStatus(String message, int colorRes) {}
                    @Override public void onClaimedMetadata(OnboardingModels.PairingClaim claim) {}
                    @Override public void trackDialog(AlertDialog dialog) { dialogRef.set(dialog); }
                    @Override public void onScanQrRequested() {}
                };

                FirstRunWizard.attachInstantSetupCard(
                        activity, screen, io, current::get, targets,
                        () -> {
                            clipboardReads.incrementAndGet();
                            return nextClip.get();
                        });

                // 1. Rendering the setup card must NEVER touch the clipboard.
                assertEquals("Setup-card render must not read clipboard", 0, clipboardReads.get());
                assertNull("Credentials must not be filled prior to explicit user tap", filledKey.get());

                Button pasteBtn = findButtonByText(
                        activity.getWindow().getDecorView(),
                        activity.getString(R.string.onboarding_paste_action));
                assertNotNull("Expected explicit paste button on instant setup card", pasteBtn);

                // 2. Stale/busy state suppresses clipboard read even on click.
                current.set(false);
                pasteBtn.performClick();
                assertEquals("Stale setup card must not read clipboard", 0, clipboardReads.get());

                // 3. Explicit tap while active reads clipboard once and fills parsed credentials.
                current.set(true);
                pasteBtn.performClick();
                assertEquals("Explicit paste tap must read clipboard exactly once", 1, clipboardReads.get());
                assertEquals("https://hermes.example.com", filledServer.get());
                assertEquals("home", filledSlug.get());
                assertEquals("sk-secret-token-123456", filledKey.get());

                // 4. Explicit tap with empty clipboard opens manual fallback dialog without crashing.
                // R26.6.1: Invalid paste keeps dialog & input open with inline error; dismiss clears input.
                nextClip.set(null);
                pasteBtn.performClick();
                assertEquals(2, clipboardReads.get());
                AlertDialog manualDialog = dialogRef.get();
                assertNotNull("Empty clipboard tap must open manual paste dialog", manualDialog);
                assertTrue(manualDialog.isShowing());
                View dialogRoot = manualDialog.getWindow().getDecorView();
                android.widget.EditText manualInput = findFirstEditText(dialogRoot);
                assertNotNull("Expected EditText inside manual paste dialog", manualInput);
                manualInputRef.set(manualInput);
                manualInput.setText("not-a-valid-link");
                Button confirmBtn = manualDialog.getButton(AlertDialog.BUTTON_POSITIVE);
                assertNotNull(confirmBtn);
                confirmBtn.performClick();
                assertTrue("Invalid paste must keep manual dialog open", manualDialog.isShowing());
                assertEquals("Invalid paste must preserve user input",
                        "not-a-valid-link", manualInput.getText().toString());
                assertTrue("Invalid paste must show inline error",
                        hasVisibleText(dialogRoot, activity.getString(R.string.onboarding_paste_invalid)));
                manualDialog.dismiss();
                dialogRef.set(null);
            });
            getInstrumentation().waitForIdleSync();
            runTestOnUiThread(() -> assertEquals("Dismissing manual dialog must clear sensitive input",
                    0, manualInputRef.get().getText().length()));
        } finally {
            try {
                runTestOnUiThread(() -> {
                    AlertDialog dialog = dialogRef.getAndSet(null);
                    if (dialog != null && dialog.isShowing()) dialog.dismiss();
                });
            } finally {
                io.shutdownNow();
                ActivityTestLifecycle.finishAndAwaitDestroyed(this, activity);
            }
        }
    }

    private static Button findButtonByText(View root, String text) {
        List<Button> buttons = new ArrayList<>();
        collectButtons(root, buttons);
        for (Button button : buttons) {
            if (text.contentEquals(button.getText())) return button;
        }
        return null;
    }

    private static void collectButtons(View node, List<Button> out) {
        if (node == null) return;
        if (node instanceof Button button) {
            out.add(button);
        }
        if (node instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                collectButtons(group.getChildAt(i), out);
            }
        }
    }

    private static android.widget.EditText findFirstEditText(View node) {
        if (node instanceof android.widget.EditText et) return et;
        if (node instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                android.widget.EditText found = findFirstEditText(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean hasVisibleText(View node, String expected) {
        if (node == null || node.getVisibility() != View.VISIBLE) return false;
        if (node instanceof android.widget.TextView tv && expected.contentEquals(tv.getText())) return true;
        if (node instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hasVisibleText(group.getChildAt(i), expected)) return true;
            }
        }
        return false;
    }
}
