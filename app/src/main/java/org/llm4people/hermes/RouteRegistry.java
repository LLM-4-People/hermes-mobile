package org.llm4people.hermes;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Modular extension point for navigation destinations and workspace actions.
 * Allows feature modules (navigation chrome, OS hooks, live surfaces, brain studio, delivery inbox)
 * to register routes and actions cleanly without editing monolithic switch tables.
 */
public final class RouteRegistry {
    private static final Pattern ROUTE_ID = Pattern.compile("[a-z][a-z0-9_/-]{0,63}");

    public enum Section {
        DISCUSSIONS,
        NOTES_TASKS,
        APPS,
        SETTINGS
    }

    public record Destination(
            String id,
            Section section,
            String label,
            String hint,
            int iconRes,
            boolean primary,
            String requiredCapability,
            Runnable open) {
        public Destination {
            if (id == null || !ROUTE_ID.matcher(id).matches()) {
                throw new IllegalArgumentException("Invalid route id");
            }
            Objects.requireNonNull(section, "section");
            if (label == null || label.trim().isEmpty()) {
                throw new IllegalArgumentException("Destination label required");
            }
            hint = hint == null ? "" : hint.trim();
            requiredCapability = requiredCapability == null || requiredCapability.trim().isEmpty()
                    ? null : requiredCapability.trim();
            Objects.requireNonNull(open, "open");
        }
    }

    private final Map<String, Destination> destinations = new LinkedHashMap<>();
    private final Map<String, Runnable> actions = new LinkedHashMap<>();

    public synchronized RouteRegistry register(Destination destination) {
        Objects.requireNonNull(destination, "destination");
        if (destinations.containsKey(destination.id())) {
            throw new IllegalStateException("Duplicate destination id: " + destination.id());
        }
        destinations.put(destination.id(), destination);
        actions.putIfAbsent(destination.id(), destination.open());
        return this;
    }

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
