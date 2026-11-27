package org.llm4people.hermes.panel;

import org.llm4people.hermes.RouteRegistry;
import java.util.Objects;

/**
 * Registers all Hermes Brain Studio routes and thread power actions into {@link RouteRegistry}
 * so navigation shells (Bottom Bar / Rail / Hub) dispatch them cleanly without switch-table edits.
 */
public final class BrainStudioRoutes {
    public static final String ROUTE_MEMORY = "brain/memory";
    public static final String ROUTE_NOTES = "brain/notes";
    public static final String ROUTE_SHARED_WITH_ME = "brain/shared_with_me";
    public static final String ROUTE_SEARCH = "brain/search";
    public static final String ROUTE_SKILLS = "brain/skills";
    public static final String ROUTE_WORKSPACE_FILES = "brain/workspace_files";
    public static final String ROUTE_PROFILE_DEFAULTS = "brain/profile_defaults";

    public static final String ACTION_PIN_THREAD = "thread/pin";
    public static final String ACTION_FORK_THREAD = "thread/fork";
    public static final String ACTION_EXPORT_THREAD = "thread/export";
    public static final String ACTION_SHARE_THREAD = "thread/share";

    public interface Handlers {
        void openMemory();
        void openNotes();
        void openSharedWithMe();
        void openGlobalSearch();
        void openSkillStudio();
        void openWorkspaceFiles();
        void openProfileDefaults();
        void togglePinThread();
        void forkThread();
        void exportThread();
        void shareThread();
    }

    private BrainStudioRoutes() {}

    /** Register all Brain Studio routes and thread actions into {@code registry}. */
    public static RouteRegistry register(RouteRegistry registry, Handlers handlers) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(handlers, "handlers");

        registry.registerAction(ROUTE_MEMORY, handlers::openMemory);
        registry.registerAction(ROUTE_NOTES, handlers::openNotes);
        registry.registerAction(ROUTE_SHARED_WITH_ME, handlers::openSharedWithMe);
        registry.registerAction(ROUTE_SEARCH, handlers::openGlobalSearch);
        registry.registerAction(ROUTE_SKILLS, handlers::openSkillStudio);
        registry.registerAction(ROUTE_WORKSPACE_FILES, handlers::openWorkspaceFiles);
        registry.registerAction(ROUTE_PROFILE_DEFAULTS, handlers::openProfileDefaults);
        registry.registerAction(ACTION_PIN_THREAD, handlers::togglePinThread);
        registry.registerAction(ACTION_FORK_THREAD, handlers::forkThread);
        registry.registerAction(ACTION_EXPORT_THREAD, handlers::exportThread);
        registry.registerAction(ACTION_SHARE_THREAD, handlers::shareThread);
        return registry;
    }
}
