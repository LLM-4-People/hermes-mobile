package org.llm4people.hermes.panel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.llm4people.hermes.R;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Pure-JVM positive & negative regression tests for ProfileDefaultsModel. */
public final class ProfileDefaultsModelTest {

    @Test
    public void dirtyStateTransitionsTrackModelEffortAndToolsetsAndRevertCleanly() {
        ProfileDefaultsModel model = new ProfileDefaultsModel();
        model.load(
                "anthropic",
                "claude-sonnet-4",
                "medium",
                Arrays.asList("web", "files"),
                Arrays.asList("web", "files", "terminal", "memory"));

        assertFalse("Freshly loaded snapshot is clean", model.isDirty());

        // Changing model marks dirty; reverting makes it clean again.
        model.selectModel("openai", "gpt-5");
        assertTrue(model.isDirty());
        model.selectModel("anthropic", "claude-sonnet-4");
        assertFalse(model.isDirty());

        // Changing reasoning effort marks dirty; reverting restores clean.
        model.selectEffort("high");
        assertTrue(model.isDirty());
        model.selectEffort("MEDIUM");
        assertFalse(model.isDirty());

        // Toggling a toolset marks dirty; markSaved resets baseline.
        model.setToolsetEnabled("terminal", true);
        assertTrue(model.isDirty());
        assertEquals(3, model.enabledToolsetCount());
        model.markSaved(model.currentSnapshot());
        assertFalse(model.isDirty());
        assertEquals("anthropic", model.currentSnapshot().provider());
        assertTrue(model.currentSnapshot().enabledToolsets().contains("terminal"));

        // R22.5.1: Live-region transition guard silences initial load and unchanged full-render rebuilds.
        assertFalse(ProfileDefaultsPanel.shouldAnnounceTransition(null, "Saved"));
        assertFalse(ProfileDefaultsPanel.shouldAnnounceTransition("Saved", "Saved"));
        assertFalse(ProfileDefaultsPanel.shouldAnnounceTransition("Saved", null));
        assertTrue(ProfileDefaultsPanel.shouldAnnounceTransition("Saved", "Unsaved changes"));
    }

    @Test
    public void markSavedWithSubmittedSnapshotPreservesInFlightEditsAsDirty() {
        ProfileDefaultsModel model = new ProfileDefaultsModel();
        model.load(
                "anthropic",
                "claude-sonnet-4",
                "medium",
                Arrays.asList("web"),
                Arrays.asList("web", "files", "terminal"));

        // User edits effort and submits PATCH.
        model.selectEffort("high");
        ProfileDefaultsModel.Snapshot submitted = model.currentSnapshot();
        assertTrue(model.isDirty());

        // While PATCH is in flight, user makes another edit.
        model.setToolsetEnabled("files", true);

        // PATCH completes with the previously submitted snapshot: model MUST stay dirty!
        model.markSaved(submitted);
        assertTrue("In-flight edits made after PATCH submission must remain dirty", model.isDirty());
        assertEquals("high", submitted.reasoningEffort());
        assertFalse(submitted.enabledToolsets().contains("files"));

        // Null snapshot is safely ignored.
        model.markSaved(null);
        assertTrue(model.isDirty());

        // Reverting the in-flight edit makes it clean again.
        model.setToolsetEnabled("files", false);
        assertFalse(model.isDirty());
    }

