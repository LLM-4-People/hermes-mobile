package org.llm4people.hermes.artifact;

import android.util.JsonReader;
import android.util.JsonToken;
import org.llm4people.hermes.VisibleText;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import static org.llm4people.hermes.artifact.WorkspaceLayout.*;

/** Inert, typed draft construction. Only user-entered answers are added to the declared prompt. */
final class DraftForm {
    private static final Pattern SLUG = Pattern.compile("[a-z][a-z0-9-]{0,35}");
    static final int MAX_INPUT = 1500;
    static final int MAX_FIELDS = 6;
    static boolean validId(String id) { return id != null && SLUG.matcher(id).matches(); }
    static final class Field {
        String id, label, kind;
        boolean required = true;
        List<String> options = Collections.emptyList();
    }

    static List<Field> fields(JsonReader in) throws IOException {
        List<Field> items = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        in.beginArray();
        while (in.hasNext()) {
            require(items.size() < MAX_FIELDS);
            Field f = field(in);
            require(ids.add(f.id));
            items.add(f);
        }
        in.endArray();
        require(!items.isEmpty());
        return items;
    }

    private static Field field(JsonReader in) throws IOException {
        Field f = new Field();
        Set<String> keys = new HashSet<>();
        in.beginObject();
        while (in.hasNext()) {
            String k = in.nextName();
            require(keys.add(k));
            switch (k) {
                case "id": f.id = label(in,36); require(validId(f.id)); break;
                case "label": f.label = label(in,64); break;
                case "kind": f.kind = choice(in, Arrays.asList("text","multiline","choice")); break;
                case "required": require(in.peek() == JsonToken.BOOLEAN); f.required = in.nextBoolean(); break;
                case "options": f.options = WorkspaceParts.strings(in, null, 12); break;
                default: throw invalid();
            }
        }
        in.endObject();
        require(f.id != null && f.label != null && f.kind != null);
        require("choice".equals(f.kind) ? !f.options.isEmpty() : !keys.contains("options"));
        return f;
    }

    static String compose(Block form, Map<String,String> inputs) throws MissingAnswer {
        StringBuilder out = new StringBuilder(form.prompt);
        for (Field f : form.fields) {
            String answer = inputs.get(f.id);
            answer = answer == null ? "" : answer.trim();
            if (VisibleText.isBlank(answer)) {
                if (f.required) throw new MissingAnswer(f);
                continue;
            }
            if (answer.length() > MAX_INPUT
                    || ("choice".equals(f.kind) && !f.options.contains(answer))) throw new MissingAnswer(f);
            out.append("\n\n").append(f.label).append("\n").append(answer);
        }
        return out.toString();
    }

    static final class MissingAnswer extends Exception {
        final Field field;
        MissingAnswer(Field f) { super("Field requires a valid answer"); field = f; }
    }
}
