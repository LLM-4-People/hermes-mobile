package org.llm4people.hermes;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.RadioButton;

import org.json.JSONObject;
import org.llm4people.hermes.artifact.ArtifactDesk;
import org.llm4people.hermes.artifact.DataTableBlock;
import org.llm4people.hermes.artifact.ThemePalette;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.os.BiometricVault;
import org.llm4people.hermes.profile.Account;
import org.llm4people.hermes.profile.ProfileGate;
import org.llm4people.hermes.profile.ProfileLockPolicy;
import org.llm4people.hermes.profile.WorkspaceAccountFixture;
import org.llm4people.hermes.search.UniversalSearchModel;
import org.llm4people.hermes.search.UniversalSearchSheet;
import org.llm4people.hermes.thread.ThreadWorkspace;
import org.llm4people.hermes.voice.VoiceDrafts;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Instrumentation regression tests for Triple-Check Round 8 accessibility polish:
 * - R8.5.1: Editable search EditTexts in DataTableBlock & UniversalSearchSheet avoid contentDescription
 *   and rely on hint (+ root accessibilityPaneTitle on UniversalSearchSheet).
 * - R8.5.2: Dialog EditTexts in WorkspaceForms.reviewDraft() & ThreadWorkspace.rename() retain non-empty
 *   hints even after text is cleared.
 * - R8.5.3: Security lock-mode RadioButtons use a stateful ColorStateList with disabled_ink.
 */
@SuppressWarnings("deprecation")
public final class AccessibilityControlsRegressionTest extends InstrumentationTestCase {

    @Override
    protected void tearDown() throws Exception {
        try {
            Diagnostics.clear();
        } finally {
            super.tearDown();
        }
    }

    public void testDataTableFilterEditTextUsesHintWithoutContentDescription() throws Throwable {
        Context context = getInstrumentation().getTargetContext();
        MainActivity activity = launchActivity(context.getPackageName(), MainActivity.class, null);
        try {
            runTestOnUiThread(() -> {
                try {
                    List<DataTableBlock.Column> cols = Arrays.asList(
                            new DataTableBlock.Column("name", "Name", false),
                            new DataTableBlock.Column("count", "Count", true));
                    List<List<String>> rows = Arrays.asList(
                            Arrays.asList("Alpha", "1"),
                            Arrays.asList("Beta", "2"));
                    DataTableBlock.Model model = new DataTableBlock.Model(cols, rows, true, true, "");
                    WorkspaceHost host = new WorkspaceHost(activity, () -> true);
                    Method build = DataTableBlock.class.getDeclaredMethod(
                            "build", Context.class, DataTableBlock.Model.class, ThemePalette.class, ArtifactDesk.Host.class);
                    build.setAccessible(true);
                    View rendered = (View) build.invoke(null, activity, model, null, host);
                    EditText search = findFirst(rendered, EditText.class);
                    assertNotNull("Expected filter EditText inside DataTableBlock", search);
                    assertNull("Editable search EditText must not set contentDescription",
                            search.getContentDescription());
                    String expectedHint = activity.getString(R.string.ws_table_filter_hint);
                    assertNotNull("Expected non-null hint on DataTableBlock filter", search.getHint());
                    assertEquals(expectedHint, search.getHint().toString());

                    search.setText("");
                    assertNull(search.getContentDescription());
                    assertEquals(expectedHint, search.getHint().toString());
                } catch (Exception err) {
                    throw new AssertionError(err);
                }
            });
        } finally {
            finishAndAwaitDestroyed(activity);
        }
    }

    public void testUniversalSearchSheetUsesHintAndRootPaneTitleWithoutEditableContentDescription() throws Throwable {
        Context context = getInstrumentation().getTargetContext();
        Activity activity = launchActivity(context.getPackageName(), MainActivity.class, null);
        AtomicReference<UniversalSearchSheet> sheetRef = new AtomicReference<>();
        try {
            runTestOnUiThread(() -> {
                try {
                    UniversalSearchSheet sheet = UniversalSearchSheet.show(
                            activity, null, Collections.emptyList(), new UniversalSearchSheet.Callbacks() {
                                @Override public void openThread(String id, String title) {}
                                @Override public void openNote(String noteId) {}
                                @Override public void openSharedItem(UniversalSearchModel.Result item) {}
                                @Override public void runAction(String actionId) {}
                                @Override public void onDismissed() {}
                            });
                    sheetRef.set(sheet);

                    Field queryFieldRef = UniversalSearchSheet.class.getDeclaredField("queryField");
                    queryFieldRef.setAccessible(true);
                    EditText queryField = (EditText) queryFieldRef.get(sheet);
                    assertNotNull("Expected queryField on UniversalSearchSheet", queryField);
                    assertNull("Editable queryField must not set contentDescription",
                            queryField.getContentDescription());
                    assertEquals(activity.getString(R.string.universal_search_hint),
                            String.valueOf(queryField.getHint()));

                    Field dialogField = UniversalSearchSheet.class.getDeclaredField("dialog");
                    dialogField.setAccessible(true);
                    Dialog dialog = (Dialog) dialogField.get(sheet);
                    assertNotNull("Expected dialog on UniversalSearchSheet", dialog);
                    ViewGroup content = dialog.findViewById(android.R.id.content);
                    assertNotNull("Expected content container in UniversalSearchSheet dialog", content);
                    assertTrue("Expected root view inside UniversalSearchSheet dialog", content.getChildCount() > 0);
                    View root = content.getChildAt(0);
                    assertEquals(activity.getString(R.string.universal_search_title),
                            String.valueOf(root.getAccessibilityPaneTitle()));
                } catch (Exception err) {
                    throw new AssertionError(err);
                }
            });
        } finally {
            try {
                runTestOnUiThread(() -> {
                    UniversalSearchSheet sheet = sheetRef.getAndSet(null);
                    if (sheet != null) sheet.close();
                });
            } finally {
                finishAndAwaitDestroyed(activity);
            }
        }
    }

