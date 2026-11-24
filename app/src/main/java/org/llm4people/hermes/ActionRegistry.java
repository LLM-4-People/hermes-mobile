package org.llm4people.hermes;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Central action dispatcher shared by navigation chrome, overflow menus, and future feature modules. */
public final class ActionRegistry {
    private final Map<String, Runnable> actions = new LinkedHashMap<>();

    public void register(String id, Runnable handler) {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("Action id must not be blank");
        actions.put(id, Objects.requireNonNull(handler));
    }

    public boolean contains(String id) {
        return id != null && actions.containsKey(id);
    }

    public boolean dispatch(String id) {
        Runnable handler = id == null ? null : actions.get(id);
        if (handler == null) {
            Diagnostics.warning("workspace", "unknown_action");
            return false;
        }
        handler.run();
        return true;
    }
}
