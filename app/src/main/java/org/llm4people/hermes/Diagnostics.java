package org.llm4people.hermes;

import android.util.Log;

/** Records operation names only. Never pass credentials, URLs, IDs or content. */
public final class Diagnostics {
    private static final String TAG = "HermesMobile";
    private Diagnostics() {}

    public static void event(String category, String action) {
        Log.i(TAG, category + "." + action);
    }

    public static void warning(String category, String action) {
        Log.w(TAG, category + "." + action);
    }

    public static void error(String category, String action) {
        Log.e(TAG, category + "." + action);
    }
}
