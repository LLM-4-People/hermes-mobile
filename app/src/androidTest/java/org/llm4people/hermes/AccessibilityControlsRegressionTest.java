package org.llm4people.hermes;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
import android.test.InstrumentationTestCase;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.ambient.AmbientAccessPanel;
import org.llm4people.hermes.ambient.NotificationAllowlist;
import org.llm4people.hermes.artifact.ArtifactDesk;
import org.llm4people.hermes.artifact.DataTableBlock;
import org.llm4people.hermes.artifact.ThemePalette;
import org.llm4people.hermes.artifact.ThreadInstruments;
import org.llm4people.hermes.customization.AppCustomization;
import org.llm4people.hermes.customization.AppCustomizationStore;
import org.llm4people.hermes.hub.AppsLauncherModel;
import org.llm4people.hermes.hub.AppsLauncherPanel;
import org.llm4people.hermes.hub.NotesTasksPanel;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.os.BiometricVault;
import org.llm4people.hermes.palette.CommandItem;
import org.llm4people.hermes.palette.CommandPalette;
import org.llm4people.hermes.palette.CommandRegistry;
import org.llm4people.hermes.panel.MemoryModel;
import org.llm4people.hermes.panel.ModelCapabilityBadges;
import org.llm4people.hermes.panel.NotesModel;
import org.llm4people.hermes.panel.ProfileDefaultsModel;
import org.llm4people.hermes.panel.ReminderPanel;
import org.llm4people.hermes.panel.ScheduledJobModel;
import org.llm4people.hermes.panel.SearchResultModel;
import org.llm4people.hermes.panel.SessionPanel;
import org.llm4people.hermes.panel.SkillStudioModel;
import org.llm4people.hermes.panel.UniversalShareModel;
import org.llm4people.hermes.panel.WorkspacePathGuard;
import org.llm4people.hermes.places.NearbyGuide;
import org.llm4people.hermes.places.NearbyResult;
import org.llm4people.hermes.places.PlaceSelection;
import org.llm4people.hermes.profile.Account;
import org.llm4people.hermes.profile.AdminConsoleSheet;
import org.llm4people.hermes.profile.FirstRunWizard;
import org.llm4people.hermes.onboarding.OnboardingModels;
import org.llm4people.hermes.profile.ProfileGate;
import org.llm4people.hermes.profile.ProfileLockPolicy;
import org.llm4people.hermes.profile.WorkspaceAccountFixture;
import org.llm4people.hermes.push.DeliveryItem;
import org.llm4people.hermes.search.UniversalSearchModel;
import org.llm4people.hermes.search.UniversalSearchSheet;
import org.llm4people.hermes.settings.AppearanceSettingsPanel;
import org.llm4people.hermes.settings.SettingsHubPanel;
import org.llm4people.hermes.thread.ThreadWorkspace;
import org.llm4people.hermes.voice.VoiceDrafts;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Instrumentation regression tests for accessibility polish:
 * - R8.5.1: Editable search EditTexts in DataTableBlock & UniversalSearchSheet avoid contentDescription
 *   and rely on hint (+ root accessibilityPaneTitle on UniversalSearchSheet).
 * - R8.5.2: Dialog EditTexts in WorkspaceForms.reviewDraft() & ThreadWorkspace.rename() retain non-empty
 *   hints even after text is cleared.
 * - R8.5.3: Security lock-mode RadioButtons use a stateful ColorStateList with disabled_ink.
 * - R13.5.1: UniversalSearchSheet result rows include non-empty subtitles in contentDescription,
 *   hide redundant children from accessibility, and expose Button class name.
 * - R14.5.1-R14.5.6: TalkBack container roles (via Ui.setButtonRole SSoT), complete
 *   contentDescription, hidden decorative/redundant children, and non-nested clickables across
 *   AppsLauncherPanel, NotesPanel, CommandPalette, ProfileDefaultsPanel, DataTableBlock, and NearbyGuide.
 * - R15.5.1-R15.5.3 & R15.4.6: TalkBack container roles, zero nested clickables, sibling Switch,
 *   localized pluralized diff descriptions, and roundedRipple/stateful disabled ink across
 *   GlobalSearchPanel, SkillStudio, WorkspaceFilesPanel, and WorkspaceForms.
 * - R16.8.4, R16.5.3 & R16.5.1: Runtime AccessibilityNodeInfo assertions for Ui.setSelectableButtonRole
 *   and Ui.setCheckableState, polite live regions on inline validation errors (AppearanceSettingsPanel,
 *   NotesPanel, MemoryPanel), and ThreadInstrumentPanel.primary roundedRipple + stateful disabled ink.
 * - R17.5.1-R17.5.4: Polite live regions on NotesTasksPanel error card, AdminConsoleSheet summary &
 *   deduplicated users error card, UniversalSearchSheet offline banner & guarded empty-state transitions,
 *   and SessionSettings empty-search notice.
 * - R18.5.1-R18.5.3: Transition-guarded polite live regions across all 8 live-filtered empty states +
 *   SkillStudio, streaming reasoning toggle contentDescription in ThreadViews & MessageText, and
 *   FirstRunWizard manual-paste dialog padded profile_input styling.
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
            ActivityTestLifecycle.finishAndAwaitDestroyed(this, activity);
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

                    Field localizedField = UniversalSearchSheet.class.getDeclaredField("localized");
                    localizedField.setAccessible(true);
                    Context localized = (Context) localizedField.get(sheet);
                    Method buildRow = UniversalSearchSheet.class.getDeclaredMethod(
                            "buildResultRow", UniversalSearchModel.Result.class);
                    buildRow.setAccessible(true);

                    // R13.5.1: Snippet row includes title, badge, and subtitle in contentDescription,
                    // hides redundant children from accessibility, and announces Button role.
                    UniversalSearchModel.Result chatResult = new UniversalSearchModel.Result(
                            "chat:1", UniversalSearchModel.Category.CHATS,
                            "Release plan", "Verify staging smoke tests",
                            "t1", "", "", "", "", "", true, 100, Collections.emptyList());
                    ViewGroup chatRow = (ViewGroup) buildRow.invoke(sheet, chatResult);
                    String chatExpectedBase = localized.getString(
                            R.string.universal_search_open_result_cd,
                            "Release plan",
                            localized.getString(UniversalSearchModel.Category.CHATS.labelRes));
                    assertEquals(chatExpectedBase + ". Verify staging smoke tests",
                            String.valueOf(chatRow.getContentDescription()));
                    assertEquals(2, chatRow.getChildCount());
                    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                            chatRow.getChildAt(0).getImportantForAccessibility());
                    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                            chatRow.getChildAt(1).getImportantForAccessibility());
                    assertButtonAccessibilityRole(chatRow);

                    // R13.5.1: SHARED row includes owner and permission in contentDescription.
                    UniversalSearchModel.Result sharedResult = new UniversalSearchModel.Result(
                            "share:1", UniversalSearchModel.Category.SHARED,
                            "Shared runbook", "",
                            "n1", "", "s1", "note", "alice", "edit", true, 90, Collections.emptyList());
                    ViewGroup sharedRow = (ViewGroup) buildRow.invoke(sheet, sharedResult);
                    String sharedKind = UniversalShareModel.kindLabel(localized, "note");
                    String sharedOwner = localized.getString(R.string.ws_shared_by, "alice");
                    String sharedPerm = UniversalShareModel.permissionLabel(localized, "edit");
                    String sharedExpectedBase = localized.getString(
                            R.string.universal_search_open_result_cd, "Shared runbook", sharedKind);
                    assertEquals(sharedExpectedBase + ". " + sharedOwner + " · " + sharedPerm,
                            String.valueOf(sharedRow.getContentDescription()));
                    assertEquals(2, sharedRow.getChildCount());
                    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                            sharedRow.getChildAt(0).getImportantForAccessibility());
                    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                            sharedRow.getChildAt(1).getImportantForAccessibility());
                    assertButtonAccessibilityRole(sharedRow);

                    // Empty-subtitle row preserves base contentDescription without trailing punctuation.
                    UniversalSearchModel.Result bareResult = new UniversalSearchModel.Result(
                            "tool:1", UniversalSearchModel.Category.TOOLS,
                            "Settings", "",
                            "", "settings", "", "", "", "", false, 80, Collections.emptyList());
                    ViewGroup bareRow = (ViewGroup) buildRow.invoke(sheet, bareResult);
                    String bareExpectedBase = localized.getString(
                            R.string.universal_search_open_result_cd,
                            "Settings",
                            localized.getString(UniversalSearchModel.Category.TOOLS.labelRes));
                    assertEquals(bareExpectedBase, String.valueOf(bareRow.getContentDescription()));
                    assertEquals(1, bareRow.getChildCount());
                    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                            bareRow.getChildAt(0).getImportantForAccessibility());
                    assertButtonAccessibilityRole(bareRow);
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
                ActivityTestLifecycle.finishAndAwaitDestroyed(this, activity);
            }
        }
    }

    public void testReviewDraftAndThreadRenameDialogEditTextsPreserveHintWhenCleared() throws Throwable {
        Context context = getInstrumentation().getTargetContext();
        AtomicReference<ThreadWorkspace> workspaceRef = new AtomicReference<>();
        AtomicReference<VoiceDrafts> voiceRef = new AtomicReference<>();
        try (WorkspaceAccountFixture fixture = new WorkspaceAccountFixture(context)) {
            MainActivity activity = launchActivity(context.getPackageName(), MainActivity.class, null);
            try {
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
                    ActivityTestLifecycle.finishAndAwaitDestroyed(this, activity);
                }
            }
        } finally {
            Diagnostics.clear();
        }
    }

    public void testSecurityLockModeRadioButtonsUseStatefulEnabledDisabledInk() throws Throwable {
        Context context = getInstrumentation().getTargetContext();
        AtomicReference<ProfileGate> gateRef = new AtomicReference<>();
        AtomicReference<AlertDialog> dialogRef = new AtomicReference<>();
        try (WorkspaceAccountFixture fixture = new WorkspaceAccountFixture(context)) {
            Activity activity = launchActivity(context.getPackageName(), MainActivity.class, null);
            try {
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
                    ActivityTestLifecycle.finishAndAwaitDestroyed(this, activity);
                }
            }
        } finally {
            Diagnostics.clear();
        }
    }

    public void testTalkBackContainerRolesAndHiddenChildrenAcrossPanels() throws Throwable {
        Context context = getInstrumentation().getTargetContext();
        AtomicReference<SessionPanel> sessionPanelRef = new AtomicReference<>();
        try (WorkspaceAccountFixture fixture = new WorkspaceAccountFixture(context)) {
            MainActivity activity = launchActivity(context.getPackageName(), MainActivity.class, null);
            try {
                runTestOnUiThread(() -> {
                try {
                    // Null-safety on Ui.setButtonRole SSoT.
                    Ui.setButtonRole(null);

                    // R14.5.1: AppsLauncherPanel workflows row & custom screen row.
                    AppsLauncherPanel launcher = new AppsLauncherPanel(
                            activity,
                            new AppsLauncherPanel.StateProvider() {
                                @Override public String voiceLocaleLabel() { return "English"; }
                                @Override public List<AppsLauncherModel.ScreenEntry> screens() {
                                    return Collections.singletonList(
                                            new AppsLauncherModel.ScreenEntry("dashboard", "Morning Brief", true));
                                }
                                @Override public int templateCount() { return 3; }
                            },
                            new AppsLauncherPanel.Callbacks() {
                                @Override public void startVoiceChat() {}
                                @Override public void openVoiceOptions() {}
                                @Override public void openScreen(String pageId) {}
                                @Override public void createTool() {}
                                @Override public void openScreensGallery() {}
                                @Override public void pinHomeWidget() {}
                                @Override public void restoreDefaults() {}
                                @Override public void openTemplates() {}
                            });
                    launcher.buildView();

                    Field workflowsSlotField = AppsLauncherPanel.class.getDeclaredField("workflowsSlot");
                    workflowsSlotField.setAccessible(true);
                    ViewGroup workflowsSlot = (ViewGroup) workflowsSlotField.get(launcher);
                    assertNotNull("Expected workflowsSlot on AppsLauncherPanel", workflowsSlot);
                    ViewGroup workflowsCard = (ViewGroup) workflowsSlot.getChildAt(1);
                    ViewGroup workflowsRow = (ViewGroup) workflowsCard.getChildAt(0);
                    String expectedWorkflowsLabel = activity.getString(R.string.templates_route_label);
                    String expectedWorkflowsCount = activity.getResources()
                            .getQuantityString(R.plurals.hub_apps_templates_count, 3, 3);
                    String expectedWorkflowsBody = activity.getString(R.string.hub_apps_templates_body);
                    assertEquals(expectedWorkflowsLabel + ". " + expectedWorkflowsCount + ". " + expectedWorkflowsBody,
                            String.valueOf(workflowsRow.getContentDescription()));
                    assertButtonAccessibilityRole(workflowsRow);
                    List<TextView> workflowTexts = new ArrayList<>();
                    collect(workflowsRow, TextView.class, workflowTexts);
                    assertEquals(3, workflowTexts.size());
                    for (TextView tv : workflowTexts) {
                        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, tv.getImportantForAccessibility());
                    }

                    Method buildScreenRow = AppsLauncherPanel.class.getDeclaredMethod(
                            "buildScreenRow", AppsLauncherModel.ScreenEntry.class);
                    buildScreenRow.setAccessible(true);
                    ViewGroup screenRow = (ViewGroup) buildScreenRow.invoke(
                            launcher, new AppsLauncherModel.ScreenEntry("dashboard", "Morning Brief", true));
                    assertEquals("Morning Brief", String.valueOf(screenRow.getContentDescription()));
                    assertButtonAccessibilityRole(screenRow);
                    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                            screenRow.getChildAt(1).getImportantForAccessibility());

                    // Shared SessionPanel fixture for NotesPanel & ProfileDefaultsPanel.
                    HermesApi api = new HermesApi("http://127.0.0.1:1", "tok");
                    SessionPanel sessionPanel = new SessionPanel(activity, api, fixture.account);
                    sessionPanelRef.set(sessionPanel);

                    // R14.5.2: NotesPanel noteCard avoids nested clickables and exposes complete headerArea description.
                    Class<?> notesPanelClass = Class.forName("org.llm4people.hermes.panel.NotesPanel");
                    Constructor<?> notesCtor = notesPanelClass.getDeclaredConstructor(
                            SessionPanel.class, BooleanSupplier.class, Consumer.class, Runnable.class);
                    notesCtor.setAccessible(true);
                    Object notesPanel = notesCtor.newInstance(
                            sessionPanel, (BooleanSupplier) () -> true, (Consumer<String>) s -> {}, null);
                    Method noteCardMethod = notesPanelClass.getDeclaredMethod("noteCard", NotesModel.Note.class);
                    noteCardMethod.setAccessible(true);

                    String longBody = "A".repeat(260);
                    NotesModel.Note ownedPinned = new NotesModel.Note(
                            "n-1", "Sprint Goals", longBody, Arrays.asList("work", "q4"),
                            true, "2026-11-24T00:00:00Z", "", "owner");
                    ViewGroup ownedCard = (ViewGroup) noteCardMethod.invoke(notesPanel, ownedPinned);
                    assertFalse("Outer note card must not be clickable", ownedCard.isClickable());
                    assertEquals("Expected headerArea and actions sibling inside note card", 2, ownedCard.getChildCount());
                    ViewGroup headerArea = (ViewGroup) ownedCard.getChildAt(0);
                    assertTrue("Dedicated headerArea must be clickable", headerArea.isClickable());
                    assertButtonAccessibilityRole(headerArea);
                    assertNoClickableDescendants(headerArea);
                    String ownedCd = String.valueOf(headerArea.getContentDescription());
                    assertTrue(ownedCd.contains(activity.getString(R.string.notes_open_note_cd, "Sprint Goals")));
                    assertTrue(ownedCd.contains(activity.getString(R.string.brain_memory_pinned)));
                    assertTrue(ownedCd.contains("A".repeat(240) + "…"));
                    assertTrue(ownedCd.contains("work"));
                    assertTrue(ownedCd.contains("q4"));
                    assertEquals(3, headerArea.getChildCount());
                    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                            headerArea.getChildAt(0).getImportantForAccessibility());
                    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                            headerArea.getChildAt(1).getImportantForAccessibility());
                    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                            headerArea.getChildAt(2).getImportantForAccessibility());
                    List<Button> ownedActions = new ArrayList<>();
                    collect(ownedCard.getChildAt(1), Button.class, ownedActions);
                    assertTrue("Action buttons must live in sibling container outside headerArea",
                            ownedActions.size() >= 2);

                    NotesModel.Note sharedNote = new NotesModel.Note(
                            "n-2", "Team Handbook", "Onboarding checklist", Collections.emptyList(),
                            false, "2026-11-24T00:00:00Z", "alice", "edit");
                    ViewGroup sharedCard = (ViewGroup) noteCardMethod.invoke(notesPanel, sharedNote);
                    assertFalse("Shared note outer card must not be clickable", sharedCard.isClickable());
                    ViewGroup sharedHeader = (ViewGroup) sharedCard.getChildAt(0);
                    assertButtonAccessibilityRole(sharedHeader);
                    assertNoClickableDescendants(sharedHeader);
                    String sharedCd = String.valueOf(sharedHeader.getContentDescription());
                    assertTrue(sharedCd.contains(activity.getString(R.string.notes_open_note_cd, "Team Handbook")));
                    assertTrue(sharedCd.contains(UniversalShareModel.permissionLabel(activity, "edit")));
                    assertTrue(sharedCd.contains(activity.getString(R.string.ws_shared_by, "alice")));
                    assertTrue(sharedCd.contains("Onboarding checklist"));

                    // R14.5.3: CommandPalette rowView hides header & subtitle and announces Button role.
                    CommandPalette palette = new CommandPalette(activity, new CommandRegistry(), () -> true);
                    Method rowViewMethod = CommandPalette.class.getDeclaredMethod("rowView", CommandItem.class);
                    rowViewMethod.setAccessible(true);
                    CommandItem withSub = new CommandItem(
                            "cmd-1", CommandItem.Category.ACTION, "New Chat", "Start a fresh conversation", "", () -> {});
                    ViewGroup paletteRow = (ViewGroup) rowViewMethod.invoke(palette, withSub);
                    assertButtonAccessibilityRole(paletteRow);
                    assertEquals(2, paletteRow.getChildCount());
                    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                            paletteRow.getChildAt(0).getImportantForAccessibility());
                    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                            paletteRow.getChildAt(1).getImportantForAccessibility());
                    assertEquals("New Chat, " + activity.getString(R.string.palette_cat_action)
                                    + ", Start a fresh conversation",
                            String.valueOf(paletteRow.getContentDescription()));

                    CommandItem bareItem = new CommandItem(
                            "cmd-2", CommandItem.Category.SCREEN, "Notes", "", "", () -> {});
                    ViewGroup barePaletteRow = (ViewGroup) rowViewMethod.invoke(palette, bareItem);
                    assertButtonAccessibilityRole(barePaletteRow);
                    assertEquals(1, barePaletteRow.getChildCount());
                    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                            barePaletteRow.getChildAt(0).getImportantForAccessibility());

                    // R14.5.4: ProfileDefaultsPanel buildPickerModelRow hides copy & activePill and announces Button role.
                    Class<?> profilePanelClass = Class.forName("org.llm4people.hermes.panel.ProfileDefaultsPanel");
                    Constructor<?> profileCtor = profilePanelClass.getDeclaredConstructor(
                            SessionPanel.class, Runnable.class);
                    profileCtor.setAccessible(true);
                    Object profilePanel = profileCtor.newInstance(sessionPanel, null);
                    Field modelField = profilePanelClass.getDeclaredField("model");
                    modelField.setAccessible(true);
                    ProfileDefaultsModel profileModel = (ProfileDefaultsModel) modelField.get(profilePanel);
                    profileModel.load("openai", "gpt-4o", "", Collections.emptyList(), Collections.emptyList());

                    Method buildPickerRow = profilePanelClass.getDeclaredMethod(
                            "buildPickerModelRow", ProfileDefaultsModel.ModelOption.class, Runnable.class);
                    buildPickerRow.setAccessible(true);
                    ProfileDefaultsModel.ModelOption selectedOpt = new ProfileDefaultsModel.ModelOption(
                            "openai", "OpenAI", ModelCapabilityBadges.ModelInfo.minimal("gpt-4o"));
                    ViewGroup selectedModelRow = (ViewGroup) buildPickerRow.invoke(
                            profilePanel, selectedOpt, (Runnable) () -> {});
                    assertButtonAccessibilityRole(selectedModelRow);
                    assertEquals(2, selectedModelRow.getChildCount());
                    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                            selectedModelRow.getChildAt(0).getImportantForAccessibility());
                    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                            selectedModelRow.getChildAt(1).getImportantForAccessibility());
                    assertEquals("gpt-4o, OpenAI, " + activity.getString(R.string.profile_defaults_selected_badge),
                            String.valueOf(selectedModelRow.getContentDescription()));

                    ProfileDefaultsModel.ModelOption otherOpt = new ProfileDefaultsModel.ModelOption(
                            "anthropic", "Anthropic", ModelCapabilityBadges.ModelInfo.minimal("claude-sonnet"));
                    ViewGroup otherModelRow = (ViewGroup) buildPickerRow.invoke(
                            profilePanel, otherOpt, (Runnable) () -> {});
                    assertButtonAccessibilityRole(otherModelRow);
                    assertEquals(1, otherModelRow.getChildCount());
                    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                            otherModelRow.getChildAt(0).getImportantForAccessibility());

                    // R14.5.5: DataTableBlock actionable row hides cell TextViews & exposes Button role;
                    // non-actionable row preserves cell accessibility.
                    List<DataTableBlock.Column> cols = Arrays.asList(
                            new DataTableBlock.Column("name", "Name", false),
                            new DataTableBlock.Column("count", "Count", true));
                    List<List<String>> tableRows = Collections.singletonList(Arrays.asList("Alpha", "42"));
                    WorkspaceHost host = new WorkspaceHost(activity, () -> true);
                    Method buildTable = DataTableBlock.class.getDeclaredMethod(
                            "build", Context.class, DataTableBlock.Model.class, ThemePalette.class, ArtifactDesk.Host.class);
                    buildTable.setAccessible(true);

                    DataTableBlock.Model actionableModel = new DataTableBlock.Model(cols, tableRows, false, false, "Review");
                    View actionableTable = (View) buildTable.invoke(null, activity, actionableModel, null, host);
                    ViewGroup actionableRow = findClickableRow(actionableTable);
                    assertNotNull("Expected clickable actionable row in DataTableBlock", actionableRow);
                    assertButtonAccessibilityRole(actionableRow);
                    assertEquals(activity.getString(R.string.ws_table_row_draft_a11y, "Alpha, 42"),
                            String.valueOf(actionableRow.getContentDescription()));
                    assertEquals(2, actionableRow.getChildCount());
                    for (int i = 0; i < actionableRow.getChildCount(); i++) {
                        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                                actionableRow.getChildAt(i).getImportantForAccessibility());
                    }

                    DataTableBlock.Model readOnlyModel = new DataTableBlock.Model(cols, tableRows, false, false, "");
                    View readOnlyTable = (View) buildTable.invoke(null, activity, readOnlyModel, null, host);
                    assertNull("Non-actionable DataTableBlock must not mark data rows clickable",
                            findClickableRow(readOnlyTable));
                    List<TextView> readOnlyCells = new ArrayList<>();
                    collect(readOnlyTable, TextView.class, readOnlyCells);
                    boolean foundVisibleAlpha = false;
                    for (TextView tv : readOnlyCells) {
                        if ("Alpha".equals(String.valueOf(tv.getText()))) {
                            assertTrue("Non-actionable cell must remain accessible",
                                    tv.getImportantForAccessibility() != View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                            foundVisibleAlpha = true;
                        }
                    }
                    assertTrue("Expected Alpha cell in non-actionable DataTableBlock", foundVisibleAlpha);

                    // R14.5.6: NearbyGuide Venue.top hides marker, name, and dist and exposes Button role.
                    JSONObject placeJson = new JSONObject()
                            .put("osm_type", "node")
                            .put("osm_id", 1)
                            .put("name", "Cafe Test")
                            .put("latitude", 45.5)
                            .put("longitude", -73.5)
                            .put("distance_m", 120);
                    JSONObject nearbyJson = new JSONObject()
                            .put("source", "OpenStreetMap")
                            .put("attribution", "© OpenStreetMap contributors")
                            .put("source_date", "2026-01-01T00:00:00Z")
                            .put("category", "cafe")
                            .put("radius_m", 1500)
                            .put("limit", 12)
                            .put("places", new JSONArray().put(placeJson));
                    NearbyResult result = NearbyResult.parse(activity, nearbyJson);
                    NearbyGuide guide = new NearbyGuide(activity, new NearbyGuide.Actions() {
                        @Override public void locate() {}
                        @Override public void manual() {}
                        @Override public void select(int index, boolean toggleDetails) {}
                        @Override public void list(boolean all) {}
                        @Override public void cuisine() {}
                        @Override public void compare() {}
                        @Override public void map(View clicked, NearbyResult.Place place) {}
                        @Override public void source(View clicked, NearbyResult.Place place) {}
                    });
                    guide.bind(new NearbyGuide.State(
                            NearbyGuide.Phase.READY, result, 45.5, -73.5, 10.0, new PlaceSelection(result)));
                    ViewGroup venueTop = guide.findViewWithTag("places-row-node/1");
                    assertNotNull("Expected venue row places-row-node/1 in NearbyGuide", venueTop);
                    assertButtonAccessibilityRole(venueTop);
                    assertNotNull(venueTop.getContentDescription());
                    assertTrue(venueTop.getContentDescription().toString().contains("Cafe Test"));
                    assertEquals(3, venueTop.getChildCount());
                    for (int i = 0; i < venueTop.getChildCount(); i++) {
                        assertEquals("NearbyGuide venue child " + i + " must be hidden from accessibility",
                                View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                                venueTop.getChildAt(i).getImportantForAccessibility());
                    }
                } catch (Exception err) {
                    throw new AssertionError(err);
                }
            });
            } finally {
                try {
                    runTestOnUiThread(() -> {
                        SessionPanel sp = sessionPanelRef.getAndSet(null);
                        if (sp != null) sp.close();
                    });
                } finally {
                    ActivityTestLifecycle.finishAndAwaitDestroyed(this, activity);
                }
            }
        } finally {
            Diagnostics.clear();
        }
    }

    public void testRound15TalkBackPanelsAndWorkspaceFormsPolish() throws Throwable {
        Context context = getInstrumentation().getTargetContext();
        AtomicReference<SessionPanel> sessionPanelRef = new AtomicReference<>();
        try (WorkspaceAccountFixture fixture = new WorkspaceAccountFixture(context)) {
            MainActivity activity = launchActivity(context.getPackageName(), MainActivity.class, null);
            try {
                runTestOnUiThread(() -> {
                try {
                    HermesApi api = new HermesApi("http://127.0.0.1:1", "tok");
                    SessionPanel sessionPanel = new SessionPanel(activity, api, fixture.account);
                    sessionPanelRef.set(sessionPanel);
                    int minTouchPx = Ui.dp(activity, 48);

                    // R15.5.1: GlobalSearchPanel hitCard eliminates nested Button, uses Button role & composite description.
                    Class<?> searchPanelClass = Class.forName("org.llm4people.hermes.panel.GlobalSearchPanel");
                    Constructor<?> searchCtor = searchPanelClass.getDeclaredConstructor(
                            SessionPanel.class, Consumer.class, Runnable.class);
                    searchCtor.setAccessible(true);
                    Object searchPanel = searchCtor.newInstance(sessionPanel, (Consumer<String>) id -> {}, null);
                    Method hitCardMethod = searchPanelClass.getDeclaredMethod(
                            "hitCard", SearchResultModel.Hit.class, ZoneId.class);
                    hitCardMethod.setAccessible(true);

                    SearchResultModel.Hit clickableHit = new SearchResultModel.Hit(
                            "s-1", "t-1", "Release Checklist", "m-1", "assistant",
                            new SearchResultModel.HighlightedSnippet("Ship v2 after smoke tests", Collections.emptyList()),
                            "2026-11-24T12:00:00Z");
                    ViewGroup hitCard = (ViewGroup) hitCardMethod.invoke(searchPanel, clickableHit, ZoneId.of("UTC"));
                    assertTrue("Clickable search hit card must be clickable", hitCard.isClickable());
                    assertTrue("Search hit card minHeight must be >= 48dp", hitCard.getMinimumHeight() >= minTouchPx);
                    assertButtonAccessibilityRole(hitCard);
                    assertNoClickableDescendants(hitCard);
                    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                            hitCard.getChildAt(0).getImportantForAccessibility());
                    String hitCd = String.valueOf(hitCard.getContentDescription());
                    assertTrue(hitCd.contains("Release Checklist"));
                    assertTrue(hitCd.contains("Ship v2 after smoke tests"));
                    assertTrue(hitCd.contains(activity.getString(R.string.brain_search_open_thread)));

                    // Negative case: hit without target session stays non-clickable and keeps text accessible.
                    SearchResultModel.Hit nonClickableHit = new SearchResultModel.Hit(
                            "", "", "Orphan Hit", "m-2", "user",
                            new SearchResultModel.HighlightedSnippet("No session target", Collections.emptyList()),
                            "");
                    ViewGroup readOnlyHit = (ViewGroup) hitCardMethod.invoke(searchPanel, nonClickableHit, ZoneId.of("UTC"));
                    assertFalse("Hit without session target must not be clickable", readOnlyHit.isClickable());
                    assertTrue("Non-clickable hit children must remain accessible",
                            readOnlyHit.getChildAt(0).getImportantForAccessibility()
                                    != View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);

                    // R15.5.2: SkillStudio skillRow keeps outer card non-clickable, separates detailArea and Switch sibling.
                    Class<?> skillStudioClass = Class.forName("org.llm4people.hermes.panel.SkillStudio");
                    Constructor<?> skillCtor = skillStudioClass.getDeclaredConstructor(
                            SessionPanel.class, BooleanSupplier.class, Consumer.class, Runnable.class);
                    skillCtor.setAccessible(true);
                    Object skillStudio = skillCtor.newInstance(
                            sessionPanel, (BooleanSupplier) () -> true, (Consumer<String>) s -> {}, null);
                    setField(skillStudio, "studioSupported", true);

                    Class<?> skillInvClass = Class.forName("org.llm4people.hermes.panel.SkillInventory");
                    Method parseSkills = skillInvClass.getDeclaredMethod("parse", JSONObject.class);
                    parseSkills.setAccessible(true);
                    JSONObject skillPayload = new JSONObject().put("data", new JSONArray().put(
                            new JSONObject()
                                    .put("name", "deploy-helper")
                                    .put("description", "Automates staging deploys")
                                    .put("category", "ops")
                                    .put("source", "user")
                                    .put("writable", true)
                                    .put("enabled", true)));
                    @SuppressWarnings("unchecked")
                    List<?> parsedSkills = (List<?>) parseSkills.invoke(null, skillPayload);
                    Object skill = parsedSkills.get(0);

                    Method skillRowMethod = skillStudioClass.getDeclaredMethod("skillRow", skillInvClass);
                    skillRowMethod.setAccessible(true);
                    ViewGroup skillCard = (ViewGroup) skillRowMethod.invoke(skillStudio, skill);
                    assertFalse("Outer SkillStudio card must not be clickable", skillCard.isClickable());
                    ViewGroup skillRowContainer = (ViewGroup) skillCard.getChildAt(0);
                    assertEquals("Expected detailArea + Switch sibling when studioSupported",
                            2, skillRowContainer.getChildCount());
                    ViewGroup detailArea = (ViewGroup) skillRowContainer.getChildAt(0);
                    assertTrue("Skill detailArea must be clickable", detailArea.isClickable());
                    assertTrue("Skill detailArea minHeight must be >= 48dp", detailArea.getMinimumHeight() >= minTouchPx);
                    assertButtonAccessibilityRole(detailArea);
                    assertNoClickableDescendants(detailArea);
                    assertEquals(2, detailArea.getChildCount());
                    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                            detailArea.getChildAt(0).getImportantForAccessibility());
                    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                            detailArea.getChildAt(1).getImportantForAccessibility());
                    String detailCd = String.valueOf(detailArea.getContentDescription());
                    assertTrue(detailCd.contains("deploy-helper"));
                    assertTrue(detailCd.contains("Automates staging deploys"));
                    assertTrue(detailCd.contains("ops"));
                    assertTrue(detailCd.contains(SkillStudioModel.sourceLabel(activity, "user", true)));

                    assertTrue("Second sibling must be Switch", skillRowContainer.getChildAt(1) instanceof Switch);
                    Switch toggle = (Switch) skillRowContainer.getChildAt(1);
                    assertTrue("Switch minHeight must be >= 48dp", toggle.getMinimumHeight() >= minTouchPx);
                    assertTrue("Switch minWidth must be >= 48dp", toggle.getMinimumWidth() >= minTouchPx);
                    assertTrue(String.valueOf(toggle.getContentDescription())
                            .contains(activity.getString(R.string.brain_skills_enabled)));

                    // Read-only catalog mode: no Switch sibling, detailArea still has Button role and no nested clickables.
                    setField(skillStudio, "studioSupported", false);
                    ViewGroup readOnlySkillCard = (ViewGroup) skillRowMethod.invoke(skillStudio, skill);
                    ViewGroup readOnlyRow = (ViewGroup) readOnlySkillCard.getChildAt(0);
                    assertEquals(1, readOnlyRow.getChildCount());
                    assertButtonAccessibilityRole(readOnlyRow.getChildAt(0));
                    assertNoClickableDescendants((ViewGroup) readOnlyRow.getChildAt(0));

                    // R15.5.3: WorkspaceFilesPanel entryRow & renderDiff composite descriptions + hidden children + Button role.
                    Class<?> filesPanelClass = Class.forName("org.llm4people.hermes.panel.WorkspaceFilesPanel");
                    Constructor<?> filesCtor = filesPanelClass.getDeclaredConstructor(
                            SessionPanel.class, String.class, BooleanSupplier.class, Consumer.class, Runnable.class);
                    filesCtor.setAccessible(true);
                    Object filesPanel = filesCtor.newInstance(
                            sessionPanel, "sess-1", (BooleanSupplier) () -> true, (Consumer<String>) s -> {}, null);
                    LinearLayout filesBody = Ui.column(activity);
                    setField(filesPanel, "body", filesBody);

                    Class<?> entryClass = Class.forName("org.llm4people.hermes.panel.BrainJson$WorkspaceEntry");
                    Constructor<?> entryCtor = entryClass.getDeclaredConstructor(
                            String.class, String.class, long.class, String.class, String.class);
                    entryCtor.setAccessible(true);
                    Object fileEntry = entryCtor.newInstance("src/App.java", "file", 2048L, "java", "M");
                    Method entryRowMethod = filesPanelClass.getDeclaredMethod("entryRow", entryClass);
                    entryRowMethod.setAccessible(true);
                    ViewGroup entryCard = (ViewGroup) entryRowMethod.invoke(filesPanel, fileEntry);
                    assertTrue("Workspace entry card must be clickable", entryCard.isClickable());
                    assertButtonAccessibilityRole(entryCard);
                    assertNoClickableDescendants(entryCard);
                    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                            entryCard.getChildAt(0).getImportantForAccessibility());
                    String entryCd = String.valueOf(entryCard.getContentDescription());
                    assertTrue(entryCd.contains("src/App.java"));
                    assertTrue(entryCd.contains(Formatter.formatShortFileSize(activity, 2048L)));
                    assertTrue(entryCd.contains(WorkspacePathGuard.gitStatusLabel(activity, "M")));

                    Class<?> diffFileClass = Class.forName("org.llm4people.hermes.panel.BrainJson$DiffFile");
                    Constructor<?> diffFileCtor = diffFileClass.getDeclaredConstructor(
                            String.class, String.class, int.class, int.class);
                    diffFileCtor.setAccessible(true);
                    Object modDiff = diffFileCtor.newInstance("src/Main.java", "M", 3, 1);
                    Object addOnlyDiff = diffFileCtor.newInstance("src/New.java", "A", 5, 0);

                    Class<?> wsDiffClass = Class.forName("org.llm4people.hermes.panel.BrainJson$WorkspaceDiff");
                    Constructor<?> wsDiffCtor = wsDiffClass.getDeclaredConstructor(
                            String.class, int.class, int.class, List.class, String.class);
                    wsDiffCtor.setAccessible(true);
                    Object wsDiff = wsDiffCtor.newInstance("", 0, 0, Arrays.asList(modDiff, addOnlyDiff), "");
                    Method renderDiffMethod = filesPanelClass.getDeclaredMethod("renderDiff", wsDiffClass);
                    renderDiffMethod.setAccessible(true);
                    renderDiffMethod.invoke(filesPanel, wsDiff);

                    assertEquals(2, filesBody.getChildCount());
                    ViewGroup modCard = (ViewGroup) filesBody.getChildAt(0);
                    assertButtonAccessibilityRole(modCard);
                    assertNoClickableDescendants(modCard);
                    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                            modCard.getChildAt(0).getImportantForAccessibility());
                    String modCd = String.valueOf(modCard.getContentDescription());
                    assertTrue(modCd.contains("src/Main.java"));
                    assertTrue(modCd.contains(WorkspacePathGuard.gitStatusLabel(activity, "M")));
                    assertTrue(modCd.contains(activity.getResources().getQuantityString(R.plurals.diff_lines_added, 3, 3)));
                    assertTrue(modCd.contains(activity.getResources().getQuantityString(R.plurals.diff_lines_removed, 1, 1)));

                    ViewGroup addCard = (ViewGroup) filesBody.getChildAt(1);
                    String addCd = String.valueOf(addCard.getContentDescription());
                    assertTrue(addCd.contains("src/New.java"));
                    assertTrue(addCd.contains(activity.getResources().getQuantityString(R.plurals.diff_lines_added, 5, 5)));
                    assertFalse("Zero deletions must not be announced in diff contentDescription",
                            addCd.contains(activity.getResources().getQuantityString(R.plurals.diff_lines_removed, 0, 0)));

                    // R15.4.6: WorkspaceForms prepare button uses roundedRipple with mask & stateful enabledDisabledInk.
                    Class<?> blockClass = Class.forName("org.llm4people.hermes.artifact.WorkspaceLayout$Block");
                    Constructor<?> blockCtor = blockClass.getDeclaredConstructor();
                    blockCtor.setAccessible(true);
                    Object formBlock = blockCtor.newInstance();
                    setField(formBlock, "type", "form");
                    setField(formBlock, "prompt", "Prepare a draft");

                    Class<?> fieldClass = Class.forName("org.llm4people.hermes.artifact.DraftForm$Field");
                    Constructor<?> fieldCtor = fieldClass.getDeclaredConstructor();
                    fieldCtor.setAccessible(true);
                    Object textField = fieldCtor.newInstance();
                    setField(textField, "id", "topic");
                    setField(textField, "label", "Topic");
                    setField(textField, "kind", "text");
                    setField(textField, "required", true);
                    setField(formBlock, "fields", Collections.singletonList(textField));

                    ThemePalette palette = ThemePalette.resolve(
                            ThemePalette.OverrideSpec.defaults(), ThemePalette.isNight(activity));
                    LinearLayout formGroup = Ui.column(activity);
                    WorkspaceHost host = new WorkspaceHost(activity, () -> true);
                    Class<?> formsClass = Class.forName("org.llm4people.hermes.artifact.WorkspaceForms");
                    Method mountForm = formsClass.getDeclaredMethod(
                            "mount", LinearLayout.class, blockClass, ThemePalette.class, Activity.class, ArtifactDesk.Host.class);
                    mountForm.setAccessible(true);
                    mountForm.invoke(null, formGroup, formBlock, palette, activity, host);

                    Button prepare = findFirst(formGroup, Button.class);
                    assertNotNull("Expected prepare Button in mounted WorkspaceForms", prepare);
                    assertTrue("Prepare button must use RippleDrawable", prepare.getBackground() instanceof RippleDrawable);
                    RippleDrawable ripple = (RippleDrawable) prepare.getBackground();
                    assertNotNull("Ui.roundedRipple must supply a bounded ripple mask",
                            ripple.findDrawableByLayerId(android.R.id.mask));
                    ColorStateList prepareColors = prepare.getTextColors();
                    assertNotNull(prepareColors);
                    assertTrue("Prepare button text colors must be stateful", prepareColors.isStateful());
                    assertEquals(palette.accent,
                            prepareColors.getColorForState(new int[]{android.R.attr.state_enabled}, 0));
                    assertEquals(palette.muted,
                            prepareColors.getColorForState(new int[]{-android.R.attr.state_enabled}, 0));
                } catch (Exception err) {
                    throw new AssertionError(err);
                }
            });
            } finally {
                try {
                    runTestOnUiThread(() -> {
                        SessionPanel sp = sessionPanelRef.getAndSet(null);
                        if (sp != null) sp.close();
                    });
                } finally {
                    ActivityTestLifecycle.finishAndAwaitDestroyed(this, activity);
                }
            }
        } finally {
            Diagnostics.clear();
        }
    }

    public void testRound16DelegatesLiveRegionsAndPrimaryRipple() throws Throwable {
        Context context = getInstrumentation().getTargetContext();
        AtomicReference<SessionPanel> sessionPanelRef = new AtomicReference<>();
        AtomicReference<ThreadWorkspace> workspaceRef = new AtomicReference<>();
        AtomicReference<VoiceDrafts> voiceRef = new AtomicReference<>();
        try (WorkspaceAccountFixture fixture = new WorkspaceAccountFixture(context)) {
            MainActivity activity = launchActivity(context.getPackageName(), MainActivity.class, null);
            try {
                runTestOnUiThread(() -> {
                try {
                    // R16.8.4: Null-safety on Ui accessibility delegate helpers.
                    Ui.setSelectableButtonRole(null);
                    Ui.setCheckableState(null, true);
                    Ui.setCheckableState(null, false);

                    // R16.8.4: Ui.setSelectableButtonRole announces Button role + dynamic host.isSelected().
                    LinearLayout selectableTab = Ui.row(activity);
                    Ui.setSelectableButtonRole(selectableTab);
                    selectableTab.setSelected(false);
                    assertSelectableButtonNode(selectableTab, false);
                    selectableTab.setSelected(true);
                    assertSelectableButtonNode(selectableTab, true);

                    // R16.8.4: Ui.setCheckableState announces checkable=true and toggled checked state.
                    Button checkableChip = Ui.button(activity, "Filter", v -> {});
                    Ui.setCheckableState(checkableChip, true);
                    assertCheckableNode(checkableChip, true);
                    Ui.setCheckableState(checkableChip, false);
                    assertCheckableNode(checkableChip, false);

                    // R16.5.3: AppearanceSettingsPanel inline validation errors announce politely.
                    AppCustomizationStore store = new AppCustomizationStore(new AppCustomizationStore.Backing() {
                        private String raw;
                        @Override public String read() { return raw; }
                        @Override public void write(String value) { raw = value; }
                        @Override public void remove() { raw = null; }
                    });
                    AppearanceSettingsPanel appearance = new AppearanceSettingsPanel(
                            activity, store, "system", new AppearanceSettingsPanel.Listener() {
                                @Override public void onCustomizationChanged(AppCustomization customization) {}
                                @Override public void onThemeModeChanged(String themeMode) {}
                            });
                    appearance.buildView();
                    Field hexErrField = AppearanceSettingsPanel.class.getDeclaredField("hexErrorText");
                    hexErrField.setAccessible(true);
                    TextView hexErrorText = (TextView) hexErrField.get(appearance);
                    assertNotNull("Expected hexErrorText on AppearanceSettingsPanel", hexErrorText);
                    assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,
                            hexErrorText.getAccessibilityLiveRegion());
                    Field greetErrField = AppearanceSettingsPanel.class.getDeclaredField("greetingError");
                    greetErrField.setAccessible(true);
                    TextView greetingError = (TextView) greetErrField.get(appearance);
                    assertNotNull("Expected greetingError on AppearanceSettingsPanel", greetingError);
                    assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,
                            greetingError.getAccessibilityLiveRegion());

                    // R16.5.3: NotesPanel & MemoryPanel editor dialog validation errorViews announce politely.
                    HermesApi api = new HermesApi("http://127.0.0.1:1", "tok");
                    SessionPanel sessionPanel = new SessionPanel(activity, api, fixture.account);
                    sessionPanelRef.set(sessionPanel);
                    Field childDialogField = SessionPanel.class.getDeclaredField("child");
                    childDialogField.setAccessible(true);
                    int dangerColor = activity.getColor(R.color.danger);

                    Class<?> notesPanelClass = Class.forName("org.llm4people.hermes.panel.NotesPanel");
                    Constructor<?> notesCtor = notesPanelClass.getDeclaredConstructor(
                            SessionPanel.class, BooleanSupplier.class, Consumer.class, Runnable.class);
                    notesCtor.setAccessible(true);
                    Object notesPanel = notesCtor.newInstance(
                            sessionPanel, (BooleanSupplier) () -> true, (Consumer<String>) s -> {}, null);
                    Method notesOpenEditor = notesPanelClass.getDeclaredMethod("openEditor", NotesModel.Note.class);
                    notesOpenEditor.setAccessible(true);
                    notesOpenEditor.invoke(notesPanel, new Object[]{null});
                    Dialog notesDialog = (Dialog) childDialogField.get(sessionPanel);
                    assertNotNull("Expected NotesPanel editor dialog", notesDialog);
                    TextView notesError = findDangerErrorTextView(notesDialog.getWindow().getDecorView(), dangerColor);
                    assertNotNull("Expected danger errorView in NotesPanel editor", notesError);
                    assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, notesError.getAccessibilityLiveRegion());
                    notesDialog.dismiss();

                    Class<?> memoryPanelClass = Class.forName("org.llm4people.hermes.panel.MemoryPanel");
                    Constructor<?> memoryCtor = memoryPanelClass.getDeclaredConstructor(
                            SessionPanel.class, Runnable.class);
                    memoryCtor.setAccessible(true);
                    Object memoryPanel = memoryCtor.newInstance(sessionPanel, null);
                    Method memoryOpenEditor = memoryPanelClass.getDeclaredMethod(
                            "openEditor", MemoryModel.Bucket.class, MemoryModel.Entry.class);
                    memoryOpenEditor.setAccessible(true);
                    MemoryModel.Bucket bucket = new MemoryModel.Bucket(
                            MemoryModel.TARGET_MEMORY, Collections.emptyList(), 0, MemoryModel.DEFAULT_CHAR_LIMIT, true);
                    memoryOpenEditor.invoke(memoryPanel, bucket, null);
                    Dialog memoryDialog = (Dialog) childDialogField.get(sessionPanel);
                    assertNotNull("Expected MemoryPanel editor dialog", memoryDialog);
                    TextView memoryError = findDangerErrorTextView(memoryDialog.getWindow().getDecorView(), dangerColor);
                    assertNotNull("Expected danger errorView in MemoryPanel editor", memoryError);
                    assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, memoryError.getAccessibilityLiveRegion());
                    memoryDialog.dismiss();

                    // R16.5.1: ThreadInstrumentPanel.primary uses Ui.roundedRipple mask, stateful ink & Gravity.CENTER.
                    VoiceDrafts voice = new VoiceDrafts(activity);
                    voiceRef.set(voice);
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
                    setField(workspace, "screen", indexField.get(workspace));

                    Class<?> tipClass = Class.forName("org.llm4people.hermes.thread.ThreadInstrumentPanel");
                    Constructor<?> tipCtor = tipClass.getDeclaredConstructor(
                            ThreadWorkspace.class, ThreadInstruments.class, ArtifactDesk.Host.class);
                    tipCtor.setAccessible(true);
                    Object tip = tipCtor.newInstance(
                            workspace, new ThreadInstruments(fixture.account, "s-r16"),
                            new WorkspaceHost(activity, () -> true));
                    LinearLayout controlsRow = Ui.row(activity);
                    setField(tip, "controls", controlsRow);
                    Method primaryMethod = tipClass.getDeclaredMethod("primary", int.class, Runnable.class);
                    primaryMethod.setAccessible(true);
                    primaryMethod.invoke(tip, R.string.panel_save, (Runnable) () -> {});

                    assertEquals(1, controlsRow.getChildCount());
                    assertTrue(controlsRow.getChildAt(0) instanceof Button);
                    Button primaryBtn = (Button) controlsRow.getChildAt(0);
                    assertEquals(Gravity.CENTER, primaryBtn.getGravity());
                    assertTrue("Primary button must use RippleDrawable",
                            primaryBtn.getBackground() instanceof RippleDrawable);
                    RippleDrawable primaryRipple = (RippleDrawable) primaryBtn.getBackground();
                    assertNotNull("Ui.roundedRipple must supply a bounded ripple mask",
                            primaryRipple.findDrawableByLayerId(android.R.id.mask));
                    ColorStateList primaryInk = primaryBtn.getTextColors();
                    assertNotNull(primaryInk);
                    assertTrue("Primary button text colors must be stateful", primaryInk.isStateful());
                    assertEquals(activity.getColor(R.color.on_accent),
                            primaryInk.getColorForState(new int[]{android.R.attr.state_enabled}, 0));
                    assertEquals(activity.getColor(R.color.disabled_ink),
                            primaryInk.getColorForState(new int[]{-android.R.attr.state_enabled}, 0));
                } catch (Exception err) {
                    throw new AssertionError(err);
                }
            });
            } finally {
                try {
                    runTestOnUiThread(() -> {
                        SessionPanel sp = sessionPanelRef.getAndSet(null);
                        if (sp != null) sp.close();
                        ThreadWorkspace ws = workspaceRef.getAndSet(null);
                        if (ws != null) ws.close();
                        VoiceDrafts voice = voiceRef.getAndSet(null);
                        if (voice != null) {
                            voice.reader().stop();
                            voice.cancel();
                        }
                    });
                } finally {
                    ActivityTestLifecycle.finishAndAwaitDestroyed(this, activity);
                }
            }
        } finally {
            Diagnostics.clear();
        }
    }

    public void testRound17LiveRegionsAcrossPanels() throws Throwable {
        Context context = getInstrumentation().getTargetContext();
        AtomicReference<NotesTasksPanel> notesTasksRef = new AtomicReference<>();
        AtomicReference<AdminConsoleSheet> adminRef = new AtomicReference<>();
        AtomicReference<UniversalSearchSheet> searchRef = new AtomicReference<>();
        AtomicReference<SessionPanel> sessionPanelRef = new AtomicReference<>();
        try (WorkspaceAccountFixture fixture = new WorkspaceAccountFixture(context)) {
            MainActivity activity = launchActivity(context.getPackageName(), MainActivity.class, null);
            try {
                runTestOnUiThread(() -> {
                    try {
                        HermesApi api = new HermesApi("http://127.0.0.1:1", "tok");
                        int dangerColor = activity.getColor(R.color.danger);

                        // R17.5.1: NotesTasksPanel.showErrorCard announces error message politely.
                        NotesTasksPanel notesTasks = new NotesTasksPanel(
                                activity, fixture.account, api, () -> true, new NotesTasksPanel.Callbacks() {
                                    @Override public void createNote() {}
                                    @Override public void openAllNotes() {}
                                    @Override public void openNote(String noteId) {}
                                    @Override public void createReminder() {}
                                    @Override public void openAllReminders() {}
                                    @Override public void openReminder(String jobId) {}
                                    @Override public void openFullInbox() {}
                                    @Override public void openDelivery(DeliveryItem item) {}
                                    @Override public void openSharedWithMe() {}
                                });
                        notesTasksRef.set(notesTasks);
                        LinearLayout errSlot = Ui.column(activity);
                        Method showErrorCard = NotesTasksPanel.class.getDeclaredMethod(
                                "showErrorCard", LinearLayout.class, String.class, Runnable.class);
                        showErrorCard.setAccessible(true);
                        showErrorCard.invoke(notesTasks, errSlot, "Network error", (Runnable) () -> {});
                        TextView notesTasksErr = findDangerErrorTextView(errSlot, dangerColor);
                        assertNotNull("Expected danger error TextView in NotesTasksPanel error card", notesTasksErr);
                        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,
                                notesTasksErr.getAccessibilityLiveRegion());

                        // R17.5.2: AdminConsoleSheet summaryLine is polite; errText is polite only when settings != null.
                        AdminConsoleSheet admin = new AdminConsoleSheet(activity, fixture.account, true, () -> true);
                        adminRef.set(admin);
                        assertTrue("Expected AdminConsoleSheet.show() to open for admin", admin.show());
                        Field summaryField = AdminConsoleSheet.class.getDeclaredField("summaryLine");
                        summaryField.setAccessible(true);
                        TextView summaryLine = (TextView) summaryField.get(admin);
                        assertNotNull(summaryLine);
                        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,
                                summaryLine.getAccessibilityLiveRegion());

                        Field contentSlotField = AdminConsoleSheet.class.getDeclaredField("contentSlot");
                        contentSlotField.setAccessible(true);
                        LinearLayout adminContent = (LinearLayout) contentSlotField.get(admin);
                        Method renderUsersTab = AdminConsoleSheet.class.getDeclaredMethod("renderUsersTab");
                        renderUsersTab.setAccessible(true);

                        // When settings == null, summaryLine already announces users_load_failed -> errText stays NONE.
                        setField(admin, "usersLoadState", AdminConsoleSheet.UsersLoadState.ERROR);
                        setField(admin, "profiles", Collections.emptyList());
                        setField(admin, "settings", null);
                        adminContent.removeAllViews();
                        renderUsersTab.invoke(admin);
                        TextView dedupErr = findDangerErrorTextView(adminContent, dangerColor);
                        assertNotNull("Expected users error TextView when settings == null", dedupErr);
                        assertEquals(View.ACCESSIBILITY_LIVE_REGION_NONE, dedupErr.getAccessibilityLiveRegion());

                        // When settings != null, summaryLine shows counts -> errText announces politely.
                        setField(admin, "settings",
                                AdminConsoleSheet.validateSettingsInput("en", "gpt-4o", "4", "2", "600", true));
                        adminContent.removeAllViews();
                        renderUsersTab.invoke(admin);
                        TextView liveErr = findDangerErrorTextView(adminContent, dangerColor);
                        assertNotNull("Expected users error TextView when settings != null", liveErr);
                        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, liveErr.getAccessibilityLiveRegion());

                        // R17.5.3: UniversalSearchSheet offline banner is polite; empty-state announces on transitions only.
                        UniversalSearchModel.LocalEntry localSample =
                                UniversalSearchModel.LocalEntry.tool("settings", "Settings", "Preferences", "");
                        UniversalSearchSheet sheet = UniversalSearchSheet.show(
                                activity, null, Collections.singletonList(localSample),
                                new UniversalSearchSheet.Callbacks() {
                                    @Override public void openThread(String id, String title) {}
                                    @Override public void openNote(String noteId) {}
                                    @Override public void openSharedItem(UniversalSearchModel.Result item) {}
                                    @Override public void runAction(String actionId) {}
                                    @Override public void onDismissed() {}
                                });
                        searchRef.set(sheet);
                        Field sessionField = UniversalSearchSheet.class.getDeclaredField("session");
                        sessionField.setAccessible(true);
                        UniversalSearchModel.SearchSession searchSession =
                                (UniversalSearchModel.SearchSession) sessionField.get(sheet);
                        Field statusSlotField = UniversalSearchSheet.class.getDeclaredField("statusSlot");
                        statusSlotField.setAccessible(true);
                        LinearLayout statusSlot = (LinearLayout) statusSlotField.get(sheet);
                        Field resultsField = UniversalSearchSheet.class.getDeclaredField("resultsContainer");
                        resultsField.setAccessible(true);
                        LinearLayout resultsContainer = (LinearLayout) resultsField.get(sheet);
                        Method renderResults = UniversalSearchSheet.class.getDeclaredMethod("renderResults");
                        renderResults.setAccessible(true);

                        // First empty state transition -> polite live region.
                        int ep1 = searchSession.setQuery("zzz-nomatch");
                        searchSession.acceptRemote(ep1, Collections.emptyList(), null, null, false);
                        renderResults.invoke(sheet);
                        TextView emptyTitle1 = findTextViewWithText(
                                resultsContainer, activity.getString(R.string.universal_search_empty_title));
                        assertNotNull("Expected empty-state title on first empty render", emptyTitle1);
                        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,
                                emptyTitle1.getAccessibilityLiveRegion());

                        // Subsequent keystroke staying in same empty state -> guarded (NONE).
                        int ep2 = searchSession.setQuery("zzz-nomatch-2");
                        searchSession.acceptRemote(ep2, Collections.emptyList(), null, null, false);
                        renderResults.invoke(sheet);
                        TextView emptyTitle2 = findTextViewWithText(
                                resultsContainer, activity.getString(R.string.universal_search_empty_title));
                        assertNotNull(emptyTitle2);
                        assertEquals(View.ACCESSIBILITY_LIVE_REGION_NONE,
                                emptyTitle2.getAccessibilityLiveRegion());

                        // Degraded failure -> offline banner msg is polite AND error empty-state transition is polite.
                        searchSession.acceptRemote(ep2, Collections.emptyList(), null, null, true);
                        renderResults.invoke(sheet);
                        TextView offlineBanner = findTextViewWithText(
                                statusSlot, activity.getString(R.string.universal_search_offline_local_only));
                        assertNotNull("Expected offline banner in UniversalSearchSheet statusSlot", offlineBanner);
                        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,
                                offlineBanner.getAccessibilityLiveRegion());
                        TextView degradedTitle = findTextViewWithText(
                                resultsContainer, activity.getString(R.string.universal_search_error));
                        assertNotNull(degradedTitle);
                        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,
                                degradedTitle.getAccessibilityLiveRegion());

                        // Non-empty results reset empty announcement state so a later empty query announces again.
                        searchSession.setQuery("");
                        renderResults.invoke(sheet);
                        int ep3 = searchSession.setQuery("zzz-again");
                        searchSession.acceptRemote(ep3, Collections.emptyList(), null, null, false);
                        renderResults.invoke(sheet);
                        TextView emptyTitle3 = findTextViewWithText(
                                resultsContainer, activity.getString(R.string.universal_search_empty_title));
                        assertNotNull(emptyTitle3);
                        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,
                                emptyTitle3.getAccessibilityLiveRegion());

                        // R17.5.4: SessionSettings picker empty-search notice has polite live region.
                        SessionPanel sessionPanel = new SessionPanel(activity, api, fixture.account);
                        sessionPanelRef.set(sessionPanel);
                        Class<?> settingsClass = Class.forName("org.llm4people.hermes.panel.SessionSettings");
                        Constructor<?> settingsCtor = settingsClass.getDeclaredConstructor(
                                SessionPanel.class, String.class, String.class,
                                BooleanSupplier.class, Consumer.class, Supplier.class);
                        settingsCtor.setAccessible(true);
                        Object settingsObj = settingsCtor.newInstance(
                                sessionPanel, "s-1", "s-1", (BooleanSupplier) () -> true,
                                (Consumer<String>) s -> {},
                                (Supplier<android.content.res.Configuration>) () -> activity.getResources().getConfiguration());
                        Method pickerMethod = settingsClass.getDeclaredMethod(
                                "picker", LinearLayout.class, ArrayList.class, Object.class, Consumer.class);
                        pickerMethod.setAccessible(true);
                        LinearLayout pickerBox = Ui.column(activity);
                        pickerMethod.invoke(settingsObj, pickerBox,
                                new ArrayList<>(Arrays.asList("Alpha", "Beta")), "Alpha", (Consumer<Object>) o -> {});
                        TextView emptySearch = findTextViewWithText(
                                pickerBox, activity.getString(R.string.panel_empty_search));
                        assertNotNull("Expected empty-search TextView in SessionSettings picker", emptySearch);
                        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,
                                emptySearch.getAccessibilityLiveRegion());
                    } catch (Exception err) {
                        throw new AssertionError(err);
                    }
                });
            } finally {
                try {
                    runTestOnUiThread(() -> {
                        NotesTasksPanel nt = notesTasksRef.getAndSet(null);
                        if (nt != null) nt.close();
                        AdminConsoleSheet ac = adminRef.getAndSet(null);
                        if (ac != null) ac.close();
                        UniversalSearchSheet us = searchRef.getAndSet(null);
                        if (us != null) us.close();
                        SessionPanel sp = sessionPanelRef.getAndSet(null);
                        if (sp != null) sp.close();
                    });
                } finally {
                    ActivityTestLifecycle.finishAndAwaitDestroyed(this, activity);
                }
            }
        } finally {
            Diagnostics.clear();
        }
    }

    public void testRound18LiveRegionsReasoningAndManualPaste() throws Throwable {
        Context context = getInstrumentation().getTargetContext();
        AtomicReference<SessionPanel> sessionPanelRef = new AtomicReference<>();
        AtomicReference<AmbientAccessPanel> ambientRef = new AtomicReference<>();
        AtomicReference<AlertDialog> pasteDialogRef = new AtomicReference<>();
        try (WorkspaceAccountFixture fixture = new WorkspaceAccountFixture(context)) {
            MainActivity activity = launchActivity(context.getPackageName(), MainActivity.class, null);
            try {
                runTestOnUiThread(() -> {
                    try {
                        HermesApi api = new HermesApi("http://127.0.0.1:1", "tok");
                        SessionPanel sessionPanel = new SessionPanel(activity, api, fixture.account);
                        sessionPanelRef.set(sessionPanel);

                        // R18.5.1 (1/8): SettingsHubPanel live-filtered empty state transition guard.
                        SettingsHubPanel settingsHub = new SettingsHubPanel(activity, new SettingsHubPanel.StateProvider() {
                            @Override public String profileName() { return "Test"; }
                            @Override public String serverEndpoint() { return "https://example.invalid"; }
                            @Override public String lockModeLabel() { return "PIN"; }
                            @Override public String lockTimeoutLabel() { return "5m"; }
                            @Override public String securitySummary() { return "Locked"; }
                            @Override public String themeModeLabel() { return "System"; }
                            @Override public String appearanceSummary() { return "Default"; }
                            @Override public String languageSummary() { return "English"; }
                            @Override public String voiceLocaleLabel() { return "English"; }
                            @Override public String updateBadge() { return ""; }
                            @Override public String versionName() { return "1.0"; }
                        }, action -> {});
                        View hubRoot = settingsHub.buildView();
                        EditText hubSearch = findFirst(hubRoot, EditText.class);
                        assertNotNull(hubSearch);
                        hubSearch.setText("zzz-none-1");
                        TextView hubEmpty1 = findTextViewWithText(hubRoot,
                                activity.getString(R.string.settings_search_empty, "zzz-none-1"));
                        assertNotNull(hubEmpty1);
                        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, hubEmpty1.getAccessibilityLiveRegion());
                        hubSearch.setText("zzz-none-2");
                        TextView hubEmpty2 = findTextViewWithText(hubRoot,
                                activity.getString(R.string.settings_search_empty, "zzz-none-2"));
                        assertNotNull(hubEmpty2);
                        assertEquals(View.ACCESSIBILITY_LIVE_REGION_NONE, hubEmpty2.getAccessibilityLiveRegion());
                        hubSearch.setText("");
                        hubSearch.setText("zzz-none-3");
                        TextView hubEmpty3 = findTextViewWithText(hubRoot,
                                activity.getString(R.string.settings_search_empty, "zzz-none-3"));
                        assertNotNull(hubEmpty3);
                        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, hubEmpty3.getAccessibilityLiveRegion());

                        // R18.5.1 (2/8): DataTableBlock filterable empty state transition guard.
                        List<DataTableBlock.Column> cols = Collections.singletonList(
                                new DataTableBlock.Column("name", "Name", false));
                        DataTableBlock.Model tableModel = new DataTableBlock.Model(
                                cols, Collections.singletonList(Collections.singletonList("Alpha")), false, true, "");
                        Method buildTable = DataTableBlock.class.getDeclaredMethod(
                                "build", Context.class, DataTableBlock.Model.class, ThemePalette.class, ArtifactDesk.Host.class);
                        buildTable.setAccessible(true);
                        View tableView = (View) buildTable.invoke(
                                null, activity, tableModel, null, new WorkspaceHost(activity, () -> true));
                        EditText tableFilter = findFirst(tableView, EditText.class);
                        assertNotNull(tableFilter);
                        String tableEmptyStr = activity.getString(R.string.ws_table_empty);
                        tableFilter.setText("zzz");
                        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,
                                findTextViewWithText(tableView, tableEmptyStr).getAccessibilityLiveRegion());
                        tableFilter.setText("zzzz");
                        assertEquals(View.ACCESSIBILITY_LIVE_REGION_NONE,
                                findTextViewWithText(tableView, tableEmptyStr).getAccessibilityLiveRegion());
                        tableFilter.setText("");
                        tableFilter.setText("zzz");
                        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,
                                findTextViewWithText(tableView, tableEmptyStr).getAccessibilityLiveRegion());

                        // R18.5.1 (3/8): NotesPanel live-filtered empty state transition guard.
                        Class<?> notesClass = Class.forName("org.llm4people.hermes.panel.NotesPanel");
                        Constructor<?> notesCtor = notesClass.getDeclaredConstructor(
                                SessionPanel.class, BooleanSupplier.class, Consumer.class, Runnable.class);
                        notesCtor.setAccessible(true);
                        Object notesPanel = notesCtor.newInstance(
                                sessionPanel, (BooleanSupplier) () -> true, (Consumer<String>) s -> {}, null);
                        LinearLayout notesBody = Ui.column(activity);
                        EditText notesFilter = new EditText(activity);
                        setField(notesPanel, "body", notesBody);
                        setField(notesPanel, "filterField", notesFilter);
                        setField(notesPanel, "notes", Collections.singletonList(
                                new NotesModel.Note("n1", "Alpha", "Body", Collections.emptyList(), false, "", "", "owner")));
                        Method notesRender = notesClass.getDeclaredMethod("renderList");
                        notesRender.setAccessible(true);
                        String notesEmptyStr = activity.getString(
                                NotesModel.emptyMessageRes(NotesModel.FilterTab.MINE, false));
                        notesFilter.setText("zzz");
                        notesRender.invoke(notesPanel);
                        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,
                                findTextViewWithText(notesBody, notesEmptyStr).getAccessibilityLiveRegion());
                        notesFilter.setText("zzzz");
                        notesRender.invoke(notesPanel);
                        assertEquals(View.ACCESSIBILITY_LIVE_REGION_NONE,
                                findTextViewWithText(notesBody, notesEmptyStr).getAccessibilityLiveRegion());
                        notesFilter.setText("");
                        notesRender.invoke(notesPanel);
                        notesFilter.setText("zzz");
                        notesRender.invoke(notesPanel);
                        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,
                                findTextViewWithText(notesBody, notesEmptyStr).getAccessibilityLiveRegion());

                        // R18.5.1 (4/8): MemoryPanel live-filtered empty state transition guard.
                        Class<?> memClass = Class.forName("org.llm4people.hermes.panel.MemoryPanel");
                        Constructor<?> memCtor = memClass.getDeclaredConstructor(SessionPanel.class, Runnable.class);
                        memCtor.setAccessible(true);
                        Object memPanel = memCtor.newInstance(sessionPanel, null);
                        LinearLayout memBody = Ui.column(activity);
                        EditText memFilter = new EditText(activity);
                        MemoryModel.Bucket memBucket = new MemoryModel.Bucket(
                                MemoryModel.TARGET_MEMORY,
                                Collections.singletonList(new MemoryModel.Entry("e1", "Alpha memory", false)),
                                12, MemoryModel.DEFAULT_CHAR_LIMIT, true);
                        MemoryModel.Bucket userBucket = new MemoryModel.Bucket(
                                MemoryModel.TARGET_USER, Collections.emptyList(), 0, MemoryModel.DEFAULT_CHAR_LIMIT, true);
                        setField(memPanel, "body", memBody);
                        setField(memPanel, "filterField", memFilter);
                        setField(memPanel, "snapshot", new MemoryModel.Snapshot(memBucket, userBucket));
                        Method memRender = memClass.getDeclaredMethod("renderEntries");
                        memRender.setAccessible(true);
                        String memEmptyStr = activity.getString(R.string.brain_memory_empty_filter);
                        memFilter.setText("zzz");
                        memRender.invoke(memPanel);
                        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,
                                findTextViewWithText(memBody, memEmptyStr).getAccessibilityLiveRegion());
                        memFilter.setText("zzzz");
                        memRender.invoke(memPanel);
                        assertEquals(View.ACCESSIBILITY_LIVE_REGION_NONE,
                                findTextViewWithText(memBody, memEmptyStr).getAccessibilityLiveRegion());
                        memFilter.setText("");
                        memRender.invoke(memPanel);
                        memFilter.setText("zzz");
                        memRender.invoke(memPanel);
                        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,
                                findTextViewWithText(memBody, memEmptyStr).getAccessibilityLiveRegion());

                        // R18.5.1 (5/8): ProfileDefaultsPanel toolset filter empty state transition guard.
                        Class<?> profClass = Class.forName("org.llm4people.hermes.panel.ProfileDefaultsPanel");
                        Constructor<?> profCtor = profClass.getDeclaredConstructor(SessionPanel.class, Runnable.class);
                        profCtor.setAccessible(true);
                        Object profPanel = profCtor.newInstance(sessionPanel, null);
                        Field profModelField = profClass.getDeclaredField("model");
                        profModelField.setAccessible(true);
                        ProfileDefaultsModel profModel = (ProfileDefaultsModel) profModelField.get(profPanel);
                        profModel.load("openai", "gpt-4o", "", Collections.singletonList("web"), Arrays.asList("web", "code"));
                        LinearLayout toolsetBox = Ui.column(activity);
                        setField(profPanel, "toolsetListContainer", toolsetBox);
                        Method refreshToolsets = profClass.getDeclaredMethod("refreshToolsetRows");
                        refreshToolsets.setAccessible(true);
                        String toolsetEmptyStr = activity.getString(R.string.profile_defaults_toolsets_empty_search);
                        setField(profPanel, "toolsetFilterQuery", "zzz");
                        refreshToolsets.invoke(profPanel);
                        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,
                                findTextViewWithText(toolsetBox, toolsetEmptyStr).getAccessibilityLiveRegion());
                        setField(profPanel, "toolsetFilterQuery", "zzzz");
                        refreshToolsets.invoke(profPanel);
                        assertEquals(View.ACCESSIBILITY_LIVE_REGION_NONE,
                                findTextViewWithText(toolsetBox, toolsetEmptyStr).getAccessibilityLiveRegion());
                        setField(profPanel, "toolsetFilterQuery", "");
                        refreshToolsets.invoke(profPanel);
                        setField(profPanel, "toolsetFilterQuery", "zzz");
                        refreshToolsets.invoke(profPanel);
                        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,
                                findTextViewWithText(toolsetBox, toolsetEmptyStr).getAccessibilityLiveRegion());

                        // R18.5.1 (6/8): AmbientAccessPanel allowlist filter empty state transition guard.
                        AmbientAccessPanel ambient = new AmbientAccessPanel(
                                activity, NotificationAllowlist.forAccount(fixture.account), text -> {});
                        ambientRef.set(ambient);
                        LinearLayout allowlistBox = Ui.column(activity);
                        setField(ambient, "allowlistContainer", allowlistBox);
                        setField(ambient, "installedApps", Collections.singletonList(
                                new AmbientAccessPanel.AppEntry("com.example.chat", "Chat")));
                        Method renderAllowlist = AmbientAccessPanel.class.getDeclaredMethod("renderAllowlist");
                        renderAllowlist.setAccessible(true);
                        String allowEmptyStr = activity.getString(R.string.ambient_allowlist_empty);
                        setField(ambient, "searchFilter", "zzz");
                        renderAllowlist.invoke(ambient);
                        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,
                                findTextViewWithText(allowlistBox, allowEmptyStr).getAccessibilityLiveRegion());
                        setField(ambient, "searchFilter", "zzzz");
                        renderAllowlist.invoke(ambient);
                        assertEquals(View.ACCESSIBILITY_LIVE_REGION_NONE,
                                findTextViewWithText(allowlistBox, allowEmptyStr).getAccessibilityLiveRegion());
                        setField(ambient, "searchFilter", "");
                        renderAllowlist.invoke(ambient);
                        setField(ambient, "searchFilter", "zzz");
                        renderAllowlist.invoke(ambient);
                        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,
                                findTextViewWithText(allowlistBox, allowEmptyStr).getAccessibilityLiveRegion());

                        // R18.5.1 (7/8): ReminderPanel filtered jobs empty state transition guard.
                        Constructor<ReminderPanel> remCtor = ReminderPanel.class.getDeclaredConstructor(SessionPanel.class);
                        remCtor.setAccessible(true);
                        ReminderPanel remPanel = remCtor.newInstance(sessionPanel);
                        LinearLayout jobsBox = Ui.column(activity);
                        JSONArray jobsArr = new JSONArray().put(new JSONObject()
                                .put("id", "j1")
                                .put("name", "Daily Brief")
                                .put("state", "active"));
                        Method renderJobs = ReminderPanel.class.getDeclaredMethod(
                                "renderJobsOverview", LinearLayout.class, JSONArray.class, ZoneId.class,
                                Locale.class, TextView.class);
                        renderJobs.setAccessible(true);
                        String jobsEmptyStr = activity.getString(R.string.panel_jobs_filter_empty);
                        setField(remPanel, "activeFilter", ScheduledJobModel.Filter.PAUSED);
                        renderJobs.invoke(remPanel, jobsBox, jobsArr, ZoneId.of("UTC"), Locale.US, null);
                        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,
                                findTextViewWithText(jobsBox, jobsEmptyStr).getAccessibilityLiveRegion());
                        renderJobs.invoke(remPanel, jobsBox, jobsArr, ZoneId.of("UTC"), Locale.US, null);
                        assertEquals(View.ACCESSIBILITY_LIVE_REGION_NONE,
                                findTextViewWithText(jobsBox, jobsEmptyStr).getAccessibilityLiveRegion());
                        setField(remPanel, "activeFilter", ScheduledJobModel.Filter.ALL);
                        renderJobs.invoke(remPanel, jobsBox, jobsArr, ZoneId.of("UTC"), Locale.US, null);
                        setField(remPanel, "activeFilter", ScheduledJobModel.Filter.PAUSED);
                        renderJobs.invoke(remPanel, jobsBox, jobsArr, ZoneId.of("UTC"), Locale.US, null);
                        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,
                                findTextViewWithText(jobsBox, jobsEmptyStr).getAccessibilityLiveRegion());

                        // R18.5.1 (8/8): SkillStudio empty-state transition guard prevents per-keystroke spam.
                        Class<?> skillStudioClass = Class.forName("org.llm4people.hermes.panel.SkillStudio");
                        Constructor<?> skillCtor = skillStudioClass.getDeclaredConstructor(
                                SessionPanel.class, BooleanSupplier.class, Consumer.class, Runnable.class);
                        skillCtor.setAccessible(true);
                        Object skillStudio = skillCtor.newInstance(
                                sessionPanel, (BooleanSupplier) () -> true, (Consumer<String>) s -> {}, null);
                        Class<?> skillInvClass = Class.forName("org.llm4people.hermes.panel.SkillInventory");
                        Method parseSkills = skillInvClass.getDeclaredMethod("parse", JSONObject.class);
                        parseSkills.setAccessible(true);
                        Object parsedSkills = parseSkills.invoke(null, new JSONObject().put("data", new JSONArray().put(
                                new JSONObject().put("name", "deploy").put("description", "Deploy").put("category", "ops"))));
                        LinearLayout skillListBox = Ui.column(activity);
                        EditText skillSearch = new EditText(activity);
                        setField(skillStudio, "listBox", skillListBox);
                        setField(skillStudio, "searchField", skillSearch);
                        setField(skillStudio, "inventory", parsedSkills);
                        Method skillRender = skillStudioClass.getDeclaredMethod("renderList");
                        skillRender.setAccessible(true);
                        String skillsEmptyStr = activity.getString(R.string.skills_no_matches);
                        skillSearch.setText("zzz");
                        skillRender.invoke(skillStudio);
                        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,
                                findTextViewWithText(skillListBox, skillsEmptyStr).getAccessibilityLiveRegion());
                        skillSearch.setText("zzzz");
                        skillRender.invoke(skillStudio);
                        assertEquals(View.ACCESSIBILITY_LIVE_REGION_NONE,
                                findTextViewWithText(skillListBox, skillsEmptyStr).getAccessibilityLiveRegion());
                        skillSearch.setText("");
                        skillRender.invoke(skillStudio);
                        skillSearch.setText("zzz");
                        skillRender.invoke(skillStudio);
                        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,
                                findTextViewWithText(skillListBox, skillsEmptyStr).getAccessibilityLiveRegion());

                        // R18.5.2: Streaming reasoning toggle uses reasoning_streaming_show_label when collapsed.
                        String streamingCd = activity.getString(R.string.reasoning_streaming_show_label);
                        String finishedCd = activity.getString(R.string.reasoning_show_label);
                        String hideCd = activity.getString(R.string.reasoning_hide);

                        Class<?> msgTextClass = Class.forName("org.llm4people.hermes.thread.MessageText");
                        Method reasoningCardMethod = msgTextClass.getDeclaredMethod(
                                "reasoningCard", Context.class, String.class, boolean.class, Consumer.class);
                        reasoningCardMethod.setAccessible(true);
                        View streamingCard = (View) reasoningCardMethod.invoke(null, activity, "Step 1", true, null);
                        Button streamingToggle = findFirst(streamingCard, Button.class);
                        assertNotNull(streamingToggle);
                        assertEquals(streamingCd, String.valueOf(streamingToggle.getContentDescription()));
                        streamingToggle.performClick();
                        assertEquals(hideCd, String.valueOf(streamingToggle.getContentDescription()));
                        streamingToggle.performClick();
                        assertEquals(streamingCd, String.valueOf(streamingToggle.getContentDescription()));

                        View staticCard = (View) reasoningCardMethod.invoke(null, activity, "Step 1", false, null);
                        Button staticToggle = findFirst(staticCard, Button.class);
                        assertNotNull(staticToggle);
                        assertEquals(finishedCd, String.valueOf(staticToggle.getContentDescription()));

                        Class<?> liveCardClass = Class.forName("org.llm4people.hermes.thread.ThreadViews$LiveReasoningCard");
                        Constructor<?> liveCardCtor = liveCardClass.getDeclaredConstructor(Context.class, Consumer.class);
                        liveCardCtor.setAccessible(true);
                        Object liveCard = liveCardCtor.newInstance(activity, null);
                        Field liveToggleField = liveCardClass.getDeclaredField("toggle");
                        liveToggleField.setAccessible(true);
                        Button liveToggle = (Button) liveToggleField.get(liveCard);
                        assertEquals(streamingCd, String.valueOf(liveToggle.getContentDescription()));
                        Method liveUpdate = liveCardClass.getDeclaredMethod("update", String.class, boolean.class);
                        liveUpdate.setAccessible(true);
                        liveUpdate.invoke(liveCard, "Thinking...", true);
                        assertEquals(streamingCd, String.valueOf(liveToggle.getContentDescription()));
                        liveUpdate.invoke(liveCard, "Done.", false);
                        assertEquals(finishedCd, String.valueOf(liveToggle.getContentDescription()));
                        liveToggle.performClick();
                        assertEquals(hideCd, String.valueOf(liveToggle.getContentDescription()));
                        liveUpdate.invoke(liveCard, "More...", true);
                        assertEquals(hideCd, String.valueOf(liveToggle.getContentDescription()));
                        liveToggle.performClick();
                        assertEquals(streamingCd, String.valueOf(liveToggle.getContentDescription()));

                        // R18.5.3: FirstRunWizard.showManualPasteDialog uses padded profile_input EditText.
                        Method showPaste = FirstRunWizard.class.getDeclaredMethod(
                                "showManualPasteDialog",
                                Activity.class, FirstRunWizard.Step1Targets.class, Consumer.class, TextView.class);
                        showPaste.setAccessible(true);
                        FirstRunWizard.Step1Targets targets = new FirstRunWizard.Step1Targets() {
                            @Override public void fillFields(String n, String u, String s, String k, boolean f) {}
                            @Override public void stagePairing(FirstRunWizard.StagedPairing staged) {}
                            @Override public void triggerConnectionProbe(String endpoint, String bearer) {}
                            @Override public void showInstantStatus(String message, int colorRes) {}
                            @Override public void onClaimedMetadata(OnboardingModels.PairingClaim claim) {}
                            @Override public void trackDialog(AlertDialog dialog) { pasteDialogRef.set(dialog); }
                            @Override public void onScanQrRequested() {}
                        };
                        showPaste.invoke(null, activity, targets, (Consumer<Object>) p -> {}, Ui.text(activity, "", 14));
                        AlertDialog pasteDialog = pasteDialogRef.get();
                        assertNotNull("Expected manual paste AlertDialog", pasteDialog);
                        EditText pasteInput = findFirst(pasteDialog.getWindow().getDecorView(), EditText.class);
                        assertNotNull("Expected EditText inside manual paste dialog", pasteInput);
                        assertTrue("EditText must be wrapped in padded LinearLayout",
                                pasteInput.getParent() instanceof LinearLayout);
                        LinearLayout pasteBox = (LinearLayout) pasteInput.getParent();
                        assertTrue(pasteBox.getPaddingStart() >= Ui.dp(activity, 20));
                        assertTrue(pasteBox.getPaddingTop() >= Ui.dp(activity, 8));
                        assertTrue("Manual paste EditText minHeight must be >= 48dp",
                                pasteInput.getMinimumHeight() >= Ui.dp(activity, 48));
                        assertEquals(activity.getColor(R.color.ink), pasteInput.getCurrentTextColor());
                        assertEquals(activity.getColor(R.color.muted), pasteInput.getCurrentHintTextColor());
                        assertNotNull(pasteInput.getBackground());
                        assertNull(pasteInput.getBackgroundTintList());
                    } catch (Exception err) {
                        throw new AssertionError(err);
                    }
                });
            } finally {
                try {
                    runTestOnUiThread(() -> {
                        AlertDialog dlg = pasteDialogRef.getAndSet(null);
                        if (dlg != null && dlg.isShowing()) dlg.dismiss();
                        AmbientAccessPanel ap = ambientRef.getAndSet(null);
                        if (ap != null) ap.close();
                        SessionPanel sp = sessionPanelRef.getAndSet(null);
                        if (sp != null) sp.close();
                    });
                } finally {
                    ActivityTestLifecycle.finishAndAwaitDestroyed(this, activity);
                }
            }
        } finally {
            Diagnostics.clear();
        }
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

    private static void assertButtonAccessibilityRole(View view) {
        AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
        try {
            view.onInitializeAccessibilityNodeInfo(info);
            assertEquals(Button.class.getName(), String.valueOf(info.getClassName()));
        } finally {
            info.recycle();
        }
    }

    private static void assertSelectableButtonNode(View view, boolean expectedSelected) {
        AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
        try {
            view.onInitializeAccessibilityNodeInfo(info);
            assertEquals(Button.class.getName(), String.valueOf(info.getClassName()));
            assertEquals(expectedSelected, info.isSelected());
        } finally {
            info.recycle();
        }
    }

    private static void assertCheckableNode(View view, boolean expectedChecked) {
        AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
        try {
            view.onInitializeAccessibilityNodeInfo(info);
            assertTrue("Expected checkable AccessibilityNodeInfo", info.isCheckable());
            assertEquals(expectedChecked, info.isChecked());
        } finally {
            info.recycle();
        }
    }

    private static TextView findDangerErrorTextView(View root, int dangerColor) {
        List<TextView> views = new ArrayList<>();
        collect(root, TextView.class, views);
        for (TextView tv : views) {
            if (tv.getClass() == TextView.class && tv.getCurrentTextColor() == dangerColor) {
                return tv;
            }
        }
        return null;
    }

    private static TextView findTextViewWithText(View root, String expected) {
        List<TextView> views = new ArrayList<>();
        collect(root, TextView.class, views);
        for (TextView tv : views) {
            if (tv.getClass() == TextView.class && expected.equals(String.valueOf(tv.getText()))) {
                return tv;
            }
        }
        return null;
    }

    private static void assertNoClickableDescendants(ViewGroup parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            assertFalse("Unexpected clickable descendant inside clickable container: " + child,
                    child.isClickable());
            if (child instanceof ViewGroup group) {
                assertNoClickableDescendants(group);
            }
        }
    }

    private static ViewGroup findClickableRow(View root) {
        List<LinearLayout> layouts = new ArrayList<>();
        collect(root, LinearLayout.class, layouts);
        for (LinearLayout layout : layouts) {
            if (layout.isClickable()) return layout;
        }
        return null;
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
