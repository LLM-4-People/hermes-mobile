package org.llm4people.hermes.artifact;

import android.util.JsonReader;
import android.util.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The bounded wire vocabulary shared by the instructions, renderer, and persisted layout. */
final class WorkspaceLayout {
    static final String FENCE = "hermes-workspace";
    static final int MAX_CHARS = 16 * 1024;
    static final Map<String,String> SURFACES = names(
            "home", "Atelier", "threads", "Fils", "thread", "Conversation", "settings", "Réglages",
            "reminders", "Rappels", "artifacts", "Artefacts", "stats", "Statistiques");
    static final Map<String,String> ACTIONS = names(
            "threads", "Voir les fils", "new_thread", "Créer un fil", "artifacts", "Ouvrir les artefacts",
            "models", "Choisir un modèle", "tools", "Choisir les outils", "reminders", "Voir les rappels",
            "stats", "Voir les statistiques", "profiles", "Gérer les profils",
            "notifications", "Gérer les notifications", "workspace", "Ouvrir l’atelier", "pages", "Mes écrans");

    static final List<String> SOURCES = Arrays.asList("profile.label", "thread.title", "thread.messageCount");
    static final List<String> SLOTS = Arrays.asList("notice", "transcript", "composer");
    static final int MAX_BLOCKS = 12;
    static final class Block {
        String type = "section";
        String page, source, prompt;
        List<String> slots = Collections.emptyList();
        List<DraftForm.Field> fields;
        boolean nativeContent;
        String title;
        String note = "";
        List<String> actions = Collections.emptyList();
    }

    String surface;
    String mode = "extend";
    String title;
    String theme = "system";
    String density = "comfortable";
    List<Block> blocks;
    private WorkspaceLayout() {}

    static String instructions() {
        return "You are displayed in Hermes Mobile. When asked to change the app layout, propose a complete " +
                "JSON document in a fenced " + FENCE + " block. Only an explicitly applied proposal changes a " +
                "screen. When asked to turn a discussion or review into a reusable tool, propose a named page " +
                "with mode=replace and fields for the task. Describe its purpose in ordinary language. For a " +
                "revision keep the same page slug and stable field IDs, and name the change. The person can try " +
                "a proposal, pin it to this discussion, compare a revision and undo a change. Ask a focused " +
                "clarification with choices when a decision is needed. Format: {\"version\":1,\"surface\":\"home\",\"title\":\"Atelier\"," +
                "\"theme\":\"system\",\"density\":\"compact\",\"blocks\":[{\"title\":\"Travail\"," +
                "\"actions\":[\"threads\",\"new_thread\"]},{\"type\":\"content\"}]}. " +
                "Surface IDs: " + String.join(", ", SURFACES.keySet()) + ". Action IDs: " +
                String.join(", ", ACTIONS.keySet()) + ". Theme system/light/dark; density compact/comfortable. " +
                "Extend existing content with one content block, 1–" + MAX_BLOCKS + " blocks total. Section title, optional note and up to 8 " +
                "distinct action IDs. With mode=replace, you can omit native content entirely, also on a new " +
                "surface page/<lowercase-slug>. Blocks: {type:link,title,page}, {type:value,title,source}, " +
                "{type:form,title,prompt,fields:[{id,label,kind,required}]}; use quoted JSON keys/strings. " +
                "Form kind text, multiline, or choice with options. Forms stage editable drafts; users send in the " +
                "native composer. Sources: " + String.join(", ", SOURCES) + ". Thread content can declare slots " +
                "in desired order from " + String.join(", ", SLOTS) + ". Use French labels. For a versioned code artifact include its filename " +
                "after the fence language, e.g. file=src/main.py or file=Dockerfile. For diffs use diff.";
    }