    public void testReviewDraftAndThreadRenameDialogEditTextsPreserveHintWhenCleared() throws Throwable {
        Context context = getInstrumentation().getTargetContext();
        MainActivity activity = launchActivity(context.getPackageName(), MainActivity.class, null);
        AtomicReference<ThreadWorkspace> workspaceRef = new AtomicReference<>();
        AtomicReference<VoiceDrafts> voiceRef = new AtomicReference<>();
        try (WorkspaceAccountFixture fixture = new WorkspaceAccountFixture(context)) {
            runTestOnUiThread(() -> {
                try {
                    // 1. WorkspaceForms.reviewDraft() EditText retains ws_form_enter_message hint when cleared.
                    WorkspaceHost host = new WorkspaceHost(activity, () -> true);
                    Class<?> formsClass = Class.forName("org.llm4people.hermes.artifact.WorkspaceForms");
                    Method reviewDraft = formsClass.getDeclaredMethod(
                            "reviewDraft", Activity.class, ArtifactDesk.Host.class, String.class);
                    reviewDraft.setAccessible(true);
                    reviewDraft.invoke(null, activity, host, "Draft message");

                    AlertDialog reviewDialog = findLatestDeskDialog(activity);
                    assertNotNull("Expected active WorkspaceForms.reviewDraft AlertDialog", reviewDialog);
                    EditText draftEdit = findFirst(reviewDialog.getWindow().getDecorView(), EditText.class);
                    assertNotNull("Expected EditText inside reviewDraft dialog", draftEdit);
                    draftEdit.setText("");
                    assertNotNull("Expected non-null hint after clearing reviewDraft EditText", draftEdit.getHint());
                    assertEquals(activity.getString(R.string.ws_form_enter_message), draftEdit.getHint().toString());
                    ArtifactDesk.dismiss(activity);

                    // 2. ThreadWorkspace.rename() EditText retains thread_rename_title hint when cleared.
                    VoiceDrafts voice = new VoiceDrafts(activity);
                    voiceRef.set(voice);
                    HermesApi api = new HermesApi("http://127.0.0.1:1", "tok");
                    ThreadWorkspace workspace = new ThreadWorkspace(
                            activity, api, fixture.account, new ThreadWorkspace.Host() {
                                @Override public void selected(String sessionId, String title) {}
                                @Override public void pendingChanged() {}
                                @Override public JSONObject options(String sessionId) { return new JSONObject(); }
                                @Override public void timing(String sessionId, long firstTextMillis,
                                                             long elapsedMillis, long outputTokens) {}
                            }, voice);
                    workspaceRef.set(workspace);

                    Field indexField = ThreadWorkspace.class.getDeclaredField("index");
                    indexField.setAccessible(true);
                    Object indexViews = indexField.get(workspace);
                    setField(workspace, "screen", indexViews);
                    setField(workspace, "session", "session-rename-test");
                    setField(workspace, "remoteSession", "session-rename-test");
                    setField(workspace, "title", "Existing title");

                    workspace.rename();
                    Field dialogField = indexViews.getClass().getDeclaredField("dialog");
                    dialogField.setAccessible(true);
                    AlertDialog renameDialog = (AlertDialog) dialogField.get(indexViews);
                    assertNotNull("Expected active ThreadWorkspace.rename AlertDialog", renameDialog);
                    EditText renameEdit = findFirst(renameDialog.getWindow().getDecorView(), EditText.class);
                    assertNotNull("Expected EditText inside ThreadWorkspace.rename dialog", renameEdit);
                    renameEdit.setText("");
                    assertNotNull("Expected non-null hint after clearing rename EditText", renameEdit.getHint());
                    assertEquals(activity.getString(R.string.thread_rename_title), renameEdit.getHint().toString());
                    renameDialog.dismiss();
                } catch (Exception err) {
                    throw new AssertionError(err);
                }
            });
        } finally {
            try {
                runTestOnUiThread(() -> {
                    ArtifactDesk.dismiss(activity);
                    ThreadWorkspace workspace = workspaceRef.getAndSet(null);
                    if (workspace != null) workspace.close();
                    VoiceDrafts voice = voiceRef.getAndSet(null);
                    if (voice != null) {
                        voice.reader().stop();
                        voice.cancel();
                    }
                });
            } finally {
                finishAndAwaitDestroyed(activity);
            }
        }
    }

