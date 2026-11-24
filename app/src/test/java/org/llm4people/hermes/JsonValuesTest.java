package org.llm4people.hermes;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public final class JsonValuesTest {
    @Test
    public void toMapAndConvertValueHandleNullsNestedArraysAndJsonNull() {
        assertNull(JsonValues.toMap(null));
        assertNull(JsonValues.convertValue(null));
        assertNull(JsonValues.convertValue(JSONObject.NULL));

        Map<String, Object> innerMap = new LinkedHashMap<>();
        innerMap.put("keep", "value");
        innerMap.put("dropNull", null);
        innerMap.put("dropJsonNull", JSONObject.NULL);

        List<Object> deepNestedArray = new ArrayList<>();
        deepNestedArray.add("a");
        deepNestedArray.add(null);
        deepNestedArray.add(JSONObject.NULL);
        deepNestedArray.add(innerMap);

        List<Object> outerArray = new ArrayList<>();
        outerArray.add(deepNestedArray);
        outerArray.add(42);
        outerArray.add(null);
        outerArray.add(JSONObject.NULL);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("matrix", outerArray);
        root.put("dropped", JSONObject.NULL);
        root.put("flag", Boolean.TRUE);

        @SuppressWarnings("unchecked")
        Map<String, Object> converted = (Map<String, Object>) JsonValues.convertValue(root);
        assertFalse(converted.containsKey("dropped"));
        assertEquals(Boolean.TRUE, converted.get("flag"));

        List<?> matrix = JsonValues.asList(converted.get("matrix"));
        assertEquals(2, matrix.size());
        assertEquals(42, matrix.get(1));

        List<?> nested = JsonValues.asList(matrix.get(0));
        assertEquals(2, nested.size());
        assertEquals("a", nested.get(0));

        Map<?, ?> convertedInner = JsonValues.asMap(nested.get(1));
        assertEquals(1, convertedInner.size());
        assertEquals("value", convertedInner.get("keep"));
        assertFalse(convertedInner.containsKey("dropNull"));
        assertFalse(convertedInner.containsKey("dropJsonNull"));
    }

    @Test
    public void typedAccessorsHandlePositiveAndNegativeInputs() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", "  Hermes  ");
        row.put("count", 5);
        row.put("nested", Map.of("k", "v"));
        row.put("items", Arrays.asList("one", "two"));

        assertEquals("Hermes", JsonValues.mapText(row, "name"));
        assertEquals("", JsonValues.mapText(row, "count"));
        assertEquals("", JsonValues.mapText(row, "missing"));
        assertEquals("", JsonValues.mapText(null, "name"));

        assertSame(row.get("nested"), JsonValues.asMap(row.get("nested")));
        assertNull(JsonValues.asMap("not-a-map"));
        assertNull(JsonValues.asMap(null));

        assertSame(row.get("items"), JsonValues.asList(row.get("items")));
        assertNull(JsonValues.asList(123));
        assertNull(JsonValues.asList(null));
    }

    @Test
    public void quoteEscapesRfc8259StringsAndControlCharacters() {
        assertEquals("null", JsonValues.quote(null));
        assertEquals("\"\"", JsonValues.quote(""));
        assertEquals("\"hello\"", JsonValues.quote("hello"));
        assertEquals("\"a\\\"b\\\\c\"", JsonValues.quote("a\"b\\c"));
        assertEquals("\"\\b\\f\\n\\r\\t\"", JsonValues.quote("\b\f\n\r\t"));
        assertEquals("\"\\u0000\\u001f\"", JsonValues.quote("\u0000\u001f"));
        assertEquals("\"Montréal ☕\"", JsonValues.quote("Montréal ☕"));
    }
}
