package org.llm4people.hermes;

import android.test.AndroidTestCase;

import org.json.JSONArray;
import org.json.JSONObject;

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
}
