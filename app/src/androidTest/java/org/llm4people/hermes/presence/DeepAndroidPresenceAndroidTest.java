package org.llm4people.hermes.presence;

import android.content.Context;
import android.test.InstrumentationTestCase;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.artifact.LiveUiPatch;
import org.llm4people.hermes.customization.AppCustomization;
import org.llm4people.hermes.customization.AppCustomizationStore;
import org.llm4people.hermes.customization.InteractiveCardView;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@SuppressWarnings("deprecation")
public final class DeepAndroidPresenceAndroidTest extends InstrumentationTestCase {
    private static final class MemoryBacking implements AppCustomizationStore.Backing {
        String stored;
        @Override public String read() { return stored; }
        @Override public void write(String value) { stored = value; }
        @Override public void remove() { stored = null; }
    }

    private Context themedContext() {
        return new ContextThemeWrapper(getInstrumentation().getTargetContext(), R.style.Theme_Hermes);
    }

    public void testInteractiveCardViewsEnforce48dpTargetsZeroWebViewEditableDraftAndApplyUndo() {
        Context ctx = themedContext();
        int minTouchPx = Ui.dp(ctx, 48);

        // 1. Checklist + Quick Picks (Editable Draft Rule) + Toggles
        AtomicReference<String> stagedDraft = new AtomicReference<>();
        AtomicBoolean checklistFlipped = new AtomicBoolean();
        AtomicBoolean toggleFlipped = new AtomicBoolean();

        InteractiveCardView.Callbacks callbacks = new InteractiveCardView.Callbacks() {
            @Override public void onChecklistChanged(String cardId, String itemId, boolean checked) {
                checklistFlipped.set(checked);
            }
            @Override public void onQuickPickDraft(String cardId, String choiceId, String draftText) {
                stagedDraft.set(draftText);
            }
            @Override public void onToggleChanged(String cardId, String toggleId, boolean checked) {
                toggleFlipped.set(checked);
            }
        };

        InteractiveCardView checklistCard = new InteractiveCardView(ctx,
                InteractiveCardView.Spec.checklist("chk1", "Liste de courses", "À vérifier",
                        List.of(new InteractiveCardView.ChecklistItem("i1", "Pain frais", false))),
                callbacks);
        assertNoWebViewAndMinTouch(checklistCard, minTouchPx);
        CheckBox box = checklistCard.findViewWithTag("checklist:i1");
        assertNotNull(box);
        box.performClick();
        assertTrue(checklistFlipped.get());

        InteractiveCardView picksCard = new InteractiveCardView(ctx,
                InteractiveCardView.Spec.quickPicks("qp1", "Prochaine étape", "",
                        List.of(new InteractiveCardView.QuickPick("p1", "Planifier", "Prépare mon planning"))),
                callbacks);
        assertNoWebViewAndMinTouch(picksCard, minTouchPx);
        Button pickBtn = picksCard.findViewWithTag("pick:p1");
        assertNotNull(pickBtn);
        pickBtn.performClick();
        assertEquals("Prépare mon planning", stagedDraft.get());

        InteractiveCardView togglesCard = new InteractiveCardView(ctx,
                InteractiveCardView.Spec.toggles("tg1", "Options", "",
                        List.of(new InteractiveCardView.ToggleItem("t1", "Mode concis", "Réponses plus courtes", false))),
                callbacks);
        assertNoWebViewAndMinTouch(togglesCard, minTouchPx);
        Switch sw = togglesCard.findViewWithTag("toggle:t1");
        assertNotNull(sw);
        sw.performClick();
        assertTrue(toggleFlipped.get());

        // 2. Live Customization Preview Card -> Apply & Undo
        AppCustomizationStore store = new AppCustomizationStore(new MemoryBacking());
        InteractiveCardView.CustomizationDelta proposed = new InteractiveCardView.CustomizationDelta(
                AppCustomization.PalettePreset.TERRACOTTA, null, AppCustomization.Density.SPACIOUS,
                null, null, "Bonjour", null);

        InteractiveCardView previewCard = new InteractiveCardView(ctx,
                InteractiveCardView.Spec.customizationPreview("prev1", "Nouvel habillage", "Aperçu instantané", proposed),
                new InteractiveCardView.Callbacks() {
                    @Override public void onApplyCustomization(String cardId, AppCustomization next) {
                        store.apply(next);
                    }
                    @Override public void onUndoCustomization(String cardId) {
                        store.undo();
                    }
                });
        assertNoWebViewAndMinTouch(previewCard, minTouchPx);
        Button applyBtn = previewCard.findViewWithTag("card:apply");
        Button undoBtn = previewCard.findViewWithTag("card:undo");
        assertNotNull(applyBtn);
        assertNotNull(undoBtn);
        applyBtn.performClick();
        assertEquals(AppCustomization.PalettePreset.TERRACOTTA, store.current().palette);
        undoBtn.performClick();
        assertEquals(AppCustomization.PalettePreset.FOREST, store.current().palette);
    }

