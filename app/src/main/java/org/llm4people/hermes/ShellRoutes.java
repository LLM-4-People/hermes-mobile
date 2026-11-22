package org.llm4people.hermes;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Central route renderer dispatcher so feature modules plug into MainActivity without switch blocks. */
public final class ShellRoutes {
    @FunctionalInterface
    public interface RouteHandler {
        void render(String route, boolean force);
    }

    private final Map<String, RouteHandler> handlers = new LinkedHashMap<>();
    private RouteHandler fallback;

    public void register(String route, RouteHandler handler) {
        if (route == null || route.isEmpty()) throw new IllegalArgumentException("Route must not be blank");
        handlers.put(route, Objects.requireNonNull(handler));
    }

    public void setFallback(RouteHandler handler) {
        fallback = Objects.requireNonNull(handler);
    }

    public boolean hasRoute(String route) {
        return route != null && handlers.containsKey(route);
    }

    public boolean render(String route, boolean force) {
        RouteHandler handler = route == null ? null : handlers.get(route);
        if (handler != null) {
            handler.render(route, force);
            return true;
        }
        if (fallback != null && route != null) {
            fallback.render(route, force);
            return true;
        }
        try {
            Diagnostics.warning("navigation", "unknown_route");
        } catch (RuntimeException unmockedHostJvm) {
            // Host JVM unit tests run against unmocked Android framework stubs.
        }
        return false;
    }
}
