package org.llm4people.hermes.push;

import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.test.InstrumentationTestCase;

import org.unifiedpush.android.connector.FailedReason;
import org.unifiedpush.android.connector.UnifiedPush;

import java.io.IOException;
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
        fixture = new ComponentName(getInstrumentation().getContext().getPackageName(), DistributorFixture.class.getName());
        oldSetting = context.getPackageManager().getComponentEnabledSetting(fixture);
        setFixture(PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
        store = PushStore.get(context);
    }

    private void setFixture(int state) throws IOException {
        String command = switch (state) {
            case PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> "enable";
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> "disable";
            default -> "default-state";
        };
        // The component belongs to the instrumentation APK. The shell command also works on API 28.
        try (var result = new ParcelFileDescriptor.AutoCloseInputStream(getInstrumentation().getUiAutomation()
                .executeShellCommand("pm " + command + " " + fixture.flattenToString()))) {
            while (result.read() != -1) { /* Wait for the package manager operation to finish. */ }
        }
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
        PushStore.Device device = store.prepareUnified(UUID.randomUUID().toString(), SERVER, fixture.getPackageName(), VAPID);
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
        assertTrue(store.registrationFailed(owner, FailedReason.NETWORK, 1L));
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

    public void testClearingDistributorDataRevokesThePackageEvenWhenStillInstalled() throws Exception {
        PushStore.Device first = prepare();
        PushStore.Device second = prepare();
        register(first);
        register(second);
        DistributorPackageReceiver receiver = new DistributorPackageReceiver();
        Intent cleared = new Intent(Intent.ACTION_PACKAGE_DATA_CLEARED, Uri.fromParts("package", SUCCESSOR, null));
        receiver.onReceive(context, cleared);
        assertEquals(PushStore.PENDING, store.forProfile(first.profile).state);
        cleared.setData(Uri.fromParts("package", fixture.getPackageName(), null));
        receiver.onReceive(context, cleared);
        assertEquals(PushStore.REMOVING, store.forProfile(first.profile).state);
        assertEquals(PushStore.REMOVING, store.forProfile(second.profile).state);
    }

    public void testMissingPackageReleasesAllAffectedProfilesAndPicker() throws Exception {
        for (int i = 0; i < 2; i++) {
            PushStore.Device device = prepare();
            register(device);
            store.updateSubscription(device, "{}");
            store.enable(store.forProfile(device.profile));
        }
        setFixture(PackageManager.COMPONENT_ENABLED_STATE_DISABLED);
        DistributorEnrollment.reconcile(context);
        for (PushStore.Device device : devices) {
            assertEquals(PushStore.REMOVING, store.forProfile(device.profile).state);
            assertFalse(MobilePush.isEnabled(context, device.profile));
        }
        setFixture(PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
        assertTrue(DistributorEnrollment.choices(context).contains(fixture.getPackageName()));
    }
}
