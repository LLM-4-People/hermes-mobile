package org.llm4people.hermes.verification;

import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;

import com.android.uiautomator.core.UiObject;
import com.android.uiautomator.core.UiSelector;
import com.android.uiautomator.testrunner.UiAutomatorTestCase;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Set one focused Android editor atomically, without keyboard autocorrection or dropped key events. */
@SuppressWarnings("deprecation")
public final class FocusedText extends UiAutomatorTestCase {
    private static final class Editor extends UiObject {
        Editor() {
            super(new UiSelector().className("android.widget.EditText").focused(true));
        }

        boolean replace(String value) {
            AccessibilityNodeInfo node = findAccessibilityNodeInfo(10000);
            if (node == null || !node.isEditable()) return false;
            Bundle arguments = new Bundle();
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
        }
    }

    public void testReplace() throws Exception {
        String path = getParams().getString("valueFile");
        byte[] contents = Files.readAllBytes(Paths.get(path));
        assertTrue("The focused editor rejected text entry",
                new Editor().replace(new String(contents, StandardCharsets.UTF_8)));
    }
}
