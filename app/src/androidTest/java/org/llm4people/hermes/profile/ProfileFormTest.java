package org.llm4people.hermes.profile;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.test.InstrumentationTestCase;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.llm4people.hermes.ActivityTestLifecycle;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@SuppressWarnings("deprecation")
public final class ProfileFormTest extends InstrumentationTestCase {
    public void testKeyboardSubmissionKeepsPrivateFieldMaskedAndHonorsBusyButton() throws Throwable {
        Context context = getInstrumentation().getTargetContext();
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        assertNotNull(launch);
        Activity activity = getInstrumentation().startActivitySync(launch);
        try {
            runTestOnUiThread(() -> {
                ProfileScreen form = new ProfileScreen(activity, "Profil de test", null);
                EditText name = form.field("Nom", "", InputType.TYPE_CLASS_TEXT, 80, false);
                int passwordType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
                EditText phrase = form.field("Phrase secrète", "12 caractères minimum", passwordType, 512, true);
                int[] submissions = {0};
                Button save = form.action("Enregistrer", true, view -> submissions[0]++);

                assertEquals(phrase.getId(), name.getNextFocusForwardId());
                assertEquals(EditorInfo.IME_ACTION_NEXT, name.getImeOptions() & EditorInfo.IME_MASK_ACTION);
                assertEquals(EditorInfo.IME_ACTION_DONE, phrase.getImeOptions() & EditorInfo.IME_MASK_ACTION);
                assertTrue((phrase.getImeOptions() & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0);
                assertTrue((phrase.getImeOptions() & EditorInfo.IME_FLAG_NO_EXTRACT_UI) != 0);
                assertTrue(phrase.getTransformationMethod() instanceof PasswordTransformationMethod);
                assertEquals(name.getTypeface(), phrase.getTypeface());
                assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO, phrase.getImportantForAutofill());
                assertFalse(phrase.isSaveEnabled());
                assertTrue(phrase.getMinHeight() >= Ui.dp(activity, 48));

                phrase.onEditorAction(EditorInfo.IME_ACTION_DONE);
                assertEquals(1, submissions[0]);
                save.setEnabled(false);
                phrase.onEditorAction(EditorInfo.IME_ACTION_DONE);
                assertEquals(1, submissions[0]);
            });
        } finally {
            ActivityTestLifecycle.finishAndAwaitDestroyed(this, activity);
        }
    }

