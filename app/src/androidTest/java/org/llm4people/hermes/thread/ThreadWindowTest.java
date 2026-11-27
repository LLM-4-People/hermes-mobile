package org.llm4people.hermes.thread;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import junit.framework.TestCase;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ThreadWindowTest extends TestCase {
    private static JSONObject row(String segment, int id) {
        return ThreadData.put(ThreadData.put(new JSONObject(), "id", id), "session_id", segment);
    }

    public void testReloadReachesTheActualOlderSourceWhenCompactedTipReusesItsRowId() throws Exception {
        JSONArray old = new JSONArray(), latest = new JSONArray();
        for (int id = 1; id <= 40; id++) {
            old.put(row("source", id));
            latest.put(row("after-compaction", id));
        }
        List<String> reads = new ArrayList<>();
        ThreadWindow loaded = ThreadWindow.read(path -> {
            reads.add(path);
            return ThreadData.put(new JSONObject(), "data", reads.size() == 1 ? latest : old);
        }, "/messages?limit=", 0, 40, -1, ThreadData.rowId(old.getJSONObject(0)), () -> true, ThreadData.PAGE);
        assertEquals(List.of("/messages?limit=40&offset=0", "/messages?limit=40&offset=40"), reads);
        assertEquals(80, loaded.rawCount());
        JSONArray history = loaded.rows();
        assertEquals(80, history.length());
        assertEquals("source", history.getJSONObject(0).getString("session_id"));
        assertEquals("after-compaction", history.getJSONObject(79).getString("session_id"));
    }

    public void testLookupStopsOnAccountOrThreadDeparture() throws Exception {
        JSONArray rows = new JSONArray();
        for (int i = 0; i < 40; i++) rows.put(row("one", i));
        List<String> reads = new ArrayList<>();
        try {
            ThreadWindow.read(path -> {
                reads.add(path);
                return ThreadData.put(new JSONObject(), "data", rows);
            }, "/messages?limit=", 0, 80, -1, ThreadData.rowId(row("older", 1)), reads::isEmpty, ThreadData.PAGE);
            fail("Read after departure");
        } catch (IOException expected) { assertEquals(1, reads.size()); }
    }
}
