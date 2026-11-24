package org.llm4people.hermes.panel;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Pure-JVM regression tests pinning the @impeccable Material 3 polish contracts across
 * MemoryPanel, SkillStudio, WorkspaceFilesPanel, and UsagePanel.
 */
public final class BrainPanelsPolishContractTest {

    @Test
    public void memoryPanelUsesFilledAddScrollableActionsDangerStateListAndScrollableDiff() throws Exception {
        String src = readPanel("MemoryPanel.java");
        assertTrue("Add memory CTA promoted to Ui.filledButton", src.contains("Ui.filledButton("));
        assertTrue("Entry action row wrapped in horizontal scroll", src.contains("BrainUi.chipBar(panel.activity, actions)"));
        assertTrue("Delete button uses enabled/disabled danger ColorStateList",
                src.contains("new ColorStateList(") && src.contains("R.color.disabled_ink") && src.contains("R.color.danger"));
        assertTrue("Diff preview wrapped in ScrollView with Before/After cards",
                src.contains("new ScrollView(panel.activity)")
                        && src.contains("Ui.tonalCard(panel.activity)")
                        && src.contains("Ui.card(panel.activity)"));
        assertTrue("Pin checkbox explicitly sets R.color.ink",
                src.contains("pinBox.setTextColor(panel.activity.getColor(R.color.ink))"));
    }

    @Test
    public void skillStudioUsesRippleRowsProperFormFieldsWarningCardAndAccessibleHeadings() throws Exception {
        String src = readPanel("SkillStudio.java");
        assertTrue("skillRow uses Material 3 roundedRipple + forward chevron",
                src.contains("Ui.roundedRipple(panel.activity, Ui.CARD_CORNER_DP")
                        && src.contains("R.drawable.ic_navigate_forward"));
        assertTrue("Primary CTAs promoted to Ui.filledButton/Ui.tonalButton",
                src.contains("Ui.filledButton(panel.activity, panel.t(R.string.brain_skills_new)")
                        && src.contains("Ui.filledButton(panel.activity, panel.t(R.string.brain_skills_use_in_thread)")
                        && src.contains("Ui.filledButton(panel.activity, panel.t(R.string.panel_save)"));
        assertTrue("Delete button uses enabled/disabled danger ColorStateList",
                src.contains("new ColorStateList(") && src.contains("R.color.disabled_ink"));
        assertTrue("Create form uses proper IME_ACTION_NEXT fields with setLabelFor",
                src.contains("EditorInfo.IME_ACTION_NEXT")
                        && src.contains("label.setLabelFor(field.getId())")
                        && src.contains("rawMode.setTextColor(panel.activity.getColor(R.color.ink))"));
        int createStart = src.indexOf("private void openCreateForm()");
        int editStart = src.indexOf("private void openEditForm(");
        String createBody = src.substring(createStart, editStart);
        assertFalse("openCreateForm must not reuse BrainUi.searchField",
                createBody.contains("BrainUi.searchField("));
        assertTrue("Lint warning card uses warning_surface/warning stroke without raw bullet prefix",
                src.contains("R.color.warning_surface, R.color.warning, 1")
                        && !src.contains("\"• \""));
        assertTrue("Detail heading marked as accessibility heading",
                src.contains("heading.setAccessibilityHeading(true)"));
    }

    @Test
    public void workspaceFilesPanelUsesRippleCardsMiddleEllipsisAndTonalBranchSummary() throws Exception {
        String src = readPanel("WorkspaceFilesPanel.java");
        assertTrue("Clickable cards use roundedRipple + chevron + Button accessibility role",
                src.contains("Ui.roundedRipple(panel.activity, Ui.CARD_CORNER_DP")
                        && src.contains("R.drawable.ic_navigate_forward")
                        && src.contains("info.setClassName(Button.class.getName())"));
        assertTrue("View full diff promoted to Ui.tonalButton",
                src.contains("Ui.tonalButton(panel.activity, panel.t(R.string.brain_files_view_all_diff)"));
        assertTrue("Path header constrained with maxLines(2), MIDDLE ellipsis, and full contentDescription",
                src.contains("pathLabel.setMaxLines(2)")
                        && src.contains("TextUtils.TruncateAt.MIDDLE")
                        && src.contains("pathLabel.setContentDescription(location)"));
        assertTrue("Git branch summary uses Ui.tonalCard with Ui.pill counters",
                src.contains("Ui.tonalCard(panel.activity)")
                        && src.contains("Ui.pill(panel.activity"));
    }

    @Test
    public void usagePanelUsesGroupedCardsTabularSansPersistedDisclosuresAndDangerErrors() throws Exception {
        String src = readPanel("UsagePanel.java");
        assertTrue("Primary metrics grouped in Ui.card with inset dividers and technical details in Ui.tonalCard",
                src.contains("Ui.card(panel.activity)")
                        && src.contains("Ui.tonalCard(panel.activity)")
                        && src.contains("Ui.insetDivider(panel.activity, 0)"));
        assertTrue("Metrics use sans-serif-medium with tabular figures instead of Typeface.MONOSPACE",
                src.contains("sans-serif-medium")
                        && src.contains("setFontFeatureSettings(\"tnum\")")
                        && !src.contains("Typeface.MONOSPACE"));
        assertTrue("Refresh button styled as Ui.outlinedButton",
                src.contains("Ui.outlinedButton(panel.activity, panel.t(R.string.panel_refresh)"));
        assertTrue("Both disclosure states persisted with ic_prompt_disclosure",
                src.contains("localTechnicalExpanded")
                        && src.contains("serverTechnicalExpanded")
                        && src.contains("R.drawable.ic_prompt_disclosure"));
        assertTrue("Error notices colored with R.color.danger",
                src.contains("err.setTextColor(panel.activity.getColor(R.color.danger))"));
    }

    private static String readPanel(String fileName) throws Exception {
        Path cwd = Path.of("").toAbsolutePath();
        Path root = Files.isDirectory(cwd.resolve("app/src/main/java")) ? cwd : cwd.getParent();
        Path file = root.resolve("app/src/main/java/org/llm4people/hermes/panel").resolve(fileName);
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
