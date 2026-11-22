package org.llm4people.hermes.artifact;

import android.util.JsonReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.llm4people.hermes.artifact.WorkspaceLayout.*;

/** Parses a closed component vocabulary independent of JSON member order. */
final class WorkspaceParts {
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
        in.beginObject();
        while (in.hasNext()) {
            String key = in.nextName();
            require(keys.add(key));
            switch (key) {
                case "type": b.type = choice(in, Arrays.asList("section","content","link","value","form","device")); break;
                case "title": b.title = label(in,64); break;
                case "note": b.note = label(in,240); break;
                case "actions": b.actions = strings(in, ACTIONS.keySet(), 8); break;
                case "slots": b.slots = strings(in, SLOTS, SLOTS.size()); require(!b.slots.isEmpty()); break;
                case "page": b.page = label(in,48); require(custom(b.page)); break;
                case "source": b.source = choice(in, SOURCES); break;
                case "prompt": b.prompt = label(in,240); break;
                case "request": b.request = DeviceAction.read(in); break;
                case "fields": b.fields = DraftForm.fields(in); break;
                default: throw invalid();
            }
        }
        in.endObject();
        switch (b.type) {
            case "content":
                allow(keys, "type","slots");
                b.nativeContent = true;
                break;
            case "section":
                allow(keys, "type","title","note","actions");
                require(b.title != null && (!b.note.isEmpty() || !b.actions.isEmpty()));
                break;
            case "link": allow(keys,"type","title","page"); require(b.title != null && b.page != null); break;
            case "value": allow(keys,"type","title","source"); require(b.title != null && b.source != null); break;
            case "device":
                allow(keys,"type","request");
                require(b.request != null);
                b.title = b.request.kind().title;
                break;
            case "form":
                allow(keys,"type","title","prompt","fields");
                require(b.title != null && b.prompt != null && b.fields != null);
                break;
            default: throw invalid();
        }
        return b;
    }

    private static void allow(Set<String> keys, String... allowed) throws IOException {
        require(Arrays.asList(allowed).containsAll(keys));
    }

    static List<String> strings(JsonReader in, Iterable<String> vocabulary, int limit) throws IOException {
        List<String> values = new ArrayList<>();
        in.beginArray();
        while (in.hasNext()) {
            String v = vocabulary == null ? label(in,64) : choice(in, vocabulary);
            require(values.size() < limit && !values.contains(v));
            values.add(v);
        }
        in.endArray();
        return values;
    }
}
