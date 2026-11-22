package org.llm4people.hermes.artifact;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayDeque;
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

    static final class Block {
        final int messageIndex, ordinal;
        final String language, filename, content;

        Block(int messageIndex, int ordinal, String language, String filename, String content) {
            this.messageIndex = messageIndex;
            this.ordinal = ordinal;
            this.language = language;
            this.filename = filename;
            this.content = content;
        }

        void addVersion(LinkedHashMap<String, Record> groups) {
            boolean layout = WorkspaceLayout.FENCE.equals(language);
            String surface = layout ? surfaceOf(content) : null;
            String key = surface != null ? "workspace:" + surface : filename.isEmpty() || layout ? "block:" + messageIndex + ":" + ordinal
                    : "file:" + language + ":" + filename;
            Record record = groups.remove(key);
            if (record == null) record = new Record(title(layout, surface), language);
            if (record.versions.isEmpty() || !record.versions.get(record.versions.size() - 1).equals(content))
                record.versions.add(content);
            groups.put(key, record);
        }

        private String title(boolean layout, String surface) {
            if (layout) return surface == null ? "Proposition à corriger" : "Disposition · " + WorkspaceLayout.screenName(surface);
            if (!filename.isEmpty()) return filename;
            if ("diff".equals(language) || "patch".equals(language)) return "Modifications";
            return language.isEmpty() ? "Extrait de code" : "Extrait " + language;
        }
    }

    static final class Scan {
        final List<Block> blocks = new ArrayList<>();
        boolean limited;
    }

    static Scan scan(JSONArray messages) {
        Scan scan = new Scan();
        List<Block> newest = scan.blocks;
        int remaining = MAX_CHARS;
        int oldest = Math.max(0, messages.length() - MAX_MESSAGES);
        scan.limited = oldest > 0;
        for (int i = messages.length() - 1; i >= oldest; i--) {
            JSONObject msg = messages.optJSONObject(i);
            if (msg == null || !"assistant".equals(msg.optString("role"))) continue;
            String text = messageText(msg.opt("content"));
            if (text == null) { scan.limited = true; continue; }
            remaining -= text.length();
            if (remaining < 0) { scan.limited = true; break; }
            final int messageIndex = i;
            int capacity = MAX_FENCES - newest.size();
            ArrayDeque<Block> current = new ArrayDeque<>();
            Fences.scan(text, (language, filename, content, n) -> {
                if (current.size() == capacity) {
                    current.removeLast();
                    scan.limited = true;
                }
                current.addFirst(new Block(messageIndex, n, language, filename, content));
                return true;
            });
            newest.addAll(current);
            if (newest.size() == MAX_FENCES && i > oldest) { scan.limited = true; break; }
        }
        Collections.reverse(newest);
        return scan;
    }

    ArtifactCatalog(JSONArray messages) {
        Scan scan = scan(messages);
        limited = scan.limited;
        LinkedHashMap<String, Record> groups = new LinkedHashMap<>();
        for (Block b : scan.blocks) b.addVersion(groups);
        records.addAll(groups.values());
        Collections.reverse(records);
    }

    private static String surfaceOf(String proposal) {
        try { return WorkspaceLayout.parse(proposal).surface; }
        catch (IOException e) { return null; }
    }

    private static String messageText(Object value) {
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
            int separatorLength = result.length() == 0 ? 0 : 1;
            if (text.length() > MAX_MESSAGE_CHARS - result.length() - separatorLength) return null;
            if (separatorLength != 0) result.append('\n');
            result.append(text);
        }
        return result.toString();
    }
}