    static WorkspaceLayout parse(String raw) throws IOException {
        require(raw != null && raw.length() <= MAX_CHARS);
        validateJsonStrings(raw);
        WorkspaceLayout layout = new WorkspaceLayout();
        Set<String> fields = new HashSet<>();
        try (JsonReader in = new JsonReader(new StringReader(raw))) {
            in.setLenient(false);
            in.beginObject();
            while (in.hasNext()) {
                String field = in.nextName();
                require(fields.add(field));
                switch (field) {
                    case "version":
                        require(in.peek() == JsonToken.NUMBER && "1".equals(in.nextString()));
                        break;
                    case "surface": layout.surface = label(in, 48); break;
                    case "mode": layout.mode = choice(in, Arrays.asList("extend","replace")); break;
                    case "title": layout.title = label(in, 64); break;
                    case "theme": layout.theme = choice(in, Arrays.asList("system", "light", "dark")); break;
                    case "density": layout.density = choice(in, Arrays.asList("comfortable", "compact")); break;
                    case "blocks": layout.blocks = WorkspaceParts.read(in); break;
                    default: throw invalid();
                }
            }
            in.endObject();
            require(in.peek() == JsonToken.END_DOCUMENT && fields.contains("version") && layout.surface != null
                    && layout.title != null && layout.blocks != null);
            require(SURFACES.containsKey(layout.surface) || custom(layout.surface));
            int nativeCount = 0;
            for (Block block : layout.blocks) {
                if (block.nativeContent) nativeCount++;
                if (!block.slots.isEmpty()) require("thread".equals(layout.surface));
            }
            require("extend".equals(layout.mode) ? nativeCount == 1 : nativeCount == 0);
            if (custom(layout.surface)) require("replace".equals(layout.mode));
            return layout;
        } catch (IllegalStateException | NumberFormatException e) {
            throw invalid();
        }
    }

    // Android's strict JsonReader still decodes unknown escapes as literal characters.
    // Check string syntax before it discards the original spelling of field names and values.
    private static void validateJsonStrings(String raw) throws IOException {
        boolean quoted = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (quoted) {
                require(c >= 0x20);
                if (c != '\\') continue;
                require(++i < raw.length());
                char escape = raw.charAt(i);
                if (escape == 'u') {
                    require(i + 4 < raw.length());
                    for (int digit = 0; digit < 4; digit++)
                        require("0123456789abcdefABCDEF".indexOf(raw.charAt(++i)) >= 0);
                } else {
                    require("\"\\/bfnrt".indexOf(escape) >= 0);
                }
            }
        }
        require(!quoted);
    }

    static boolean custom(String id) {
        return id != null && id.startsWith("page/") && DraftForm.validId(id.substring(5));
    }

    static String screenName(String id) {
        return custom(id) ? "Écran personnel · " + id.substring(5) : SURFACES.get(id);
    }

    List<String> slots() {
        for (Block b : blocks) if (b.nativeContent) return b.slots;
        return Collections.emptyList();
    }

    static String choice(JsonReader in, Iterable<String> options) throws IOException {
        String name = label(in, 40);
        for (String option : options) if (option.equals(name)) return name;
        throw invalid();
    }

    static String label(JsonReader in, int max) throws IOException {
        require(in.peek() == JsonToken.STRING);
        String s = in.nextString();
        require(!s.isEmpty() && s.length() <= max);
        require(!Character.isSpaceChar(s.codePointAt(0)) && !Character.isSpaceChar(s.codePointBefore(s.length())));
        for (int i = 0; i < s.length();) {
            int cp = s.codePointAt(i);
            int kind = Character.getType(cp);
            require(!Character.isISOControl(cp) && kind != Character.FORMAT && kind != Character.SURROGATE
                    && kind != Character.LINE_SEPARATOR && kind != Character.PARAGRAPH_SEPARATOR);
            i += Character.charCount(cp);
        }
        return s;
    }

    static void require(boolean ok) throws IOException { if (!ok) throw invalid(); }
    static IOException invalid() { return new IOException("Invalid workspace layout"); }
    private static Map<String,String> names(String... pairs) {
        Map<String,String> names = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) names.put(pairs[i], pairs[i + 1]);
        return Collections.unmodifiableMap(names);
    }
}
