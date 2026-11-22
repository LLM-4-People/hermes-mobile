package org.llm4people.hermes.push;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.test.InstrumentationTestCase;

import org.unifiedpush.android.connector.UnifiedPush;

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
        } finally {
            setFixture(oldSetting);
            super.tearDown();
        }
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

    public void testUnprovenOrChangedSignerReceivesNoOldWireTokenEvenWhenRemoved() throws Exception {
        for (String pin : new String[]{"different-certificate", null}) {
            PushStore.Device device = prepare();
            String token = register(device);
            store.getWritableDatabase().execSQL("UPDATE devices SET signer=? WHERE installation=?",
                    new Object[]{pin, device.installation});
            outgoing.clear();
            DistributorEnrollment.reconcile(context, true, null);
            assertEquals(PushStore.REMOVING, store.forProfile(device.profile).state);
            assertTrue(outgoing.stream().noneMatch(intent -> token.equals(intent.getStringExtra("token"))));
        }
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
