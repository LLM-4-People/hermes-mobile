package org.llm4people.hermes.panel;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.llm4people.hermes.PlainTextSanitizer;
import org.llm4people.hermes.VisibleText;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/** The gateway supplies a loadable name, description, optional category, and optional Studio metadata. */
final class SkillInventory {
    private static final Pattern WORDS = Pattern.compile("[\\p{javaWhitespace}\\p{Zs}]+");
    final String name;
    final String description;
    final String category;
    final boolean enabled;
    final List<String> tags;
    final String source;
    final boolean writable;
    final boolean hasSupportingFiles;
    private final String searchText;

    private SkillInventory(String name, String description, String category, boolean enabled,
                           List<String> tags, String source, boolean writable, boolean hasSupportingFiles) {
        this.name = name;
        this.description = description;
        this.category = category;
        this.enabled = enabled;
        this.tags = tags;
        this.source = source;
        this.writable = writable;
        this.hasSupportingFiles = hasSupportingFiles;
        StringBuilder searchable = new StringBuilder(name).append(' ').append(description).append(' ').append(category);
        for (String tag : tags) searchable.append(' ').append(tag);
        searchText = fold(searchable.toString());
    }

    SkillInventory withEnabled(boolean nextEnabled) {
        return new SkillInventory(name, description, category, nextEnabled, tags, source, writable, hasSupportingFiles);
    }

    static List<SkillInventory> parse(JSONObject response) throws JSONException {
        if (response.has("object") && !"list".equals(response.opt("object")))
            throw new JSONException("Invalid skill catalog");
        JSONArray rows = response.getJSONArray("data");
        ArrayList<SkillInventory> skills = new ArrayList<>(rows.length());
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null || !(row.opt("name") instanceof String name)) continue;
            // The name is a load identifier. Removing a control character would request a different skill.
            if (VisibleText.isBlank(name) || name.codePoints().anyMatch(Character::isISOControl)) continue;
            String description = optionalText(row, "description");
            String category = optionalText(row, "category");
            if (description == null || category == null) continue;
            boolean enabled = row.optBoolean("enabled", true);
            List<String> tags = parseStringList(row.optJSONArray("tags"));
            String source = optionalText(row, "source");
            if (source == null) source = "";
            boolean writable = row.optBoolean("writable", "user".equalsIgnoreCase(source));
            boolean hasSupportingFiles = row.optBoolean("has_supporting_files", false);
            skills.add(new SkillInventory(name, description, category, enabled, tags, source, writable, hasSupportingFiles));
        }
        if (rows.length() > 0 && skills.isEmpty()) throw new JSONException("No usable skill entries");
        return skills;
    }

    private static List<String> parseStringList(JSONArray array) {
        if (array == null || array.length() == 0) return Collections.emptyList();
        List<String> out = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            Object raw = array.opt(i);
            if (raw instanceof String text && !text.trim().isEmpty()) out.add(text.trim());
        }
        return Collections.unmodifiableList(out);
    }

    private static String optionalText(JSONObject row, String key) {
        if (row.isNull(key)) return "";
        return row.opt(key) instanceof String text ? text : null;
    }

    private static String fold(String value) {
        return PlainTextSanitizer.foldAccents(value);
    }

    static List<SkillInventory> search(List<SkillInventory> skills, String query) {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isEmpty()) return new ArrayList<>(skills);
        String[] words = WORDS.split(fold(trimmed));
        List<SkillInventory> matches = new ArrayList<>();
        for (SkillInventory skill : skills) {
            boolean matchesAll = true;
            for (String word : words) if (!skill.searchText.contains(word)) { matchesAll = false; break; }
            if (matchesAll) matches.add(skill);
        }
        return matches;
    }
}
