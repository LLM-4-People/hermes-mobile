package org.llm4people.hermes.artifact;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/** Retained only while the artifact reader is open. Versions come from server transcript order. */
final class ArtifactCatalog {
    static final int MAX_MESSAGE_CHARS = 512 * 1024;
    private static final int MAX_CHARS = 2 * 1024 * 1024;
    private static final int MAX_MESSAGES = 1024;
    private static final int MAX_FENCES = 256;
    final List<Record> records = new ArrayList<>();
    boolean limited;

    static final class Record {
        final String title, language;
        final List<String> versions = new ArrayList<>();
        Record(String title, String language) { this.title = title; this.language = language; }
    }

    private static final class Block {
        final String key, title, language, content;
        Block(String key, String title, String language, String content) {
            this.key = key; this.title = title; this.language = language; this.content = content;
        }
    }

    ArtifactCatalog(JSONArray messages) {
        List<Block> newest = new ArrayList<>();
        int remaining = MAX_CHARS;
        int oldest = Math.max(0, messages.length() - MAX_MESSAGES);
        limited = oldest > 0;
        for (int i = messages.length() - 1; i >= oldest; i--) {
            JSONObject msg = messages.optJSONObject(i);
            if (msg == null || !"assistant".equals(msg.optString("role"))) continue;
            String text = messageText(msg.opt("content"));
            if (text == null) { limited = true; continue; }
            remaining -= text.length();
            if (remaining < 0) { limited = true; break; }
            final int messageIndex = i;
            List<Block> current = new ArrayList<>();
            Fences.scan(text, (language, filename, content, n) -> {
                if (current.size() + newest.size() >= MAX_FENCES) { limited = true; return false; }
                boolean layout = WorkspaceLayout.FENCE.equals(language);
                String surface = layout ? surfaceOf(content) : null;
                String key = surface != null ? "workspace:" + surface : filename.isEmpty() || layout ? "block:" + messageIndex + ":" + n
                        : "file:" + language + ":" + filename;
                String title = layout ? (surface == null ? "Proposition à corriger" : "Disposition · " + WorkspaceLayout.SURFACES.get(surface))
                        : !filename.isEmpty() ? filename
                        : ("diff".equals(language) || "patch".equals(language)) ? "Modifications"
                        : language.isEmpty() ? "Extrait de code" : "Extrait " + language;
                current.add(new Block(key, title, language, content));
                return true;
            });
            Collections.reverse(current);
            newest.addAll(current);
            if (newest.size() == MAX_FENCES && i > oldest) { limited = true; break; }
        }
        Collections.reverse(newest);
        LinkedHashMap<String, Record> groups = new LinkedHashMap<>();
        for (Block b : newest) {
            Record r = groups.remove(b.key);
            if (r == null) r = new Record(b.title, b.language);
            if (r.versions.isEmpty() || !r.versions.get(r.versions.size() - 1).equals(b.content))
                r.versions.add(b.content);
            groups.put(b.key, r);
        }
        records.addAll(groups.values());
        Collections.reverse(records);
    }

    private static String surfaceOf(String proposal) {
        try { return WorkspaceLayout.parse(proposal).surface; }
        catch (IOException e) { return null; }
    }

    private String messageText(Object value) {
        if (value instanceof String) return ((String) value).length() <= MAX_MESSAGE_CHARS ? (String) value : null;
        if (!(value instanceof JSONArray)) return "";
        JSONArray parts = (JSONArray) value;
        if (parts.length() > 1024) return null;
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.optJSONObject(i);
            if (part == null) continue;
            String type = part.optString("type");
            if (!"text".equals(type) && !"output_text".equals(type)) continue;
            Object v = part.opt("text");
            if (!(v instanceof String)) continue;
            String text = (String) v;
            if (text.length() > MAX_MESSAGE_CHARS - result.length() - 1) return null;
            if (result.length() > 0) result.append('\n');
            result.append(text);
        }
        return result.toString();
    }
}
