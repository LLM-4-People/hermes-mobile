package org.llm4people.hermes;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Single Source of Truth for converting {@link JSONObject}/{@link JSONArray} trees into plain Java
 * {@link Map}/{@link List} structures (skipping {@code null} and {@link JSONObject#NULL}), safely
 * reading typed entries from parsed maps, and RFC 8259 string quoting across host JVM and Android.
 */
public final class JsonValues {
    private JsonValues() {}

    /** Recursively converts a {@link JSONObject} to a {@link LinkedHashMap}, or returns {@code null} if null. */
    public static Map<String, Object> toMap(JSONObject obj) {
        if (obj == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        Iterator<String> keys = obj.keys();
        if (keys == null) return out;
        while (keys.hasNext()) {
            String key = keys.next();
            if (key == null) continue;
            Object val = obj.opt(key);
            if (isNull(val)) continue;
            Object converted = convertValue(val);
            if (converted != null) out.put(key, converted);
        }
        return out;
    }

    /** Recursively converts JSON values (objects, nested arrays, maps, lists) while stripping {@link JSONObject#NULL}. */
    public static Object convertValue(Object val) {
        if (isNull(val)) return null;
        if (val instanceof JSONObject obj) return toMap(obj);
        if (val instanceof JSONArray arr) {
            List<Object> list = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                Object converted = convertValue(arr.opt(i));
                if (converted != null) list.add(converted);
            }
            return list;
        }
        if (val instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null) continue;
                Object converted = convertValue(entry.getValue());
                if (converted != null) out.put(String.valueOf(entry.getKey()), converted);
            }
            return out;
        }
        if (val instanceof List<?> rawList) {
            List<Object> list = new ArrayList<>(rawList.size());
            for (Object elem : rawList) {
                Object converted = convertValue(elem);
                if (converted != null) list.add(converted);
            }
            return list;
        }
        return val;
    }

    /** Returns {@code raw} as a {@link Map} or {@code null} if not a map. */
    public static Map<?, ?> asMap(Object raw) {
        return raw instanceof Map<?, ?> m ? m : null;
    }

    /** Returns {@code raw} as a {@link List} or {@code null} if not a list. */
    public static List<?> asList(Object raw) {
        return raw instanceof List<?> l ? l : null;
    }

    /** Reads a trimmed {@link String} entry from {@code row}, returning {@code ""} when missing or non-string. */
    public static String mapText(Map<?, ?> row, String key) {
        if (row == null) return "";
        Object val = row.get(key);
        return val instanceof String s ? s.trim() : "";
    }

    /** Escapes {@code raw} as an RFC 8259 JSON string literal (or {@code "null"} when null). */
    public static String quote(String raw) {
        if (raw == null) return "null";
        StringBuilder out = new StringBuilder(raw.length() + 2);
        out.append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
        return out.toString();
    }

    private static boolean isNull(Object val) {
        return val == null || val == JSONObject.NULL;
    }
}
