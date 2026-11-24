package org.llm4people.hermes.palette;

import android.content.Context;
import android.view.KeyEvent;
import org.llm4people.hermes.R;
import org.llm4people.hermes.ambient.AmbientAccessPanel;
import org.llm4people.hermes.doctor.ConnectionDoctorPanel;
import org.llm4people.hermes.panel.BrainStudioRoutes;
import org.llm4people.hermes.settings.AppearanceSettingsPanel;
import org.llm4people.hermes.settings.SettingsHubPanel;
import org.llm4people.hermes.templates.TemplatesPanel;
import org.llm4people.hermes.templates.WorkflowTemplate;
import org.llm4people.hermes.update.UpdatePanel;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Accent-insensitive fuzzy &amp; multi-token command registry powering {@link CommandPalette}.
 * Pure-Java ranking logic runs identically on host JVM unit tests and Android devices.
 */
public final class CommandRegistry {
    public static final int MAX_QUERY_CHARS = 128;
    public static final int MAX_RESULTS = 40;

    public record RecentThread(String sessionId, String title, String snippet) {}

    private final List<CommandItem> items = new ArrayList<>();

    public CommandRegistry() {}

    public CommandRegistry(List<CommandItem> initial) {
        addAll(initial);
    }

    public synchronized CommandRegistry add(CommandItem item) {
        if (item == null) return this;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id.equals(item.id)) {
                items.set(i, item);
                return this;
            }
        }
        items.add(item);
        return this;
    }

    public synchronized CommandRegistry addAll(List<CommandItem> list) {
        if (list == null) return this;
        for (CommandItem item : list) add(item);
        return this;
    }

    public synchronized int size() {
        return items.size();
    }

    /** Returns true when a hardware keyboard shortcut invokes the palette (Ctrl+K or Cmd+K). */
    public static boolean isPaletteShortcut(int keyCode, boolean ctrlPressed, boolean metaPressed) {
        return keyCode == KeyEvent.KEYCODE_K && (ctrlPressed || metaPressed);
    }

    /** Accent-insensitive, lowercased normalization (e.g. "Mémoire" -> "memoire"). */
    public static String normalize(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String bounded = raw.length() > MAX_QUERY_CHARS * 2 ? raw.substring(0, MAX_QUERY_CHARS * 2) : raw;
        String decomposed = Normalizer.normalize(bounded, Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(decomposed.length());
        boolean lastSpace = true;
        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            int type = Character.getType(c);
            if (type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
                    || type == Character.ENCLOSING_MARK) {
                continue;
            }
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
                lastSpace = false;
            } else if (!lastSpace) {
                sb.append(' ');
                lastSpace = true;
            }
        }
        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == ' ') end--;
        return sb.substring(0, end);
    }

    /** Filters and ranks commands matching all query tokens (prefix > word-prefix > substring > fuzzy subsequence). */
    public synchronized List<CommandItem> search(String rawQuery) {
        String bounded = rawQuery == null ? "" : (rawQuery.length() > MAX_QUERY_CHARS
                ? rawQuery.substring(0, MAX_QUERY_CHARS) : rawQuery);
        String normalizedQuery = normalize(bounded);
        List<Scored> scored = new ArrayList<>();
        if (normalizedQuery.isEmpty()) {
            for (int i = 0; i < items.size(); i++) {
                CommandItem item = items.get(i);
                scored.add(new Scored(item, 1000 - item.category.order * 100, i));
            }
        } else {
            String[] tokens = normalizedQuery.split(" ");
            for (int i = 0; i < items.size(); i++) {
                CommandItem item = items.get(i);
                int score = matchScore(item, normalizedQuery, tokens);
                if (score > 0) scored.add(new Scored(item, score, i));
            }
        }
        scored.sort(Comparator.comparingInt((Scored s) -> s.score).reversed()
                .thenComparingInt(s -> s.item.category.order)
                .thenComparingInt(s -> s.index));
        int limit = Math.min(MAX_RESULTS, scored.size());
        List<CommandItem> out = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) out.add(scored.get(i).item);
        return Collections.unmodifiableList(out);
    }

    static int matchScore(CommandItem item, String fullQuery, String[] tokens) {
        String title = normalize(item.title);
        String subtitle = normalize(item.subtitle);
        String keywords = normalize(item.keywords);
        int total = 0;
        if (title.equals(fullQuery)) total += 500;
        else if (title.startsWith(fullQuery)) total += 350;
        else if (title.contains(fullQuery)) total += 220;

        for (String token : tokens) {
            if (token.isEmpty()) continue;
            int best = tokenScore(title, token, 180, 140, 100, 45);
            best = Math.max(best, tokenScore(subtitle, token, 95, 80, 60, 25));
            best = Math.max(best, tokenScore(keywords, token, 90, 75, 55, 20));
            if (best == 0) return 0;
            total += best;
        }
        return total;
    }

    private static int tokenScore(String field, String token, int exactPrefix, int wordPrefix,
                                  int substring, int fuzzySubseq) {
        if (field.isEmpty()) return 0;
        if (field.startsWith(token)) return exactPrefix;
        if (field.contains(" " + token)) return wordPrefix;
        if (field.contains(token)) return substring;
        if (token.length() >= 3 && isOrderedSubsequence(field, token)) return fuzzySubseq;
        return 0;
    }

    private static boolean isOrderedSubsequence(String haystack, String needle) {
        int hi = 0;
        for (int ni = 0; ni < needle.length(); ni++) {
            char c = needle.charAt(ni);
            hi = haystack.indexOf(c, hi);
            if (hi < 0) return false;
            hi++;
        }
        return true;
    }

    private record Scored(CommandItem item, int score, int index) {}

    /** Builds the default curated native action catalog in the active FR/EN locale. */
    public static List<CommandItem> defaultNativeCommands(Context context, Consumer<String> dispatchAction) {
        return defaultNativeCommands(context, false, dispatchAction);
    }

    /**
     * Builds the curated native action catalog in the active FR/EN locale.
     * Admin-only profile &amp; server actions are included strictly when {@code isAdmin} is true.
     */
    public static List<CommandItem> defaultNativeCommands(Context context, boolean isAdmin,
                                                          Consumer<String> dispatchAction) {
        Objects.requireNonNull(context, "context");
        Consumer<String> run = dispatchAction != null ? dispatchAction : ignored -> {};
        List<CommandItem> list = new ArrayList<>();
        addAction(list, context, "new_thread", R.string.palette_act_new_thread_title,
                R.string.palette_act_new_thread_hint, R.string.palette_kw_new_thread, run);
        addAction(list, context, TemplatesPanel.ROUTE_ID, R.string.palette_act_templates_title,
                R.string.palette_act_templates_hint, R.string.palette_kw_templates, run);
        addAction(list, context, BrainStudioRoutes.ROUTE_NOTES, R.string.palette_act_notes_title,
                R.string.palette_act_notes_hint, R.string.palette_kw_notes, run);
        addAction(list, context, BrainStudioRoutes.ROUTE_MEMORY, R.string.palette_act_memory_title,
                R.string.palette_act_memory_hint, R.string.palette_kw_memory, run);
        addAction(list, context, BrainStudioRoutes.ROUTE_SKILLS, R.string.palette_act_skills_title,
                R.string.palette_act_skills_hint, R.string.palette_kw_skills, run);
        addAction(list, context, BrainStudioRoutes.ROUTE_SHARED_WITH_ME, R.string.palette_act_shared_title,
                R.string.palette_act_shared_hint, R.string.palette_kw_shared, run);
        addAction(list, context, BrainStudioRoutes.ROUTE_WORKSPACE_FILES, R.string.palette_act_files_title,
                R.string.palette_act_files_hint, R.string.palette_kw_files, run);
        addAction(list, context, BrainStudioRoutes.ROUTE_SEARCH, R.string.palette_act_search_title,
                R.string.palette_act_search_hint, R.string.palette_kw_search, run);
        addAction(list, context, "reminders", R.string.palette_act_reminders_title,
                R.string.palette_act_reminders_hint, R.string.palette_kw_reminders, run);
        addAction(list, context, "deliveries", R.string.palette_act_deliveries_title,
                R.string.palette_act_deliveries_hint, R.string.palette_kw_deliveries, run);
        addAction(list, context, AmbientAccessPanel.ROUTE_ID, R.string.palette_act_ambient_title,
                R.string.palette_act_ambient_hint, R.string.palette_kw_ambient, run);
        addAction(list, context, ConnectionDoctorPanel.ROUTE_ID, R.string.palette_act_doctor_title,
                R.string.palette_act_doctor_hint, R.string.palette_kw_doctor, run);
        addAction(list, context, UpdatePanel.ROUTE_ID, R.string.palette_act_updates_title,
                R.string.palette_act_updates_hint, R.string.palette_kw_updates, run);
        addAction(list, context, SettingsHubPanel.ROUTE_ID, R.string.palette_act_settings_title,
                R.string.palette_act_settings_hint, R.string.palette_kw_settings, run);
        addAction(list, context, AppearanceSettingsPanel.ROUTE_ID, R.string.palette_act_appearance_title,
                R.string.palette_act_appearance_hint, R.string.palette_kw_appearance, run);
        addAction(list, context, "language", R.string.palette_act_language_title,
                R.string.palette_act_language_hint, R.string.palette_kw_language, run);
        addAction(list, context, "security", R.string.palette_act_security_title,
                R.string.palette_act_security_hint, R.string.palette_kw_security, run);
        addAction(list, context, "profile_hub", R.string.palette_act_profile_hub_title,
                R.string.palette_act_profile_hub_hint, R.string.palette_kw_profile_hub, run);
        addAction(list, context, "switch_profile", R.string.palette_act_switch_profile_title,
                R.string.palette_act_switch_profile_hint, R.string.palette_kw_switch_profile, run);
        addAction(list, context, "pair_device", R.string.palette_act_pair_device_title,
                R.string.palette_act_pair_device_hint, R.string.palette_kw_pair_device, run);
        if (isAdmin) {
            addAction(list, context, "invite_person", R.string.palette_act_invite_person_title,
                    R.string.palette_act_invite_person_hint, R.string.palette_kw_invite_person, run);
            addAction(list, context, "admin_console", R.string.palette_act_admin_console_title,
                    R.string.palette_act_admin_console_hint, R.string.palette_kw_admin_console, run);
        }
        addAction(list, context, "pages", R.string.palette_act_screens_title,
                R.string.palette_act_screens_hint, R.string.palette_kw_screens, run);
        return Collections.unmodifiableList(list);
    }

    private static void addAction(List<CommandItem> target, Context ctx, String actionId,
                                  int titleRes, int hintRes, int keywordsRes, Consumer<String> dispatch) {
        target.add(new CommandItem(
                "act:" + actionId,
                CommandItem.Category.ACTION,
                ctx.getString(titleRes),
                ctx.getString(hintRes),
                ctx.getString(keywordsRes),
                () -> dispatch.accept(actionId)));
    }

    /** Exposes reusable templates in the palette — strictly staging an editable draft on selection. */
    public static List<CommandItem> fromTemplates(List<WorkflowTemplate> templates, Consumer<String> stageDraft) {
        if (templates == null || templates.isEmpty()) return Collections.emptyList();
        Consumer<String> stage = stageDraft != null ? stageDraft : ignored -> {};
        List<CommandItem> out = new ArrayList<>(templates.size());
        for (WorkflowTemplate tpl : templates) {
            if (tpl == null) continue;
            out.add(new CommandItem(
                    "tpl:" + tpl.id,
                    CommandItem.Category.TEMPLATE,
                    tpl.title,
                    tpl.subtitle,
                    tpl.title,
                    () -> stage.accept(tpl.draft)));
        }
        return Collections.unmodifiableList(out);
    }

    /** Exposes recent discussions in the palette (bounded and deduplicated). */
    public static List<CommandItem> fromRecentThreads(List<RecentThread> threads, Consumer<String> openThread) {
        if (threads == null || threads.isEmpty()) return Collections.emptyList();
        Consumer<String> open = openThread != null ? openThread : ignored -> {};
        Map<String, CommandItem> dedup = new LinkedHashMap<>();
        for (RecentThread t : threads) {
            if (t == null || t.sessionId() == null || t.sessionId().trim().isEmpty()) continue;
            String title = CommandItem.sanitize(t.title(), CommandItem.MAX_TITLE_CHARS);
            if (title.isEmpty()) continue;
            String key = "thr:" + t.sessionId().trim();
            if (dedup.containsKey(key) || dedup.size() >= 15) continue;
            String sid = t.sessionId().trim();
            dedup.put(key, new CommandItem(
                    key,
                    CommandItem.Category.THREAD,
                    title,
                    t.snippet() == null ? "" : t.snippet(),
                    title,
                    () -> open.accept(sid)));
        }
        return Collections.unmodifiableList(new ArrayList<>(dedup.values()));
    }
}
