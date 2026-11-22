package org.llm4people.hermes.panel;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckedTextView;
import android.widget.EditText;
import android.widget.ListView;
import java.util.function.Consumer;
import junit.framework.Assert;
import org.llm4people.hermes.Ui;

/** Measures the real dialog contents in the app area left above a phone keyboard. */
public final class PickerWindow {
    private PickerWindow() {}

    public static void open(SessionPanel panel, boolean landscape) {
        Configuration window = new Configuration(panel.activity.getResources().getConfiguration());
        window.orientation = landscape ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT;
        window.screenWidthDp = landscape ? 640 : 320;
        // Exercise a live multi-window resize between opening settings and choosing a model.
        window.screenHeightDp = landscape ? 481 : 640;
        new SessionSettings(panel, "thread", "thread", () -> window).show();
        if (landscape) window.screenHeightDp = 320;
    }

    public static void check(ListView list, EditText search, boolean landscape, Consumer<CheckedTextView> tap) {
        ViewGroup dialog = list.getRootView().findViewById(android.R.id.content);
        ViewGroup panel = (ViewGroup) dialog.getChildAt(0);
        int width = Ui.dp(panel.getContext(), landscape ? 640 : 320);
        int height = Ui.dp(panel.getContext(), landscape ? 82 : 240);
        int minimum = Ui.dp(panel.getContext(), 48);
        int oldWidth = panel.getWidth(), oldHeight = panel.getHeight();
        try {
            // These rectangles exclude system bars. The live decor may already supply their padding.
            size(panel, width + panel.getPaddingLeft() + panel.getPaddingRight(),
                    height + panel.getPaddingTop() + panel.getPaddingBottom());
            Rect viewport = new Rect(panel.getPaddingLeft(), panel.getPaddingTop(),
                    panel.getPaddingLeft() + width, panel.getPaddingTop() + height);
            Rect field = visibleBounds(search, panel, viewport);
            Assert.assertTrue("Search target " + field, field.width() >= minimum && field.height() >= minimum);
            Assert.assertTrue(search.isEnabled() && search.isFocusable());
            Assert.assertTrue("No laid-out native match", list.getChildCount() > 0);
            CheckedTextView row = (CheckedTextView) list.getChildAt(0);
            Rect target = visibleBounds(row, panel, viewport);
            Assert.assertTrue("First model target " + target, target.width() >= minimum && target.height() >= minimum);
            Assert.assertFalse("Search overlaps the result", Rect.intersects(field, target));
            Assert.assertEquals("Match must be reachable through the native hit test", 0,
                    list.pointToPosition(row.getLeft() + row.getWidth() / 2, row.getTop() + minimum / 2));
            Assert.assertTrue(list.getAdapter().isEnabled(0));
            if (tap != null) tap.accept(row);
        } finally {
            if (panel.isAttachedToWindow()) {
                size(panel, oldWidth, oldHeight);
                panel.requestLayout();
            }
        }
    }

    private static Rect visibleBounds(View child, ViewGroup root, Rect viewport) {
        Rect visible = new Rect(viewport);
        for (View view = child; view != root; view = (View) view.getParent()) {
            Rect box = new Rect(0, 0, view.getWidth(), view.getHeight());
            root.offsetDescendantRectToMyCoords(view, box);
            if (!visible.intersect(box)) return new Rect();
        }
        return visible;
    }

    private static void size(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(view.getLeft(), view.getTop(), view.getLeft() + width, view.getTop() + height);
    }
}
