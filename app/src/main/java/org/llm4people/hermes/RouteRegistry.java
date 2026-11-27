package org.llm4people.hermes;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Modular extension point for navigation routes and workspace actions.
 * Allows feature modules (navigation chrome, OS hooks, live surfaces, brain studio, delivery inbox)
 * to register route and action handlers cleanly without editing monolithic switch tables.
 */
public final class RouteRegistry {
    private static final Pattern ROUTE_ID = Pattern.compile("[a-z][a-z0-9_/-]{0,63}");

    private final Map<String, Runnable> actions = new LinkedHashMap<>();

    public synchronized RouteRegistry registerAction(String id, Runnable handler) {
        if (id == null || !ROUTE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid action id");
        }
        Objects.requireNonNull(handler, "handler");
        if (actions.containsKey(id)) {
            throw new IllegalStateException("Duplicate action id: " + id);
        }
        actions.put(id, handler);
        return this;
    }

    public boolean dispatch(String id) {
        Runnable handler;
        synchronized (this) {
            handler = id == null ? null : actions.get(id);
        }
        if (handler == null) {
            Diagnostics.warning("route", "unknown_action");
            return false;
        }
        handler.run();
        return true;
    }
}
