package org.llm4people.hermes;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.artifact.ArtifactDesk;
import org.llm4people.hermes.artifact.DataTableBlock;
import org.llm4people.hermes.artifact.InstrumentStateStore;
import org.llm4people.hermes.artifact.InteractiveControlsBlock;
import org.llm4people.hermes.artifact.LiveUiPatch;
import org.llm4people.hermes.artifact.LiveUiPatchBar;
import org.llm4people.hermes.artifact.MessageParts;
import org.llm4people.hermes.artifact.ThemePalette;
import org.llm4people.hermes.artifact.ThreadInstruments;
import org.llm4people.hermes.panel.SessionPanel;
import org.llm4people.hermes.places.NearbyCategory;
import org.llm4people.hermes.places.NearbyGuide;
import org.llm4people.hermes.places.NearbyResult;
import org.llm4people.hermes.places.PlaceSelection;
import org.llm4people.hermes.profile.Account;
import org.llm4people.hermes.voice.VoiceDrafts;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Instrumentation regression tests for Triple-Check Rounds 10 &amp; 11 UI/a11y/i18n polish:
 * - R10.1.1 &amp; R10.5.6: Localized InstrumentChanges diffs (nearby_places, sparkline, metrics, controls, section_renamed).
 * - R10.5.1: PromptQuestion multi-select CheckBox &amp; VoiceDrafts mic ImageButton stateful colors.
 * - R10.5.2 &amp; R10.5.5: MessageText reasoning contentDescription toggle &amp; rounded ripples (MessageText &amp; NearbyGuide).
 * - R10.5.3: InteractiveControlsBlock stateful colors/StateListDrawable &amp; DataTableBlock non-sortable TextView headers.
 * - R10.5.4: PushSettings, SessionSettings, and ThreadViews stateful disabled/enabled colors.
 * - R11.1.1: Localized FR/EN ws_image_attached marker through ThreadData.content, ThreadInstrumentPanel.spoken, and MessageParts.project.
 * - R11.5.1, R11.5.2, R11.5.3: PromptButtons.Role.SKIP disabled_ink, InstrumentVersions rounded ripple mask, SkillStudio 48dp Switch.
 */
@SuppressWarnings("deprecation")
public final class UiPolishR10RegressionTest extends InstrumentationTestCase {
    private static final int[] STATE_DISABLED = new int[]{-android.R.attr.state_enabled};
    private static final int[] STATE_ENABLED = new int[]{android.R.attr.state_enabled};

    @Override
    protected void tearDown() throws Exception {
        try {
            Diagnostics.clear();
        } finally {
            super.tearDown();
        }
    }

