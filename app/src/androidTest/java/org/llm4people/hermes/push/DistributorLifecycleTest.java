package org.llm4people.hermes.push;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import org.llm4people.hermes.ActivityTestLifecycle;
import org.llm4people.hermes.R;
import org.unifiedpush.android.connector.UnifiedPush;
import org.unifiedpush.android.connector.FailedReason;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Exercises the real 3.3.6 registration DB, wire tokens and authenticated inbound receiver. */
public final class DistributorLifecycleTest extends InstrumentationTestCase {
    private static final String REGISTER = "org.unifiedpush.android.distributor.REGISTER";
    private static final String CONNECTOR = "org.unifiedpush.android.connector.";
    private static final String SUCCESSOR = "org.example.unchosen.distributor";
    private static final String VAPID = "B" + String.join("", java.util.Collections.nCopies(86, "A"));
    private static final String SERVER = "http://127.0.0.1:1/p/test/";
    private final List<PushStore.Device> devices = new ArrayList<>();
    private final List<Intent> outgoing = new ArrayList<>();
    private ComponentName fixture;
    private int oldSetting;
    private Context context;
    private PushStore store;

    @Override protected void setUp() throws Exception {
        super.setUp();
        Context base = getInstrumentation().getTargetContext();
        context = new ContextWrapper(base) {
            @Override public void sendBroadcast(Intent intent) { outgoing.add(intent); }
            @Override public void sendBroadcast(Intent intent, String permission, Bundle options) { outgoing.add(intent); }
        };
        fixture = new ComponentName(base.getPackageName(), "org.llm4people.hermes.push.DistributorFixture");
        oldSetting = context.getPackageManager().getComponentEnabledSetting(fixture);
        setFixture(PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
        store = PushStore.get(context);
    }

    private void setFixture(int state) {
        // The disabled fixture is in the debug target. Its own UID can toggle it on every supported SDK.
        context.getPackageManager().setComponentEnabledSetting(fixture, state, PackageManager.DONT_KILL_APP);
        assertEquals(state, context.getPackageManager().getComponentEnabledSetting(fixture));
    }

    @Override protected void tearDown() throws Exception {
        try {
            for (PushStore.Device device : devices) {
                store.deactivate(device.profile);
                UnifiedPush.unregister(context, device.installation);
                store.finishRemoval(device);
            }
            cancelBackgroundSyncJob();
        } finally {
            setFixture(oldSetting);
            super.tearDown();
        }
    }

    private void cancelBackgroundSyncJob() throws Exception {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler != null) scheduler.cancel(0x484d5055);
        PushConnection.WORK.submit(() -> {}).get(10, java.util.concurrent.TimeUnit.SECONDS);
    }

    private PushStore.Device prepare() throws Exception {
        PushStore.Device device = store.prepareUnified(UUID.randomUUID().toString(), SERVER, fixture.getPackageName(), DistributorIdentity.current(context, fixture.getPackageName()), VAPID);
        devices.add(device);
        return device;
    }

    private String register(PushStore.Device device) throws Exception {
        outgoing.clear();
        DistributorEnrollment.sync(context, device);
        for (Intent intent : outgoing) {
            if (REGISTER.equals(intent.getAction()) && fixture.getPackageName().equals(intent.getPackage()))
                return intent.getStringExtra("token");
        }
        fail("Missing consented REGISTER");
        return null;
    }

    private void signal(String action, String token, String successor) {
        new DistributorReceiver().process(context, new Intent(CONNECTOR + action)
                .putExtra("token", token).putExtra("newDistributor", successor));
    }

    public void testColdReservationAndFreshTokenAfterRetryWithIndependentOwner() throws Exception {
        PushStore.Device owner = prepare();
        String initial = register(owner); // Server reservation exists; SDK selection was never saved.
        assertNotNull(initial);
        PushStore.Device other = prepare();
        String second = register(other);
        assertFalse(initial.equals(second));
        new DistributorReceiver().process(context, new Intent(CONNECTOR + "REGISTRATION_FAILED")
                .putExtra("token", initial).putExtra("reason", "NETWORK"));
        PushStore.Device waiting = store.forProfile(owner.profile);
        assertTrue(waiting.retryAt > System.currentTimeMillis());
        assertTrue(context.getSystemService(JobScheduler.class).getAllPendingJobs().stream().anyMatch(job ->
                job.isPersisted() && job.getNetworkType() == JobInfo.NETWORK_TYPE_ANY
                        && job.getExtras().getLong("due") == waiting.retryAt));
        // Make the stored retry due immediately without sleeping or changing connectivity.
        store.getWritableDatabase().execSQL("UPDATE devices SET retry_at=1 WHERE installation=?", new Object[]{owner.installation});
        String renewed = register(store.forInstallation(owner.installation));
        assertFalse(initial.equals(renewed));
        assertEquals(second, register(other));
        signal("UNREGISTERED", initial, SUCCESSOR);
        assertEquals(PushStore.PENDING, store.forProfile(owner.profile).state);
        assertEquals(owner.managementKey(), store.forProfile(owner.profile).managementKey());
    }

