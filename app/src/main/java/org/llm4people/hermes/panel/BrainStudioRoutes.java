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
        String get(int resId, String fallback);
    }

    private BrainStudioRoutes() {}

    /** Register all Cerveau Studio destinations using localized string resources from {@code context}. */
    public static RouteRegistry register(RouteRegistry registry, Context context, Handlers handlers) {
        Objects.requireNonNull(context, "context");
        return registerInternal(registry, handlers, (resId, fallback) -> context.getString(resId));
    }

    /** Register all Cerveau Studio destinations (host-JVM / fallback overload using default French strings). */
    public static RouteRegistry register(RouteRegistry registry, Handlers handlers) {
        return registerInternal(registry, handlers, (resId, fallback) -> fallback);
    }

    private static RouteRegistry registerInternal(RouteRegistry registry, Handlers handlers, TextLookup text) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(handlers, "handlers");

        registry.register(new RouteRegistry.Destination(
                ROUTE_MEMORY,
                RouteRegistry.Section.CERVEAU,
                text.get(R.string.brain_route_memory_label, "Mémoire de Hermes"),
                text.get(R.string.brain_route_memory_hint,
                        "Ce que Hermes retient et ce qu’il sait à propos de vous."),
                0,
                true,
                BrainCapabilities.MEMORY_API,
                handlers::openMemory));

        registry.register(new RouteRegistry.Destination(
                ROUTE_NOTES,
                RouteRegistry.Section.CERVEAU,
                text.get(R.string.brain_route_notes_label, "Notes intelligentes"),
                text.get(R.string.brain_route_notes_hint,
                        "Vos notes structurées et partagées avec Hermes."),
                0,
                true,
                BrainCapabilities.SMART_NOTES,
                handlers::openNotes));

        registry.register(new RouteRegistry.Destination(
                ROUTE_SHARED_WITH_ME,
                RouteRegistry.Section.CERVEAU,
                text.get(R.string.brain_route_shared_label, "Partagé avec moi"),
                text.get(R.string.brain_route_shared_hint,
                        "Notes, savoir-faire et éléments partagés par d’autres utilisateurs sur ce serveur."),
                0,
                false,
                BrainCapabilities.UNIVERSAL_SHARING,
                handlers::openSharedWithMe));

        registry.register(new RouteRegistry.Destination(
                ROUTE_SEARCH,
                RouteRegistry.Section.CERVEAU,
                text.get(R.string.brain_route_search_label, "Rechercher dans vos discussions"),
                text.get(R.string.brain_route_search_hint,
                        "Retrouvez un passage dans tout votre historique."),
                0,
                true,
                BrainCapabilities.SESSION_SEARCH,
                handlers::openGlobalSearch));

        registry.register(new RouteRegistry.Destination(
                ROUTE_SKILLS,
                RouteRegistry.Section.CERVEAU,
                text.get(R.string.brain_route_skills_label, "Savoir-faire"),
                text.get(R.string.brain_route_skills_hint,
                        "Guides et méthodes réutilisables par Hermes."),
                0,
                true,
                null,
                handlers::openSkillStudio));

        registry.register(new RouteRegistry.Destination(
                ROUTE_WORKSPACE_FILES,
                RouteRegistry.Section.CERVEAU,
                text.get(R.string.brain_route_files_label, "Fichiers du projet"),
                text.get(R.string.brain_route_files_hint,
                        "Parcourez les fichiers et modifications de cette discussion."),
                0,
                false,
                BrainCapabilities.WORKSPACE_FILES,
                handlers::openWorkspaceFiles));

        registry.register(new RouteRegistry.Destination(
                ROUTE_PROFILE_DEFAULTS,
                RouteRegistry.Section.CERVEAU,
                text.get(R.string.brain_route_profile_label, "Réglages du profil"),
                text.get(R.string.brain_route_profile_hint,
                        "Modèle et capacités utilisés par défaut pour ce profil."),
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
