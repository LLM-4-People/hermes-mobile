package org.llm4people.hermes.panel;

import android.content.Context;

/**
 * Pure-Java validation exception carrying an Android {@code @StringRes} ID and format arguments
 * so domain models stay free of hardcoded UI prose while preserving {@link IllegalArgumentException}.
 */
public final class LocalizedInputException extends IllegalArgumentException {
    public final int resId;
    public final Object[] args;

    public LocalizedInputException(String code, int resId, Object... args) {
        super(code);
        this.resId = resId;
        this.args = args == null ? new Object[0] : args.clone();
    }

    public String resolve(Context context) {
        if (context == null || resId == 0) return getMessage();
        return args.length == 0 ? context.getString(resId) : context.getString(resId, args);
    }
}