    public void testTemporaryAndPermanentSuccessorsRequireFreshConsent() throws Exception {
        PushStore.Device first = prepare();
        String token = register(first);
        PushStore.Device second = prepare();
        String otherToken = register(second);
        outgoing.clear();
        signal("TEMP_UNAVAILABLE", token, SUCCESSOR);
        signal("UNREGISTERED", "invalid", SUCCESSOR);
        assertEquals(fixture.getPackageName(), UnifiedPush.getSavedDistributor(context));
        assertEquals(PushStore.PENDING, store.forProfile(first.profile).state);
        signal("UNREGISTERED", token, SUCCESSOR);
        assertEquals(PushStore.REMOVING, store.forProfile(first.profile).state);
        assertTrue(outgoing.stream().noneMatch(intent -> SUCCESSOR.equals(intent.getPackage())));
        assertEquals(otherToken, register(second));
    }

    public void testResetAndVisibleEntryRepairBothAccountsWithTheirOriginalKeys() throws Exception {
        PushStore.Device first = prepare(), second = prepare();
        String a = register(first), b = register(second);
        for (PushStore.Device device : devices) {
            store.updateSubscription(device, "{}");
            store.enable(store.forProfile(device.profile));
        }
        Intent cleared = new Intent(Intent.ACTION_PACKAGE_DATA_CLEARED, Uri.fromParts("package", fixture.getPackageName(), null));
        for (boolean missedBroadcast : new boolean[]{false, true}) {
            outgoing.clear();
            if (missedBroadcast) DistributorEnrollment.reconcile(context, true, null);
            else new DistributorPackageReceiver().onReceive(context, cleared);
            assertEquals(2, outgoing.stream().filter(intent -> REGISTER.equals(intent.getAction())).count());
            for (String token : new String[]{a, b})
                assertTrue(outgoing.stream().anyMatch(intent -> token.equals(intent.getStringExtra("token"))));
            for (PushStore.Device device : devices) {
                PushStore.Device recovered = store.forProfile(device.profile);
                assertEquals(PushStore.ACTIVE, recovered.state);
                assertEquals(device.managementKey(), recovered.managementKey());
                assertFalse(store.updateSubscription(recovered, "{}"));
            }
        }
        outgoing.clear();
        DistributorEnrollment.reconcile(context);
        assertTrue(outgoing.isEmpty());
    }

    public void testFailedActiveRepairRetriesOnlyItsBindingAndIgnoresFailureAfterEndpoint() throws Exception {
        PushStore.Device first = prepare(), second = prepare();
        String token = register(first);
        register(second);
        for (PushStore.Device device : devices) {
            store.updateSubscription(device, "{}");
            store.enable(store.forProfile(device.profile));
        }
        DistributorEnrollment.reconcile(context, true, null);
        Intent failure = new Intent(CONNECTOR + "REGISTRATION_FAILED").putExtra("token", token).putExtra("reason", "NETWORK");
        new DistributorReceiver().process(context, failure);
        PushStore.Device failed = store.forProfile(first.profile);
        assertTrue(failed.receives());
        assertEquals(PushStore.RETRY_REPAIR, failed.repair);
        assertTrue(failed.retryAt > System.currentTimeMillis());
        assertNull(store.forProfile(second.profile).failure);
        outgoing.clear();
        DistributorEnrollment.reconcile(context, true, null);
        assertTrue(outgoing.isEmpty());
        store.getWritableDatabase().execSQL("UPDATE devices SET retry_at=1 WHERE installation=?", new Object[]{first.installation});
        DistributorEnrollment.retryDue(context);
        assertEquals(1, outgoing.stream().filter(intent -> REGISTER.equals(intent.getAction())).count());
        assertEquals(token, outgoing.get(0).getStringExtra("token"));
        assertFalse(store.updateSubscription(store.forProfile(first.profile), "{}"));
        new DistributorReceiver().process(context, failure);
        PushStore.Device restored = store.forProfile(first.profile);
        assertEquals(PushStore.NO_REPAIR, restored.repair);
        assertEquals(0L, restored.retryAt);
        assertNull(restored.failure);
    }

