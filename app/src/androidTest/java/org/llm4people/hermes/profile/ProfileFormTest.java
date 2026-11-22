package org.llm4people.hermes.profile;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.test.InstrumentationTestCase;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;

import org.llm4people.hermes.Ui;

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
            runTestOnUiThread(activity::finish);
        }
    }
}