    public void testPresenceSettingsPanelDefaultsAllOffAndOneTapTurnEverythingOff() {
        Context ctx = themedContext();
        int minTouchPx = Ui.dp(ctx, 48);
        ContinuousLocationTracker locationTracker = new ContinuousLocationTracker();
        AppCustomizationStore customStore = new AppCustomizationStore(new MemoryBacking());
        AtomicReference<PresencePolicy> saved = new AtomicReference<>(PresencePolicy.defaults());

        PresenceSettingsPanel panel = new PresenceSettingsPanel(
                ctx, PresencePolicy.defaults(), locationTracker, customStore, saved::set, null);
        View root = panel.buildView();
        assertNoWebViewAndMinTouch(root, minTouchPx);

        TextView banner = root.findViewWithTag("presence:status_banner");
        assertNotNull(banner);
        assertEquals(ctx.getString(R.string.presence_status_all_off), banner.getText().toString());

        Switch screenSwitch = root.findViewWithTag("presence:toggle_screen");
        Switch readSwitch = root.findViewWithTag("presence:toggle_read");
        assertNotNull(screenSwitch);
        assertNotNull(readSwitch);
        assertFalse(screenSwitch.isChecked());
        assertFalse(readSwitch.isChecked());

        screenSwitch.setChecked(true);
        readSwitch.setChecked(true);
        assertTrue(saved.get().shareAppPresence);
        assertTrue(saved.get().shareReadWithoutReply);

        // 1-tap Turn Everything Off resets all switches and policy cleanly!
        Button allOff = root.findViewWithTag("presence:turn_all_off");
        assertNotNull(allOff);
        allOff.performClick();
        assertFalse(saved.get().anySharingEnabled());
        assertFalse(screenSwitch.isChecked());
        assertFalse(readSwitch.isChecked());
        assertEquals(ctx.getString(R.string.presence_status_all_off), banner.getText().toString());

        // Appearance preset -> Undo -> Reset works live.
        Button slateBtn = root.findViewWithTag("custom:palette:slate");
        Button undoBtn = root.findViewWithTag("custom:undo");
        assertNotNull(slateBtn);
        assertNotNull(undoBtn);
        slateBtn.performClick();
        assertEquals(AppCustomization.PalettePreset.SLATE, customStore.current().palette);
        undoBtn.performClick();
        assertEquals(AppCustomization.PalettePreset.FOREST, customStore.current().palette);
    }

    public void testAppPresenceTrackerProducesValidAndroidJsonObject() {
        AppPresenceTracker tracker = new AppPresenceTracker();
        long epoch = tracker.bindProfile("p1", 7L);
        tracker.updateScreen(epoch, "atelier", "sess-77");
        tracker.updateActiveRunCount(epoch, 1);
        tracker.recordAssistantMessageDelivered(epoch, "sess-77", "m1");
        tracker.markAssistantMessageRead(epoch, "sess-77", "m1", 10_000L);

        PresencePolicy policy = PresencePolicy.defaults()
                .withAppPresence(true)
                .withReadWithoutReply(true)
                .withReadGraceSec(10);
        JSONObject obj = tracker.toWireContext(policy, null, 25_000L);
        assertNotNull(obj);
        assertEquals(1, obj.optInt("schema_version"));
        assertEquals("atelier", obj.optString("current_screen"));
        assertEquals("sess-77", obj.optString("active_session_id"));
        assertEquals(1, obj.optInt("active_run_count"));
        JSONObject rwr = obj.optJSONObject("read_without_reply");
        assertNotNull(rwr);
        assertTrue(rwr.optBoolean("in_app_unread_reply"));
    }

    public void testLiveUiPatchParsesCanonicalTrack9aInteractiveCards() throws Exception {
        JSONObject raw = new JSONObject()
                .put("revision_id", "rev-cards-1")
                .put("scope", "thread")
                .put("interactive_cards", new JSONArray()
                        .put(new JSONObject()
                                .put("id", "c_pick")
                                .put("kind", "quick_pick")
                                .put("title", "Suite")
                                .put("items", new JSONArray().put(new JSONObject()
                                        .put("id", "i1")
                                        .put("label", "Résumer")
                                        .put("draft", "Résume en 3 puces"))))
                        .put(new JSONObject()
                                .put("id", "c_prev")
                                .put("kind", "customization_preview")
                                .put("title", "Habillage")
                                .put("customization", new JSONObject()
                                        .put("accent", "indigo")
                                        .put("density", "spacious")
                                        .put("font_scale_step", 1)
                                        .put("greeting", "Bonjour"))));
        LiveUiPatch patch = LiveUiPatch.parse(raw, false);
        assertEquals(2, patch.interactiveCards.size());
        assertEquals("c_pick", patch.interactiveCards.get(0).cardId);
        assertEquals("Résume en 3 puces", patch.interactiveCards.get(0).quickPicks.get(0).draftText());
        assertEquals(AppCustomization.PalettePreset.INDIGO,
                patch.interactiveCards.get(1).proposedCustomization.palette);
    }

    private static void assertNoWebViewAndMinTouch(View root, int minTouchPx) {
        assertFalse("Interactive surfaces must be 100% native (zero WebView)", root instanceof WebView);
        if (root instanceof Button || root instanceof CheckBox || root instanceof Switch) {
            assertTrue("Interactive control minHeight must be >= 48dp: " + root,
                    root.getMinimumHeight() >= minTouchPx);
        }
        if (root instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                assertNoWebViewAndMinTouch(group.getChildAt(i), minTouchPx);
            }
        }
    }
}