    public void testResetBeforeFirstBindingResponseFencesTheOldPutAndRetainsOnlyMatchingReceipts() throws Exception {
        for (boolean sameEndpoint : new boolean[]{true, false}) {
            PushStore.Device owner = prepare();
            String token = register(owner);
            assertTrue(store.updateSubscription(owner, "{}"));
            PushStore.Device uploading = store.forProfile(owner.profile);
            String event = UUID.randomUUID().toString().replace("-", "");
            long now = System.currentTimeMillis();
            store.defer(uploading, event, "reminder", now + 60_000, now);
            outgoing.clear();
            new DistributorPackageReceiver().onReceive(context, new Intent(Intent.ACTION_PACKAGE_DATA_CLEARED,
                    Uri.fromParts("package", fixture.getPackageName(), null)));
            assertTrue(outgoing.stream().anyMatch(i -> REGISTER.equals(i.getAction()) && token.equals(i.getStringExtra("token"))));
            PushStore.Device repairing = store.forProfile(owner.profile);
            assertFalse(uploading.subscriptionVersion.equals(repairing.subscriptionVersion));
            store.enable(uploading);
            assertFalse(store.forProfile(owner.profile).receives());
            assertTrue(store.registrationFailed(repairing, org.unifiedpush.android.connector.FailedReason.NETWORK, now));
            try (PushStore reopened = new PushStore(context, "push.db")) {
                assertEquals(PushStore.RETRY_REPAIR, reopened.forProfile(owner.profile).repair);
                assertEquals(repairing.subscriptionVersion, reopened.forProfile(owner.profile).subscriptionVersion);
            }
            assertTrue(store.updateSubscription(store.forProfile(owner.profile), sameEndpoint ? "{}" : "{\"new\":true}"));
            store.enable(uploading); // Even after NEW_ENDPOINT, a pre-reset ACK remains obsolete.
            assertFalse(store.forProfile(owner.profile).receives());
            try (android.database.Cursor staged = store.getReadableDatabase().rawQuery(
                    "SELECT COUNT(*) FROM deferred WHERE installation=? AND event=?", new String[]{owner.installation, event})) {
                assertTrue(staged.moveToFirst());
                assertEquals(sameEndpoint ? 1 : 0, staged.getInt(0));
            }
            PushStore.Device confirmed = store.forProfile(owner.profile);
            store.enable(confirmed);
            assertTrue(store.forProfile(owner.profile).receives());
            store.deactivate(owner.profile);
        }
    }

    public void testUnprovenOrChangedSignerReceivesNoOldWireTokenEvenWhenRemoved() throws Exception {
        for (String pin : new String[]{"different-certificate", null}) {
            cancelBackgroundSyncJob();
            PushStore.Device device = prepare();
            String token = register(device);
            store.getWritableDatabase().execSQL("UPDATE devices SET signer=? WHERE installation=?",
                    new Object[]{pin, device.installation});
            outgoing.clear();
            DistributorEnrollment.reconcile(context, true, null);
            assertEquals(PushStore.REMOVING, store.forProfile(device.profile).state);
            assertTrue(outgoing.stream().noneMatch(intent -> token.equals(intent.getStringExtra("token"))));
            cancelBackgroundSyncJob();
        }
    }

    public void testOpenDistributorRequiresCurrentEligibleInstallationAndSigner() throws Exception {
        PushStore.Device active = prepare();
        store.updateSubscription(active, "{}");
        store.enable(store.forProfile(active.profile));
        active = store.forProfile(active.profile);
        PushStore.Device pending = prepare();
        assertFalse(PushSettings.mayOpenDistributor(context, pending.profile, pending));
        assertTrue(store.registrationFailed(pending, FailedReason.ACTION_REQUIRED, 0));
        PushStore.Device action = store.forProfile(pending.profile);
        assertTrue(PushSettings.mayOpenDistributor(context, action.profile, action));
        assertTrue(PushSettings.mayOpenDistributor(context, active.profile, active));
        assertFalse(PushSettings.mayOpenDistributor(context, active.profile, action));

        store.deactivate(action.profile);
        assertFalse(PushSettings.mayOpenDistributor(context, action.profile, action));
        store.finishRemoval(action);
        assertFalse(PushSettings.mayOpenDistributor(context, action.profile, action));
        PushStore.Device replacement = store.prepareUnified(action.profile, SERVER, action.distributor, action.signer, VAPID);
        devices.add(replacement);
        assertTrue(store.registrationFailed(replacement, FailedReason.ACTION_REQUIRED, 0));
        assertFalse(PushSettings.mayOpenDistributor(context, action.profile, action));
        assertTrue(PushSettings.mayOpenDistributor(context, active.profile, active));

        store.getWritableDatabase().execSQL("UPDATE devices SET signer=? WHERE installation=?",
                new Object[]{"different-certificate", active.installation});
        assertFalse(PushSettings.mayOpenDistributor(context, active.profile, active));
        PushStore.Device untrusted = store.forProfile(active.profile);
        assertFalse(PushSettings.mayOpenDistributor(context, untrusted.profile, untrusted));
    }