    @Test
    public void searchableModelFilterMatchesIdLabelAndProviderCaseInsensitively() {
        List<ProfileDefaultsModel.ModelOption> catalog = Arrays.asList(
                new ProfileDefaultsModel.ModelOption("anthropic", "Anthropic",
                        new ModelCapabilityBadges.ModelInfo("claude-sonnet-4", "Claude Sonnet 4",
                                200_000, 8192, true, true, true, 3.0, 15.0)),
                new ProfileDefaultsModel.ModelOption("openai", "OpenAI",
                        new ModelCapabilityBadges.ModelInfo("gpt-5-mini", "GPT-5 Mini",
                                128_000, 16_384, true, true, false, 0.5, 2.0)),
                new ProfileDefaultsModel.ModelOption("google", "Google Gemini",
                        new ModelCapabilityBadges.ModelInfo("gemini-2.5-pro", "Gemini 2.5 Pro",
                                1_000_000, 65_536, true, true, true, 1.25, 10.0)));

        // Blank query returns all models.
        assertEquals(3, ProfileDefaultsModel.filterModels(catalog, "", "").size());
        assertEquals(3, ProfileDefaultsModel.filterModels(catalog, null, "   ").size());

        // Match by model id (case-insensitive).
        List<ProfileDefaultsModel.ModelOption> byId = ProfileDefaultsModel.filterModels(catalog, "", "GPT-5");
        assertEquals(1, byId.size());
        assertEquals("gpt-5-mini", byId.get(0).info().id());

        // Match by provider display name.
        List<ProfileDefaultsModel.ModelOption> byProviderName = ProfileDefaultsModel.filterModels(catalog, "", "gemini");
        assertEquals(1, byProviderName.size());
        assertEquals("google", byProviderName.get(0).providerSlug());

        // Provider filter narrows results cleanly.
        List<ProfileDefaultsModel.ModelOption> anthropicOnly =
                ProfileDefaultsModel.filterModels(catalog, "anthropic", "");
        assertEquals(1, anthropicOnly.size());
        assertEquals("claude-sonnet-4", anthropicOnly.get(0).info().id());

        // Combined provider + query with no match returns empty.
        assertTrue(ProfileDefaultsModel.filterModels(catalog, "openai", "claude").isEmpty());
        assertTrue(ProfileDefaultsModel.filterModels(catalog, "", "nonexistent-xyz").isEmpty());
        assertTrue(ProfileDefaultsModel.filterModels(null, "", "gpt").isEmpty());
    }

    @Test
    public void toolsetFilterBulkActionsAndThresholdWorkCorrectly() {
        ProfileDefaultsModel small = new ProfileDefaultsModel();
        small.load("p", "m", "", Collections.singletonList("web"),
                Arrays.asList("web", "files", "terminal", "memory", "git", "calendar"));
        assertFalse("6 toolsets stays compact without filter bar", small.shouldShowToolsetFilter());

        ProfileDefaultsModel large = new ProfileDefaultsModel();
        large.load("p", "m", "", Arrays.asList("web", "git"),
                Arrays.asList("web", "files", "terminal", "memory", "git", "calendar", "browser"));
        assertTrue("7 toolsets shows live search filter", large.shouldShowToolsetFilter());
        assertEquals(2, large.enabledToolsetCount());
        assertEquals(7, large.totalToolsetCount());

        // Filter toolsets case-insensitively and handle negative/no-match queries.
        assertEquals(Collections.singletonList("calendar"), large.filterToolsets("CAL"));
        assertEquals(7, large.filterToolsets("   ").size());
        assertTrue(large.filterToolsets("zzzz").isEmpty());

        // Enable all / disable all update counts & dirty state.
        large.enableAllToolsets();
        assertEquals(7, large.enabledToolsetCount());
        assertTrue(large.isDirty());

        large.disableAllToolsets();
        assertEquals(0, large.enabledToolsetCount());
        assertTrue(large.isDirty());
    }

    @Test
    public void disableAllPreservesNonCatalogToolsetsAndAllowsCleanRevert() {
        ProfileDefaultsModel model = new ProfileDefaultsModel();
        model.load(
                "anthropic",
                "claude-sonnet-4",
                "auto",
                Arrays.asList("web", "hidden_plugin"),
                Arrays.asList("web", "files", "terminal"));

        assertFalse(model.isDirty());
        assertEquals(1, model.enabledToolsetCount());

        // Disabling all visible toolsets must preserve non-catalog enabled toolsets.
        model.disableAllToolsets();
        assertEquals(0, model.enabledToolsetCount());
        assertTrue(model.isDirty());
        assertTrue(model.selectedToolsets().contains("hidden_plugin"));
        assertFalse(model.selectedToolsets().contains("web"));

        // Re-enabling the original visible toolset cleanly reverts dirty state to false.
        model.setToolsetEnabled("web", true);
        assertEquals(1, model.enabledToolsetCount());
        assertFalse(model.isDirty());
    }

