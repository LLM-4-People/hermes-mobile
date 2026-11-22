package org.llm4people.hermes.panel;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** The gateway supplies a loadable name, a description and an optional category. */
final class SkillInventory {
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern WORDS = Pattern.compile("[\\p{javaWhitespace}\\p{Zs}]+");
    final String name;
    final String description;
    final String category;
    private final String searchText;

    private SkillInventory(String name, String description, String category) {
        this.name = name;
        this.description = description;
        this.category = category;
        searchText = fold(name + " " + description + " " + category);
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
            if (name.trim().isEmpty() || name.codePoints().anyMatch(Character::isISOControl)) continue;
            String description = optionalText(row, "description");
            String category = optionalText(row, "category");
            if (description != null && category != null) skills.add(new SkillInventory(name, description, category));
        }
        if (rows.length() > 0 && skills.isEmpty()) throw new JSONException("No usable skill entries");
        return skills;
    }

    private static String optionalText(JSONObject row, String key) {
        if (row.isNull(key)) return "";
        return row.opt(key) instanceof String text ? text : null;
    }

    private static String fold(String value) {
        return MARKS.matcher(Normalizer.normalize(value, Normalizer.Form.NFD))
                .replaceAll("").toLowerCase(Locale.ROOT);
    }

    static List<SkillInventory> search(List<SkillInventory> skills, String query) {
        String[] words = WORDS.split(fold(query.trim()));
        List<SkillInventory> matches = new ArrayList<>();
        for (SkillInventory skill : skills) {
            boolean matchesAll = true;
            for (String word : words) if (!skill.searchText.contains(word)) { matchesAll = false; break; }
            if (matchesAll) matches.add(skill);
        }
        return matches;
    }
}