    public void testEstablishedRepairAndAndroidPermissionShowTheirOwnActions() throws Throwable {
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        Activity activity = getInstrumentation().startActivitySync(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            PushConnection.WORK.submit(() -> {}).get(10, java.util.concurrent.TimeUnit.SECONDS);
            PushStore.Device enabled = prepare();
            store.updateSubscription(enabled, "{}");
            store.enable(store.forProfile(enabled.profile));
            PushSettings settings = new PushSettings(activity, null);
            for (int repair : new int[]{PushStore.NO_REPAIR, PushStore.WAITING_ENDPOINT, PushStore.RETRY_REPAIR, PushStore.REPAIR_ACTION}) {
                String failure = repair == PushStore.RETRY_REPAIR ? "NETWORK" : repair == PushStore.REPAIR_ACTION ? "ACTION_REQUIRED" : null;
                long retryAt = repair == PushStore.WAITING_ENDPOINT || repair == PushStore.RETRY_REPAIR ? 1000 : 0;
                store.getWritableDatabase().execSQL("UPDATE devices SET repair=?,failure=?,retry_at=? WHERE installation=?",
                        new Object[]{repair, failure, retryAt, enabled.installation});
                PushStore.Device row = store.forProfile(enabled.profile);
                for (boolean allowed : new boolean[]{false, true}) {
                    runTestOnUiThread(() -> {
                        AlertDialog dialog = settings.settingsDialog(row, allowed).show();
                        try {
                            assertEquals(!allowed, hasAction(dialog, activity.getString(R.string.push_settings_android_settings)));
                            assertEquals(repair == PushStore.REPAIR_ACTION, hasAction(dialog, activity.getString(R.string.instrument_retry)));
                            for (String action : new String[]{
                                    activity.getString(R.string.push_settings_disable),
                                    activity.getString(R.string.push_settings_history),
                                    activity.getString(R.string.push_settings_open_distributor)})
                                assertTrue(action, hasAction(dialog, action));
                            TextView message = dialog.findViewById(android.R.id.message);
                            String text = message.getText().toString();
                            assertEquals(!allowed, text.contains(activity.getString(R.string.push_settings_blocked_message)));
                            if (repair != PushStore.NO_REPAIR)
                                assertTrue(text.contains(activity.getString(R.string.push_settings_relay_registered)));
                            assertEquals(repair == PushStore.RETRY_REPAIR,
                                    text.contains(activity.getString(R.string.push_settings_retry_distributor_auto)));
                            assertEquals(repair == PushStore.WAITING_ENDPOINT,
                                    text.contains(activity.getString(R.string.push_settings_waiting_distributor)));
                        } finally { dialog.dismiss(); }
                    });
                }
            }
        } finally { ActivityTestLifecycle.finishAndAwaitDestroyed(this, activity); }
    }

    private static boolean hasAction(AlertDialog dialog, String label) {
        ArrayList<View> views = new ArrayList<>();
        dialog.getWindow().getDecorView().findViewsWithText(views, label, View.FIND_VIEWS_WITH_TEXT);
        for (View view : views)
            if (view instanceof Button button && button.isShown() && label.contentEquals(button.getText()) && button.hasOnClickListeners())
                return true;
        return false;
    }

    public void testMissingPackageReleasesAllAffectedProfilesAndPicker() throws Exception {
        for (int i = 0; i < 2; i++) {
            PushStore.Device device = prepare();
            register(device);
            store.updateSubscription(device, "{}");
            store.enable(store.forProfile(device.profile));
        }
        setFixture(PackageManager.COMPONENT_ENABLED_STATE_DISABLED);
        assertFalse(MobilePush.isEnabled(context, devices.get(0).profile));
        for (PushStore.Device device : devices) {
            assertEquals(PushStore.REMOVING, store.forProfile(device.profile).state);
            assertFalse(MobilePush.isEnabled(context, device.profile));
        }
        setFixture(PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
        assertTrue(DistributorEnrollment.choices(context).contains(fixture.getPackageName()));
    }
}
