package org.llm4people.hermes.artifact;

import android.util.JsonReader;
import android.util.JsonToken;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.llm4people.hermes.JsonValues;
import org.llm4people.hermes.places.NearbyCategory;

import static org.llm4people.hermes.artifact.WorkspaceLayout.*;

/** Parses a closed component vocabulary independent of JSON member order. */
final class WorkspaceParts {
    private static final List<String> BLOCK_TYPES = Arrays.asList(
            "section", "content", "link", "value", "form", "device", "nearby_places",
            "metric_grid", "sparkline", "table", "controls");

    static List<Block> read(JsonReader in) throws IOException {
        List<Block> result = new ArrayList<>();
        in.beginArray();
        while (in.hasNext()) {
            require(result.size() < MAX_BLOCKS);
            result.add(block(in));
        }
        in.endArray();
        require(!result.isEmpty());
        return result;
    }

    private static Block block(JsonReader in) throws IOException {
        Block b = new Block();
        Set<String> keys = new HashSet<>();
        List<Double> sparkValues = null;
        String sparkStyle = "line", sparkUnit = "", sparkCaption = "";
        Double sparkTarget = null, sparkMin = null, sparkMax = null;
        List<DataTableBlock.Column> tableCols = null;
        List<List<String>> tableRows = null;
        boolean sortable = true, filterable = false;

        in.beginObject();
        while (in.hasNext()) {
            String key = in.nextName();
            require(keys.add(key));
            switch (key) {
                case "type" -> b.type = choice(in, BLOCK_TYPES);
                case "title" -> b.title = label(in, 64);
                case "note" -> b.note = label(in, 240);
                case "actions" -> b.actions = strings(in, ACTIONS.keySet(), 8);
                case "slots" -> { b.slots = strings(in, SLOTS, SLOTS.size()); require(!b.slots.isEmpty()); }
                case "page" -> { b.page = label(in, 48); require(custom(b.page)); }
                case "source" -> b.source = choice(in, SOURCES);
                case "prompt" -> b.prompt = label(in, 240);
                case "category" -> b.category = choice(in, NearbyCategory.ids());
                case "radius_m" -> {
                    int r = strictInt(in);
                    require(NearbyCategory.validRadius(r));
                    b.radiusMeters = r;
                }
                case "limit" -> {
                    int lim = strictInt(in);
                    require(NearbyCategory.validLimit(lim));
                    b.limit = lim;
                }
                case "request" -> b.request = DeviceAction.read(in);
                case "fields" -> b.fields = DraftForm.fields(in);
                case "items" -> {
                    // Disambiguate after loop or inspect type? Since JSON member order is arbitrary,
                    // support "metrics" and "controls" or peek-parse "items" if type already known;
                    // better: read generic token only when type is unambiguous, OR read structured array cleanly!
                    if ("metric_grid".equals(b.type)) {
                        b.metrics = MetricGridBlock.readItems(in);
                    } else if ("controls".equals(b.type)) {
                        b.controls = InteractiveControlsBlock.readControls(in);
                    } else {
                        // Type hasn't appeared yet: buffer as raw items via helper that supports either!
                        parseDeferredItems(in, b);
                    }
                }
                case "metrics" -> b.metrics = MetricGridBlock.readItems(in);
                case "controls" -> b.controls = InteractiveControlsBlock.readControls(in);
                case "state_key" -> { b.stateKey = label(in, 36); require(DraftForm.validId(b.stateKey)); }
                case "values" -> sparkValues = SparklineView.readNumbers(in);
                case "style" -> sparkStyle = choice(in, SparklineView.STYLES);
                case "unit" -> sparkUnit = label(in, 16);
                case "caption" -> sparkCaption = label(in, 120);
                case "target" -> sparkTarget = SparklineView.readFiniteDouble(in);
                case "min" -> sparkMin = SparklineView.readFiniteDouble(in);
                case "max" -> sparkMax = SparklineView.readFiniteDouble(in);
                case "columns" -> tableCols = DataTableBlock.readColumns(in);
                case "rows" -> tableRows = DataTableBlock.readRows(in, -1);
                case "sortable" -> { require(in.peek() == JsonToken.BOOLEAN); sortable = in.nextBoolean(); }
                case "filterable" -> { require(in.peek() == JsonToken.BOOLEAN); filterable = in.nextBoolean(); }
                default -> throw invalid();
            }
        }
        in.endObject();

        switch (b.type) {
            case "content":
                allow(keys, "type", "slots");
                b.nativeContent = true;
                break;
            case "section":
                allow(keys, "type", "title", "note", "actions");
                require(b.title != null && (!b.note.isEmpty() || !b.actions.isEmpty()));
                break;
            case "link":
                allow(keys, "type", "title", "page");
                require(b.title != null && b.page != null);
                break;
            case "value":
                allow(keys, "type", "title", "source");
                require(b.title != null && b.source != null);
                break;
            case "device":
                allow(keys, "type", "request");
                require(b.request != null);
                b.title = b.request.kind().name();
                break;
            case "nearby_places":
                allow(keys, "type", "category", "radius_m", "limit");
                NearbyCategory cat = NearbyCategory.fromId(b.category);
                require(cat != null);
                b.title = cat.heading;
                break;
            case "form":
                allow(keys, "type", "title", "prompt", "fields");
                require(b.title != null && b.prompt != null && b.fields != null);
                break;
            case "metric_grid":
                allow(keys, "type", "title", "note", "items", "metrics");
                require(b.title != null && b.metrics != null && !b.metrics.isEmpty());
                break;
            case "sparkline":
                allow(keys, "type", "title", "note", "values", "style", "unit", "caption", "target", "min", "max");
                require(b.title != null && sparkValues != null);
                b.sparkline = SparklineView.Series.of(sparkValues, sparkStyle, sparkUnit, sparkCaption,
                        sparkTarget, sparkMin, sparkMax);
                break;
            case "table":
                allow(keys, "type", "title", "note", "columns", "rows", "sortable", "filterable", "prompt");
                require(b.title != null && tableCols != null && tableRows != null);
                b.table = new DataTableBlock.Model(tableCols, tableRows, sortable, filterable, b.prompt);
                break;
            case "controls":
                allow(keys, "type", "title", "note", "state_key", "prompt", "items", "controls");
                require(b.title != null && b.controls != null && !b.controls.isEmpty());
                break;
            default:
                throw invalid();
        }
        return b;
    }

