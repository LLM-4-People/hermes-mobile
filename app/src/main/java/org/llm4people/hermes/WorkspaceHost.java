package org.llm4people.hermes;

import org.llm4people.hermes.artifact.ArtifactDesk;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Prevents a detached workspace view or late dialog from acting on a later profile unlock. */
final class WorkspaceHost implements ArtifactDesk.Host {
    private final ArtifactDesk.Host host;
    private final BooleanSupplier unlocked;

    WorkspaceHost(ArtifactDesk.Host host, BooleanSupplier unlocked) {
        this.host = host;
        this.unlocked = unlocked;
    }

    @Override public BooleanSupplier deviceActionOrigin() {
        if (!unlocked.getAsBoolean()) return () -> false;
        BooleanSupplier origin = host.deviceActionOrigin();
        return () -> unlocked.getAsBoolean() && origin.getAsBoolean();
    }

    @Override public void onAction(String id) { if (unlocked.getAsBoolean()) host.onAction(id); }
    @Override public void onLayoutChanged() { if (unlocked.getAsBoolean()) host.onLayoutChanged(); }
    @Override public void openWorkspace(String id) { if (unlocked.getAsBoolean()) host.openWorkspace(id); }
    @Override public void stageDraft(String text) { if (unlocked.getAsBoolean()) host.stageDraft(text); }
    @Override public void threadSlots(List<String> order) { if (unlocked.getAsBoolean()) host.threadSlots(order); }
    @Override public String workspaceValue(String source) {
        return unlocked.getAsBoolean() ? host.workspaceValue(source) : "";
    }
}
