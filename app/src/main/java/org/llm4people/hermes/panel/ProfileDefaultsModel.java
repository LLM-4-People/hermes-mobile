package org.llm4people.hermes.panel;

import org.llm4people.hermes.PlainTextSanitizer;
import org.llm4people.hermes.R;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Pure-JVM Single Source of Truth for Profile Defaults editing state:
 * dirty-state tracking, searchable model & toolset filtering, reasoning-effort normalization
 * and helper descriptions, and SOUL.md character metrics.
 */
public final class ProfileDefaultsModel {
    public static final int TOOLSET_FILTER_THRESHOLD = 6;
    public static final int SOUL_PREVIEW_MAX_CHARS = 180;

    public static final String EFFORT_AUTO = "";
    public static final String EFFORT_LOW = "low";
    public static final String EFFORT_MEDIUM = "medium";
    public static final String EFFORT_HIGH = "high";

    public record Snapshot(
            String provider,
            String model,
            String reasoningEffort,
            Set<String> enabledToolsets) {
        public Snapshot {
            provider = clean(provider);
            model = clean(model);
            reasoningEffort = normalizeEffort(reasoningEffort);
            enabledToolsets = enabledToolsets == null
                    ? Collections.emptySet()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(enabledToolsets));
        }
    }

    public record ModelOption(
            String providerSlug,
            String providerName,
            ModelCapabilityBadges.ModelInfo info) {
        public ModelOption {
            providerSlug = clean(providerSlug);
            providerName = clean(providerName).isEmpty() ? providerSlug : clean(providerName);
            info = Objects.requireNonNull(info, "info");
        }
    }

    private Snapshot saved = new Snapshot("", "", EFFORT_AUTO, Collections.emptySet());
    private final List<String> availableToolsets = new ArrayList<>();
    private String selectedProvider = "";
    private String selectedModel = "";
    private String selectedEffort = EFFORT_AUTO;
    private final Set<String> selectedToolsets = new LinkedHashSet<>();

    public void load(
            String provider,
            String model,
            String reasoningEffort,
            Collection<String> enabledToolsets,
            Collection<String> catalogToolsets) {
        availableToolsets.clear();
        if (catalogToolsets != null) {
            Set<String> seen = new LinkedHashSet<>();
            for (String item : catalogToolsets) {
                String trimmed = clean(item);
                if (!trimmed.isEmpty() && seen.add(trimmed)) {
                    availableToolsets.add(trimmed);
                }
            }
        }
        selectedProvider = clean(provider);
        selectedModel = clean(model);
        selectedEffort = normalizeEffort(reasoningEffort);
        selectedToolsets.clear();
        if (enabledToolsets != null) {
            for (String item : enabledToolsets) {
                String trimmed = clean(item);
                if (!trimmed.isEmpty()) selectedToolsets.add(trimmed);
            }
        }
        saved = currentSnapshot();
    }

    public Snapshot currentSnapshot() {
        return new Snapshot(selectedProvider, selectedModel, selectedEffort, selectedToolsets);
    }

    public Snapshot savedSnapshot() {
        return saved;
    }

    public boolean isDirty() {
        return !saved.equals(currentSnapshot());
    }

    public void markSaved() {
        saved = currentSnapshot();
    }

    public void markSaved(Snapshot committed) {
        if (committed != null) saved = committed;
    }

    public String selectedProvider() { return selectedProvider; }
    public String selectedModel() { return selectedModel; }
    public String reasoningEffort() { return selectedEffort; }
    public List<String> availableToolsets() { return Collections.unmodifiableList(availableToolsets); }
    public Set<String> selectedToolsets() { return Collections.unmodifiableSet(selectedToolsets); }

    public void selectModel(String provider, String model) {
        selectedProvider = clean(provider);
        selectedModel = clean(model);
    }

    public void selectEffort(String effort) {
        selectedEffort = normalizeEffort(effort);
    }

    public boolean isToolsetEnabled(String toolset) {
        return toolset != null && selectedToolsets.contains(toolset.trim());
    }

    public void setToolsetEnabled(String toolset, boolean enabled) {
        String trimmed = clean(toolset);
        if (trimmed.isEmpty()) return;
        if (enabled) selectedToolsets.add(trimmed);
        else selectedToolsets.remove(trimmed);
    }

    public void enableAllToolsets() {
        selectedToolsets.addAll(availableToolsets);
    }

    public void disableAllToolsets() {
        if (availableToolsets.isEmpty()) selectedToolsets.clear();
        else selectedToolsets.removeAll(availableToolsets);
    }

    public int enabledToolsetCount() {
        if (availableToolsets.isEmpty()) return selectedToolsets.size();
        int count = 0;
        for (String toolset : availableToolsets) {
            if (selectedToolsets.contains(toolset)) count++;
        }
        return count;
    }

    public int totalToolsetCount() {
        return availableToolsets.size();
    }

    public boolean shouldShowToolsetFilter() {
        return availableToolsets.size() > TOOLSET_FILTER_THRESHOLD;
    }

    public List<String> filterToolsets(String query) {
        String needle = clean(query).toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) return Collections.unmodifiableList(availableToolsets);
        List<String> matches = new ArrayList<>();
        for (String toolset : availableToolsets) {
            if (toolset.toLowerCase(Locale.ROOT).contains(needle)) {
                matches.add(toolset);
            }
        }
        return Collections.unmodifiableList(matches);
    }

    public static List<ModelOption> filterModels(
            List<ModelOption> options, String providerFilterSlug, String query) {
        if (options == null || options.isEmpty()) return Collections.emptyList();
        String providerSlug = clean(providerFilterSlug);
        String needle = clean(query).toLowerCase(Locale.ROOT);
        List<ModelOption> out = new ArrayList<>();
        for (ModelOption option : options) {
            if (option == null) continue;
            if (!providerSlug.isEmpty() && !providerSlug.equals(option.providerSlug())) continue;
            if (!needle.isEmpty()) {
                boolean matches = option.info().id().toLowerCase(Locale.ROOT).contains(needle)
                        || option.info().label().toLowerCase(Locale.ROOT).contains(needle)
                        || option.providerSlug().toLowerCase(Locale.ROOT).contains(needle)
                        || option.providerName().toLowerCase(Locale.ROOT).contains(needle);
                if (!matches) continue;
            }
            out.add(option);
        }
        return Collections.unmodifiableList(out);
    }

    public static String normalizeEffort(String raw) {
        if (raw == null) return EFFORT_AUTO;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case EFFORT_LOW -> EFFORT_LOW;
            case EFFORT_MEDIUM -> EFFORT_MEDIUM;
            case EFFORT_HIGH -> EFFORT_HIGH;
            default -> EFFORT_AUTO;
        };
    }

    public static int effortLabelRes(String effort) {
        return switch (normalizeEffort(effort)) {
            case EFFORT_LOW -> R.string.brain_profile_reasoning_low;
            case EFFORT_MEDIUM -> R.string.brain_profile_reasoning_medium;
            case EFFORT_HIGH -> R.string.brain_profile_reasoning_high;
            default -> R.string.brain_profile_reasoning_auto;
        };
    }

    public static int effortHelperRes(String effort) {
        return switch (normalizeEffort(effort)) {
            case EFFORT_LOW -> R.string.profile_defaults_reasoning_desc_low;
            case EFFORT_MEDIUM -> R.string.profile_defaults_reasoning_desc_medium;
            case EFFORT_HIGH -> R.string.profile_defaults_reasoning_desc_high;
            default -> R.string.profile_defaults_reasoning_desc_auto;
        };
    }

    public static int soulCharCount(String content) {
        return content == null ? 0 : content.length();
    }

    public static String soulPreview(String content) {
        return PlainTextSanitizer.collapseWithEllipsis(content, SOUL_PREVIEW_MAX_CHARS);
    }

    public static boolean isSoulDirty(String initialContent, String currentContent) {
        String before = initialContent == null ? "" : initialContent;
        String after = currentContent == null ? "" : currentContent;
        return !before.equals(after);
    }

    private static String clean(String raw) {
        return raw == null ? "" : raw.trim();
    }
}