    public void testSecurityLockModeRadioButtonsUseStatefulEnabledDisabledInk() throws Throwable {
        Context context = getInstrumentation().getTargetContext();
        Activity activity = launchActivity(context.getPackageName(), MainActivity.class, null);
        AtomicReference<ProfileGate> gateRef = new AtomicReference<>();
        AtomicReference<AlertDialog> dialogRef = new AtomicReference<>();
        try (WorkspaceAccountFixture fixture = new WorkspaceAccountFixture(context)) {
            runTestOnUiThread(() -> {
                try {
                    ProfileGate gate = new ProfileGate(activity);
                    gateRef.set(gate);
                    BiometricVault.Session[] activeEnroll = new BiometricVault.Session[1];
                    AlertDialog[] childHolder = new AlertDialog[1];
                    Method showMode = WorkspaceDialogs.class.getDeclaredMethod(
                            "showChangeLockModeDialog",
                            Activity.class, Account.class, ProfileGate.class,
                            BiometricVault.Session[].class, AlertDialog[].class, Runnable.class);
                    showMode.setAccessible(true);
                    AlertDialog dialog = (AlertDialog) showMode.invoke(
                            null, activity, fixture.account, gate, activeEnroll, childHolder, (Runnable) () -> {});
                    dialogRef.set(dialog);
                    assertNotNull("Expected Change lock mode AlertDialog", dialog);

                    List<RadioButton> radios = new ArrayList<>();
                    collect(dialog.getWindow().getDecorView(), RadioButton.class, radios);
                    assertEquals(ProfileLockPolicy.Mode.values().length, radios.size());

                    int expectedEnabled = activity.getColor(R.color.ink);
                    int expectedDisabled = activity.getColor(R.color.disabled_ink);
                    for (RadioButton rb : radios) {
                        ColorStateList colors = rb.getTextColors();
                        assertNotNull("RadioButton must have a ColorStateList", colors);
                        assertTrue("RadioButton text colors must be stateful", colors.isStateful());
                        assertEquals("Enabled RadioButton must use R.color.ink",
                                expectedEnabled, colors.getColorForState(new int[]{android.R.attr.state_enabled}, 0));
                        assertEquals("Disabled RadioButton must use R.color.disabled_ink",
                                expectedDisabled, colors.getColorForState(new int[]{-android.R.attr.state_enabled}, 0));
                    }
                } catch (Exception err) {
                    throw new AssertionError(err);
                }
            });
        } finally {
            try {
                runTestOnUiThread(() -> {
                    AlertDialog dialog = dialogRef.getAndSet(null);
                    if (dialog != null && dialog.isShowing()) dialog.dismiss();
                    ProfileGate gate = gateRef.getAndSet(null);
                    if (gate != null) gate.close();
                });
            } finally {
                finishAndAwaitDestroyed(activity);
            }
        }
    }

    private void finishAndAwaitDestroyed(Activity activity) throws Throwable {
        if (activity == null) return;
        runTestOnUiThread(() -> {
            if (!activity.isFinishing() && !activity.isDestroyed()) {
                activity.finish();
            }
        });
        getInstrumentation().waitForIdleSync();
        long deadline = SystemClock.uptimeMillis() + 5_000L;
        AtomicBoolean destroyed = new AtomicBoolean(false);
        while (SystemClock.uptimeMillis() < deadline) {
            runTestOnUiThread(() -> destroyed.set(activity.isDestroyed()));
            if (destroyed.get()) return;
            SystemClock.sleep(10L);
        }
        runTestOnUiThread(() -> destroyed.set(activity.isDestroyed()));
        assertTrue("Expected launched MainActivity to complete onDestroy() before next test", destroyed.get());
    }

    @SuppressWarnings("unchecked")
    private static AlertDialog findLatestDeskDialog(Activity activity) throws Exception {
        Class<?> deskDialogs = Class.forName("org.llm4people.hermes.artifact.DeskDialogs");
        Field visibleField = deskDialogs.getDeclaredField("VISIBLE");
        visibleField.setAccessible(true);
        Map<Activity, List<?>> visible = (Map<Activity, List<?>>) visibleField.get(null);
        List<?> entries = visible == null ? null : visible.get(activity);
        if (entries == null || entries.isEmpty()) return null;
        Object latest = entries.get(entries.size() - 1);
        Method dialogAccessor = latest.getClass().getDeclaredMethod("dialog");
        dialogAccessor.setAccessible(true);
        WeakReference<AlertDialog> ref = (WeakReference<AlertDialog>) dialogAccessor.invoke(latest);
        return ref == null ? null : ref.get();
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static <T extends View> T findFirst(View root, Class<T> type) {
        List<T> found = new ArrayList<>();
        collect(root, type, found);
        return found.isEmpty() ? null : found.get(0);
    }

    private static <T extends View> void collect(View node, Class<T> type, List<T> out) {
        if (node == null) return;
        if (type.isInstance(node)) {
            out.add(type.cast(node));
        }
        if (node instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                collect(group.getChildAt(i), type, out);
            }
        }
    }
}
