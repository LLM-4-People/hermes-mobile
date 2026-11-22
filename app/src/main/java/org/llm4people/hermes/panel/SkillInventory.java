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
        JSONArray rows = response.getJSONArray("data");
        ArrayList<SkillInventory> skills = new ArrayList<>(rows.length());
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            String name = text(row, "name");
            if (name.trim().isEmpty() || name.codePoints().anyMatch(Character::isISOControl))
                throw new JSONException("Invalid skill name");
            skills.add(new SkillInventory(name, text(row, "description"), text(row, "category")));
        }
        return skills;
    }

    private static String text(JSONObject row, String key) throws JSONException {
        if (row.isNull(key)) return "";
        Object value = row.get(key);
        if (value instanceof String) return (String) value;
        throw new JSONException("Invalid skill metadata");
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
