package org.llm4people.hermes.artifact;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.R;

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

        void addVersion(Context context, LinkedHashMap<String, Record> groups) {
            boolean layout = WorkspaceLayout.FENCE.equals(language);
            String surface = layout ? surfaceOf(content) : null;
            String key = surface != null ? "workspace:" + surface : filename.isEmpty() || layout ? "block:" + messageIndex + ":" + ordinal
                    : "file:" + language + ":" + filename;
            Record record = groups.remove(key);
            if (record == null) record = new Record(title(context, layout, surface), language);
            if (record.versions.isEmpty() || !record.versions.get(record.versions.size() - 1).equals(content))
                record.versions.add(content);
            groups.put(key, record);
        }

        private String title(Context context, boolean layout, String surface) {
            if (layout) {
                if (surface == null) {
                    return context != null ? context.getString(R.string.ws_art_proposal_to_fix) : WorkspaceLayout.FENCE;
                }
                String screen = WorkspaceLayout.screenName(context, surface);
                return context != null ? context.getString(R.string.ws_art_layout_titled, screen) : surface;
            }
            if (!filename.isEmpty()) return filename;
            if ("diff".equals(language) || "patch".equals(language)) {
                return context != null ? context.getString(R.string.ws_art_diff_title) : language;
            }
            if (language.isEmpty()) {
                return context != null ? context.getString(R.string.ws_art_snippet_default) : "code";
            }
            return context != null ? context.getString(R.string.ws_art_snippet_lang, language) : language;
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
            if (msg == null || !"assistant".equals(msg.optString("role"))
                    || "hidden".equals(msg.optString("display_kind"))) continue;
            String text = MessageParts.eligible(msg.opt("content"));
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
        this(null, messages);
    }

    ArtifactCatalog(Context context, JSONArray messages) {
        Scan scan = scan(messages);
        limited = scan.limited;
        LinkedHashMap<String, Record> groups = new LinkedHashMap<>();
        for (Block b : scan.blocks) b.addVersion(context, groups);
        records.addAll(groups.values());
        Collections.reverse(records);
    }

    private static String surfaceOf(String proposal) {
        try { return WorkspaceLayout.parse(proposal).surface; }
        catch (IOException e) { return null; }
    }

}