    public void testMultiModeSetupFocusChainAndPinRepeatSubmissionAcrossAllLockModes() throws Throwable {
        Context context = getInstrumentation().getTargetContext();
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        assertNotNull(launch);
        Activity activity = getInstrumentation().startActivitySync(launch);
        AtomicReference<ProfileGate> gateRef = new AtomicReference<>();
        try {
            runTestOnUiThread(() -> {
                // 1. Direct ProfileScreen helper contract: pinRepeat submission & key IME/focus across all 3 lock modes.
                ProfileScreen form = new ProfileScreen(activity, "Profil multi-mode", null);
                int secretType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
                int pinType = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD;
                EditText key = form.field("Clé", "", secretType, 256, true);
                LinearLayout pinSlot = Ui.column(activity);
                EditText pin = form.field(pinSlot, "Code PIN", "", pinType, 12, true);
                EditText pinRepeat = form.field(pinSlot, "Confirmer PIN", "", pinType, 12, true);
                LinearLayout passSlot = Ui.column(activity);
                EditText phrase = form.field(passSlot, "Phrase", "", secretType, 512, true);
                EditText repeat = form.field(passSlot, "Confirmer phrase", "", secretType, 512, true);
                form.card(pinSlot, 4);
                form.card(passSlot, 4);
                int[] submissions = {0};
                Button save = form.action("Enregistrer", true, view -> submissions[0]++);
                ProfileScreen.connectSubmission(pinRepeat, save);

                assertEquals(EditorInfo.IME_ACTION_DONE, pinRepeat.getImeOptions() & EditorInfo.IME_MASK_ACTION);
                assertEquals(View.NO_ID, pinRepeat.getNextFocusForwardId());
                assertEquals(View.NO_ID, pinRepeat.getNextFocusDownId());
                assertEquals(EditorInfo.IME_ACTION_DONE, repeat.getImeOptions() & EditorInfo.IME_MASK_ACTION);
                assertEquals(View.NO_ID, repeat.getNextFocusForwardId());
                assertEquals(View.NO_ID, repeat.getNextFocusDownId());

                pinRepeat.onEditorAction(EditorInfo.IME_ACTION_DONE);
                assertEquals(1, submissions[0]);
                save.setEnabled(false);
                pinRepeat.onEditorAction(EditorInfo.IME_ACTION_DONE);
                assertEquals(1, submissions[0]);
                save.setEnabled(true);

                ProfileScreen.configureLockModeFocus(ProfileLockPolicy.Mode.PIN, key, pin, phrase, save);
                assertEquals(pin.getId(), key.getNextFocusForwardId());
                assertEquals(pin.getId(), key.getNextFocusDownId());
                assertEquals(EditorInfo.IME_ACTION_NEXT, key.getImeOptions() & EditorInfo.IME_MASK_ACTION);
                key.onEditorAction(EditorInfo.IME_ACTION_DONE);
                assertEquals(1, submissions[0]);

                ProfileScreen.configureLockModeFocus(ProfileLockPolicy.Mode.PASSPHRASE, key, pin, phrase, save);
                assertEquals(phrase.getId(), key.getNextFocusForwardId());
                assertEquals(phrase.getId(), key.getNextFocusDownId());
                assertEquals(EditorInfo.IME_ACTION_NEXT, key.getImeOptions() & EditorInfo.IME_MASK_ACTION);
                key.onEditorAction(EditorInfo.IME_ACTION_DONE);
                assertEquals(1, submissions[0]);

                ProfileScreen.configureLockModeFocus(ProfileLockPolicy.Mode.BIOMETRIC, key, pin, phrase, save);
                assertEquals(View.NO_ID, key.getNextFocusForwardId());
                assertEquals(View.NO_ID, key.getNextFocusDownId());
                assertEquals(EditorInfo.IME_ACTION_DONE, key.getImeOptions() & EditorInfo.IME_MASK_ACTION);
                key.onEditorAction(EditorInfo.IME_ACTION_DONE);
                assertEquals(2, submissions[0]);

                // Switching back out of BIOMETRIC clears key's submission listener.
                ProfileScreen.configureLockModeFocus(ProfileLockPolicy.Mode.PIN, key, pin, phrase, save);
                assertEquals(pin.getId(), key.getNextFocusForwardId());
                assertEquals(EditorInfo.IME_ACTION_NEXT, key.getImeOptions() & EditorInfo.IME_MASK_ACTION);
                key.onEditorAction(EditorInfo.IME_ACTION_DONE);
                assertEquals(2, submissions[0]);

                // 2. Live ProfileGate.add(...) setup screen verifies actual chip clicks update key & pinRepeat.
                ProfileGate gate = new ProfileGate(activity);
                gateRef.set(gate);
                gate.openAddWithPairing(null);
                View root = activity.findViewById(android.R.id.content);
                EditText gateKey = findLabeledEdit(root, activity.getString(R.string.profile_key_label));
                EditText gatePin = findLabeledEdit(root, activity.getString(R.string.lock_pin_label));
                EditText gatePinRepeat = findLabeledEdit(root, activity.getString(R.string.lock_pin_confirm_label));
                EditText gatePhrase = findLabeledEdit(root, activity.getString(R.string.profile_passphrase_label));
                Button pinChip = findButtonByText(root, activity.getString(R.string.lock_mode_pin));
                Button passChip = findButtonByText(root, activity.getString(R.string.lock_mode_passphrase));
                Button bioChip = findButtonByText(root, activity.getString(R.string.lock_mode_biometric));
                assertNotNull(gateKey);
                assertNotNull(gatePin);
                assertNotNull(gatePinRepeat);
                assertNotNull(gatePhrase);
                assertNotNull(pinChip);
                assertNotNull(passChip);
                assertNotNull(bioChip);

                assertEquals(EditorInfo.IME_ACTION_DONE, gatePinRepeat.getImeOptions() & EditorInfo.IME_MASK_ACTION);
                assertEquals(View.NO_ID, gatePinRepeat.getNextFocusForwardId());
                assertEquals(View.NO_ID, gatePinRepeat.getNextFocusDownId());

                pinChip.performClick();
                assertEquals(gatePin.getId(), gateKey.getNextFocusForwardId());
                assertEquals(gatePin.getId(), gateKey.getNextFocusDownId());
                assertEquals(EditorInfo.IME_ACTION_NEXT, gateKey.getImeOptions() & EditorInfo.IME_MASK_ACTION);

                passChip.performClick();
                assertEquals(gatePhrase.getId(), gateKey.getNextFocusForwardId());
                assertEquals(gatePhrase.getId(), gateKey.getNextFocusDownId());
                assertEquals(EditorInfo.IME_ACTION_NEXT, gateKey.getImeOptions() & EditorInfo.IME_MASK_ACTION);

                bioChip.performClick();
                assertEquals(View.NO_ID, gateKey.getNextFocusForwardId());
                assertEquals(View.NO_ID, gateKey.getNextFocusDownId());
                assertEquals(EditorInfo.IME_ACTION_DONE, gateKey.getImeOptions() & EditorInfo.IME_MASK_ACTION);
            });
        } finally {
            try {
                ProfileGate gate = gateRef.getAndSet(null);
                if (gate != null) {
                    runTestOnUiThread(gate::close);
                }
            } finally {
                ActivityTestLifecycle.finishAndAwaitDestroyed(this, activity);
            }
        }
    }