    public void testInstrumentChangesLocalizesV2DiffsAndSectionRenames() throws Throwable {
        Context raw = getInstrumentation().getTargetContext();
        Context c = AppLanguage.wrapContext(raw);

        Object oldNearby = parseLayout("""
                {"version":2,"surface":"page/explore","mode":"replace","title":"Explore","blocks":[
                  {"type":"nearby_places","category":"cafe","radius_m":1500,"limit":12}
                ]}""");
        Object nextNearby = parseLayout("""
                {"version":2,"surface":"page/explore","mode":"replace","title":"Explore","blocks":[
                  {"type":"nearby_places","category":"pharmacy","radius_m":1500,"limit":12}
                ]}""");
        List<?> nearbyDiffs = invokeInstrumentChangesBetween(c, oldNearby, nextNearby);
        Object categoryChange = findChangeByLabel(nearbyDiffs, c.getString(R.string.ws_diff_category));
        assertNotNull("Expected localized category change row", categoryChange);
        assertEquals(NearbyCategory.CAFE.label(c), recordAccessor(categoryChange, "before"));
        assertEquals(NearbyCategory.PHARMACY.label(c), recordAccessor(categoryChange, "after"));

        Object oldSpark = parseLayout("""
                {"version":2,"surface":"page/trend","mode":"replace","title":"Trend","blocks":[
                  {"type":"sparkline","title":"Activity","style":"line","values":[1,2,3]}
                ]}""");
        Object nextSpark = parseLayout("""
                {"version":2,"surface":"page/trend","mode":"replace","title":"Trend","blocks":[
                  {"type":"sparkline","title":"Activity","style":"bar","values":[1,2,3]}
                ]}""");
        List<?> sparkDiffs = invokeInstrumentChangesBetween(c, oldSpark, nextSpark);
        assertEquals(1, sparkDiffs.size());
        assertEquals(c.getString(R.string.ws_sparkline_line), recordAccessor(sparkDiffs.get(0), "before"));
        assertEquals(c.getString(R.string.ws_sparkline_bar), recordAccessor(sparkDiffs.get(0), "after"));

        Object oldMetric = parseLayout("""
                {"version":2,"surface":"page/kpi","mode":"replace","title":"KPI","blocks":[
                  {"type":"metric_grid","title":"Stats","items":[{"label":"Who","source":"profile.label"}]}
                ]}""");
        Object nextMetric = parseLayout("""
                {"version":2,"surface":"page/kpi","mode":"replace","title":"KPI","blocks":[
                  {"type":"metric_grid","title":"Stats","items":[{"label":"Who","source":"thread.title"}]}
                ]}""");
        List<?> metricDiffs = invokeInstrumentChangesBetween(c, oldMetric, nextMetric);
        assertEquals(1, metricDiffs.size());
        String metricBefore = (String) recordAccessor(metricDiffs.get(0), "before");
        String metricAfter = (String) recordAccessor(metricDiffs.get(0), "after");
        assertFalse("Must not leak raw source= prefix", metricBefore.contains("source="));
        assertTrue(metricBefore.contains(c.getString(R.string.ws_source_profile_label)));
        assertTrue(metricAfter.contains(c.getString(R.string.ws_source_thread_title)));
        String metricDesc = invokeInstrumentChangesDescribe(c, firstBlock(oldMetric));
        assertTrue(metricDesc.contains(c.getString(R.string.ws_source_profile_label)));

        Object oldControls = parseLayout("""
                {"version":2,"surface":"page/ctrl","mode":"replace","title":"Ctrl","blocks":[
                  {"type":"controls","title":"Options","controls":[
                    {"id":"t1","label":"One","kind":"toggle","default":false},
                    {"id":"c1","label":"Two","kind":"checkbox","default":false}
                  ]}
                ]}""");
        Object nextControls = parseLayout("""
                {"version":2,"surface":"page/ctrl","mode":"replace","title":"Ctrl","blocks":[
                  {"type":"controls","title":"Options","controls":[
                    {"id":"s1","label":"Three","kind":"slider","min":0,"max":10,"step":1,"default":5},
                    {"id":"h1","label":"Four","kind":"chips","options":["A","B"],"default":"A"}
                  ]}
                ]}""");
        List<?> ctrlDiffs = invokeInstrumentChangesBetween(c, oldControls, nextControls);
        assertEquals(1, ctrlDiffs.size());
        String ctrlBefore = (String) recordAccessor(ctrlDiffs.get(0), "before");
        String ctrlAfter = (String) recordAccessor(ctrlDiffs.get(0), "after");
        assertTrue(ctrlBefore.contains("(" + c.getString(R.string.ws_diff_ctrl_toggle) + ")"));
        assertTrue(ctrlBefore.contains("(" + c.getString(R.string.ws_diff_ctrl_checkbox) + ")"));
        assertTrue(ctrlAfter.contains("(" + c.getString(R.string.ws_diff_ctrl_slider) + ")"));
        assertTrue(ctrlAfter.contains("(" + c.getString(R.string.ws_diff_ctrl_chips) + ")"));

        Object oldSections = parseLayout("""
                {"version":1,"surface":"page/sec","mode":"replace","title":"Sec","blocks":[
                  {"title":"Check","note":"Before"},
                  {"title":"Check","note":"After"}
                ]}""");
        Object nextSections = parseLayout("""
                {"version":1,"surface":"page/sec","mode":"replace","title":"Sec","blocks":[
                  {"title":"Check","note":"Before"},
                  {"title":"Review","note":"After"}
                ]}""");
        List<?> renameDiffs = invokeInstrumentChangesBetween(c, oldSections, nextSections);
        assertEquals(1, renameDiffs.size());
        assertEquals(c.getString(R.string.ws_diff_section_renamed, "Check · After", "Review"),
                recordAccessor(renameDiffs.get(0), "section"));
    }

