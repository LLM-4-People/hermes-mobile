package org.llm4people.hermes.push;

import android.app.Application;

public final class PushApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        PushConnection.restore(this);
    }
}
