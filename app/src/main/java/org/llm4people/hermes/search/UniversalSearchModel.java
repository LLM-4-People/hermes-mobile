package org.llm4people.hermes.search;

import org.json.JSONObject;
import org.llm4people.hermes.JsonValues;
import org.llm4people.hermes.PlainTextSanitizer;
import org.llm4people.hermes.R;
import org.llm4people.hermes.panel.SearchResultModel;
import org.llm4people.hermes.panel.UniversalShareModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Pure-JVM SSoT for Universal Search across local entries (chats, settings, apps, templates, tools)
 * and live server-side results (FTS5 sessions, Smart Notes, incoming shares).
 */
public final class UniversalSearchModel {
    public static final int MAX_LOCAL_SUGGESTIONS = 12;
    public static final int MAX_REMOTE_SESSION_HITS = 20;

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    public enum Category {
        ALL(R.string.universal_search_cat_all, 0),
        CHATS(R.string.universal_search_cat_chats, 1),
        NOTES(R.string.universal_search_cat_notes, 2),
        SHARED(R.string.universal_search_cat_shared, 3),
        SETTINGS(R.string.universal_search_cat_settings, 4),
        TOOLS(R.string.universal_search_cat_tools, 5);

        public final int labelRes;
        final int order;

        Category(int labelRes, int order) {
            this.labelRes = labelRes;
            this.order = order;
        }
    }

    /** Instant locally indexed item supplied by the host screen (0 ms search). */
    public record LocalEntry(
            String id,
            Category category,
            String title,
            String subtitle,
            String keywords,
            String targetId,
            String actionId,
            boolean recent) {
        public LocalEntry {
            id = safe(id);
            category = category == null || category == Category.ALL ? Category.TOOLS : category;
            title = safe(title);
            subtitle = safe(subtitle);
            keywords = safe(keywords);
            targetId = safe(targetId);
            actionId = safe(actionId);
        }

        public static LocalEntry chat(String sessionId, String title, String preview, boolean recent) {
            String safeId = safe(sessionId);
            return new LocalEntry("chat:" + safeId, Category.CHATS, title, preview, "", safeId, "", recent);
        }

        public static LocalEntry setting(String actionId, String title, String subtitle, String keywords) {
            String safeAction = safe(actionId);
            return new LocalEntry("setting:" + safeAction, Category.SETTINGS, title, subtitle, keywords, "", safeAction, false);
        }

        public static LocalEntry tool(String actionId, String title, String subtitle, String keywords) {
            String safeAction = safe(actionId);
            return new LocalEntry("tool:" + safeAction, Category.TOOLS, title, subtitle, keywords, "", safeAction, false);
        }
    }

    /** Unified search hit merged from local index and server endpoints. */
    public record Result(
            String dedupeKey,
            Category category,
            String title,
            String subtitle,
            String targetId,
            String actionId,
            String shareId,
            String resourceKind,
            String ownerProfile,
            String permission,
            boolean fromServer,
            int score,
            List<SearchResultModel.Span> snippetHighlights,
            boolean fallbackTitle) {
        public Result {
            dedupeKey = safe(dedupeKey);
            category = category == null || category == Category.ALL ? Category.TOOLS : category;
            title = safe(title);
            subtitle = safe(subtitle);
            targetId = safe(targetId);
            actionId = safe(actionId);
            shareId = safe(shareId);
            resourceKind = safe(resourceKind).toLowerCase(Locale.ROOT);
            ownerProfile = safe(ownerProfile);
            permission = safe(permission).toLowerCase(Locale.ROOT);
            snippetHighlights = snippetHighlights == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(snippetHighlights));
        }

