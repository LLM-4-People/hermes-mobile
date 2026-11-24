package org.llm4people.hermes;

import org.llm4people.hermes.hub.AppsLauncherPanel;
import org.llm4people.hermes.hub.NotesTasksPanel;
import org.llm4people.hermes.settings.SettingsHubPanel;

import java.util.ArrayDeque;

/** The unlocked account's live navigation stack. Returning to a route unwinds to its first entry. */
public final class WorkspaceNavigation {
    public static final String DISCUSSION = "discussion";
    static final String NOTES_TASKS = NotesTasksPanel.ROUTE_ID;
    static final String APPS = AppsLauncherPanel.ROUTE_ID;
    static final String SETTINGS = SettingsHubPanel.ROUTE_ID;
    public static final String GALLERY = "pages";
    static final String COMPOSER = "composer";
    static final String HOME = APPS;
    private final ArrayDeque<String> routes = new ArrayDeque<>();

    private String displayed;

    WorkspaceNavigation() { routes.addLast(DISCUSSION); }

    String current() { return routes.getLast(); }
    boolean contains(String route) { return routes.contains(route); }

    /** Maps any active route to one of the three primary living destinations or top-bar Settings. */
    static String topDestination(String route) {
        if (route == null || DISCUSSION.equals(route) || COMPOSER.equals(route)) return DISCUSSION;
        if (NOTES_TASKS.equals(route)) return NOTES_TASKS;
        if (SETTINGS.equals(route)
                || org.llm4people.hermes.settings.AppearanceSettingsPanel.ROUTE_ID.equals(route)
                || org.llm4people.hermes.ambient.AmbientAccessPanel.ROUTE_ID.equals(route)
                || org.llm4people.hermes.presence.PresenceSettingsPanel.ROUTE_ID.equals(route)
                || org.llm4people.hermes.os.PermissionsPanel.ROUTE_ID.equals(route)
                || org.llm4people.hermes.update.UpdatePanel.ROUTE_ID.equals(route)
                || org.llm4people.hermes.doctor.ConnectionDoctorPanel.ROUTE_ID.equals(route)) {
            return SETTINGS;
        }
        return APPS;
    }

    /** Switches cleanly between primary destinations while keeping Discussions as the Back root. */
    void selectTab(String destination) {
        if (destination == null || DISCUSSION.equals(destination)) {
            open(DISCUSSION);
            return;
        }
        if (destination.equals(current())) return;
        routes.clear();
        routes.addLast(DISCUSSION);
        routes.addLast(destination);
    }

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
