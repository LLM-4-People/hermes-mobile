package org.llm4people.hermes.panel;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Wire values keep a profile default distinct from a deliberately empty tool selection. */
final class PanelData {
    private PanelData() {}

    static String text(JSONObject object, String field) {
        Object value = object.opt(field);
        return value instanceof String ? ((String) value).trim() : "";
    }

    static JSONObject put(JSONObject object, String field, Object value) {
        try {
            return object.put(field, value);
        } catch (JSONException invalid) {
            throw new IllegalArgumentException("Invalid panel value", invalid);
        }
    }

    static JSONObject parse(String saved) {
        if (saved == null) return new JSONObject();
        try {
            return new JSONObject(saved);
        } catch (JSONException malformed) {
            // Never silently substitute unrestricted tools for an unreadable saved selection.
            throw new IllegalStateException("Unreadable session settings", malformed);
        }
    }

    static JSONObject restoreSelection(String saved) {
        JSONObject row = parse(saved);
        for (String key : new String[]{"allowed_toolsets", "excluded_tools"}) {
            if (!row.has(key)) continue;
            JSONArray arr = row.optJSONArray(key);
            if (arr == null) throw new IllegalStateException("Invalid saved tool filter");
            for (int i = 0; i < arr.length(); i++) {
                Object entry = arr.opt(i);
                if (!(entry instanceof String) || ((String) entry).trim().isEmpty())
                    throw new IllegalStateException("Invalid saved tool name");
            }
        }
        String provider = text(row, "provider"), model = text(row, "model");
        if ((row.has("provider") || row.has("model")) && (provider.isEmpty() || model.isEmpty()))
            throw new IllegalStateException("Invalid saved model route");
        return selection(provider, model, row.optJSONArray("allowed_toolsets"), row.optJSONArray("excluded_tools"));
    }

    static JSONObject selection(String provider, String model, JSONArray tools, JSONArray excluded) {
        JSONObject result = new JSONObject();
        if (!provider.isEmpty() && !model.isEmpty()) {
            put(result, "provider", provider);
            put(result, "model", model);
        }
        if (tools != null) put(result, "allowed_toolsets", tools);
        if (excluded != null && excluded.length() > 0) put(result, "excluded_tools", excluded);
        return result;
    }

    static double measure(JSONObject source, String field) {
        Object value = source.opt(field);
        if (!(value instanceof Number)) return Double.NaN;
        double n = ((Number) value).doubleValue();
        return Double.isFinite(n) && n >= 0 ? n : Double.NaN;
    }

    static double cacheRatio(JSONObject row) {
        double read = measure(row, "cache_read_tokens"), input = measure(row, "input_tokens");
        return input > 0 && read > 0 && read <= input ? read / input : Double.NaN;
    }

    static double endToEndRate(JSONObject row) {
        double elapsed = measure(row, "elapsed_ms"), tokens = measure(row, "output_tokens");
        return elapsed > 0 ? tokens * 1000d / elapsed : Double.NaN;
    }

    static String schedule(JSONObject job) {
        JSONObject parsed = job.optJSONObject("schedule");
        if (parsed == null) return text(job, "schedule");
        switch (text(parsed, "kind")) {
            case "once": return text(parsed, "run_at");
            case "cron": return text(parsed, "expr");
            case "interval": return parsed.optInt("minutes", 0) > 0 ? parsed.optInt("minutes") + "m" : "";
            default: return "";
        }
    }
}
