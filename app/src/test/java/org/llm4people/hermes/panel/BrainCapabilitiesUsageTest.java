package org.llm4people.hermes.panel;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.llm4people.hermes.Diagnostics;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pure-JVM regression tests for R8.4.3 & R8.5.1:
 * - Verifies BrainCapabilities canonical constants resolve cleanly through BrainCapabilities.fromFlags(...)
 *   and that wired callers reference BrainCapabilities constants instead of raw capability string literals.
 * - Verifies retired ws_table_filter_a11y string is removed from both FR and EN workspace resources.
 */
public final class BrainCapabilitiesUsageTest {

    @Before
    public void silenceDiagnostics() {
        Diagnostics.clear();
        Diagnostics.setSink((level, tag, message) -> {});
    }

    @After
    public void resetDiagnostics() {
        Diagnostics.clear();
        Diagnostics.setSink(null);
    }

    private static Path resolveAppRoot() {
        Path direct = Path.of("src/main");
        return Files.isDirectory(direct) ? direct : Path.of("app/src/main");
    }

    private static String readUtf8(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    @Test
    public void canonicalCapabilityConstantsResolveViaFromFlagsAndNoRawLiteralsRemainInCallers() throws Exception {
        Map<String, Boolean> flags = new LinkedHashMap<>();
        flags.put(BrainCapabilities.RUN_TOOL_EXCLUSIONS, Boolean.TRUE);
        flags.put(BrainCapabilities.RUN_TOOLSET_SELECTION, Boolean.TRUE);
        flags.put(BrainCapabilities.PROFILE_DEFAULTS, Boolean.TRUE);
        flags.put(BrainCapabilities.SESSION_SEARCH, Boolean.TRUE);

        BrainCapabilities caps = BrainCapabilities.fromFlags(flags);
        assertTrue(caps.supports(BrainCapabilities.RUN_TOOL_EXCLUSIONS));
        assertTrue(caps.supports(BrainCapabilities.RUN_TOOLSET_SELECTION));
        assertTrue(caps.supports(BrainCapabilities.PROFILE_DEFAULTS));
        assertTrue(caps.profileDefaults());
        assertTrue(caps.supports(BrainCapabilities.SESSION_SEARCH));
        assertTrue(caps.sessionSearch());

        assertFalse(BrainCapabilities.empty().supports(BrainCapabilities.RUN_TOOL_EXCLUSIONS));
        assertFalse(BrainCapabilities.empty().supports(BrainCapabilities.RUN_TOOLSET_SELECTION));
        assertFalse(BrainCapabilities.empty().profileDefaults());
        assertFalse(BrainCapabilities.empty().sessionSearch());

        Path main = resolveAppRoot();
        String sessionSettings = readUtf8(main.resolve("java/org/llm4people/hermes/panel/SessionSettings.java"));
        assertTrue(sessionSettings.contains("BrainCapabilities.RUN_TOOL_EXCLUSIONS"));
        assertTrue(sessionSettings.contains("BrainCapabilities.RUN_TOOLSET_SELECTION"));
        assertFalse(sessionSettings.contains("\"run_tool_exclusions\""));
        assertFalse(sessionSettings.contains("\"run_toolset_selection\""));

        String appLanguage = readUtf8(main.resolve("java/org/llm4people/hermes/AppLanguage.java"));
        assertTrue(appLanguage.contains("BrainCapabilities.PROFILE_DEFAULTS"));
        assertFalse(appLanguage.contains("\"profile_defaults\""));

        String universalSearch = readUtf8(main.resolve("java/org/llm4people/hermes/search/UniversalSearchSheet.java"));
        assertTrue(universalSearch.contains("BrainCapabilities.SESSION_SEARCH"));
        assertFalse(universalSearch.contains("\"session_search\""));
    }

    @Test
    public void retiredTableFilterA11yStringIsPrunedFromFrenchAndEnglishResources() throws Exception {
        Path res = resolveAppRoot().resolve("res");
        String fr = readUtf8(res.resolve("values/i18n_workspace_strings.xml"));
        String en = readUtf8(res.resolve("values-en/i18n_workspace_strings.xml"));
        assertFalse("FR i18n_workspace_strings.xml must not retain ws_table_filter_a11y",
                fr.contains("ws_table_filter_a11y"));
        assertFalse("EN i18n_workspace_strings.xml must not retain ws_table_filter_a11y",
                en.contains("ws_table_filter_a11y"));
    }
}