        public Result(
                String dedupeKey, Category category, String title, String subtitle,
                String targetId, String actionId, String shareId, String resourceKind,
                String ownerProfile, String permission, boolean fromServer, int score,
                List<SearchResultModel.Span> snippetHighlights) {
            this(dedupeKey, category, title, subtitle, targetId, actionId, shareId, resourceKind,
                    ownerProfile, permission, fromServer, score, snippetHighlights, false);
        }
    }

    /** Monotonic epoch session state guarding async debounced remote results against stale overwrites. */
    public static final class SearchSession {
        private final List<LocalEntry> localEntries;
        private String activeQuery = "";
        private Category activeCategory = Category.ALL;
        private volatile int epoch;
        private boolean remoteLoading;
        private boolean remoteDegraded;
        private List<Result> remoteSessionHits = Collections.emptyList();
        private List<Result> remoteNotes = Collections.emptyList();
        private List<Result> remoteShared = Collections.emptyList();

        public SearchSession(List<LocalEntry> localEntries) {
            this.localEntries = localEntries == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(localEntries));
        }

        public int setQuery(String rawQuery) {
            String normalized = SearchResultModel.normalizeQuery(rawQuery);
            if (!normalized.equals(activeQuery)) {
                activeQuery = normalized;
                epoch++;
                remoteSessionHits = Collections.emptyList();
                remoteDegraded = false;
                remoteLoading = !normalized.isEmpty();
            }
            return epoch;
        }

        public void setCategory(Category category) {
            this.activeCategory = category == null ? Category.ALL : category;
        }

        public String activeQuery() { return activeQuery; }
        public Category activeCategory() { return activeCategory; }
        public int epoch() { return epoch; }
        public boolean isRemoteLoading() { return remoteLoading; }
        public boolean isRemoteDegraded() { return remoteDegraded; }

        public void markRemoteLoading(int expectedEpoch, boolean loading) {
            if (expectedEpoch == epoch) this.remoteLoading = loading;
        }

        /** Accepts cached notes/shares fetched independently of query text. */
        public void updateRemoteCatalog(List<Result> notes, List<Result> shared) {
            if (notes != null) this.remoteNotes = Collections.unmodifiableList(new ArrayList<>(notes));
            if (shared != null) this.remoteShared = Collections.unmodifiableList(new ArrayList<>(shared));
        }

        /**
         * Accepts a debounced remote search response only if {@code responseEpoch} matches the current
         * monotonic {@link #epoch}. Stale responses are silently discarded and return {@code false}.
         */
        public boolean acceptRemote(
                int responseEpoch,
                List<Result> sessionHits,
                List<Result> notes,
                List<Result> shared,
                boolean degraded) {
            if (responseEpoch != epoch) return false;
            this.remoteLoading = false;
            this.remoteDegraded = degraded;
            if (sessionHits != null) {
                this.remoteSessionHits = Collections.unmodifiableList(new ArrayList<>(sessionHits));
            }
            if (notes != null) {
                this.remoteNotes = Collections.unmodifiableList(new ArrayList<>(notes));
            }
            if (shared != null) {
                this.remoteShared = Collections.unmodifiableList(new ArrayList<>(shared));
            }
            return true;
        }

        public List<Result> results() {
            List<Result> remoteAll = new ArrayList<>(
                    remoteSessionHits.size() + remoteNotes.size() + remoteShared.size());
            remoteAll.addAll(remoteSessionHits);
            remoteAll.addAll(remoteNotes);
            remoteAll.addAll(remoteShared);
            return merge(activeQuery, activeCategory, localEntries, remoteAll);
        }
    }

    private UniversalSearchModel() {}

    /** Accent- and case-insensitive normalization for matching FR/EN queries. */
    public static String fold(String raw) {
        return PlainTextSanitizer.foldAccents(raw).trim();
    }

    /**
     * Merges, filters, scores, deduplicates, and sorts local + remote results for {@code rawQuery}
     * and {@code category}. Pure Java and deterministic on the host JVM.
     */
    public static List<Result> merge(
            String rawQuery,
            Category category,
            List<LocalEntry> localEntries,
            List<Result> remoteResults) {
        Category filter = category == null ? Category.ALL : category;
        String query = SearchResultModel.normalizeQuery(rawQuery);
        String foldedQuery = fold(query);
        String[] tokens = foldedQuery.isEmpty() ? new String[0] : WHITESPACE.split(foldedQuery);

        Map<String, Result> byKey = new LinkedHashMap<>();

        if (localEntries != null) {
            int suggestedCount = 0;
            for (LocalEntry entry : localEntries) {
                if (entry == null || entry.title().isEmpty()) continue;
                if (filter != Category.ALL && entry.category() != filter) continue;
                int score;
                if (tokens.length == 0) {
                    if (filter == Category.ALL && !entry.recent() && suggestedCount >= MAX_LOCAL_SUGGESTIONS) {
                        continue;
                    }
                    score = entry.recent() ? 50 : 20;
                    suggestedCount++;
                } else {
                    score = scoreText(entry.title(), entry.subtitle(), entry.keywords(), foldedQuery, tokens);
                    if (score <= 0) continue;
                    if (entry.recent()) score += 5;
                }
                String key = dedupeKeyForLocal(entry);
                Result candidate = new Result(
                        key,
                        entry.category(),
                        entry.title(),
                        entry.subtitle(),
                        entry.targetId(),
                        entry.actionId(),
                        "",
                        "",
                        "",
                        "",
                        false,
                        score,
                        Collections.emptyList());
                putBest(byKey, candidate);
            }
        }

        if (remoteResults != null) {
            for (Result remote : remoteResults) {
                if (remote == null || remote.title().isEmpty()) continue;
                if (filter != Category.ALL && remote.category() != filter) continue;
                int score;
                if (tokens.length == 0) {
                    // Blank query shows remote notes/shared items when browsing those categories or All.
                    if (remote.category() == Category.CHATS) continue;
                    score = remote.category() == Category.SHARED ? 35 : 30;
                } else if (remote.category() == Category.CHATS && remote.fromServer()) {
                    // Server FTS5 already matched the query inside message content; boost title matches.
                    int textScore = scoreText(remote.title(), remote.subtitle(), remote.ownerProfile(), foldedQuery, tokens);
                    score = Math.max(70, textScore + 15);
                } else {
                    String extra = remote.ownerProfile() + " " + remote.resourceKind() + " " + remote.permission();
                    score = scoreText(remote.title(), remote.subtitle(), extra, foldedQuery, tokens);
                    if (score <= 0) continue;
                }
                Result scored = new Result(
                        remote.dedupeKey().isEmpty() ? fallbackKey(remote) : remote.dedupeKey(),
                        remote.category(),
                        remote.title(),
                        remote.subtitle(),
                        remote.targetId(),
                        remote.actionId(),
                        remote.shareId(),
                        remote.resourceKind(),
                        remote.ownerProfile(),
                        remote.permission(),
                        remote.fromServer(),
                        score,
                        remote.snippetHighlights(),
                        remote.fallbackTitle());
                putBest(byKey, scored);
            }
        }

        List<Result> ordered = new ArrayList<>(byKey.values());
        ordered.sort(Comparator
                .comparingInt(Result::score).reversed()
                .thenComparingInt(r -> r.category().order)
                .thenComparing(r -> fold(r.title())));
        return Collections.unmodifiableList(ordered);
    }

    /** Groups ordered results by category in canonical display order for Material 3 section cards. */
    public static Map<Category, List<Result>> groupByCategory(List<Result> results) {
        Map<Category, List<Result>> grouped = new LinkedHashMap<>();
        if (results == null || results.isEmpty()) return Collections.emptyMap();
        for (Category cat : new Category[]{Category.CHATS, Category.NOTES, Category.SHARED, Category.SETTINGS, Category.TOOLS}) {
            List<Result> bucket = new ArrayList<>();
            for (Result r : results) {
                if (r != null && r.category() == cat) bucket.add(r);
            }
            if (!bucket.isEmpty()) grouped.put(cat, Collections.unmodifiableList(bucket));
        }
        return Collections.unmodifiableMap(grouped);
    }

    /** Parses `/api/sessions/search` response into unified chat results reusing {@link SearchResultModel#parseSnippet}. */
    public static List<Result> parseSessionSearchHits(JSONObject response, String untitledFallback) {
        return parseSessionSearchHitsMap(JsonValues.toMap(response), untitledFallback);
    }

    /** Pure-JVM parser for `/api/sessions/search` payloads. */
    public static List<Result> parseSessionSearchHitsMap(Map<String, Object> response, String untitledFallback) {
        if (response == null) return Collections.emptyList();
        List<?> data = JsonValues.asList(response.get("data"));
        if (data == null || data.isEmpty()) return Collections.emptyList();
        String fallback = safe(untitledFallback);
        List<Result> out = new ArrayList<>(data.size());
        for (Object item : data) {
            Map<?, ?> row = JsonValues.asMap(item);
            if (row == null) continue;
            String threadId = JsonValues.mapText(row, "thread_id");
            String sessionId = JsonValues.mapText(row, "session_id");
            String target = !threadId.isEmpty() ? threadId : sessionId;
            if (target.isEmpty()) continue;
            String title = JsonValues.mapText(row, "thread_title");
            boolean isFallback = title.isEmpty();
            if (isFallback) title = fallback;
            SearchResultModel.HighlightedSnippet snippet = SearchResultModel.parseSnippet(JsonValues.mapText(row, "snippet"));
            out.add(new Result(
                    "chat:" + target,
                    Category.CHATS,
                    title,
                    snippet.plainText(),
                    target,
                    "",
                    "",
                    UniversalShareModel.KIND_SESSION,
                    "",
                    "",
                    true,
                    70,
                    snippet.highlights(),
                    isFallback));
        }
        return Collections.unmodifiableList(out);
    }

    /** Parses `/api/notes` response into unified Smart Note results. */
    public static List<Result> parseNotesResults(JSONObject response, String untitledFallback) {
        return parseNotesResultsMap(JsonValues.toMap(response), untitledFallback);
    }

    /** Pure-JVM parser for `/api/notes` payloads. */
    public static List<Result> parseNotesResultsMap(Map<String, Object> response, String untitledFallback) {
        if (response == null) return Collections.emptyList();
        List<?> rows = JsonValues.asList(response.get("data"));
        if (rows == null) rows = JsonValues.asList(response.get("notes"));
        if (rows == null || rows.isEmpty()) return Collections.emptyList();
        String fallback = safe(untitledFallback);
        List<Result> out = new ArrayList<>(rows.size());
        for (Object item : rows) {
            Map<?, ?> row = JsonValues.asMap(item);
            if (row == null) continue;
            String id = JsonValues.mapText(row, "id");
            if (id.isEmpty()) id = JsonValues.mapText(row, "note_id");
            if (id.isEmpty()) continue;
            String title = JsonValues.mapText(row, "title");
            boolean isFallback = title.isEmpty();
            if (isFallback) title = fallback;
            String body = JsonValues.mapText(row, "body");
            if (body.isEmpty()) body = JsonValues.mapText(row, "content");
            String owner = JsonValues.mapText(row, "owner_profile");
            String permission = JsonValues.mapText(row, "permission");
            String subtitle = PlainTextSanitizer.collapseWithEllipsis(body, 140);
            out.add(new Result(
                    "note:" + id,
                    Category.NOTES,
                    title,
                    subtitle,
                    id,
                    "",
                    "",
                    UniversalShareModel.KIND_NOTE,
                    owner,
                    permission,
                    true,
                    30,
                    Collections.emptyList(),
                    isFallback));
        }
        return Collections.unmodifiableList(out);
    }

    /** Parses `/api/shares/incoming` response into unified Shared results. */
    public static List<Result> parseSharedResults(JSONObject response) {
        return parseSharedResultsMap(JsonValues.toMap(response));
    }

    /** Pure-JVM parser for `/api/shares/incoming` payloads. */
    public static List<Result> parseSharedResultsMap(Map<String, Object> response) {
        if (response == null) return Collections.emptyList();
        List<?> rows = JsonValues.asList(response.get("data"));
        if (rows == null) rows = JsonValues.asList(response.get("shares"));
        if (rows == null || rows.isEmpty()) return Collections.emptyList();
        List<Result> out = new ArrayList<>(rows.size());
        for (Object item : rows) {
            Map<?, ?> row = JsonValues.asMap(item);
            if (row == null) continue;
            String shareId = JsonValues.mapText(row, "share_id");
            if (shareId.isEmpty()) shareId = JsonValues.mapText(row, "id");
            if (shareId.isEmpty()) continue;
            String kind = JsonValues.mapText(row, "resource_kind").toLowerCase(Locale.ROOT);
            String resourceId = JsonValues.mapText(row, "resource_id");
            String label = JsonValues.mapText(row, "label");
            if (label.isEmpty()) label = resourceId;
            if (label.isEmpty()) continue;
            String owner = JsonValues.mapText(row, "owner_profile");
            String perm = JsonValues.mapText(row, "permission").toLowerCase(Locale.ROOT);
            if (perm.isEmpty()) perm = UniversalShareModel.PERM_READ;
            out.add(new Result(
                    "shared:" + shareId,
                    Category.SHARED,
                    label,
                    owner,
                    resourceId,
                    "",
                    shareId,
                    kind,
                    owner,
                    perm,
                    true,
                    35,
                    Collections.emptyList()));
        }
        return Collections.unmodifiableList(out);
    }

    private static int scoreText(String title, String subtitle, String keywords, String foldedQuery, String[] tokens) {
        String fTitle = fold(title);
        String fSub = fold(subtitle);
        String fKeys = fold(keywords);
        String combined = fTitle + " " + fSub + " " + fKeys;
        for (String token : tokens) {
            if (!token.isEmpty() && !combined.contains(token)) return 0;
        }
        if (fTitle.equals(foldedQuery)) return 100;
        if (fTitle.startsWith(foldedQuery)) return 85;
        if (fTitle.contains(foldedQuery)) return 65;
        if (fSub.contains(foldedQuery)) return 45;
        if (fKeys.contains(foldedQuery)) return 30;
        // Multi-token match split across fields.
        int titleHits = 0;
        for (String token : tokens) {
            if (fTitle.contains(token)) titleHits++;
        }
        return titleHits > 0 ? 55 + Math.min(20, titleHits * 5) : 35;
    }

    private static void putBest(Map<String, Result> byKey, Result candidate) {
        Result existing = byKey.get(candidate.dedupeKey());
        if (existing == null) {
            byKey.put(candidate.dedupeKey(), candidate);
            return;
        }
        // Prefer richer server snippet when both exist for the same chat/note, never letting a
        // synthetic fallback title overwrite a real non-fallback title.
        boolean candidateRicher = candidate.fromServer() && !candidate.subtitle().isEmpty()
                && (!existing.fromServer() || existing.subtitle().isEmpty());
        if (candidateRicher || candidate.score() > existing.score()) {
            boolean useExistingTitle = !existing.title().isEmpty()
                    && (candidate.title().isEmpty() || (candidate.fallbackTitle() && !existing.fallbackTitle()));
            String bestTitle = useExistingTitle ? existing.title() : candidate.title();
            boolean bestFallback = useExistingTitle ? existing.fallbackTitle() : candidate.fallbackTitle();
            String bestSub = !candidate.subtitle().isEmpty() ? candidate.subtitle() : existing.subtitle();
            List<SearchResultModel.Span> bestSpans = !candidate.snippetHighlights().isEmpty()
                    ? candidate.snippetHighlights() : existing.snippetHighlights();
            byKey.put(candidate.dedupeKey(), new Result(
                    candidate.dedupeKey(),
                    candidate.category(),
                    bestTitle,
                    bestSub,
                    !candidate.targetId().isEmpty() ? candidate.targetId() : existing.targetId(),
                    !candidate.actionId().isEmpty() ? candidate.actionId() : existing.actionId(),
                    !candidate.shareId().isEmpty() ? candidate.shareId() : existing.shareId(),
                    !candidate.resourceKind().isEmpty() ? candidate.resourceKind() : existing.resourceKind(),
                    !candidate.ownerProfile().isEmpty() ? candidate.ownerProfile() : existing.ownerProfile(),
                    !candidate.permission().isEmpty() ? candidate.permission() : existing.permission(),
                    candidate.fromServer() || existing.fromServer(),
                    Math.max(candidate.score(), existing.score()),
                    bestSpans,
                    bestFallback));
        }
    }

    private static String dedupeKeyForLocal(LocalEntry entry) {
        if (entry.category() == Category.CHATS && !entry.targetId().isEmpty()) {
            return "chat:" + entry.targetId();
        }
        if (entry.category() == Category.NOTES && !entry.targetId().isEmpty()) {
            return "note:" + entry.targetId();
        }
        if (!entry.id().isEmpty()) return entry.id();
        return entry.category().name().toLowerCase(Locale.ROOT) + ":" + fold(entry.title());
    }

    private static String fallbackKey(Result result) {
        String id = !result.shareId().isEmpty() ? result.shareId()
                : !result.targetId().isEmpty() ? result.targetId()
                : !result.actionId().isEmpty() ? result.actionId()
                : fold(result.title());
        return result.category().name().toLowerCase(Locale.ROOT) + ":" + id;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
