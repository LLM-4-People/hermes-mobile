package org.llm4people.hermes.journey;

import android.app.Instrumentation;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inspector.WindowInspector;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Drives visible native widgets using pointer input and Android's editable-text accessibility action. */
public final class JourneyUi {
    private final Instrumentation driver;
    private final Activity activity;
    private final File evidence;

    public JourneyUi(Instrumentation driver, Activity activity, File evidence) {
        this.driver = driver;
        this.activity = activity;
        this.evidence = evidence;
    }

    <T> T main(Supplier<T> task) {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        driver.runOnMainSync(() -> {
            try { result.set(task.get()); }
            catch (Throwable e) { failure.set(e); }
        });
        if (failure.get() != null) throw new AssertionError("UI operation failed", failure.get());
        return result.get();
    }

    private View window() {
        List<View> windows = WindowInspector.getGlobalWindowViews();
        for (int i = windows.size() - 1; i >= 0; i--) {
            View root = windows.get(i);
            if (root.isShown() && root.hasWindowFocus()) return root;
        }
        return null;
    }

    View find(View v, Predicate<View> predicate) {
        if (v == null || !v.isShown()) return null;
        if (predicate.test(v)) return v;
        if (v instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = find(group.getChildAt(i), predicate);
                if (found != null) return found;
            }
        }
        return null;
    }

    static boolean named(View v, String name) {
        if (name.contentEquals(v.getContentDescription() == null ? "" : v.getContentDescription())) return true;
        if (!(v instanceof TextView text)) return false;
        return name.contentEquals(text.getText()) ||
                (text.getHint() != null && name.contentEquals(text.getHint()));
    }

    <T> T until(String expectation, Supplier<T> probe) {
        long deadline = SystemClock.elapsedRealtime() + 60000;
        while (SystemClock.elapsedRealtime() < deadline) {
            T value = main(probe);
            if (value != null) return value;
            SystemClock.sleep(100);
        }
        throw new AssertionError("Timed out waiting for " + expectation);
    }

    public void see(String label) {
        until(label, () -> find(window(), v -> named(v, label)));
    }

    void heading(String text) {
        until("accessible heading " + text, () -> find(window(), view -> {
            if (!(view instanceof TextView label) || !label.isAccessibilityHeading()) return false;
            AccessibilityNodeInfo node = label.createAccessibilityNodeInfo();
            try { return text.contentEquals(node.getText() == null ? "" : node.getText()); }
            finally { node.recycle(); }
        }));
    }

    void contains(String... fragments) {
        matchingText(fragments);
    }

    void selectableText(String... fragments) {
        TextView body = (TextView) matchingText(fragments);
        if (!main(body::isTextSelectable)) throw new AssertionError("Expected selectable text in the result reader");
    }

    private View matchingText(String... fragments) {
        return until(String.join(" + ", fragments), () -> find(window(), view -> {
            if (!(view instanceof TextView text)) return false;
            String value = text.getText().toString();
            for (String fragment : fragments) if (!value.contains(fragment)) return false;
            return true;
        }));
    }

    void revealText(String... fragments) { reveal(matchingText(fragments)); }

    void revealField(String name) { reveal(field(name)); }

    private void reveal(View view) {
        until("unclipped control in the app viewport", () -> {
            Rect rectangle = new Rect();
            view.getDrawingRect(rectangle);
            view.requestRectangleOnScreen(rectangle, true);
            Rect visible = new Rect();
            return view.getGlobalVisibleRect(visible) && visible.height() >= view.getHeight() &&
                    visible.width() >= view.getWidth() ? Boolean.TRUE : null;
        });
    }

    void absent(String label) {
        if (main(() -> find(window(), v -> named(v, label))) != null)
            throw new AssertionError("Unexpected visible widget: " + label);
    }

    boolean has(String label) {
        return main(() -> find(window(), v -> named(v, label))) != null;
    }

    void tap(String label) {
        View target = until(label, () -> {
            View root = window();
            View match = find(root, v -> named(v, label) && v.isEnabled());
            if (match == null) {
                View list = find(root, v -> v instanceof android.widget.ListView);
                if (list instanceof android.widget.ListView menu) {
                    for (int i = 0; i < menu.getCount(); i++) {
                        Object item = menu.getItemAtPosition(i);
                        if (item instanceof android.view.MenuItem entry && label.contentEquals(entry.getTitle())) {
                            menu.setSelection(i);
                            break;
                        }
                    }
                }
            }
            return match;
        });
        tap(target, label);
    }

    public Button button(String label) {
        return (Button) until("button " + label, () -> find(window(),
                v -> v instanceof Button && v.isEnabled() && named(v, label)));
    }

    public void tapButton(String label) {
        tap(button(label), label);
    }

    void showPendingControl(String expected) {
        String reveal = "Répondre à Hermes";
        View target = until(expected + " or " + reveal, () -> {
            View root = window();
            View prompt = find(root, v -> v instanceof TextView text
                    && text.getText().toString().contains(expected) && v.getGlobalVisibleRect(new Rect()));
            return prompt != null ? prompt : find(root, v -> v.isEnabled() && named(v, reveal)
                    && v.getGlobalVisibleRect(new Rect()));
        });
        if (main(() -> named(target, reveal))) tap(target, reveal);
    }

    void tapInListRow(String anchor, String label) {
        View target = until(label + " in the row containing " + anchor,
                () -> findInListRow(anchor, v -> v.isEnabled() && named(v, label)));
        tap(target, label);
    }

    void containsInListRow(String anchor, String fragment) {
        until(fragment + " in the row containing " + anchor, () -> findInListRow(anchor,
                v -> v instanceof TextView text && text.getText().toString().contains(fragment)));
    }

    private View findInListRow(String anchor, Predicate<View> target) {
        View row = find(window(), v -> named(v, anchor));
        while (row != null && row.getParent() instanceof View parent) {
            if (parent instanceof ListView) {
                return find(row, target);
            }
            row = parent;
        }
        return null;
    }

    private void tap(View target, String label) {
        main(() -> {
            Rect bounds = new Rect();
            target.getDrawingRect(bounds);
            target.requestRectangleOnScreen(bounds, true);
            return null;
        });
        int[] center = until("touch target " + label, () -> {
            Rect visible = new Rect();
            if (!target.isShown() || !target.getGlobalVisibleRect(visible) || visible.isEmpty()) return null;
            int[] root = new int[2];
            target.getRootView().getLocationOnScreen(root);
            return new int[]{root[0] + visible.centerX(), root[1] + visible.centerY()};
        });
        long down = SystemClock.uptimeMillis();
        for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, center[0], center[1], 0);
            try { driver.sendPointerSync(event); }
            finally { event.recycle(); }
        }
        driver.waitForIdleSync();
    }

    EditText field(String label) {
        return until("field " + label, () -> {
            View root = window();
            View direct = find(root, v -> v instanceof EditText && named(v, label));
            if (direct != null) return (EditText) direct;
            View caption = find(root, v -> named(v, label) && v.getLabelFor() != View.NO_ID);
            return caption == null ? null : root.findViewById(caption.getLabelFor());
        });
    }

    String fieldText(String label) {
        EditText edit = field(label);
        return main(() -> edit.getText().toString());
    }

    void fill(String label, String value) {
        EditText edit = field(label);
        main(() -> {
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
            if (!edit.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
                throw new AssertionError("Field refused text entry: " + label);
            return null;
        });
    }

    void back() {
        driver.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
        driver.waitForIdleSync();
    }

    void hideKeyboard() {
        main(() -> {
            activity.getWindow().getInsetsController().hide(WindowInsets.Type.ime());
            return null;
        });
        until("input method hidden", () -> {
            WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
            return insets != null && !insets.isVisible(WindowInsets.Type.ime()) ? Boolean.TRUE : null;
        });
    }

    void sideBySide(String fieldLabel) {
        until("visible conversation beside the editable instrument", () -> {
            View root = window();
            if (root == null || activity.getResources().getConfiguration().screenWidthDp < 720) return null;
            View transcript = find(root, v -> v instanceof android.widget.ListView list
                    && v.getId() != org.llm4people.hermes.R.id.discussion_list && list.getCount() > 1);
            View label = find(root, v -> named(v, fieldLabel) && v.getLabelFor() != View.NO_ID);
            if (label == null || transcript == null) return null;
            View editor = root.findViewById(label.getLabelFor());
            Rect left = new Rect();
            Rect right = new Rect();
            if (!(editor instanceof EditText) || !editor.isEnabled()
                    || !transcript.getGlobalVisibleRect(left) || !editor.getGlobalVisibleRect(right)) return null;
            return left.right <= right.left && left.bottom > right.top && right.bottom > left.top
                    ? Boolean.TRUE : null;
        });
    }

    void imeVisible() {
        until("visible input method", () -> {
            WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
            return insets != null && insets.isVisible(WindowInsets.Type.ime()) ? Boolean.TRUE : null;
        });
    }

    void usableComposer() {
        String[] observations = new String[1];
        try {
            until("fully visible 48dp composer actions", () -> {
                View root = window();
                View editor = find(root, v -> v instanceof EditText && named(v, "Message à Hermes"));
                View send = find(root, v -> v instanceof Button && named(v, "Envoyer"));
                int target = Math.round(activity.getResources().getDisplayMetrics().density * 48);
                for (View control : new View[]{editor, send}) {
                    Rect bounds = new Rect();
                    if (control == null || !control.getGlobalVisibleRect(bounds)
                            || bounds.width() < target || bounds.height() < target
                            || bounds.height() < control.getHeight() || bounds.width() < control.getWidth()) {
                        observations[0] = (control == editor ? "editor" : "send") + " observed "
                                + bounds + ", measured " + (control == null ? "absent"
                                : control.getWidth() + "×" + control.getHeight()) + ", 48dp=" + target;
                        return null;
                    }
                }
                return Boolean.TRUE;
            });
        } catch (AssertionError timeout) {
            capture("09-compositeur-incomplet");
            throw new AssertionError(observations[0], timeout);
        }
    }

    public void capture(String name) {
        Bitmap bitmap = until("laid out app viewport", () -> {
            View activityRoot = activity.getWindow().getDecorView();
            View focused = window();
            if (focused == null || focused.getWidth() == 0 || activityRoot.getWidth() == 0
                    || activityRoot.getHeight() == 0) return null;
            Bitmap shot = Bitmap.createBitmap(activityRoot.getWidth(), activityRoot.getHeight(),
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(shot);
            int[] origin = new int[2];
            activityRoot.getLocationOnScreen(origin);
            List<View> roots = WindowInspector.getGlobalWindowViews();
            int dimWindow = -1;
            for (int i = 0; i < roots.size(); i++) {
                View view = roots.get(i);
                if (view.isShown() && view.getLayoutParams() instanceof WindowManager.LayoutParams lp
                        && (lp.flags & WindowManager.LayoutParams.FLAG_DIM_BEHIND) != 0) dimWindow = i;
            }
            for (int i = 0; i < roots.size(); i++) {
                View view = roots.get(i);
                if (!view.isShown() || view.getWidth() == 0 || view.getHeight() == 0) continue;
                if (i == dimWindow) {
                    float amount = ((WindowManager.LayoutParams) view.getLayoutParams()).dimAmount;
                    canvas.drawColor(Color.argb(Math.round(255 * amount), 0, 0, 0));
                }
                int[] location = new int[2];
                view.getLocationOnScreen(location);
                canvas.save();
                canvas.translate(location[0] - origin[0], location[1] - origin[1]);
                canvas.clipRect(0, 0, view.getWidth(), view.getHeight());
                view.draw(canvas);
                canvas.restore();
                if (view == focused) break;
            }
            return shot;
        });
        try (FileOutputStream output = new FileOutputStream(new File(evidence, name + ".png"))) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                throw new AssertionError("Failed to encode synthetic fixture capture");
        } catch (java.io.IOException ex) {
            throw new AssertionError("Failed to persist fixture capture", ex);
        } finally { bitmap.recycle(); }
    }
}
