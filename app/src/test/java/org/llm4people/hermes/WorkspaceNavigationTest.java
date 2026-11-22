package org.llm4people.hermes;

import org.junit.Test;
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
            @Override public void onAction(String id) { calls.add(id); }
            @Override public void onLayoutChanged() { calls.add("layout"); }
            @Override public void openWorkspace(String id) { calls.add(id); }
            @Override public void stageDraft(String text) { calls.add(text); }
            @Override public void threadSlots(List<String> slots) { calls.addAll(slots); }
            @Override public String workspaceValue(String source) { return "private"; }
        }, active::get);
        assertEquals("private", host.workspaceValue("profile.label"));
        active.set(false);
        host.stageDraft("new draft");
        host.openWorkspace("page/a");
        host.onAction("profiles");
        host.onLayoutChanged();
        host.threadSlots(Arrays.asList("composer"));
        assertTrue(calls.isEmpty());
        assertEquals("", host.workspaceValue("profile.label"));
    }
}