    @Test
    public void reasoningEffortNormalizationAndHelperResourceMappingHandleEdgeInputs() {
        assertEquals(ProfileDefaultsModel.EFFORT_AUTO, ProfileDefaultsModel.normalizeEffort(null));
        assertEquals(ProfileDefaultsModel.EFFORT_AUTO, ProfileDefaultsModel.normalizeEffort(""));
        assertEquals(ProfileDefaultsModel.EFFORT_AUTO, ProfileDefaultsModel.normalizeEffort("  auto  "));
        assertEquals(ProfileDefaultsModel.EFFORT_AUTO, ProfileDefaultsModel.normalizeEffort("ultra-unknown"));
        assertEquals(ProfileDefaultsModel.EFFORT_LOW, ProfileDefaultsModel.normalizeEffort("LOW"));
        assertEquals(ProfileDefaultsModel.EFFORT_MEDIUM, ProfileDefaultsModel.normalizeEffort(" Medium "));
        assertEquals(ProfileDefaultsModel.EFFORT_HIGH, ProfileDefaultsModel.normalizeEffort("HIGH"));

        assertEquals(R.string.profile_defaults_reasoning_desc_auto, ProfileDefaultsModel.effortHelperRes(""));
        assertEquals(R.string.profile_defaults_reasoning_desc_low, ProfileDefaultsModel.effortHelperRes("low"));
        assertEquals(R.string.profile_defaults_reasoning_desc_medium, ProfileDefaultsModel.effortHelperRes("medium"));
        assertEquals(R.string.profile_defaults_reasoning_desc_high, ProfileDefaultsModel.effortHelperRes("HIGH"));
        assertEquals(R.string.profile_defaults_reasoning_desc_auto, ProfileDefaultsModel.effortHelperRes("bogus"));

        assertEquals(R.string.brain_profile_reasoning_auto, ProfileDefaultsModel.effortLabelRes(null));
        assertEquals(R.string.brain_profile_reasoning_low, ProfileDefaultsModel.effortLabelRes("low"));
        assertEquals(R.string.brain_profile_reasoning_medium, ProfileDefaultsModel.effortLabelRes("medium"));
        assertEquals(R.string.brain_profile_reasoning_high, ProfileDefaultsModel.effortLabelRes("high"));
    }

    @Test
    public void soulMetricsAndDirtyCheckHandleNullEmptyAndLongText() {
        assertEquals(0, ProfileDefaultsModel.soulCharCount(null));
        assertEquals(0, ProfileDefaultsModel.soulCharCount(""));
        assertEquals(12, ProfileDefaultsModel.soulCharCount("Be concise.\n"));
        assertEquals("", ProfileDefaultsModel.soulPreview(null));
        assertEquals("Line one line two", ProfileDefaultsModel.soulPreview("  Line one\n\nline   two  "));

        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 250; i++) longText.append('a');
        String preview = ProfileDefaultsModel.soulPreview(longText.toString());
        assertEquals(ProfileDefaultsModel.SOUL_PREVIEW_MAX_CHARS, preview.length());
        assertTrue(preview.endsWith("…"));

        // R15.4.2: Surrogate pair at boundary and bidi override are handled cleanly via PlainTextSanitizer.
        String emojiBoundary = "a".repeat(ProfileDefaultsModel.SOUL_PREVIEW_MAX_CHARS - 2) + "\uD83D\uDE00tail";
        String safeEmoji = ProfileDefaultsModel.soulPreview(emojiBoundary);
        assertTrue(safeEmoji.length() <= ProfileDefaultsModel.SOUL_PREVIEW_MAX_CHARS);
        assertTrue(safeEmoji.endsWith("…"));
        assertFalse("Must never split surrogate pair at boundary",
                Character.isHighSurrogate(safeEmoji.charAt(safeEmoji.length() - 2)));
        assertEquals("Be concise.", ProfileDefaultsModel.soulPreview("Be\u202E\u034F concise."));

        assertFalse(ProfileDefaultsModel.isSoulDirty(null, ""));
        assertFalse(ProfileDefaultsModel.isSoulDirty("same", "same"));
        assertTrue(ProfileDefaultsModel.isSoulDirty("same", "changed"));
    }
}
