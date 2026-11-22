package org.llm4people.hermes.artifact;

import android.util.JsonReader;
import android.util.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
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
            "notifications", "Gérer les notifications", "workspace", "Ouvrir l’atelier");

    static final class Block {
        boolean nativeContent;
        String title;
        String note = "";
        List<String> actions = Collections.emptyList();
    }

    String surface;
    String title;
    String theme = "system";
    String density = "comfortable";
    List<Block> blocks;
    private WorkspaceLayout() {}

    static String instructions() {
        return "You are displayed in Hermes Mobile. When asked to change the app layout, propose a complete " +
                "JSON document in a fenced " + FENCE + " block. Only an explicitly applied proposal changes a " +
                "screen. Format: {\"version\":1,\"surface\":\"home\",\"title\":\"Atelier\"," +
                "\"theme\":\"system\",\"density\":\"compact\",\"blocks\":[{\"title\":\"Travail\"," +
                "\"actions\":[\"threads\",\"new_thread\"]},{\"type\":\"content\"}]}. " +
                "Surface IDs: " + String.join(", ", SURFACES.keySet()) + ". Action IDs: " +
                String.join(", ", ACTIONS.keySet()) + ". Theme system/light/dark; density compact/comfortable. " +
                "Exactly one native content block, 1–8 blocks total. Section title, optional note and up to 8 " +
                "distinct action IDs. Use French labels. For a versioned code artifact include its filename " +
                "after the fence language as file=relative/name.ext. For diffs use diff.";
    }

    static WorkspaceLayout parse(String raw) throws IOException {
        require(raw != null && raw.length() <= MAX_CHARS);
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
                    case "surface": layout.surface = choice(in, SURFACES.keySet()); break;
                    case "title": layout.title = label(in, 64); break;
                    case "theme": layout.theme = choice(in, Arrays.asList("system", "light", "dark")); break;
                    case "density": layout.density = choice(in, Arrays.asList("comfortable", "compact")); break;
                    case "blocks": layout.blocks = blocks(in); break;
                    default: throw invalid();
                }
            }
            in.endObject();
            require(in.peek() == JsonToken.END_DOCUMENT && fields.contains("version") && layout.surface != null
                    && layout.title != null && layout.blocks != null);
            return layout;
        } catch (IllegalStateException | NumberFormatException e) {
            throw invalid();
        }
    }

    private static List<Block> blocks(JsonReader in) throws IOException {
        List<Block> result = new ArrayList<>();
        int nativeCount = 0;
        in.beginArray();
        while (in.hasNext()) {
            require(result.size() < 8);
            Block b = block(in);
            if (b.nativeContent) nativeCount++;
            result.add(b);
        }
        in.endArray();
        require(nativeCount == 1);
        return result;
    }

    private static Block block(JsonReader in) throws IOException {
        Block block = new Block();
        Set<String> fields = new HashSet<>();
        in.beginObject();
        while (in.hasNext()) {
            String field = in.nextName();
            require(fields.add(field));
            switch (field) {
                case "type":
                    require("content".equals(label(in, 10)));
                    block.nativeContent = true;
                    break;
                case "title": block.title = label(in, 64); break;
                case "note": block.note = label(in, 240); break;
                case "actions":
                    List<String> actions = new ArrayList<>();
                    in.beginArray();
                    while (in.hasNext()) {
                        String id = choice(in, ACTIONS.keySet());
                        require(actions.size() < 8 && !actions.contains(id));
                        actions.add(id);
                    }
                    in.endArray();
                    block.actions = actions;
                    break;
                default: throw invalid();
            }
        }
        in.endObject();
        require(block.nativeContent ? fields.size() == 1 : block.title != null
                && (!block.actions.isEmpty() || !block.note.isEmpty()));
        return block;
    }

    private static String choice(JsonReader in, Iterable<String> options) throws IOException {
        String name = label(in, 40);
        for (String option : options) if (option.equals(name)) return name;
        throw invalid();
    }

    private static String label(JsonReader in, int max) throws IOException {
        require(in.peek() == JsonToken.STRING);
        String s = in.nextString();
        require(!s.trim().isEmpty() && s.length() <= max && s.equals(s.trim()));
        for (int i = 0; i < s.length();) {
            int cp = s.codePointAt(i);
            int kind = Character.getType(cp);
            require(!Character.isISOControl(cp) && kind != Character.FORMAT && kind != Character.SURROGATE
                    && kind != Character.LINE_SEPARATOR && kind != Character.PARAGRAPH_SEPARATOR);
            i += Character.charCount(cp);
        }
        return s;
    }

    private static void require(boolean ok) throws IOException { if (!ok) throw invalid(); }
    private static IOException invalid() { return new IOException("Invalid workspace layout"); }
    private static Map<String,String> names(String... pairs) {
        Map<String,String> names = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) names.put(pairs[i], pairs[i + 1]);
        return Collections.unmodifiableMap(names);
    }
}
