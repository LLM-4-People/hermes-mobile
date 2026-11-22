package org.llm4people.hermes;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.security.NetworkSecurityPolicy;
import android.test.InstrumentationTestCase;

@SuppressWarnings("deprecation")
public final class PackagePolicyTest extends InstrumentationTestCase {
    public void testLauncherAndPrivateBackupPolicy() {
        Context context = getInstrumentation().getTargetContext();
        Intent launcher = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        assertNotNull(launcher);
        assertEquals(context.getPackageName() + ".MainActivity", launcher.getComponent().getClassName());
        assertEquals(0, context.getApplicationInfo().flags & ApplicationInfo.FLAG_ALLOW_BACKUP);
    }

    public void testDebugOnlyAllowsCleartextOnLoopback() {
        NetworkSecurityPolicy policy = NetworkSecurityPolicy.getInstance();
        assertTrue(policy.isCleartextTrafficPermitted("127.0.0.1"));
        assertTrue(policy.isCleartextTrafficPermitted("::1"));
        assertTrue(policy.isCleartextTrafficPermitted("[::1]"));
        assertFalse(policy.isCleartextTrafficPermitted("example.org"));
        assertFalse(policy.isCleartextTrafficPermitted("192.168.1.1"));
        assertFalse(policy.isCleartextTrafficPermitted("localhost.example.org"));
    }
}