    /** Handles "items" appearing before "type" in arbitrary-order JSON by capturing one bounded JSON array. */
    private static void parseDeferredItems(JsonReader in, Block b) throws IOException {
        String rawArray = captureJsonArray(in);
        try (JsonReader metricReader = new JsonReader(new java.io.StringReader(rawArray))) {
            metricReader.setLenient(false);
            b.metrics = MetricGridBlock.readItems(metricReader);
            return;
        } catch (IOException | IllegalStateException notMetrics) {
            // Try interactive controls next.
        }
        try (JsonReader ctrlReader = new JsonReader(new java.io.StringReader(rawArray))) {
            ctrlReader.setLenient(false);
            b.controls = InteractiveControlsBlock.readControls(ctrlReader);
        } catch (IllegalStateException e) {
            throw invalid();
        }
    }

    private static String captureJsonArray(JsonReader in) throws IOException {
        StringBuilder out = new StringBuilder();
        writeJsonValue(in, out, 0);
        return out.toString();
    }

    private static void writeJsonValue(JsonReader in, StringBuilder out, int depth) throws IOException {
        require(depth < 8 && out.length() < MAX_CHARS);
        switch (in.peek()) {
            case BEGIN_ARRAY -> {
                out.append('[');
                in.beginArray();
                boolean first = true;
                while (in.hasNext()) {
                    if (!first) out.append(',');
                    first = false;
                    writeJsonValue(in, out, depth + 1);
                }
                in.endArray();
                out.append(']');
            }
            case BEGIN_OBJECT -> {
                out.append('{');
                in.beginObject();
                boolean first = true;
                while (in.hasNext()) {
                    if (!first) out.append(',');
                    first = false;
                    out.append(JsonValues.quote(in.nextName()));
                    out.append(':');
                    writeJsonValue(in, out, depth + 1);
                }
                in.endObject();
                out.append('}');
            }
            case STRING -> out.append(JsonValues.quote(in.nextString()));
            case NUMBER -> out.append(in.nextString());
            case BOOLEAN -> out.append(in.nextBoolean() ? "true" : "false");
            case NULL -> { in.nextNull(); out.append("null"); }
            default -> throw invalid();
        }
    }

    private static int strictInt(JsonReader in) throws IOException {
        require(in.peek() == JsonToken.NUMBER);
        String raw = in.nextString();
        require(raw.matches("[0-9]{1,9}"));
        return Integer.parseInt(raw);
    }

    private static void allow(Set<String> keys, String... allowed) throws IOException {
        require(Arrays.asList(allowed).containsAll(keys));
    }

    static List<String> strings(JsonReader in, Iterable<String> vocabulary, int limit) throws IOException {
        List<String> values = new ArrayList<>();
        in.beginArray();
        while (in.hasNext()) {
            String v = vocabulary == null ? label(in, 64) : choice(in, vocabulary);
            require(values.size() < limit && !values.contains(v));
            values.add(v);
        }
        in.endArray();
        return values;
    }
}