    public void testStatusReannouncesRepeatedDiscreteErrorsAndSuppressesUnchangedInfo() throws Throwable {
        Context context = getInstrumentation().getTargetContext();
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        assertNotNull(launch);
        Activity activity = getInstrumentation().startActivitySync(launch);
        try {
            runTestOnUiThread(() -> {
                try {
                    ProfileScreen form = new ProfileScreen(activity, "Profil", null);
                    form.status("Erreur", true);
                    Field statusField = ProfileScreen.class.getDeclaredField("status");
                    statusField.setAccessible(true);
                    TextView statusView = (TextView) statusField.get(form);
                    assertNotNull(statusView);
                    assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, statusView.getAccessibilityLiveRegion());

                    AtomicInteger textChanges = new AtomicInteger();
                    statusView.addTextChangedListener(new TextWatcher() {
                        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                            textChanges.incrementAndGet();
                        }
                        @Override public void afterTextChanged(Editable s) {}
                    });

                    // Repeated discrete error must clear and re-set text so TalkBack re-announces.
                    form.status("Erreur", true);
                    assertTrue("Repeated error must fire clear+set text changes", textChanges.get() >= 2);
                    assertEquals("Erreur", statusView.getText().toString());

                    int afterError = textChanges.get();
                    form.status("Prêt", false);
                    assertEquals(afterError + 1, textChanges.get());
                    form.status("Prêt", false);
                    assertEquals("Unchanged non-error status must not re-fire", afterError + 1, textChanges.get());

                    // R25.5.3: Ui.showInlineError sets POLITE live region, makes visible, and forces
                    // clear+set text-change event when already visible with identical text.
                    TextView inlineErr = Ui.text(activity, "", 13);
                    inlineErr.setVisibility(View.GONE);
                    AtomicInteger inlineChanges = new AtomicInteger();
                    inlineErr.addTextChangedListener(new TextWatcher() {
                        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                            inlineChanges.incrementAndGet();
                        }
                        @Override public void afterTextChanged(Editable s) {}
                    });
                    Ui.showInlineError(inlineErr, "Champ requis");
                    assertEquals(View.VISIBLE, inlineErr.getVisibility());
                    assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, inlineErr.getAccessibilityLiveRegion());
                    assertEquals("Champ requis", inlineErr.getText().toString());
                    int afterFirstInline = inlineChanges.get();
                    Ui.showInlineError(inlineErr, "Champ requis");
                    assertTrue("Repeated visible identical inline error must clear+set for TalkBack",
                            inlineChanges.get() >= afterFirstInline + 2);
                } catch (Exception err) {
                    throw new AssertionError(err);
                }
            });
        } finally {
            ActivityTestLifecycle.finishAndAwaitDestroyed(this, activity);
        }
    }

    private static EditText findLabeledEdit(View root, String label) {
        List<View> all = new ArrayList<>();
        collectViews(root, all);
        for (View v : all) {
            if (v instanceof android.widget.TextView tv && label.contentEquals(tv.getText())
                    && tv.getLabelFor() != View.NO_ID) {
                View target = root.findViewById(tv.getLabelFor());
                if (target instanceof EditText et) return et;
            }
        }
        return null;
    }

    private static Button findButtonByText(View root, String text) {
        List<View> all = new ArrayList<>();
        collectViews(root, all);
        for (View v : all) {
            if (v instanceof Button b && text.contentEquals(b.getText())) return b;
        }
        return null;
    }

    private static void collectViews(View view, List<View> out) {
        if (view == null) return;
        out.add(view);
        if (view instanceof android.view.ViewGroup vg) {
            for (int i = 0; i < vg.getChildCount(); i++) {
                collectViews(vg.getChildAt(i), out);
            }
        }
    }
}
