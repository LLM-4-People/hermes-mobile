package org.llm4people.hermes.verification;

import android.graphics.Rect;
import android.os.Bundle;
import android.util.Base64;
import android.util.Xml;
import android.view.accessibility.AccessibilityNodeInfo;

import com.android.uiautomator.core.Configurator;
import com.android.uiautomator.core.UiObject;
import com.android.uiautomator.core.UiSelector;
import com.android.uiautomator.testrunner.UiAutomatorTestCase;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlSerializer;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Read rendered Android views and enter synthetic test values through one shell UI automation session. */
@SuppressWarnings("deprecation")
public final class FocusedText extends UiAutomatorTestCase {
    private static final class Control extends UiObject {
        Control(UiSelector selector) {
            super(selector);
        }

        AccessibilityNodeInfo node() {
            return findAccessibilityNodeInfo(800);
        }
    }

    private static String string(CharSequence text) {
        return text == null ? "" : text.toString();
    }

    private static void writeNode(XmlSerializer xml, AccessibilityNodeInfo node) throws Exception {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        xml.startTag(null, "node");
        xml.attribute(null, "text", node.isPassword() ? "" : string(node.getText()));
        xml.attribute(null, "content-desc", string(node.getContentDescription()));
        xml.attribute(null, "resource-id", string(node.getViewIdResourceName()));
        xml.attribute(null, "class", string(node.getClassName()));
        xml.attribute(null, "package", string(node.getPackageName()));
        xml.attribute(null, "window-id", String.valueOf(node.getWindowId()));
        xml.attribute(null, "scrollable", String.valueOf(node.isScrollable()));
        xml.attribute(null, "checked", String.valueOf(node.isChecked()));
        xml.attribute(null, "bounds", bounds.toShortString());
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null && child.isVisibleToUser()) writeNode(xml, child);
        }
        xml.endTag(null, "node");
    }

    private static String snapshot() throws Exception {
        AccessibilityNodeInfo root = new Control(new UiSelector()).node();
        if (root == null) return "";
        StringWriter output = new StringWriter();
        XmlSerializer xml = Xml.newSerializer();
        xml.setOutput(output);
        writeNode(xml, root);
        xml.flush();
        return output.toString();
    }

    private static boolean replace(String label, String value) {
        AccessibilityNodeInfo editor;
        if (label.contains(":id/")) {
            editor = new Control(new UiSelector().resourceId(label)).node();
            if (editor == null && "io.heckel.ntfy:id/default_server_dialog_url".equals(label)) {
                UiSelector ntfyField = new UiSelector().packageName("io.heckel.ntfy").className("android.widget.EditText");
                if (new Control(ntfyField.instance(1)).node() == null) editor = new Control(ntfyField.instance(0)).node();
            }
        } else {
            AccessibilityNodeInfo caption = new Control(new UiSelector().text(label)).node();
            if (caption == null) return false;
            editor = "android.widget.EditText".contentEquals(caption.getClassName()) ? caption : caption.getLabelFor();
            if (editor == null) {
                AccessibilityNodeInfo group = caption.getParent();
                if (group == null) return false;
                for (int i = 0; i + 1 < group.getChildCount(); i++) {
                    if (caption.equals(group.getChild(i))) {
                        editor = group.getChild(i + 1);
                        break;
                    }
                }
            }
        }
        if (editor == null || !"android.widget.EditText".contentEquals(editor.getClassName())) return false;
        Bundle text = new Bundle();
        text.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        return editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text);
    }

    private static String checkFocusedWindow(String window) throws Exception {
        Process report = new ProcessBuilder("dumpsys", "window").redirectErrorStream(true).start();
        String expected = "mCurrentFocus=" + window;
        boolean focused = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(report.getInputStream(),
                StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                if (expected.equals(line.trim())) focused = true;
            }
        }
        if (report.waitFor() != 0) return "window_report";
        return focused ? null : "focus";
    }

    private static void findVisibleTitles(AccessibilityNodeInfo node, String title,
            List<AccessibilityNodeInfo> matches) {
        // Match the child traversal that produced the host's verified passive snapshot.
        if (title.equals(string(node.getText()))) matches.add(node);
        for (int i = 0; i < node.getChildCount() && matches.size() < 2; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null && child.isVisibleToUser()) findVisibleTitles(child, title, matches);
        }
    }

    private String tapNotice(JSONObject command) throws Exception {
        String focusFailure = checkFocusedWindow(command.getString("focus"));
        if (focusFailure != null) return focusFailure;
        AccessibilityNodeInfo root = new Control(new UiSelector()).node();
        if (root == null) return "root";
        if (!"com.android.systemui".equals(string(root.getPackageName()))) return "package";
        if (root.getWindowId() != command.getInt("window")) return "window_id";
        List<AccessibilityNodeInfo> matches = new ArrayList<>(2);
        findVisibleTitles(root, command.getString("title"), matches);
        if (matches.isEmpty()) return "no_title";
        if (matches.size() > 1) return "multiple_titles";
        AccessibilityNodeInfo target = matches.get(0);
        if (target.getWindowId() != root.getWindowId()) return "title_window";
        Rect heading = new Rect();
        target.getBoundsInScreen(heading);
        JSONArray coordinates = command.getJSONArray("bounds");
        Rect expected = new Rect(coordinates.getInt(0), coordinates.getInt(1),
                coordinates.getInt(2), coordinates.getInt(3));
        if (heading.isEmpty() || !expected.equals(heading)) return "bounds";
        return getUiDevice().click(heading.centerX(), heading.centerY()) ? "tapped" : "touch";
    }

    private static void response(String value) {
        String encoded = Base64.encodeToString(value.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        // The SDK's simple JUnit reporter can leave a progress dot on the current output line.
        System.out.println();
        System.out.println("HM_UI " + encoded);
        System.out.flush();
    }

    public void testSession() throws Exception {
        Configurator.getInstance().setWaitForIdleTimeout(200);
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        response("ready");
        String request;
        while ((request = input.readLine()) != null) {
            JSONObject command = new JSONObject(request);
            switch (command.getString("action")) {
                case "tree":
                    response(snapshot());
                    break;
                case "text":
                    response(replace(command.getString("label"), command.getString("value")) ? "applied" : "retry");
                    break;
                case "open-shade":
                    response(getUiDevice().openNotification() ? "opened" : "retry");
                    break;
                case "tap-notice":
                    response(tapNotice(command));
                    break;
                case "scroll-shade":
                    AccessibilityNodeInfo list = new Control(new UiSelector()
                            .resourceId("com.android.systemui:id/notification_stack_scroller")).node();
                    int direction = command.getBoolean("forward") ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                            : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
                    response(list != null && list.performAction(direction) ? "scrolled" : "edge");
                    break;
                case "finish":
                    response("finished");
                    return;
                default:
                    throw new IllegalArgumentException("Unknown UI command");
            }
        }
    }
}
