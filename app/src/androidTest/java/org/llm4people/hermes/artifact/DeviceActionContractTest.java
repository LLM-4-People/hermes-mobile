package org.llm4people.hermes.artifact;

import android.content.Intent;
import android.net.Uri;
import android.provider.CalendarContract;
import junit.framework.TestCase;
import org.json.JSONObject;
import java.io.IOException;
import java.time.Instant;

public class DeviceActionContractTest extends TestCase {
    static String page(String request) {
        return "{\"version\":1,\"surface\":\"page/sortie\",\"mode\":\"replace\",\"title\":\"Sortie\","
                + "\"blocks\":[{\"request\":" + request + ",\"type\":\"device\"}]}";
    }

    static final String EVENT = "{\"start\":\"2026-11-23T09:00:10-05:00\",\"kind\":\"calendar_event\","
            + "\"title\":\"Réunion ☕\",\"end\":\"2026-11-23T14:30:00Z\",\"location\":\"Bureau Érable\"}";

    static DeviceAction parse(String request) throws IOException {
        return WorkspaceLayout.parse(page(request)).blocks.get(0).request;
    }

    public void testCalendarPreviewAndExactEditorPayload() throws Exception {
        DeviceAction action = parse(EVENT);
        assertEquals("Réunion ☕\n\nDébut · 23 novembre 2026 à 09:00:10 (UTC-05:00)"
                + "\nFin · 23 novembre 2026 à 14:30:00 (UTC+00:00)\nLieu · Bureau Érable", action.preview());
        Intent intent = action.target();
        assertEquals(Intent.ACTION_INSERT, intent.getAction());
        assertEquals(CalendarContract.Events.CONTENT_URI, intent.getData());
        assertEquals("Réunion ☕", intent.getStringExtra(CalendarContract.Events.TITLE));
        assertEquals("Bureau Érable", intent.getStringExtra(CalendarContract.Events.EVENT_LOCATION));
        assertEquals(4, intent.getExtras().size());
        assertEquals(Instant.parse("2026-11-23T14:00:10Z").toEpochMilli(),
                intent.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, -1));
        assertEquals(Instant.parse("2026-11-23T14:30:00Z").toEpochMilli(),
                intent.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, -1));
        assertFalse(parse(EVENT.replace(",\"location\":\"Bureau Érable\"", "")).target()
                .hasExtra(CalendarContract.Events.EVENT_LOCATION));
    }

    public void testExactShareTextAndEncodedMapQuery() throws Exception {
        String text = "  Compte rendu 👩‍💻\n\nÀ relire.  ";
        DeviceAction share = parse("{\"text\":" + JSONObject.quote(text) + ",\"kind\":\"share_text\"}");
        assertEquals(text, share.preview());
        Intent send = share.target();
        assertEquals(Intent.ACTION_SEND, send.getAction());
        assertEquals("text/plain", send.getType());
        assertEquals(text, send.getStringExtra(Intent.EXTRA_TEXT));
        assertEquals(1, send.getExtras().size());
        assertNull(send.getData());
        assertNull(send.getComponent());
        assertEquals(0, send.getFlags());
        String query = "Café A & B #1? (45, -73) /été";
        DeviceAction map = parse("{\"kind\":\"map_search\",\"query\":" + JSONObject.quote(query) + "}");
        Intent view = map.target();
        assertEquals(query, map.preview());
        assertEquals(Intent.ACTION_VIEW, view.getAction());
        assertEquals("geo:0,0?q=" + Uri.encode(query), view.getDataString());
        assertNull(view.getData().getFragment());
        assertNull(view.getExtras());
    }

    public void testClosedRequestShapesAndTimeOrdering() throws Exception {
        for (String request : new String[]{
                "null", "[]", "{}", "{\"kind\":\"send\"}", "{\"kind\":\"share_text\",\"text\":\"  \\n\"}",
                "{\"kind\":\"share_text\",\"text\":\"x\",\"text\":\"y\"}",
                "{\"kind\":\"share_text\",\"text\":\"x\",\"query\":\"y\"}",
                "{\"kind\":\"map_search\",\"query\":\"\\u202Eabc\"}",
                "{\"kind\":\"map_search\",\"query\":\"\\uFE0F\"}",
                "{\"kind\":\"map_search\",\"query\":\"A\",\"uri\":\"x\"}",
                "{\"kind\":\"map_search\",\"query\":\"A\\nB\"}",
                "{\"kind\":\"map_search\",\"query\":false}",
                EVENT.replace("2026-11-23T09:00:10-05:00", "2026-11-23T09:00:10"),
                EVENT.replace("2026-11-23T09:00:10-05:00", "+00002026-11-23T09:00:10Z"),
                EVENT.replace("2026-11-23T14:30:00Z", "2026-11-23T14:00:10Z"),
                EVENT.replace("2026-11-23T14:30:00Z", "2026-02-30T14:30:00Z"),
                EVENT.replace("2026-11-23T14:30:00Z", "2026-11-23T14:00:09Z"),
                "{\"kind\":\"share_text\",\"text\":" + JSONObject.quote(repeat(2001)) + "}",
                "{\"kind\":\"map_search\",\"query\":" + JSONObject.quote(repeat(201)) + "}"
        }) rejected(page(request));
        rejected(page(EVENT).replace("\"type\":\"device\"", "\"type\":\"device\",\"title\":\"Autre contenu\""));
        rejected(page(EVENT).replace("\"type\":\"device\"", "\"type\":\"section\",\"title\":\"Autre contenu\""));
        assertEquals(2000, parse("{\"kind\":\"share_text\",\"text\":" + JSONObject.quote(repeat(2000)) + "}").preview().length());
    }

    public void testPayloadRevisionsAreVisibleInComparison() throws Exception {
        WorkspaceLayout a = WorkspaceLayout.parse(page(EVENT));
        WorkspaceLayout b = WorkspaceLayout.parse(page(EVENT.replace("Bureau Érable", "Salle B")));
        InstrumentChanges.Change delta = InstrumentChanges.between(a,b).get(0);
        assertTrue(delta.before().contains("Bureau Érable"));
        assertTrue(delta.after().contains("Salle B"));
        assertTrue(InstrumentChanges.between(a,a).isEmpty());
    }

    private static String repeat(int count) { return new String(new char[count]).replace('\0', 'a'); }
    private void rejected(String page) throws Exception {
        try { WorkspaceLayout.parse(page); fail("Unaccepted shape should fail validation"); }
        catch (IOException expected) {}
    }
}
