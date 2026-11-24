package org.llm4people.hermes.panel;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.test.InstrumentationTestCase;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.view.inspector.WindowInspector;
import android.widget.EditText;
import android.widget.LinearLayout;

import org.llm4people.hermes.ActivityTestLifecycle;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;

@SuppressWarnings("deprecation")
public final class PanelWidthTest extends InstrumentationTestCase {
    public void testSameReminderDraftSurvivesPhoneTabletAndSplitWindowGeometry() throws Throwable {
        if (Build.VERSION.SDK_INT < 29) return;
        Context context = getInstrumentation().getTargetContext();
        Activity activity = getInstrumentation().startActivitySync(context.getPackageManager()
                .getLaunchIntentForPackage(context.getPackageName()));
        SessionPanel panel = new SessionPanel(activity, null, null);
        try {
            runTestOnUiThread(() -> new ReminderEditor(panel, new ReminderPanel(panel), null).show());
            getInstrumentation().waitForIdleSync();
            runTestOnUiThread(() -> {
                PanelFrame frame = null;
                for (View root : WindowInspector.getGlobalWindowViews()) {
                    frame = find(root, PanelFrame.class);
                    if (frame != null) break;
                }
                assertNotNull(frame);
                PanelFrame.Sections pair = find(frame, PanelFrame.Sections.class);
                EditText title = find(pair.first, EditText.class);
                assertTrue(title.requestFocus());
                title.setText("Réunion à Montréal");
                title.setSelection(7);
                int initialWidth = frame.getWidth(), initialHeight = frame.getHeight();
                try {
                    size(frame, 320, 640);
                    assertEquals(LinearLayout.VERTICAL, pair.getOrientation());
                    nearDp(title, 280);
                    assertTrue(title.getHeight() >= Ui.dp(activity, 48));

                    size(frame, 1200, 800);
                    boolean room = 2 * Ui.dp(activity, Math.max(340, 320 * activity.getResources().getConfiguration().fontScale))
                            + Ui.dp(activity, 24) + 2 * Ui.dp(activity, 20) <= Ui.dp(activity, 1040);
                    if (room) {
                        assertEquals(LinearLayout.HORIZONTAL, pair.getOrientation());
                        nearDp(title, 488);
                        assertEquals(pair.first.getTop(), pair.second.getTop());
                        int gap = Math.max(pair.second.getLeft() - pair.first.getRight(),
                                pair.first.getLeft() - pair.second.getRight());
                        assertEquals(Ui.dp(activity, 24), gap);
                    } else {
                        assertEquals(LinearLayout.VERTICAL, pair.getOrientation());
                        nearDp(title, 640);
                    }

                    size(frame, 550, 800);
                    assertEquals(LinearLayout.VERTICAL, pair.getOrientation());
                    nearDp(title, 510);
                    assertSame(title, frame.findFocus());
                    assertEquals("Réunion à Montréal", title.getText().toString());
                    assertEquals(7, title.getSelectionStart());
                } finally {
                    measure(frame, initialWidth, initialHeight);
                    frame.requestLayout();
                }
            });
        } finally {
            try { runTestOnUiThread(() -> { panel.close(); }); }
            finally { ActivityTestLifecycle.finishAndAwaitDestroyed(this, activity); }
        }
    }

    public void testLargeTypeStacksAtPortraitTabletWidthAndKeepsSinglePagesBounded() throws Throwable {
        runTestOnUiThread(() -> {
            Context base = getInstrumentation().getTargetContext();
            Configuration large = new Configuration(base.getResources().getConfiguration());
            large.fontScale = 1.5f;
            Context context = new ContextThemeWrapper(base.createConfigurationContext(large), R.style.Theme_Hermes);
            LinearLayout sheet = Ui.column(context);
            PanelFrame frame = new PanelFrame(context, sheet);
            size(frame, 1200, 800);
            nearDp(sheet, 680);
            assertEquals(Ui.dp(context, 260), sheet.getLeft(), 1);
            LinearLayout page = Ui.column(context);
            Ui.pad(page, PanelFrame.PAGE_PADDING_DP, 12);
            sheet.addView(page);
            PanelFrame.Sections sections = frame.sections(page);
            size(frame, 800, 1200);
            assertEquals(LinearLayout.VERTICAL, sections.getOrientation());
            nearDp(sections.first, 640);
            size(frame, 1200, 800);
            assertEquals(LinearLayout.HORIZONTAL, sections.getOrientation());
            nearDp(sections.first, 488);
            nearDp(sections.second, 488);
        });
    }

    public void testPickerSwitchesCleanlyBetweenShortLandscapeAndPortraitPreservingSearchDraft() throws Throwable {
        runTestOnUiThread(() -> {
            Context context = getInstrumentation().getTargetContext();
            LinearLayout sheet = Ui.column(context);
            PanelFrame frame = new PanelFrame(context, sheet);
            LinearLayout navigation = Ui.row(context);
            navigation.addView(Ui.button(context, "Retour", v -> {}));
            sheet.addView(navigation);
            LinearLayout results = Ui.column(context);
            sheet.addView(results, new LinearLayout.LayoutParams(-1, 0, 1));
            View header = Ui.text(context, "Choisir le modèle", 29);
            EditText search = new EditText(context);
            search.setText("mistral");
            search.setSelection(4);
            boolean[] splitFlag = {false};
            frame.bindPicker(navigation, results, header, search,
                    new LinearLayout.LayoutParams(-1, -2), splitFlag, true);
            assertTrue(search.requestFocus());

            // Short landscape (e.g. phone with IME visible): split horizontally.
            size(frame, 640, 82);
            assertEquals(LinearLayout.HORIZONTAL, sheet.getOrientation());
            assertTrue(splitFlag[0]);
            assertSame(navigation, search.getParent());
            assertEquals("mistral", search.getText().toString());
            assertEquals(4, search.getSelectionStart());

            // Rotating to portrait un-splits back to vertical so phone columns are never crushed.
            size(frame, 360, 640);
            assertEquals(LinearLayout.VERTICAL, sheet.getOrientation());
            assertFalse(splitFlag[0]);
            assertSame(results, search.getParent());
            nearDp(sheet, 360);
            assertEquals("mistral", search.getText().toString());
            assertEquals(4, search.getSelectionStart());
            assertSame(search, frame.findFocus());

            // Rotating back to short landscape re-splits cleanly with the same EditText.
            size(frame, 740, 360);
            assertEquals(LinearLayout.HORIZONTAL, sheet.getOrientation());
            assertTrue(splitFlag[0]);
            assertSame(navigation, search.getParent());
            nearDp(sheet, 740);
            assertEquals("mistral", search.getText().toString());
            assertEquals(4, search.getSelectionStart());
        });
    }

    private static void size(PanelFrame frame, int widthDp, int heightDp) {
        int w = Ui.dp(frame.getContext(), widthDp) + frame.getPaddingLeft() + frame.getPaddingRight();
        int h = Ui.dp(frame.getContext(), heightDp) + frame.getPaddingTop() + frame.getPaddingBottom();
        measure(frame, w, h);
    }

    private static void measure(View view, int w, int h) {
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, w, h);
    }

    private static void nearDp(View v, int expected) {
        assertTrue("Expected " + expected + " dp width, got " + v.getWidth() + " px",
                Math.abs(Ui.dp(v.getContext(), expected) - v.getWidth()) <= 2);
    }

    private static <T extends View> T find(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            T child = find(group.getChildAt(i), type);
            if (child != null) return child;
        }
        return null;
    }
}
