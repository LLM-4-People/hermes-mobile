package org.llm4people.hermes.push;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import org.llm4people.hermes.Diagnostics;

/** A fresh installation of the same package has lost its distributor registrations. */
public final class DistributorPackageReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        try {
            String action = intent.getAction();
            if (!Intent.ACTION_PACKAGE_FULLY_REMOVED.equals(action) && !Intent.ACTION_PACKAGE_DATA_CLEARED.equals(action)) return;
            Uri data = intent.getData();
            if (data == null || !"package".equals(data.getScheme()) || intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            String name = data.getSchemeSpecificPart();
            if (name == null || name.isEmpty()) return;
            if (Intent.ACTION_PACKAGE_DATA_CLEARED.equals(action)) {
                DistributorEnrollment.reconcile(context, true, name);
                return;
            }
            synchronized (PushConnection.class) {
                for (PushStore.Device device : PushStore.get(context).devices()) {
                    if (name.equals(device.distributor) && !PushStore.REMOVING.equals(device.state))
                        MobilePush.forget(context, device.profile);
                }
            }
        } catch (RuntimeException malformed) {
            Diagnostics.warning("push", "package_broadcast_rejected");
        }
    }
}
