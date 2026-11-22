package org.llm4people.hermes.verification;

import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;

import com.android.uiautomator.core.Configurator;
import com.android.uiautomator.core.UiObject;
import com.android.uiautomator.core.UiSelector;
import com.android.uiautomator.testrunner.UiAutomatorTestCase;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Enter text in a named Android editor without synthesizing keyboard input. */
@SuppressWarnings("deprecation")
public final class FocusedText extends UiAutomatorTestCase {
    private static final class Control extends UiObject {
        Control(UiSelector selector) {
            super(selector);
        }

        AccessibilityNodeInfo node() {
            return findAccessibilityNodeInfo(10000);
        }
    }

    public void testReplace() throws Exception {
        Configurator.getInstance().setWaitForIdleTimeout(300);
        byte[] contents = Files.readAllBytes(Paths.get(getParams().getString("valueFile")));
        String[] parts = new String(contents, StandardCharsets.UTF_8).split("\n", 2);
        String label = parts[0];
        AccessibilityNodeInfo editor;
        if (label.contains(":id/")) {
            editor = new Control(new UiSelector().resourceId(label)).node();
        } else {
            AccessibilityNodeInfo caption = new Control(new UiSelector().text(label)).node();
            assertNotNull("The editor label is absent", caption);
            editor = caption.getLabelFor();
            if (editor == null) {
                AccessibilityNodeInfo group = caption.getParent();
                assertNotNull("The editor label has no group", group);
                for (int i = 0; i + 1 < group.getChildCount(); i++) {
                    if (caption.equals(group.getChild(i))) {
                        editor = group.getChild(i + 1);
                        break;
                    }
                }
            }
        }
        assertNotNull("No editor associated with this label", editor);
        assertEquals("The control is not a text editor", "android.widget.EditText", editor.getClassName().toString());
        Bundle text = new Bundle();
        text.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, parts[1]);
        assertTrue("The text editor rejected ACTION_SET_TEXT", editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text));
    }
}
