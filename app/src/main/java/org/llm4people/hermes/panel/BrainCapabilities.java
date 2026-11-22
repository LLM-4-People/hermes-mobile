package org.llm4people.hermes.panel;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Single source of truth for Hermes Brain Studio gateway capability flags and graceful degradation. */
public final class BrainCapabilities {
    public static final String MEMORY_API = "memory_api";
    public static final String SESSION_SEARCH = "session_search";
    public static final String SKILL_STUDIO = "skill_studio";
    public static final String WORKSPACE_FILES = "workspace_files";
    public static final String WORKSPACE_BROWSER = "workspace_browser";
    public static final String PROFILE_DEFAULTS = "profile_defaults";
    public static final String SESSION_FORK_AT_TURN = "session_fork_at_turn";
    public static final String SESSION_EXPORT = "session_export";
    public static final String USAGE_ANALYTICS = "usage_analytics";
    public static final String RUN_METRICS_V2 = "run_metrics_v2";
    public static final String SMART_NOTES = "smart_notes";
    public static final String NOTE_SHARING = "note_sharing";
    public static final String UNIVERSAL_SHARING = "universal_sharing";
    public static final String HABIT_SUGGESTIONS = "habit_suggestions";
    public static final String RUN_TOOLSET_SELECTION = "run_toolset_selection";
    public static final String RUN_TOOL_EXCLUSIONS = "run_tool_exclusions";

    private static final BrainCapabilities EMPTY = new BrainCapabilities(Collections.emptySet());

    private final Set<String> enabled;

    private BrainCapabilities(Set<String> enabled) {
        this.enabled = Collections.unmodifiableSet(enabled);
    }

    public static BrainCapabilities empty() {
        return EMPTY;
    }

    /** Build from a map of raw gateway feature flags, normalizing known aliases into canonical flags. */
    public static BrainCapabilities fromFlags(Map<String, Boolean> rawFlags) {
        if (rawFlags == null || rawFlags.isEmpty()) return EMPTY;
        Set<String> active = new LinkedHashSet<>();
        for (Map.Entry<String, Boolean> entry : rawFlags.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.trim().isEmpty() || !Boolean.TRUE.equals(entry.getValue())) continue;
            String normalized = key.trim();
            active.add(normalized);
            // Both blueprints advertise workspace browsing under workspace_browser or workspace_files.
            if (WORKSPACE_BROWSER.equals(normalized)) active.add(WORKSPACE_FILES);
            else if (WORKSPACE_FILES.equals(normalized)) active.add(WORKSPACE_BROWSER);
        }
        return active.isEmpty() ? EMPTY : new BrainCapabilities(active);
    }

    public boolean supports(String feature) {
        if (feature == null || feature.trim().isEmpty()) return false;
        String key = feature.trim();
        if (enabled.contains(key)) return true;
        if (WORKSPACE_FILES.equals(key)) return enabled.contains(WORKSPACE_BROWSER);
        if (WORKSPACE_BROWSER.equals(key)) return enabled.contains(WORKSPACE_FILES);
        return false;
    }

    public boolean memoryApi() { return supports(MEMORY_API); }
    public boolean sessionSearch() { return supports(SESSION_SEARCH); }
    public boolean skillStudio() { return supports(SKILL_STUDIO); }
    public boolean workspaceFiles() { return supports(WORKSPACE_FILES); }
    public boolean profileDefaults() { return supports(PROFILE_DEFAULTS); }
    public boolean sessionForkAtTurn() { return supports(SESSION_FORK_AT_TURN); }
    public boolean sessionExport() { return supports(SESSION_EXPORT); }
    public boolean usageAnalytics() { return supports(USAGE_ANALYTICS); }
    public boolean runMetricsV2() { return supports(RUN_METRICS_V2); }
    public boolean smartNotes() { return supports(SMART_NOTES); }
    public boolean noteSharing() { return supports(NOTE_SHARING) || supports(UNIVERSAL_SHARING); }
    public boolean universalSharing() { return supports(UNIVERSAL_SHARING); }
    public boolean habitSuggestions() { return supports(HABIT_SUGGESTIONS); }

    /** Expose active canonical capability names for RouteRegistry filtering. */
    public Set<String> asSet() {
        return enabled;
    }
}
