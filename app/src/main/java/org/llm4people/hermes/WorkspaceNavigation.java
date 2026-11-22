package org.llm4people.hermes;

import java.util.ArrayDeque;

/** The unlocked account's live navigation stack. Returning to a route unwinds to its first entry. */
final class WorkspaceNavigation {
    static final String DISCUSSION = "discussion", HOME = "home", GALLERY = "pages", COMPOSER = "composer";
    private final ArrayDeque<String> routes = new ArrayDeque<>();

    private String displayed;

    WorkspaceNavigation() { routes.addLast(DISCUSSION); }

    String current() { return routes.getLast(); }
    boolean contains(String route) { return routes.contains(route); }

    /** Report a change before the renderer detaches the outgoing surface, including on system Back. */
    boolean display(String surface, Runnable beforeDeparture) {
        if (surface.equals(displayed)) return false;
        if (displayed != null) beforeDeparture.run();
        displayed = surface;
        return true;
    }

    void open(String route) {
        if (!routes.contains(route)) routes.addLast(route);
        else while (!current().equals(route)) routes.removeLast();
    }

    boolean back() {
        if (routes.size() == 1) return false;
        routes.removeLast();
        return true;
    }

    void forgetCurrentPage() {
        if (routes.size() > 1) routes.removeLast();
        open(GALLERY);
    }
}
