package org.llm4people.hermes.panel;

import android.content.Context;
import org.llm4people.hermes.R;
import org.llm4people.hermes.RouteRegistry;
import java.util.Objects;

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

    @FunctionalInterface
    private interface TextLookup {
        String get(int resId, String idFallback);
    }

    private BrainStudioRoutes() {}

    /** Register all Brain Studio destinations using localized string resources from {@code context}. */
    public static RouteRegistry register(RouteRegistry registry, Context context, Handlers handlers) {
        Objects.requireNonNull(context, "context");
        return registerInternal(registry, handlers, (resId, idFallback) -> context.getString(resId));
    }

    /** Register all Brain Studio destinations (host-JVM test overload using route IDs). */
    public static RouteRegistry register(RouteRegistry registry, Handlers handlers) {
        return registerInternal(registry, handlers, (resId, idFallback) -> idFallback);
    }

    private static RouteRegistry registerInternal(RouteRegistry registry, Handlers handlers, TextLookup text) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(handlers, "handlers");

        registry.register(new RouteRegistry.Destination(
                ROUTE_MEMORY,
                RouteRegistry.Section.CERVEAU,
                text.get(R.string.brain_route_memory_label, ROUTE_MEMORY),
                text.get(R.string.brain_route_memory_hint, ""),
                0,
                true,
                BrainCapabilities.MEMORY_API,
                handlers::openMemory));

        registry.register(new RouteRegistry.Destination(
                ROUTE_NOTES,
                RouteRegistry.Section.CERVEAU,
                text.get(R.string.brain_route_notes_label, ROUTE_NOTES),
                text.get(R.string.brain_route_notes_hint, ""),
                0,
                true,
                BrainCapabilities.SMART_NOTES,
                handlers::openNotes));

        registry.register(new RouteRegistry.Destination(
                ROUTE_SHARED_WITH_ME,
                RouteRegistry.Section.CERVEAU,
                text.get(R.string.brain_route_shared_label, ROUTE_SHARED_WITH_ME),
                text.get(R.string.brain_route_shared_hint, ""),
                0,
                false,
                BrainCapabilities.UNIVERSAL_SHARING,
                handlers::openSharedWithMe));

        registry.register(new RouteRegistry.Destination(
                ROUTE_SEARCH,
                RouteRegistry.Section.CERVEAU,
                text.get(R.string.brain_route_search_label, ROUTE_SEARCH),
                text.get(R.string.brain_route_search_hint, ""),
                0,
                true,
                BrainCapabilities.SESSION_SEARCH,
                handlers::openGlobalSearch));

        registry.register(new RouteRegistry.Destination(
                ROUTE_SKILLS,
                RouteRegistry.Section.CERVEAU,
                text.get(R.string.brain_route_skills_label, ROUTE_SKILLS),
                text.get(R.string.brain_route_skills_hint, ""),
                0,
                true,
                null,
                handlers::openSkillStudio));

        registry.register(new RouteRegistry.Destination(
                ROUTE_WORKSPACE_FILES,
                RouteRegistry.Section.CERVEAU,
                text.get(R.string.brain_route_files_label, ROUTE_WORKSPACE_FILES),
                text.get(R.string.brain_route_files_hint, ""),
                0,
                false,
                BrainCapabilities.WORKSPACE_FILES,
                handlers::openWorkspaceFiles));

        registry.register(new RouteRegistry.Destination(
                ROUTE_PROFILE_DEFAULTS,
                RouteRegistry.Section.SETTINGS,
                text.get(R.string.brain_route_profile_label, ROUTE_PROFILE_DEFAULTS),
                text.get(R.string.brain_route_profile_hint, ""),
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
