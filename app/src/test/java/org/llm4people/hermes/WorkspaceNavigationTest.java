package org.llm4people.hermes;

import org.junit.Test;
import org.llm4people.hermes.ambient.AmbientAccessPanel;
import org.llm4people.hermes.artifact.ArtifactDesk;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class WorkspaceNavigationTest {
    @Test public void backFromStagingReturnsToThePageAndThenItsGallery() {
        WorkspaceNavigation route = new WorkspaceNavigation();
        for (String page : Arrays.asList("home", "pages", "page/build", "composer")) route.open(page);
        for (String back : Arrays.asList("page/build", "pages", "home", "discussion")) {
            assertTrue(route.back());
            assertEquals(back, route.current());
        }
        assertFalse(route.back());
    }

    @Test public void playbackSurvivesARepaintAndStopsBeforeBackOrDirectPageNavigation() {
        WorkspaceNavigation route = new WorkspaceNavigation();
        AtomicBoolean playing = new AtomicBoolean(false);
        Runnable stop = () -> playing.set(false);
        route.open(WorkspaceNavigation.HOME);
        route.display(route.current(), stop);
        route.open(WorkspaceNavigation.COMPOSER);
        route.display(route.current(), stop);
        playing.set(true);
        assertFalse(route.display(route.current(), stop));
        assertTrue(playing.get());
        assertTrue(route.back());
        assertTrue(route.display(route.current(), stop));
        assertFalse(playing.get());

        route.open(WorkspaceNavigation.DISCUSSION);
        route.display("thread", stop);
        playing.set(true);
        route.open("page/planning");
        assertTrue(route.display(route.current(), stop));
        assertFalse(playing.get());
    }

    @Test public void removingTheCurrentPageReturnsToAnExistingGalleryOnce() {
        WorkspaceNavigation route = new WorkspaceNavigation();
        route.open("pages");
        route.open("page/a");
        route.open("page/b");
        route.open("page/a");
        route.forgetCurrentPage();
        assertEquals("pages", route.current());
        assertTrue(route.back());
        assertEquals("discussion", route.current());
        assertFalse(route.back());
    }

    @Test public void staleWorkspaceCannotReadOrActOnNewlyUnlockedAccount() {
        AtomicBoolean active = new AtomicBoolean(true);
        List<String> calls = new ArrayList<>();
        WorkspaceHost host = new WorkspaceHost(new ArtifactDesk.Host() {
            @Override public java.util.function.BooleanSupplier deviceActionOrigin() { return () -> true; }
            @Override public void onAction(String id) { calls.add(id); }
            @Override public void onLayoutChanged() { calls.add("layout"); }
            @Override public void openWorkspace(String id) { calls.add(id); }
            @Override public void stageDraft(String text) { calls.add(text); }
            @Override public void threadSlots(List<String> slots) { calls.addAll(slots); }
            @Override public String workspaceValue(String source) { return "private"; }
        }, active::get);
        assertEquals("private", host.workspaceValue("profile.label"));
        java.util.function.BooleanSupplier review = host.deviceActionOrigin();
        assertTrue(review.getAsBoolean());
        active.set(false);
        assertFalse(review.getAsBoolean());
        assertFalse(host.deviceActionOrigin().getAsBoolean());
        host.stageDraft("new draft");
        host.openWorkspace("page/a");
        host.onAction("profiles");
        host.onLayoutChanged();
        host.threadSlots(Arrays.asList("composer"));
        assertTrue(calls.isEmpty());
        assertEquals("", host.workspaceValue("profile.label"));
    }

    @Test public void fourDestinationTabSwitchingKeepsDiscussionsAsBackRoot() {
        WorkspaceNavigation nav = new WorkspaceNavigation();
        assertEquals(WorkspaceNavigation.DISCUSSION, WorkspaceNavigation.topDestination(null));
        assertEquals(WorkspaceNavigation.DISCUSSION, WorkspaceNavigation.topDestination(WorkspaceNavigation.COMPOSER));
        assertEquals(WorkspaceNavigation.HOME, WorkspaceNavigation.topDestination(WorkspaceNavigation.GALLERY));
        assertEquals(WorkspaceNavigation.HOME, WorkspaceNavigation.topDestination("page/custom"));
        assertEquals(WorkspaceNavigation.BRAIN, WorkspaceNavigation.topDestination(WorkspaceNavigation.BRAIN));
        assertEquals(WorkspaceNavigation.ACTIVITY, WorkspaceNavigation.topDestination(WorkspaceNavigation.ACTIVITY));
        assertEquals(WorkspaceNavigation.ACTIVITY, WorkspaceNavigation.topDestination(AmbientAccessPanel.ROUTE_ID));

        nav.selectTab(WorkspaceNavigation.HOME);
        assertEquals(WorkspaceNavigation.HOME, nav.current());
        nav.selectTab(WorkspaceNavigation.BRAIN);
        assertEquals(WorkspaceNavigation.BRAIN, nav.current());
        nav.selectTab(WorkspaceNavigation.ACTIVITY);
        assertEquals(WorkspaceNavigation.ACTIVITY, nav.current());
        assertTrue(nav.back());
        assertEquals(WorkspaceNavigation.DISCUSSION, nav.current());
        assertFalse(nav.back());
    }

    @Test public void routeAndActionRegistriesDispatchAndRejectInvalidEntries() {
        List<String> events = new ArrayList<>();
        ShellRoutes routes = new ShellRoutes();
        routes.register("brain", (r, f) -> events.add("route:" + r + ":" + f));
        routes.setFallback((r, f) -> events.add("fallback:" + r));
        assertTrue(routes.hasRoute("brain"));
        assertFalse(routes.hasRoute("unknown"));
        assertTrue(routes.render("brain", true));
        assertTrue(routes.render("page/notes", false));
        assertFalse(routes.render(null, false));
        assertEquals(Arrays.asList("route:brain:true", "fallback:page/notes"), events);

        try {
            routes.register("", (r, f) -> {});
            fail("Blank route accepted");
        } catch (IllegalArgumentException expected) {}

        ActionRegistry actions = new ActionRegistry();
        actions.register("search", () -> events.add("action:search"));
        assertTrue(actions.contains("search"));
        assertFalse(actions.contains(null));
        assertTrue(actions.dispatch("search"));
        assertFalse(actions.dispatch("does_not_exist"));
        assertFalse(actions.dispatch(null));
        assertEquals("action:search", events.get(events.size() - 1));

        try {
            actions.register("", () -> {});
            fail("Blank action id accepted");
        } catch (IllegalArgumentException expected) {}
    }
}
