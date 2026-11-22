package org.llm4people.hermes.push;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Disabled except while DistributorLifecycleTest verifies package discovery. */
public final class DistributorFixture extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {}
}