    public void testLocalizedImageMarkerInThreadDataAndInstrumentPanel() throws Throwable {
        Context base = getInstrumentation().getTargetContext();
        String pageJson = """
                {"version":1,"surface":"page/demo","mode":"replace","title":"Demo","blocks":[{"title":"Info","note":"Body"}]}""";
        String fence = "```hermes-workspace\n" + pageJson + "\n```";
        JSONObject row = new JSONObject()
                .put("id", "m-1")
                .put("role", "assistant")
                .put("content", new JSONArray()
                        .put(new JSONObject().put("type", "text").put("text", "Intro\n" + fence))
                        .put(new JSONObject().put("type", "image_url")));
        Object layout = parseLayout(pageJson);
        Class<?> layoutCls = Class.forName("org.llm4people.hermes.artifact.WorkspaceLayout");
        Constructor<ThreadInstruments.Pin> pinCtor = ThreadInstruments.Pin.class.getDeclaredConstructor(
                ThreadInstruments.class, layoutCls, String.class, String.class, String.class, int.class, int.class);
        pinCtor.setAccessible(true);
        ThreadInstruments.Pin pin = pinCtor.newInstance(null, layout, pageJson, "m-1", "s-1", 0, 1);
        List<ThreadInstruments.Pin> proposals = Collections.singletonList(pin);

        Class<?> threadDataCls = Class.forName("org.llm4people.hermes.thread.ThreadData");
        Method tdContent = threadDataCls.getDeclaredMethod("content", Context.class, JSONObject.class);
        tdContent.setAccessible(true);
        Class<?> panelCls = Class.forName("org.llm4people.hermes.thread.ThreadInstrumentPanel");
        Method spoken = panelCls.getDeclaredMethod("spoken", Context.class, JSONObject.class, List.class);
        spoken.setAccessible(true);

        AppLanguage.Mode originalMode = AppLanguage.readMode(base);
        if (originalMode != AppLanguage.Mode.AUTO) {
            AppLanguage.apply(base, AppLanguage.Mode.AUTO);
        }
        try {
            for (Locale locale : new Locale[]{Locale.FRENCH, Locale.ENGLISH}) {
                Context ctx = localized(base, locale);
                String expectedMarker = ctx.getString(R.string.ws_image_attached);
                String expectedSpoken = ctx.getString(R.string.instrument_spoken, "Demo");
                assertEquals(Locale.FRENCH.equals(locale) ? "[Image jointe]" : "[Image attached]", expectedMarker);

                String content = (String) tdContent.invoke(null, ctx, row);
                assertTrue("ThreadData.content should localize image marker for " + locale,
                        content.contains(expectedMarker));
                assertFalse("ThreadData.content should not emit raw [image] for " + locale,
                        content.contains("[image]"));

                String spokenNoProposals = (String) spoken.invoke(null, ctx, row, Collections.emptyList());
                assertTrue("ThreadInstrumentPanel.spoken (no proposals) should localize image marker for " + locale,
                        spokenNoProposals.contains(expectedMarker));

                String spokenWithProposal = (String) spoken.invoke(null, ctx, row, proposals);
                assertTrue("ThreadInstrumentPanel.spoken should include localized spoken label for " + locale,
                        spokenWithProposal.contains(expectedSpoken));
                assertTrue("ThreadInstrumentPanel.spoken should localize image marker for " + locale,
                        spokenWithProposal.contains(expectedMarker));

                String projectedProse = MessageParts.project(
                        row, Collections.singletonMap(0, pageJson), ignored -> "", ctx);
                assertTrue("MessageParts.project prose should localize image marker for " + locale,
                        projectedProse.contains(expectedMarker));
                assertFalse(projectedProse.contains("[image]"));
            }
        } finally {
            if (originalMode != AppLanguage.Mode.AUTO) {
                AppLanguage.apply(base, originalMode);
            }
        }
    }

