package org.llm4people.hermes;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.artifact.ArtifactDesk;
import org.llm4people.hermes.artifact.DataTableBlock;
import org.llm4people.hermes.artifact.ThemePalette;
import org.llm4people.hermes.hub.AppsLauncherModel;
import org.llm4people.hermes.hub.AppsLauncherPanel;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.os.BiometricVault;
import org.llm4people.hermes.palette.CommandItem;
import org.llm4people.hermes.palette.CommandPalette;
import org.llm4people.hermes.palette.CommandRegistry;
import org.llm4people.hermes.panel.ModelCapabilityBadges;
import org.llm4people.hermes.panel.NotesModel;
import org.llm4people.hermes.panel.ProfileDefaultsModel;
import org.llm4people.hermes.panel.SessionPanel;
import org.llm4people.hermes.panel.UniversalShareModel;
import org.llm4people.hermes.places.NearbyGuide;
import org.llm4people.hermes.places.NearbyResult;
import org.llm4people.hermes.places.PlaceSelection;
import org.llm4people.hermes.profile.Account;
import org.llm4people.hermes.profile.ProfileGate;
import org.llm4people.hermes.profile.ProfileLockPolicy;
import org.llm4people.hermes.profile.WorkspaceAccountFixture;
import org.llm4people.hermes.search.UniversalSearchModel;
import org.llm4people.hermes.search.UniversalSearchSheet;
import org.llm4people.hermes.thread.ThreadWorkspace;
import org.llm4people.hermes.voice.VoiceDrafts;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

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
                ActivityTestLifecycle.finishAndAwaitDestroyed(this, activity);
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
                ActivityTestLifecycle.finishAndAwaitDestroyed(this, activity);
            }
        }
    }

    public void testTalkBackContainerRolesAndHiddenChildrenAcrossPanels() throws Throwable {
        Context context = getInstrumentation().getTargetContext();
        MainActivity activity = launchActivity(context.getPackageName(), MainActivity.class, null);
        AtomicReference<SessionPanel> sessionPanelRef = new AtomicReference<>();
        try (WorkspaceAccountFixture fixture = new WorkspaceAccountFixture(context)) {
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
