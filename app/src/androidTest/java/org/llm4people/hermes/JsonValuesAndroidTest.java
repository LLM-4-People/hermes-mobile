package org.llm4people.hermes;

import android.test.AndroidTestCase;

import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.hub.NotesTasksModel;

import java.util.List;
import java.util.Map;

/**
 * Android instrumentation tests exercising {@link JsonValues} against the real Android
 * {@link JSONObject} and {@link JSONArray} runtime implementations.
 */
@SuppressWarnings("deprecation")
public final class JsonValuesAndroidTest extends AndroidTestCase {
    public void testToMapConvertsNestedObjectsArraysPrimitivesEmptyContainersAndStripsJsonNull() throws Exception {
        assertNull(JsonValues.toMap(null));
        assertTrue(JsonValues.toMap(new JSONObject()).isEmpty());

        JSONObject inner = new JSONObject()
                .put("keep", "montreal")
                .put("dropped", JSONObject.NULL)
                .put("emptyObj", new JSONObject())
                .put("emptyArr", new JSONArray());

        JSONArray deepArray = new JSONArray()
                .put("leaf")
                .put(JSONObject.NULL)
                .put(inner);

        JSONArray matrix = new JSONArray()
                .put(deepArray)
                .put(42)
                .put(JSONObject.NULL)
                .put(true);

        JSONObject root = new JSONObject()
                .put("title", "Hermes")
                .put("count", 7)
                .put("ratio", 1.5d)
                .put("enabled", true)
                .put("nullField", JSONObject.NULL)
                .put("emptyObj", new JSONObject())
                .put("emptyArr", new JSONArray())
                .put("matrix", matrix);

        Map<String, Object> map = JsonValues.toMap(root);
        assertNotNull(map);
        assertEquals("Hermes", map.get("title"));
        assertEquals(7, map.get("count"));
        assertEquals(1.5d, map.get("ratio"));
        assertEquals(Boolean.TRUE, map.get("enabled"));
        assertFalse(map.containsKey("nullField"));

        Map<?, ?> emptyObj = JsonValues.asMap(map.get("emptyObj"));
        assertNotNull(emptyObj);
        assertTrue(emptyObj.isEmpty());

        List<?> emptyArr = JsonValues.asList(map.get("emptyArr"));
        assertNotNull(emptyArr);
        assertTrue(emptyArr.isEmpty());

        List<?> outer = JsonValues.asList(map.get("matrix"));
        assertNotNull(outer);
        assertEquals(3, outer.size());
        assertEquals(42, outer.get(1));
        assertEquals(Boolean.TRUE, outer.get(2));

        List<?> nested = JsonValues.asList(outer.get(0));
        assertNotNull(nested);
        assertEquals(2, nested.size());
        assertEquals("leaf", nested.get(0));

        Map<?, ?> nestedMap = JsonValues.asMap(nested.get(1));
        assertNotNull(nestedMap);
        assertEquals("montreal", nestedMap.get("keep"));
        assertFalse(nestedMap.containsKey("dropped"));
        assertTrue(JsonValues.asMap(nestedMap.get("emptyObj")).isEmpty());
        assertTrue(JsonValues.asList(nestedMap.get("emptyArr")).isEmpty());
    }

    public void testConvertValueHandlesTopLevelJsonArrayAndJsonNull() throws Exception {
        assertNull(JsonValues.convertValue(JSONObject.NULL));
        assertNull(JsonValues.convertValue(null));

        JSONArray empty = new JSONArray();
        List<?> convertedEmpty = JsonValues.asList(JsonValues.convertValue(empty));
        assertNotNull(convertedEmpty);
        assertTrue(convertedEmpty.isEmpty());

        JSONArray array = new JSONArray()
                .put("first")
                .put(JSONObject.NULL)
                .put(new JSONArray().put(1).put(JSONObject.NULL).put(2))
                .put(new JSONObject().put("k", "v").put("n", JSONObject.NULL))
                .put(false);

        List<?> converted = JsonValues.asList(JsonValues.convertValue(array));
        assertNotNull(converted);
        assertEquals(4, converted.size());
        assertEquals("first", converted.get(0));

        List<?> innerNums = JsonValues.asList(converted.get(1));
        assertNotNull(innerNums);
        assertEquals(2, innerNums.size());
        assertEquals(1, innerNums.get(0));
        assertEquals(2, innerNums.get(1));

        Map<?, ?> innerObj = JsonValues.asMap(converted.get(2));
        assertNotNull(innerObj);
        assertEquals(1, innerObj.size());
        assertEquals("v", innerObj.get("k"));
        assertFalse(innerObj.containsKey("n"));

        assertEquals(Boolean.FALSE, converted.get(3));
    }