    public void testStatefulColorsAndRipplesAcrossThreadAndArtifactControls() throws Throwable {
        Context context = getInstrumentation().getTargetContext();
        MainActivity activity = launchActivity(context.getPackageName(), MainActivity.class, null);
        AtomicReference<VoiceDrafts> voiceRef = new AtomicReference<>();
        try {
            runTestOnUiThread(() -> {
                try {
                    int ink = activity.getColor(R.color.ink);
                    int accent = activity.getColor(R.color.accent);
                    int danger = activity.getColor(R.color.danger);
                    int disabledInk = activity.getColor(R.color.disabled_ink);

                    // 1. VoiceDrafts mic button stateful tint (R10.5.1)
                    VoiceDrafts voice = new VoiceDrafts(activity);
                    voiceRef.set(voice);
                    VoiceDrafts.Controls voiceControls = voice.bind(new EditText(activity));
                    assertStatefulColors("VoiceDrafts mic tint", voiceControls.button.getImageTintList(), accent, disabledInk);

                    // 2. PromptQuestion multi-select CheckBox stateful ink (R10.5.1) & ThreadViews stop button (R10.5.4)
                    Class<?> threadViewsCls = Class.forName("org.llm4people.hermes.thread.ThreadViews");
                    Constructor<?> tvCtor = threadViewsCls.getDeclaredConstructor(Activity.class, VoiceDrafts.class);
                    tvCtor.setAccessible(true);
                    Object threadViews = tvCtor.newInstance(activity, voice);

                    JSONObject prompt = new JSONObject()
                            .put("kind", "clarify")
                            .put("prompt_id", "q-r10")
                            .put("execution_generation", 1)
                            .put("question", "Pick options")
                            .put("multi_select", true)
                            .put("choices", new JSONArray().put("Option A").put("Option B"));
                    Class<?> promptCardCls = Class.forName("org.llm4people.hermes.thread.PromptCard");
                    Constructor<?> pcCtor = promptCardCls.getDeclaredConstructor(
                            threadViewsCls, JSONObject.class, Consumer.class, Runnable.class);
                    pcCtor.setAccessible(true);
                    View promptCard = (View) pcCtor.newInstance(threadViews, prompt, (Consumer<String>) s -> {}, (Runnable) () -> {});
                    CheckBox multiCheck = findFirst(promptCard, CheckBox.class);
                    assertNotNull("Expected multi-select CheckBox in PromptCard", multiCheck);
                    assertStatefulColors("PromptQuestion CheckBox", multiCheck.getTextColors(), ink, disabledInk);

                    Method chatMethod = threadViewsCls.getDeclaredMethod("chat",
                            String.class, Consumer.class, Runnable.class, Runnable.class, Runnable.class);
                    chatMethod.setAccessible(true);
                    chatMethod.invoke(threadViews, "", (Consumer<String>) s -> {},
                            (Runnable) () -> {}, (Runnable) () -> {}, (Runnable) () -> {});
                    Field stopField = threadViewsCls.getDeclaredField("stop");
                    stopField.setAccessible(true);
                    Button stopButton = (Button) stopField.get(threadViews);
                    assertNotNull("Expected stop button on ThreadViews", stopButton);
                    assertStatefulColors("ThreadViews stop text", stopButton.getTextColors(), danger, disabledInk);
                    assertStatefulColors("ThreadViews stop icon", stopButton.getCompoundDrawableTintList(), danger, disabledInk);

                    // 3. PushSettings styledSwitch & SessionSettings toggle (R10.5.4)
                    Class<?> pushSettingsCls = Class.forName("org.llm4people.hermes.push.PushSettings");
                    Constructor<?> psCtor = pushSettingsCls.getDeclaredConstructor(
                            Activity.class, Account.class, org.llm4people.hermes.push.DeliveryRouter.Actions.class);
                    psCtor.setAccessible(true);
                    Object pushSettings = psCtor.newInstance(activity, null, null);
                    Method styledSwitch = pushSettingsCls.getDeclaredMethod("styledSwitch", String.class, boolean.class);
                    styledSwitch.setAccessible(true);
                    Switch pushToggle = (Switch) styledSwitch.invoke(pushSettings, "Quiet hours", true);
                    assertStatefulColors("PushSettings switch", pushToggle.getTextColors(), ink, disabledInk);

                    SessionPanel sessionPanel = new SessionPanel(activity, null, null);
                    Class<?> sessionSettingsCls = Class.forName("org.llm4people.hermes.panel.SessionSettings");
                    Constructor<?> ssCtor = sessionSettingsCls.getDeclaredConstructor(
                            SessionPanel.class, String.class, String.class,
                            BooleanSupplier.class, Consumer.class, Supplier.class);
                    ssCtor.setAccessible(true);
                    Object sessionSettings = ssCtor.newInstance(sessionPanel, "id", "remote",
                            (BooleanSupplier) () -> true, (Consumer<String>) s -> {},
                            (Supplier<?>) () -> activity.getResources().getConfiguration());
                    Method toggleMethod = sessionSettingsCls.getDeclaredMethod(
                            "toggle", LinearLayout.class, String.class, boolean.class, boolean.class);
                    toggleMethod.setAccessible(true);
                    CheckBox sessionToggle = (CheckBox) toggleMethod.invoke(
                            sessionSettings, Ui.column(activity), "Tool", true, false);
                    assertStatefulColors("SessionSettings toggle", sessionToggle.getTextColors(), ink, disabledInk);

                    // 4. MessageText reasoning contentDescription toggle & rounded code action ripple (R10.5.2 & R10.5.5)
                    Class<?> messageTextCls = Class.forName("org.llm4people.hermes.thread.MessageText");
                    Method reasoningCard = messageTextCls.getDeclaredMethod(
                            "reasoningCard", Context.class, String.class, boolean.class, Consumer.class);
                    reasoningCard.setAccessible(true);
                    View reasoning = (View) reasoningCard.invoke(null, activity, "Step 1\nStep 2", false, null);
                    Button reasoningToggle = findFirst(reasoning, Button.class);
                    assertNotNull("Expected reasoning toggle Button", reasoningToggle);
                    assertEquals(activity.getString(R.string.reasoning_show_label),
                            String.valueOf(reasoningToggle.getContentDescription()));
                    reasoningToggle.performClick();
                    assertEquals(activity.getString(R.string.reasoning_hide),
                            String.valueOf(reasoningToggle.getContentDescription()));
                    reasoningToggle.performClick();
                    assertEquals(activity.getString(R.string.reasoning_show_label),
                            String.valueOf(reasoningToggle.getContentDescription()));

                    Class<?> pageCls = Class.forName("org.llm4people.hermes.thread.TranscriptPage");
                    Class<?> cursorCls = Class.forName("org.llm4people.hermes.thread.TranscriptPage$Cursor");
                    Constructor<?> cursorCtor = cursorCls.getDeclaredConstructor();
                    cursorCtor.setAccessible(true);
                    Constructor<?> pageCtor = pageCls.getDeclaredConstructor(
                            String.class, cursorCls, boolean.class, int.class);
                    pageCtor.setAccessible(true);
                    String codeSource = "```java\nint x = 1;\n```";
                    Object page = pageCtor.newInstance(codeSource, cursorCtor.newInstance(), true, 6000);
                    LinearLayout codeHolder = Ui.column(activity);
                    Method addCodeAction = messageTextCls.getDeclaredMethod(
                            "addCodeAction", LinearLayout.class, pageCls, String.class, Consumer.class);
                    addCodeAction.setAccessible(true);
                    addCodeAction.invoke(null, codeHolder, page, codeSource, (Consumer<?>) b -> {});
                    Button codeAction = findFirst(codeHolder, Button.class);
                    assertNotNull("Expected trailing code action button", codeAction);
                    assertRoundedRipple("MessageText code action background", codeAction.getBackground());

                    // 5. NearbyGuide venue row rounded foreground ripple (R10.5.5)
                    JSONObject nearbyJson = new JSONObject()
                            .put("source", "OpenStreetMap")
                            .put("attribution", "© OpenStreetMap contributors")
                            .put("source_date", "2026-01-01T00:00:00Z")
                            .put("category", "cafe")
                            .put("radius_m", 1500)
                            .put("limit", 12)
                            .put("places", new JSONArray().put(new JSONObject()
                                    .put("osm_type", "node")
                                    .put("osm_id", 1)
                                    .put("name", "Cafe Test")
                                    .put("latitude", 45.5)
                                    .put("longitude", -73.5)
                                    .put("distance_m", 120)));
                    NearbyResult nearbyResult = NearbyResult.parse(activity, nearbyJson);
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
                            NearbyGuide.Phase.READY, nearbyResult, 45.5, -73.5, 10.0,
                            new PlaceSelection(nearbyResult)));
                    View venueTop = guide.findViewWithTag("places-row-node/1");
                    assertNotNull("Expected venue row in NearbyGuide", venueTop);
                    assertRoundedRipple("NearbyGuide venue foreground", venueTop.getForeground());

                    // 6. InteractiveControlsBlock disabled preview stateful colors & StateListDrawable (R10.5.3)
                    Object ctrlLayout = parseLayout("""
                            {"version":2,"surface":"page/ctrl-ui","mode":"replace","title":"Controls","blocks":[
                              {"type":"controls","title":"Prefs","controls":[
                                {"id":"sw1","label":"Switch","kind":"toggle","default":true},
                                {"id":"cb1","label":"Check","kind":"checkbox","default":false},
                                {"id":"ch1","label":"Choice","kind":"chips","options":["A","B"],"default":"A"}
                              ]}
                            ]}""");
                    LinearLayout ctrlContainer = Ui.column(activity);
                    ThemePalette palette = ThemePalette.resolve(
                            ThemePalette.OverrideSpec.defaults(), ThemePalette.isNight(activity));
                    Class<?> layoutCls = Class.forName("org.llm4people.hermes.artifact.WorkspaceLayout");
                    Class<?> blockCls = Class.forName("org.llm4people.hermes.artifact.WorkspaceLayout$Block");
                    Method mountControls = InteractiveControlsBlock.class.getDeclaredMethod(
                            "mount", LinearLayout.class, layoutCls, blockCls,
                            ThemePalette.class, InstrumentStateStore.class, String.class,
                            Activity.class, ArtifactDesk.Host.class);
                    mountControls.setAccessible(true);
                    mountControls.invoke(null, ctrlContainer, ctrlLayout, firstBlock(ctrlLayout),
                            palette, null, "test", activity, null);
                    Switch ctrlSwitch = findFirst(ctrlContainer, Switch.class);
                    CheckBox ctrlCheck = findFirst(ctrlContainer, CheckBox.class);
                    List<Button> allCtrlButtons = new ArrayList<>();
                    collect(ctrlContainer, Button.class, allCtrlButtons);
                    Button activeChip = null;
                    Button inactiveChip = null;
                    for (Button b : allCtrlButtons) {
                        if (b.getClass() != Button.class) continue;
                        if ("A".contentEquals(b.getText())) activeChip = b;
                        else if ("B".contentEquals(b.getText())) inactiveChip = b;
                    }
                    assertNotNull(ctrlSwitch);
                    assertNotNull(ctrlCheck);
                    assertNotNull("Expected active chip Button 'A'", activeChip);
                    assertNotNull("Expected inactive chip Button 'B'", inactiveChip);
                    assertStatefulColors("InteractiveControls Switch", ctrlSwitch.getTextColors(), palette.ink, palette.muted);
                    assertStatefulColors("InteractiveControls CheckBox", ctrlCheck.getTextColors(), palette.ink, palette.muted);
                    assertStatefulColors("InteractiveControls active Chip", activeChip.getTextColors(), palette.onAccent, palette.muted);
                    assertStatefulColors("InteractiveControls inactive Chip", inactiveChip.getTextColors(), palette.ink, palette.muted);
                    assertRoundedRipple("InteractiveControls active Chip ripple", activeChip.getBackground());
                    RippleDrawable chipRipple = (RippleDrawable) activeChip.getBackground();
                    assertTrue("Chip ripple content should be StateListDrawable",
                            chipRipple.getDrawable(0) instanceof StateListDrawable);

                    // 7. DataTableBlock non-sortable headers are TextViews; sortable headers are stateful Buttons (R10.5.3)
                    List<DataTableBlock.Column> cols = Arrays.asList(
                            new DataTableBlock.Column("name", "Name", false),
                            new DataTableBlock.Column("val", "Value", true));
                    List<List<String>> rows = Collections.singletonList(Arrays.asList("Alpha", "42"));
                    Method buildTable = DataTableBlock.class.getDeclaredMethod(
                            "build", Context.class, DataTableBlock.Model.class, ThemePalette.class, ArtifactDesk.Host.class);
                    buildTable.setAccessible(true);

                    View nonSortable = (View) buildTable.invoke(
                            null, activity, new DataTableBlock.Model(cols, rows, false, false, ""), palette, null);
                    List<Button> nonSortableButtons = new ArrayList<>();
                    collect(nonSortable, Button.class, nonSortableButtons);
                    assertEquals("Non-sortable DataTableBlock must not render Button headers",
                            0, nonSortableButtons.size());

                    View sortable = (View) buildTable.invoke(
                            null, activity, new DataTableBlock.Model(cols, rows, true, false, ""), palette, null);
                    List<Button> sortableButtons = new ArrayList<>();
                    collect(sortable, Button.class, sortableButtons);
                    assertEquals(2, sortableButtons.size());
                    assertStatefulColors("DataTableBlock sortable header",
                            sortableButtons.get(0).getTextColors(), palette.ink, palette.muted);

                    // 8. PromptButtons.Role.SKIP distinct disabled_ink vs muted enabled (R11.5.1)
                    Class<?> promptButtonsCls = Class.forName("org.llm4people.hermes.thread.PromptButtons");
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    Class<? extends Enum> roleCls = (Class<? extends Enum>) Class.forName(
                            "org.llm4people.hermes.thread.PromptButtons$Role");
                    @SuppressWarnings("unchecked")
                    Object skipRole = Enum.valueOf(roleCls, "SKIP");
                    Method addPromptButton = promptButtonsCls.getDeclaredMethod(
                            "add", LinearLayout.class, CharSequence.class, Runnable.class, roleCls);
                    addPromptButton.setAccessible(true);
                    Button skipButton = (Button) addPromptButton.invoke(
                            null, Ui.column(activity), "Skip", null, skipRole);
                    int muted = activity.getColor(R.color.muted);
                    assertStatefulColors("PromptButtons SKIP text", skipButton.getTextColors(), muted, disabledInk);
                    assertStatefulColors("PromptButtons SKIP icon", skipButton.getCompoundDrawableTintList(), muted, disabledInk);

                    // 9. InstrumentVersions tabs use Ui.roundedRipple with non-null rounded mask (R11.5.2)
                    Class<?> versionsCls = Class.forName("org.llm4people.hermes.thread.InstrumentVersions");
                    Constructor<?> versionsCtor = versionsCls.getDeclaredConstructor(Context.class, Consumer.class);
                    versionsCtor.setAccessible(true);
                    ViewGroup versions = (ViewGroup) versionsCtor.newInstance(activity, (Consumer<?>) s -> {});
                    assertEquals(3, versions.getChildCount());
                    for (int i = 0; i < versions.getChildCount(); i++) {
                        RadioButton tab = (RadioButton) versions.getChildAt(i);
                        assertRoundedRipple("InstrumentVersions tab " + i, tab.getBackground());
                    }

                    // 10. SkillStudio label-less Switch has >= 48dp touch target width & height (R11.5.3)
                    Class<?> skillStudioCls = Class.forName("org.llm4people.hermes.panel.SkillStudio");
                    Constructor<?> studioCtor = skillStudioCls.getDeclaredConstructor(
                            SessionPanel.class, BooleanSupplier.class, Consumer.class, Runnable.class);
                    studioCtor.setAccessible(true);
                    Object studio = studioCtor.newInstance(
                            sessionPanel, (BooleanSupplier) () -> true, (Consumer<String>) s -> {}, null);
                    Field supportedField = skillStudioCls.getDeclaredField("studioSupported");
                    supportedField.setAccessible(true);
                    supportedField.setBoolean(studio, true);
                    Class<?> skillInvCls = Class.forName("org.llm4people.hermes.panel.SkillInventory");
                    Method parseSkills = skillInvCls.getDeclaredMethod("parse", JSONObject.class);
                    parseSkills.setAccessible(true);
                    List<?> parsedSkills = (List<?>) parseSkills.invoke(null, new JSONObject().put("data",
                            new JSONArray().put(new JSONObject()
                                    .put("name", "demo-skill")
                                    .put("description", "Demo")
                                    .put("category", "general")
                                    .put("enabled", true)
                                    .put("writable", true))));
                    Method skillRow = skillStudioCls.getDeclaredMethod("skillRow", skillInvCls);
                    skillRow.setAccessible(true);
                    View rowView = (View) skillRow.invoke(studio, parsedSkills.get(0));
                    Switch skillToggle = findFirst(rowView, Switch.class);
                    assertNotNull("Expected Switch inside SkillStudio skillRow", skillToggle);
                    int min48 = Ui.dp(activity, 48);
                    assertTrue("SkillStudio Switch minWidth >= 48dp", skillToggle.getMinWidth() >= min48);
                    assertTrue("SkillStudio Switch minimumWidth >= 48dp", skillToggle.getMinimumWidth() >= min48);
                    assertTrue("SkillStudio Switch minHeight >= 48dp", skillToggle.getMinHeight() >= min48);
                    assertTrue("SkillStudio Switch minimumHeight >= 48dp", skillToggle.getMinimumHeight() >= min48);

                    // 11. LiveUiPatchBar.suggestedActionsStrip heading keeps WCAG AA contrast under opposite-polarity palette (R12.5.1)
                    ThemePalette oppositePalette = ThemePalette.resolve(
                            ThemePalette.OverrideSpec.defaults(), !ThemePalette.isNight(activity));
                    LiveUiPatch.SuggestedAction suggestion = new LiveUiPatch.SuggestedAction("", "Summarize", "prompt", "Summarize this.", null, null, null);
                    ViewGroup strip = (ViewGroup) LiveUiPatchBar.suggestedActionsStrip(
                            activity, Collections.singletonList(suggestion), oppositePalette,
                            s -> {}, s -> {}, s -> {}, null);
                    TextView stripHeading = (TextView) strip.getChildAt(0);
                    int hostPaper = activity.getColor(R.color.paper);
                    int hostMuted = activity.getColor(R.color.muted);
                    assertEquals("Suggested actions heading must use host R.color.muted over transparent container",
                            hostMuted, stripHeading.getCurrentTextColor());
                    assertTrue("Suggested actions heading must maintain >= 4.5:1 WCAG AA contrast on host paper",
                            ThemePalette.contrastRatio(stripHeading.getCurrentTextColor(), hostPaper) >= 4.5d);
                } catch (Exception err) {
                    throw new AssertionError(err);
                }
            });
        } finally {
            try {
                runTestOnUiThread(() -> {
                    VoiceDrafts v = voiceRef.getAndSet(null);
                    if (v != null) v.cancel();
                });
            } finally {
                ActivityTestLifecycle.finishAndAwaitDestroyed(this, activity);
            }
        }
    }

    private static Context localized(Context base, Locale locale) {
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.setLocale(locale);
        return base.createConfigurationContext(config);
    }

    private static Object parseLayout(String json) throws Exception {
        Class<?> layoutCls = Class.forName("org.llm4people.hermes.artifact.WorkspaceLayout");
        Method parse = layoutCls.getDeclaredMethod("parse", String.class);
        parse.setAccessible(true);
        return parse.invoke(null, json);
    }

    private static Object firstBlock(Object layout) throws Exception {
        Field blocksField = layout.getClass().getDeclaredField("blocks");
        blocksField.setAccessible(true);
        return ((List<?>) blocksField.get(layout)).get(0);
    }

    private static List<?> invokeInstrumentChangesBetween(Context c, Object a, Object b) throws Exception {
        Class<?> layoutCls = Class.forName("org.llm4people.hermes.artifact.WorkspaceLayout");
        Class<?> cls = Class.forName("org.llm4people.hermes.artifact.InstrumentChanges");
        Method m = cls.getDeclaredMethod("between", Context.class, layoutCls, layoutCls);
        m.setAccessible(true);
        return (List<?>) m.invoke(null, c, a, b);
    }

    private static String invokeInstrumentChangesDescribe(Context c, Object block) throws Exception {
        Class<?> blockCls = Class.forName("org.llm4people.hermes.artifact.WorkspaceLayout$Block");
        Class<?> cls = Class.forName("org.llm4people.hermes.artifact.InstrumentChanges");
        Method m = cls.getDeclaredMethod("describe", Context.class, blockCls);
        m.setAccessible(true);
        return (String) m.invoke(null, c, block);
    }

    private static Object findChangeByLabel(List<?> changes, String expectedLabel) throws Exception {
        for (Object change : changes) {
            if (expectedLabel.equals(recordAccessor(change, "label"))) return change;
        }
        return null;
    }

    private static Object recordAccessor(Object target, String name) throws Exception {
        Method m = target.getClass().getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(target);
    }

    private static void assertStatefulColors(String label, ColorStateList csl, int expectedEnabled, int expectedDisabled) {
        assertNotNull(label + " must have non-null ColorStateList", csl);
        assertTrue(label + " must be stateful", csl.isStateful());
        assertEquals(label + " enabled color", expectedEnabled, csl.getColorForState(STATE_ENABLED, 0));
        assertEquals(label + " disabled color", expectedDisabled, csl.getColorForState(STATE_DISABLED, 0));
    }

    private static void assertRoundedRipple(String label, Drawable drawable) {
        assertTrue(label + " must be RippleDrawable", drawable instanceof RippleDrawable);
        RippleDrawable ripple = (RippleDrawable) drawable;
        Drawable mask = ripple.findDrawableByLayerId(android.R.id.mask);
        assertTrue(label + " ripple mask must be rounded GradientDrawable", mask instanceof GradientDrawable);
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
