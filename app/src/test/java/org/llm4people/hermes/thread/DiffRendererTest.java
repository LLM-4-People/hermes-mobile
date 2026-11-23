package org.llm4people.hermes.thread;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public final class DiffRendererTest {

    @Test public void structuredPreviewDiffBuildsHunksStatsRiskAndUnifiedText() {
        var hunk = new DiffRenderer.HunkInput(42, 42, List.of(" ctx", "-oldLine();", "+newLine();", "+extra();"));
        DiffRenderer.DiffModel model = DiffRenderer.fromHunks(
                "app/src/MainActivity.java", "java", List.of(hunk),
                2, 1, false, "Patch MainActivity", "Mettre à jour MainActivity", "medium");

        assertNotNull(model);
        assertEquals("app/src/MainActivity.java", model.path());
        assertEquals("java", model.language());
        assertEquals(2, model.additions());
        assertEquals(1, model.deletions());
        assertEquals("+2 / -1", DiffRenderer.statsText(model));
        assertEquals(org.llm4people.hermes.R.string.diff_risk_medium, DiffRenderer.riskLabelRes(model.risk()));
        assertFalse(model.truncated());
        assertEquals(5, model.lines().size());
        assertEquals(DiffRenderer.LineKind.HUNK, model.lines().get(0).kind());
        assertEquals(DiffRenderer.LineKind.CONTEXT, model.lines().get(1).kind());
        assertEquals(DiffRenderer.LineKind.REMOVE, model.lines().get(2).kind());
        assertEquals(DiffRenderer.LineKind.ADD, model.lines().get(3).kind());

        String unified = DiffRenderer.toUnifiedText(model);
        assertTrue(unified.contains("--- a/app/src/MainActivity.java"));
        assertTrue(unified.contains("+++ b/app/src/MainActivity.java"));
        assertTrue(unified.contains("-oldLine();"));
        assertTrue(unified.contains("+extra();"));
    }

    @Test public void legacyEditPayloadRendersAddedAndRemovedLinesCleanly() {
        DiffRenderer.DiffModel edit = DiffRenderer.fromEditStrings(
                "note.txt", "Ancienne ligne", "Nouvelle ligne 1\nNouvelle ligne 2", "", "", "", "low");
        assertNotNull(edit);
        assertEquals("note.txt", edit.path());
        assertEquals(2, edit.additions());
        assertEquals(1, edit.deletions());
        assertEquals("+2 / -1", DiffRenderer.statsText(edit));
        assertEquals(org.llm4people.hermes.R.string.diff_risk_low, DiffRenderer.riskLabelRes(edit.risk()));
        assertTrue(edit.lines().stream().anyMatch(l -> l.kind() == DiffRenderer.LineKind.REMOVE && l.text().equals("-Ancienne ligne")));
        assertTrue(edit.lines().stream().anyMatch(l -> l.kind() == DiffRenderer.LineKind.ADD && l.text().equals("+Nouvelle ligne 2")));

        DiffRenderer.DiffModel patchEdit = DiffRenderer.fromEditStrings(
                "", "", "", "--- a/foo.py\n+++ b/foo.py\n@@ -1 +1 @@\n-x = 1\n+x = 2", "", "", "high");
        assertNotNull(patchEdit);
        assertEquals("foo.py", patchEdit.path());
        assertEquals(1, patchEdit.additions());
        assertEquals(1, patchEdit.deletions());
        assertEquals(org.llm4people.hermes.R.string.diff_risk_high, DiffRenderer.riskLabelRes(patchEdit.risk()));
    }

    @Test public void hunksClassifyAddedPlusPlusAndRemovedMinusMinusLinesAccurately() {
        var hunk = new DiffRenderer.HunkInput(1, 1, List.of(
                "--- old yaml divider",
                "---",
                "---flag",
                "+++count;",
                "++x;"));
        DiffRenderer.DiffModel model = DiffRenderer.fromHunks(
                "script.sql", "sql", List.of(hunk), -1, -1, false, "", "", "low");
        assertNotNull(model);
        assertEquals(2, model.additions());
        assertEquals(3, model.deletions());
        assertEquals(DiffRenderer.LineKind.REMOVE, model.lines().get(1).kind());
        assertEquals(DiffRenderer.LineKind.REMOVE, model.lines().get(2).kind());
        assertEquals(DiffRenderer.LineKind.REMOVE, model.lines().get(3).kind());
        assertEquals(DiffRenderer.LineKind.ADD, model.lines().get(4).kind());
        assertEquals(DiffRenderer.LineKind.ADD, model.lines().get(5).kind());

        DiffRenderer.DiffModel fromEdit = DiffRenderer.fromEditStrings(
                "doc.md", "---\n-- SQL comment", "++count;\n+++more", "", "", "", "low");
        assertNotNull(fromEdit);
        assertEquals(2, fromEdit.deletions());
        assertEquals(2, fromEdit.additions());
    }

    @Test public void malformedEmptyAndHugePayloadsDegradeSafelyAndStayBounded() {
        assertNull(DiffRenderer.fromEditStrings("", "", "", "", "", "", ""));
        assertNull(DiffRenderer.fromEditStrings(null, null, null, null, null, null, null));
        assertNull(DiffRenderer.fromHunks("", "", null, -1, -1, false, null, null, "bogus"));
        assertEquals(0, DiffRenderer.riskLabelRes("critical-unknown"));
        assertEquals("", DiffRenderer.riskLabel(null, "critical-unknown"));
        assertEquals("", DiffRenderer.statsText(null));

        List<String> hugeLines = new ArrayList<>();
        for (int i = 0; i < 300; i++) hugeLines.add("+ligne " + i);
        DiffRenderer.DiffModel huge = DiffRenderer.fromHunks(
                "big.txt", "txt", List.of(new DiffRenderer.HunkInput(1, 1, hugeLines)),
                -1, -1, false, "", "", "");
        assertNotNull(huge);
        assertTrue(huge.truncated());
        assertEquals(300, huge.additions());
        assertTrue(huge.lines().size() <= DiffRenderer.MAX_PREVIEW_LINES);
        assertTrue(DiffRenderer.toUnifiedText(huge).length() <= DiffRenderer.MAX_FULL_CHARS);
    }
}
