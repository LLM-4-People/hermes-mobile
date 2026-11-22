package org.llm4people.hermes.artifact;

import java.util.List;

class WorkspaceTestHost implements ArtifactDesk.Host {
    @Override public void onAction(String id) { throw new AssertionError("Unexpected action " + id); }
    @Override public void onLayoutChanged() { throw new AssertionError("Unexpected layout mutation"); }
    @Override public void openWorkspace(String id) { throw new AssertionError("Unexpected page " + id); }
    @Override public void stageDraft(String text) { throw new AssertionError("Unexpected draft"); }
    @Override public String workspaceValue(String source) { throw new AssertionError("Unexpected source " + source); }
    @Override public void threadSlots(List<String> order) { throw new AssertionError("Unexpected native slots"); }
}
