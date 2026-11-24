package org.llm4people.hermes.thread;

import android.content.Context;

import org.llm4people.hermes.AppLanguage;

import java.util.Locale;
import java.util.function.IntFunction;

/**
 * Package-local bridge allowing thread model helpers invoked from Context-free call sites
 * to resolve localized R.string resources without hardcoded UI literals.
 */
public final class ThreadStrings {
    private static volatile Context appContext;
    private static volatile IntFunction<String> testResolver;

    private ThreadStrings() {}

    public static void attach(Context context) {
        if (context != null) {
            Context app = context.getApplicationContext();
            appContext = app != null ? app : context;
        }
    }

    static void setResolverForTest(IntFunction<String> resolver) {
        testResolver = resolver;
    }

    static String get(int resId, Object... args) {
        return get(null, resId, args);
    }

    static String get(Context context, int resId, Object... args) {
        Context base = context != null ? context : appContext;
        if (base != null) {
            Context localized = AppLanguage.wrapContext(base);
            return args.length == 0 ? localized.getString(resId) : localized.getString(resId, args);
        }
        IntFunction<String> resolver = testResolver;
        if (resolver != null) {
            String pattern = resolver.apply(resId);
            if (pattern == null) return "";
            return args.length == 0 ? pattern : String.format(Locale.ROOT, pattern, args);
        }
        return "";
    }
}
