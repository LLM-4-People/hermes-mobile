package org.llm4people.hermes.os;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.test.InstrumentationTestCase;
import android.widget.RemoteViews;

import org.llm4people.hermes.AppLanguage;
import org.llm4people.hermes.R;

import java.util.List;
import java.util.UUID;

@SuppressWarnings("deprecation")
public final class Android16OsIntegrationTest extends InstrumentationTestCase {
    public void testManifestDeclaresTileWidgetAndPrivateLiveRunReceiver() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        PackageManager pm = context.getPackageManager();

        ServiceInfo tile = pm.getServiceInfo(new ComponentName(context, HermesTileService.class), 0);
        assertEquals("android.permission.BIND_QUICK_SETTINGS_TILE", tile.permission);
        assertTrue(tile.exported);

        ActivityInfo widget = pm.getReceiverInfo(new ComponentName(context, HermesWidgetProvider.class), 0);
        assertTrue(widget.exported);

        ActivityInfo liveStop = pm.getReceiverInfo(new ComponentName(context, LiveRunActionReceiver.class), 0);
        assertFalse("LiveRunActionReceiver must not be exported", liveStop.exported);
    }

    public void testLockGracePolicyPersistsPerProfileAndClearsOnForget() {
        Context context = getInstrumentation().getTargetContext();
        String profileA = "test-grace-" + UUID.randomUUID();
        String profileB = "test-grace-" + UUID.randomUUID();
        try {
            assertEquals(LockGracePolicy.Window.IMMEDIATE, LockGracePolicy.window(context, profileA));
            LockGracePolicy.setWindow(context, profileA, LockGracePolicy.Window.MINUTES_2);
            assertEquals(LockGracePolicy.Window.MINUTES_2, LockGracePolicy.window(context, profileA));
            assertEquals(LockGracePolicy.Window.IMMEDIATE, LockGracePolicy.window(context, profileB));

            LockGracePolicy.forget(context, profileA);
            assertEquals(LockGracePolicy.Window.IMMEDIATE, LockGracePolicy.window(context, profileA));
        } finally {
            LockGracePolicy.forget(context, profileA);
            LockGracePolicy.forget(context, profileB);
        }
    }

    public void testLiveRunNotificationEnforcesPrivateOngoingAndAndroid16ProgressStyle() {
        Context context = getInstrumentation().getTargetContext();
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        LiveRunNotification.Snapshot snapshot = new LiveRunNotification.Snapshot(
                LiveRunNotification.Phase.TOOL_RUNNING, 2, 4, false, true);
        LiveRunNotification.Presentation model = LiveRunNotification.present(context, snapshot);
        assertNotNull(model);

        Notification built = LiveRunNotification.build(context, manager, snapshot, model);
        assertEquals(Notification.VISIBILITY_PRIVATE, built.visibility);
        assertEquals(Notification.CATEGORY_PROGRESS, built.category);
        assertTrue((built.flags & Notification.FLAG_ONGOING_EVENT) != 0);
        assertNotNull(built.actions);
        assertEquals(2, built.actions.length);
        Context localized = AppLanguage.wrapContext(context);
        assertEquals(localized.getString(R.string.live_run_working_title), built.extras.getString(Notification.EXTRA_TITLE));
        if (Build.VERSION.SDK_INT >= 36) {
            assertEquals(localized.getString(R.string.live_run_chip_step_of, 2, 4), built.getShortCriticalText());
        }
    }

    public void testShortcutsPublishAndDynamicWidgetPinningGuardsPrivacy() {
        Context context = getInstrumentation().getTargetContext();
        HermesShortcuts.publish(context);

        HermesWidgetSpec unsafe = new HermesWidgetSpec(
                "unsafe_test", "Private Secret", "Do not leak",
                HermesWidgetSpec.Template.STATUS, List.of(), null, false);
        assertEquals(HermesWidgetPinning.PinResult.INVALID_SPEC,
                HermesWidgetPinning.requestPinWidget(context, unsafe));

        RemoteViews dynamicViews = HermesWidgetProvider.buildDynamicViews(context, unsafe);
        assertNotNull(dynamicViews);

        HermesWidgetStore store = new HermesWidgetStore(context);
        String id = "test_widget_" + Math.abs(UUID.randomUUID().hashCode());
        try {
            HermesWidgetSpec safe = new HermesWidgetSpec(
                    id, "Tableau Hermes", "Prêt",
                    HermesWidgetSpec.Template.QUICK_ACTIONS,
                    List.of(new HermesWidgetSpec.Item("Nouvelle note", "", "Crée une note")),
                    "#225C50", true);
            RemoteViews colored = HermesWidgetProvider.buildDynamicViews(context, safe);
            assertNotNull(colored);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                android.content.res.Configuration dayCfg = new android.content.res.Configuration(
                        context.getResources().getConfiguration());
                dayCfg.uiMode = (dayCfg.uiMode & ~android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                        | android.content.res.Configuration.UI_MODE_NIGHT_NO;
                Context dayCtx = context.createConfigurationContext(dayCfg);
                android.view.View dayView = colored.apply(dayCtx, new android.widget.FrameLayout(dayCtx));
                android.widget.TextView dayBadge = dayView.findViewById(R.id.widget_dynamic_badge);
                assertNotNull(dayBadge);
                assertEquals(
                        org.llm4people.hermes.artifact.ThemePalette.adaptAccentForMode(0xFF225C50, false),
                        dayBadge.getCurrentTextColor());

                android.content.res.Configuration nightCfg = new android.content.res.Configuration(
                        context.getResources().getConfiguration());
                nightCfg.uiMode = (nightCfg.uiMode & ~android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                        | android.content.res.Configuration.UI_MODE_NIGHT_YES;
                Context nightCtx = context.createConfigurationContext(nightCfg);
                android.view.View nightView = colored.apply(nightCtx, new android.widget.FrameLayout(nightCtx));
                android.widget.TextView nightBadge = nightView.findViewById(R.id.widget_dynamic_badge);
                assertNotNull(nightBadge);
                assertEquals(
                        org.llm4people.hermes.artifact.ThemePalette.adaptAccentForMode(0xFF225C50, true),
                        nightBadge.getCurrentTextColor());
            }
            HermesWidgetSpec fromJson = HermesWidgetSpec.fromJson(safe.toJson());
            assertEquals("Tableau Hermes", fromJson.title);
            assertEquals(HermesWidgetSpec.Template.QUICK_ACTIONS, fromJson.template);
            assertEquals("Crée une note", fromJson.items.get(0).actionDraft());
            store.upsertSpec(safe, "profile-a");
            store.bindInstance(987654, id);
            assertEquals("Tableau Hermes", store.specForInstance(987654).title);
            assertEquals("Crée une note", store.draftForAction(id, 0, "profile-a"));
            assertNull(store.draftForAction(id, 0, "profile-b"));

            // Cross-feature delivery widget bridge enforces home_screen_safe: true and persists into HermesWidgetStore.
            org.json.JSONObject validDelivery = new org.json.JSONObject()
                    .put("widget", safe.toJson());
            assertTrue(org.llm4people.hermes.artifact.HomePinBridge.applyDeliveryWidgetUpdate(context, validDelivery, "profile-a"));
            assertEquals("Tableau Hermes", store.spec(id).title);

            org.json.JSONObject unsafeDelivery = new org.json.JSONObject()
                    .put("widget_id", id)
                    .put("title", "Secret")
                    .put("template", "status")
                    .put("home_screen_safe", false);
            assertFalse(org.llm4people.hermes.artifact.HomePinBridge.applyDeliveryWidgetUpdate(context, unsafeDelivery, "profile-a"));
            assertNull(org.llm4people.hermes.artifact.HomePinBridge.parseDeliveryWidgetSpec(unsafeDelivery));
        } catch (org.json.JSONException unexpected) {
            throw new AssertionError(unexpected);
        } finally {
            store.removeSpec(id);
            store.unbindInstance(987654);
        }
    }
}
