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

    @Test
    public void saturateIntAndParsersHandleOverflowNegativeFloatsStringsAndNonFiniteValues() {
        assertEquals(0, JsonValues.saturateInt(0L));
        assertEquals(Integer.MAX_VALUE, JsonValues.saturateInt(Integer.MAX_VALUE));
        assertEquals(Integer.MIN_VALUE, JsonValues.saturateInt(Integer.MIN_VALUE));
        assertEquals(Integer.MAX_VALUE, JsonValues.saturateInt(4_294_967_296L));
        assertEquals(Integer.MAX_VALUE, JsonValues.saturateInt(Long.MAX_VALUE));
        assertEquals(Integer.MIN_VALUE, JsonValues.saturateInt(-4_294_967_200L));
        assertEquals(Integer.MIN_VALUE, JsonValues.saturateInt(Long.MIN_VALUE));

        assertNull(JsonValues.parseIntOrNull(null));
        assertNull(JsonValues.parseIntOrNull(JSONObject.NULL));
        assertNull(JsonValues.parseIntOrNull(""));
        assertNull(JsonValues.parseIntOrNull("   "));
        assertNull(JsonValues.parseIntOrNull("not-a-number"));
        assertNull(JsonValues.parseIntOrNull(Boolean.TRUE));
        assertNull(JsonValues.parseIntOrNull(Double.NaN));
        assertNull(JsonValues.parseIntOrNull(Double.POSITIVE_INFINITY));
        assertNull(JsonValues.parseIntOrNull(Double.NEGATIVE_INFINITY));
        assertNull(JsonValues.parseIntOrNull(Float.NaN));
        assertNull(JsonValues.parseIntOrNull(Float.POSITIVE_INFINITY));
        assertNull(JsonValues.parseIntOrNull("NaN"));
        assertNull(JsonValues.parseIntOrNull("Infinity"));
        assertNull(JsonValues.parseIntOrNull("-Infinity"));

        assertEquals(42, JsonValues.parseInt(42, -1));
        assertEquals(7, JsonValues.parseInt((short) 7, -1));
        assertEquals(3, JsonValues.parseInt((byte) 3, -1));
        assertEquals(Integer.MAX_VALUE, JsonValues.parseInt(4_294_967_296L, -1));
        assertEquals(Integer.MAX_VALUE, JsonValues.parseInt(3_000_000_000L, -1));
        assertEquals(Integer.MIN_VALUE, JsonValues.parseInt(-4_294_967_200L, -1));
        assertEquals(Integer.MAX_VALUE, JsonValues.parseInt(4_294_967_296.0d, -1));
        assertEquals(Integer.MIN_VALUE, JsonValues.parseInt(-4_294_967_200.0d, -1));
        assertEquals(181, JsonValues.parseInt(180.6d, -1));
        assertEquals(180, JsonValues.parseInt(180.4f, -1));
        assertEquals(180, JsonValues.parseInt("180", -1));
        assertEquals(-15, JsonValues.parseInt("  -15  ", -1));
        assertEquals(181, JsonValues.parseInt("180.6", -1));
        assertEquals(Integer.MAX_VALUE, JsonValues.parseInt("1e20", -1));
        assertEquals(Integer.MIN_VALUE, JsonValues.parseInt("-1e20", -1));
        assertEquals(Integer.MAX_VALUE, JsonValues.parseInt("4294967296", -1));
        assertEquals(Integer.MIN_VALUE, JsonValues.parseInt("-4294967200", -1));
        assertEquals(300, JsonValues.parseInt(Double.NaN, 300));
        assertEquals(300, JsonValues.parseInt(Double.POSITIVE_INFINITY, 300));
        assertEquals(300, JsonValues.parseInt("NaN", 300));
        assertEquals(300, JsonValues.parseInt("Infinity", 300));

        assertEquals(0, JsonValues.parseNonNegativeInt(-5, 9));
        assertEquals(0, JsonValues.parseNonNegativeInt(-4_294_967_200L, 9));
        assertEquals(0, JsonValues.parseNonNegativeInt("-4294967200", 9));
        assertEquals(Integer.MAX_VALUE, JsonValues.parseNonNegativeInt(4_294_967_296L, 0));
        assertEquals(Integer.MAX_VALUE, JsonValues.parseNonNegativeInt(3_000_000_000L, 0));
        assertEquals(7, JsonValues.parseNonNegativeInt(Double.NaN, 7));
        assertEquals(7, JsonValues.parseNonNegativeInt(Double.POSITIVE_INFINITY, 7));
        assertEquals(0, JsonValues.parseNonNegativeInt(null, -3));
    }
}
