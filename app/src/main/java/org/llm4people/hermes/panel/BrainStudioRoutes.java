package org.llm4people.hermes.panel;

import android.content.Context;
import org.llm4people.hermes.R;
import org.llm4people.hermes.RouteRegistry;
import java.util.Objects;
import java.util.function.IntFunction;

/**
 * Registers all Hermes Brain Studio destinations and thread power actions into RouteRegistry
 * so navigation shells (Bottom Bar / Rail / Hub) discover them cleanly without switch-table edits.
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

    /** Register all Brain Studio destinations using localized string resources from {@code context}. */
    public static RouteRegistry register(RouteRegistry registry, Context context, Handlers handlers) {
        Objects.requireNonNull(context, "context");
        return register(registry, context::getString, handlers);
    }

    /** Register all Brain Studio destinations using the supplied string-resource resolver. */
    public static RouteRegistry register(RouteRegistry registry, IntFunction<String> text, Handlers handlers) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(handlers, "handlers");

        registry.register(new RouteRegistry.Destination(
                ROUTE_MEMORY,
                RouteRegistry.Section.SETTINGS,
                text.apply(R.string.brain_route_memory_label),
                text.apply(R.string.brain_route_memory_hint),
                0,
                true,
                BrainCapabilities.MEMORY_API,
                handlers::openMemory));

        registry.register(new RouteRegistry.Destination(
                ROUTE_NOTES,
                RouteRegistry.Section.NOTES_TASKS,
                text.apply(R.string.brain_route_notes_label),
                text.apply(R.string.brain_route_notes_hint),
                0,
                true,
                BrainCapabilities.SMART_NOTES,
                handlers::openNotes));

        registry.register(new RouteRegistry.Destination(
                ROUTE_SHARED_WITH_ME,
                RouteRegistry.Section.NOTES_TASKS,
                text.apply(R.string.brain_route_shared_label),
                text.apply(R.string.brain_route_shared_hint),
                0,
                false,
                BrainCapabilities.UNIVERSAL_SHARING,
                handlers::openSharedWithMe));

        registry.register(new RouteRegistry.Destination(
                ROUTE_SEARCH,
                RouteRegistry.Section.SETTINGS,
                text.apply(R.string.brain_route_search_label),
                text.apply(R.string.brain_route_search_hint),
                0,
                true,
                BrainCapabilities.SESSION_SEARCH,
                handlers::openGlobalSearch));

        registry.register(new RouteRegistry.Destination(
                ROUTE_SKILLS,
                RouteRegistry.Section.SETTINGS,
                text.apply(R.string.brain_route_skills_label),
                text.apply(R.string.brain_route_skills_hint),
                0,
                true,
                null,
                handlers::openSkillStudio));

        registry.register(new RouteRegistry.Destination(
                ROUTE_WORKSPACE_FILES,
                RouteRegistry.Section.SETTINGS,
                text.apply(R.string.brain_route_files_label),
                text.apply(R.string.brain_route_files_hint),
                0,
                false,
                BrainCapabilities.WORKSPACE_FILES,
                handlers::openWorkspaceFiles));

        registry.register(new RouteRegistry.Destination(
                ROUTE_PROFILE_DEFAULTS,
                RouteRegistry.Section.SETTINGS,
                text.apply(R.string.brain_route_profile_label),
                text.apply(R.string.brain_route_profile_hint),
                0,
                false,
                BrainCapabilities.PROFILE_DEFAULTS,
                handlers::openProfileDefaults));

        registry.registerAction(ACTION_PIN_THREAD, handlers::togglePinThread);
        registry.registerAction(ACTION_FORK_THREAD, handlers::forkThread);
        registry.registerAction(ACTION_EXPORT_THREAD, handlers::exportThread);
        registry.registerAction(ACTION_SHARE_THREAD, handlers::shareThread);
        return registry;
    }
}