    public void testNotesTasksModelJsonObjectOverloadsMatchMapParsersWithJsonNull() throws Exception {
        // Null and empty envelopes fail closed cleanly.
        assertTrue(NotesTasksModel.parseNotes(null).isEmpty());
        assertTrue(NotesTasksModel.parseNotes(new JSONObject()).isEmpty());
        assertEquals(0, NotesTasksModel.parseReminders(null).total());
        assertTrue(NotesTasksModel.parseReceivedShares(null).isEmpty());

        JSONObject notesPayload = new JSONObject().put("notes", new JSONArray()
                .put(JSONObject.NULL)
                .put(new JSONObject()
                        .put("id", "n-1")
                        .put("title", "Regular")
                        .put("content", "First\nSecond")
                        .put("pinned", false)
                        .put("updated_at", JSONObject.NULL))
                .put(new JSONObject()
                        .put("note_id", "n-2")
                        .put("title", "Pinned")
                        .put("body", "Priority")
                        .put("pinned", true)
                        .put("updated_at", "2026-11-24")));
        List<NotesTasksModel.NoteRow> notes = NotesTasksModel.parseNotes(notesPayload);
        assertEquals(NotesTasksModel.parseNotesMap(JsonValues.toMap(notesPayload)), notes);
        assertEquals(2, notes.size());
        assertEquals("n-2", notes.get(0).id());
        assertTrue(notes.get(0).pinned());
        assertEquals("First Second", notes.get(1).preview());

        JSONObject remindersPayload = new JSONObject().put("jobs", new JSONArray()
                .put(JSONObject.NULL)
                .put(new JSONObject()
                        .put("id", "j-ok")
                        .put("name", "Morning")
                        .put("prompt", "Briefing")
                        .put("schedule_display", "08:00")
                        .put("enabled", true)
                        .put("last_status", JSONObject.NULL))
                .put(new JSONObject()
                        .put("job_id", "j-err")
                        .put("title", "Sync")
                        .put("message", "Retry")
                        .put("schedule", "02:00")
                        .put("enabled", true)
                        .put("last_status", "FAILED")));
        NotesTasksModel.ReminderOverview reminders = NotesTasksModel.parseReminders(remindersPayload);
        assertEquals(NotesTasksModel.parseRemindersMap(JsonValues.toMap(remindersPayload)), reminders);
        assertEquals(2, reminders.total());
        assertEquals(1, reminders.attentionCount());
        assertEquals("j-err", reminders.rows().get(0).id());

        JSONObject sharesPayload = new JSONObject().put("data", new JSONArray()
                .put(JSONObject.NULL)
                .put(new JSONObject()
                        .put("share_id", "s-1")
                        .put("resource_kind", "NOTE")
                        .put("title", JSONObject.NULL)
                        .put("resource_id", "res-42")
                        .put("owner_profile", "alice")
                        .put("permission", "READ")));
        List<NotesTasksModel.SharedRow> shares = NotesTasksModel.parseReceivedShares(sharesPayload);
        assertEquals(NotesTasksModel.parseReceivedSharesMap(JsonValues.toMap(sharesPayload)), shares);
        assertEquals(1, shares.size());
        assertEquals("s-1", shares.get(0).shareId());
        assertEquals("res-42", shares.get(0).displayTitle());
        assertEquals("alice", shares.get(0).ownerProfile());
    }
}
