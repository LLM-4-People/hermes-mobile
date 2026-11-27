package org.llm4people.hermes.artifact;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.llm4people.hermes.R;

public final class RichBlocksModelTest {
    @Test public void sparklineNormalizesBoundsAndRejectsInvalidSeries() throws Exception {
        SparklineView.Series line = SparklineView.Series.of(
                Arrays.asList(10.0, 20.0, 30.0, 25.0), "line", "ms", "Latence récente", 22.0, null, null);
        assertEquals(10.0, line.min, 0.0001);
        assertEquals(30.0, line.max, 0.0001);
        assertEquals(25.0, line.latest, 0.0001);
        assertEquals(15.0, line.delta, 0.0001);
        assertEquals(0.0f, line.normalized[0], 0.001f);
        assertEquals(1.0f, line.normalized[2], 0.001f);
        assertEquals(0.6f, line.normalizedTarget(), 0.001f);
        assertEquals(R.string.ws_sparkline_line, line.kindLabelRes());
        assertEquals("ms", line.unit);

        SparklineView.Series bar = SparklineView.Series.of(
                Arrays.asList(5.0, 10.0), "bar", "%", "", null, null, null);
        assertEquals(0.0, bar.min, 0.0001);
        assertEquals(0.5f, bar.normalized[0], 0.001f);
        assertEquals(R.string.ws_sparkline_bar, bar.kindLabelRes());

        // Flat series stays finite (0.5 midpoint) without divide-by-zero.
        SparklineView.Series flat = SparklineView.Series.of(Arrays.asList(7.0, 7.0, 7.0), "line", "", "", null, null, null);
        assertEquals(0.5f, flat.normalized[1], 0.001f);

        // Negative bounds: <2 points, >64 points, NaN, Infinite, invalid style, max < min.
        assertThrowsIo(() -> SparklineView.Series.of(Collections.singletonList(1.0), "line", "", "", null, null, null));
        assertThrowsIo(() -> SparklineView.Series.of(Collections.nCopies(SparklineView.MAX_POINTS + 1, 1.0), "line", "", "", null, null, null));
        assertThrowsIo(() -> SparklineView.Series.of(Arrays.asList(1.0, Double.NaN), "line", "", "", null, null, null));
        assertThrowsIo(() -> SparklineView.Series.of(Arrays.asList(1.0, Double.POSITIVE_INFINITY), "line", "", "", null, null, null));
        assertThrowsIo(() -> SparklineView.Series.of(Arrays.asList(1.0, 2.0), "pie", "", "", null, null, null));
        assertThrowsIo(() -> SparklineView.Series.of(Arrays.asList(1.0, 2.0), "line", "", "", null, 10.0, 5.0));
    }

    @Test public void sortableFilterableDataTableSortsNumericallyAndPreparesDraftOnly() throws Exception {
        List<DataTableBlock.Column> cols = Arrays.asList(
                new DataTableBlock.Column("nom", "Élément", false),
                new DataTableBlock.Column("prix", "Coût", true));
        List<List<String>> rows = Arrays.asList(
                Arrays.asList("Bêta", "100 €"),
                Arrays.asList("Alpha", "2,5 €"),
                Arrays.asList("Gamma", "12 €"));
        DataTableBlock.Model table = new DataTableBlock.Model(cols, rows, true, true, "Détaille cette ligne :");

        // Numeric sort ascending: 2.5, 12, 100
        table.toggleSort(1);
        assertEquals(Arrays.asList("Alpha", "Gamma", "Bêta"), firstCol(table.visibleRows(java.util.Locale.ROOT)));

        // Toggle same column -> descending: 100, 12, 2.5
        table.toggleSort(1);
        assertEquals(Arrays.asList("Bêta", "Gamma", "Alpha"), firstCol(table.visibleRows(java.util.Locale.ROOT)));

        // Case-insensitive filter
        table.setFilter("gam", java.util.Locale.ROOT);
        assertEquals(1, table.visibleRows(java.util.Locale.ROOT).size());
        assertEquals("Gamma", table.visibleRows(java.util.Locale.ROOT).get(0).get(0));

        // Row tap prepares editable draft text cleanly
        String draft = table.draftForRow(table.visibleRows(java.util.Locale.ROOT).get(0));
        assertTrue(draft.startsWith("Détaille cette ligne :"));
        assertTrue(draft.contains("Élément : Gamma"));
        assertTrue(draft.contains("Coût : 12 €"));

        // Negative bounds
        assertThrowsIo(() -> new DataTableBlock.Model(Collections.emptyList(), rows, true, true, ""));
        assertThrowsIo(() -> new DataTableBlock.Model(cols, Collections.singletonList(Collections.singletonList("too-short")), true, true, ""));
        assertThrowsIo(() -> new DataTableBlock.Model(cols, Collections.nCopies(DataTableBlock.MAX_ROWS + 1, Arrays.asList("a", "1")), true, true, ""));
        assertThrowsIo(() -> new DataTableBlock.Model(
                Arrays.asList(new DataTableBlock.Column("dup", "A", false), new DataTableBlock.Column("dup", "B", false)),
                rows, true, true, ""));
    }

    @Test public void metricGridFormatsUnavailableAndUnitsCleanly() {
        MetricGridBlock.Item staticMetric = new MetricGridBlock.Item("Tâches", "4", "+1", "actives", "positive", "Aujourd’hui", null);
        assertEquals("4 actives", staticMetric.formattedValue(null, ""));
        assertFalse(staticMetric.isUnavailable(""));

        MetricGridBlock.Item liveMetric = new MetricGridBlock.Item("Messages", "", "", "", "neutral", "Fil actif", "thread.messageCount");
        assertTrue(liveMetric.isUnavailable(""));
        assertFalse(liveMetric.isUnavailable("14"));
        assertEquals("14", liveMetric.formattedValue(null, "14"));
    }

    private static List<String> firstCol(List<List<String>> rows) {
        return rows.stream().map(r -> r.get(0)).toList();
    }

    private interface IoRunnable { void run() throws IOException; }

    private static void assertThrowsIo(IoRunnable runnable) {
        try {
            runnable.run();
            fail("Expected IOException");
        } catch (IOException expected) {}
    }
}
