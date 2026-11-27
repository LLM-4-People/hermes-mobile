package org.llm4people.hermes;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inspector.WindowInspector;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;

import org.llm4people.hermes.profile.ProfileGate;
import org.llm4people.hermes.profile.ProfileLockPolicy;
import org.llm4people.hermes.profile.WorkspaceAccountFixture;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Instrumentation regression tests for R5.3.4 / R6.8.5 / R7.5.1 / R7.8.2:
 * - Verifies {@link WorkspaceDialogs#showSecurity} sets FLAG_SECURE on both the parent security
 *   dialog and its nested "Change lock mode" child dialog, and that closing the returned
 *   {@link AutoCloseable} handle synchronously dismisses both windows and runs {@code onDismissed}.
 * - Verifies {@code ProfileScreen.profileRow} intercepts pointer touches when enabled so the full
 *   card {@link RippleDrawable} animates and dispatches to the profile button listener.
 */
@SuppressWarnings("deprecation")
public final class WorkspaceSecurityDialogTest extends InstrumentationTestCase {

    public void testShowSecurityClosesParentAndChildDialogSynchronouslyAndEnforcesFlagSecure() throws Throwable {
        if (Build.VERSION.SDK_INT < 29) return;
        Context context = getInstrumentation().getTargetContext();
        AtomicReference<AutoCloseable> handleRef = new AtomicReference<>();
        AtomicReference<ProfileGate> gateRef = new AtomicReference<>();
        AtomicBoolean dismissed = new AtomicBoolean(false);
        try {
            try (WorkspaceAccountFixture fixture = new WorkspaceAccountFixture(context)) {
                Activity activity = launchActivity(context.getPackageName(), MainActivity.class, null);
                try {
                    String parentTitle = activity.getString(R.string.security_dialog_title);
                    String changeModeLabel = activity.getString(R.string.security_lock_mode_change);
                    String childSaveLabel = activity.getString(R.string.security_lock_mode_save);
                    runTestOnUiThread(() -> {
                        ProfileGate gate = new ProfileGate(activity);
                        gateRef.set(gate);
                        handleRef.set(WorkspaceDialogs.showSecurity(
                                activity, fixture.account, gate, () -> {}, () -> {}, () -> dismissed.set(true)));
                    });
                    getInstrumentation().waitForIdleSync();

                    View parentWindow = waitForWindowContaining(parentTitle, 3000L);
                    assertNotNull("Expected security parent dialog window", parentWindow);
                    runTestOnUiThread(() -> assertWindowSecure(parentWindow));

                    // Tap "Change lock mode" to open the nested child dialog.
                    runTestOnUiThread(() -> {
                        Button changeBtn = findButton(parentWindow, changeModeLabel);
                        assertNotNull("Expected Change lock mode button inside security dialog", changeBtn);
                        assertTrue(changeBtn.performClick());
                    });
                    getInstrumentation().waitForIdleSync();

                    View childWindow = waitForWindowContaining(childSaveLabel, 3000L);
                    assertNotNull("Expected Change lock mode child dialog window", childWindow);
                    assertNotSame(parentWindow, childWindow);
                    runTestOnUiThread(() -> {
                        assertWindowSecure(childWindow);
                        // R22.6.1: Validation error keeps dialog open, preserves inputs, and shows specific inline error.
                        RadioButton pinRadio = findRadioWithTag(childWindow, ProfileLockPolicy.Mode.PIN);
                        assertNotNull("Expected PIN RadioButton in Change lock mode dialog", pinRadio);
                        pinRadio.performClick();
                        List<EditText> inputs = collectViews(childWindow, EditText.class);
                        assertEquals("Expected first + confirm EditTexts", 2, inputs.size());
                        Button saveBtn = findButton(childWindow, childSaveLabel);
                        assertNotNull(saveBtn);

                        // R23.5.1: Initial empty-PIN Save must still emit a live-region text-change event even
                        // though note already displays the same lock_setup_missing_pin hint text.
                        String missingPinText = activity.getString(R.string.lock_setup_missing_pin);
                        TextView noteView = findTextView(childWindow, missingPinText);
                        assertNotNull("Expected initial PIN hint note in dialog", noteView);
                        AtomicInteger noteTextChanges = new AtomicInteger();
                        noteView.addTextChangedListener(new TextWatcher() {
                            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                                noteTextChanges.incrementAndGet();
                            }
                            @Override public void afterTextChanged(Editable s) {}
                        });
                        saveBtn.performClick();
                        assertTrue("Empty-PIN Save must fire text-change event for TalkBack announcement",
                                noteTextChanges.get() >= 2);
                        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, noteView.getAccessibilityLiveRegion());
                        assertEquals(missingPinText, noteView.getText().toString());
                        int afterFirstInvalid = noteTextChanges.get();
                        saveBtn.performClick();
                        assertTrue("Repeated invalid Save must re-fire text-change event",
                                noteTextChanges.get() >= afterFirstInvalid + 2);

                        inputs.get(0).setText("123456");
                        inputs.get(1).setText("654321");
                        saveBtn.performClick();
                        assertEquals("Mismatched PIN must keep first input intact",
                                "123456", inputs.get(0).getText().toString());
                        assertEquals("Mismatched PIN must keep confirm input intact",
                                "654321", inputs.get(1).getText().toString());
                        TextView mismatchNote = findTextView(childWindow, activity.getString(R.string.lock_pin_mismatch));
                        assertNotNull("Expected inline PIN mismatch error in dialog", mismatchNote);
                        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, mismatchNote.getAccessibilityLiveRegion());

                        inputs.get(0).setText("123");
                        inputs.get(1).setText("123");
                        saveBtn.performClick();
                        assertEquals("123", inputs.get(0).getText().toString());
                        assertNotNull("Expected distinct short-PIN error in dialog",
                                findTextView(childWindow, activity.getString(R.string.lock_setup_missing_pin)));
                    });
                    getInstrumentation().waitForIdleSync();
                    assertNotNull("Dialog must remain open after validation error",
                            waitForWindowContaining(childSaveLabel, 1000L));

                    // Synchronously close handle (as MainActivity.dismissOverlays() does on lock/destroy).
                    runTestOnUiThread(() -> {
                        AutoCloseable handle = handleRef.getAndSet(null);
                        assertNotNull(handle);
                        try {
                            handle.close();
                        } catch (Exception err) {
                            throw new AssertionError(err);
                        }
                    });
                    getInstrumentation().waitForIdleSync();

                    assertTrue("Expected parent dialog window to dismiss",
                            waitUntilWindowGone(parentTitle, 3000L));
                    assertTrue("Expected child dialog window to dismiss",
                            waitUntilWindowGone(childSaveLabel, 3000L));
                    assertTrue("Expected onDismissed callback to execute",
                            waitUntil(dismissed::get, 3000L));
                } finally {
                    try {
                        runTestOnUiThread(() -> {
                            AutoCloseable handle = handleRef.getAndSet(null);
                            if (handle != null) {
                                try { handle.close(); } catch (Exception ignored) {}
                            }
                            ProfileGate gate = gateRef.getAndSet(null);
                            if (gate != null) gate.close();
                        });
                    } finally {
                        ActivityTestLifecycle.finishAndAwaitDestroyed(this, activity);
                    }
                }
            }
        } finally {
            Diagnostics.clear();
        }
    }

    public void testProfileRowInterceptsPointerTouchForFullCardRippleWhenEnabled() throws Throwable {
        Context context = getInstrumentation().getTargetContext();
        Activity activity = launchActivity(context.getPackageName(), MainActivity.class, null);
        try {
            AtomicInteger clicks = new AtomicInteger();
            runTestOnUiThread(() -> {
                try {
                    Class<?> screenClass = Class.forName("org.llm4people.hermes.profile.ProfileScreen");
                    Class<?> badgeToneClass = Class.forName("org.llm4people.hermes.profile.ProfileScreen$BadgeTone");
                    Constructor<?> ctor = screenClass.getDeclaredConstructor(Activity.class, String.class, String.class);
                    ctor.setAccessible(true);
                    Object screen = ctor.newInstance(activity, "Profiles", null);

                    Method profileRow = screenClass.getDeclaredMethod(
                            "profileRow", String.class, String.class, badgeToneClass, View.OnClickListener.class);
                    profileRow.setAccessible(true);
                    Object neutralTone = Enum.valueOf(badgeToneClass.asSubclass(Enum.class), "NEUTRAL");
                    View.OnClickListener listener = v -> clicks.incrementAndGet();
                    Button button = (Button) profileRow.invoke(screen, "Alice", "Locked", neutralTone, listener);
                    assertNotNull("Expected profile row Button", button);
                    assertTrue("Profile button must remain clickable for accessibility", button.isClickable());
                    assertTrue("Profile row parent must be LinearLayout", button.getParent() instanceof LinearLayout);

                    LinearLayout row = (LinearLayout) button.getParent();
                    assertTrue("Outer profile row must own the rounded RippleDrawable",
                            row.getBackground() instanceof RippleDrawable);

                    long now = SystemClock.uptimeMillis();
                    MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 12f, 12f, 0);
                    try {
                        assertTrue("Enabled profile row must intercept pointer touch for full-card ripple",
                                row.onInterceptTouchEvent(down));
                        assertTrue("Outer row click must dispatch to profile button", row.performClick());
                        assertEquals(1, clicks.get());

                        assertTrue("Direct accessibility click on button must still work", button.performClick());
                        assertEquals(2, clicks.get());

                        button.setEnabled(false);
                        assertFalse("Disabled profile row must not intercept pointer touch",
                                row.onInterceptTouchEvent(down));
                        row.performClick();
                        assertEquals("Disabled profile button must not fire click listener", 2, clicks.get());
                    } finally {
                        down.recycle();
                    }
                } catch (Exception err) {
                    throw new AssertionError(err);
                }
            });
        } finally {
            ActivityTestLifecycle.finishAndAwaitDestroyed(this, activity);
        }
    }

    private static void assertWindowSecure(View root) {
        ViewGroup.LayoutParams lp = root.getLayoutParams();
        assertTrue("Expected WindowManager.LayoutParams on dialog root", lp instanceof WindowManager.LayoutParams);
        int flags = ((WindowManager.LayoutParams) lp).flags;
        assertTrue("Expected FLAG_SECURE on security dialog window",
                (flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
    }

    private View waitForWindowContaining(String text, long timeoutMs) throws Throwable {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < deadline) {
            View found = findWindowContainingOnUiThread(text);
            if (found != null) return found;
            SystemClock.sleep(40L);
        }
        return findWindowContainingOnUiThread(text);
    }

    private boolean waitUntilWindowGone(String text, long timeoutMs) throws Throwable {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < deadline) {
            if (findWindowContainingOnUiThread(text) == null) return true;
            SystemClock.sleep(40L);
        }
        return findWindowContainingOnUiThread(text) == null;
    }

    private static boolean waitUntil(java.util.function.BooleanSupplier condition, long timeoutMs) {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition.getAsBoolean()) return true;
            SystemClock.sleep(40L);
        }
        return condition.getAsBoolean();
    }

    private View findWindowContainingOnUiThread(String text) throws Throwable {
        if (Build.VERSION.SDK_INT < 29) return null;
        AtomicReference<View> found = new AtomicReference<>();
        runTestOnUiThread(() -> {
            List<View> windows = WindowInspector.getGlobalWindowViews();
            for (int i = windows.size() - 1; i >= 0; i--) {
                View root = windows.get(i);
                if (root != null && root.isShown() && hasText(root, text)) {
                    found.set(root);
                    return;
                }
            }
        });
        return found.get();
    }

    private static boolean hasText(View root, String text) {
        ArrayList<View> matches = new ArrayList<>();
        root.findViewsWithText(matches, text, View.FIND_VIEWS_WITH_TEXT);
        for (View v : matches) {
            if (v.isShown()) return true;
        }
        return false;
    }

    private static Button findButton(View root, String text) {
        ArrayList<View> matches = new ArrayList<>();
        root.findViewsWithText(matches, text, View.FIND_VIEWS_WITH_TEXT);
        for (View v : matches) {
            if (v instanceof Button b && b.isShown()) return b;
        }
        return null;
    }

    private static TextView findTextView(View root, String text) {
        ArrayList<View> matches = new ArrayList<>();
        root.findViewsWithText(matches, text, View.FIND_VIEWS_WITH_TEXT);
        for (View v : matches) {
            if (v instanceof TextView tv && !(v instanceof Button) && tv.isShown()) return tv;
        }
        return null;
    }

    private static RadioButton findRadioWithTag(View root, Object tag) {
        for (RadioButton rb : collectViews(root, RadioButton.class)) {
            if (tag.equals(rb.getTag())) return rb;
        }
        return null;
    }

    private static <T extends View> List<T> collectViews(View node, Class<T> type) {
        List<T> out = new ArrayList<>();
        collectInto(node, type, out);
        return out;
    }

    private static <T extends View> void collectInto(View node, Class<T> type, List<T> out) {
        if (node == null) return;
        if (type.isInstance(node)) out.add(type.cast(node));
        if (node instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                collectInto(group.getChildAt(i), type, out);
            }
        }
    }
}
