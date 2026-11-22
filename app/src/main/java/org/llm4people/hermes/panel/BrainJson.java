package org.llm4people.hermes.panel;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.llm4people.hermes.Diagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Centralized JSON codecs for Hermes Brain Studio wire contracts (Fork PR #12/#13/#14). */
final class BrainJson {
    private BrainJson() {}

    record WorkspaceEntry(String path, String kind, long sizeBytes, String language, String gitStatus) {
        boolean isDirectory() {
            return "dir".equalsIgnoreCase(kind) || "directory".equalsIgnoreCase(kind);
        }
    }

    record WorkspaceTree(String rootLabel, List<WorkspaceEntry> entries) {}

    record WorkspaceFile(String path, String language, int totalLines, String content, boolean truncated) {}

    record DiffFile(String path, String status, int additions, int deletions) {}

    record WorkspaceDiff(String branch, int ahead, int behind, List<DiffFile> files, String diffText) {}

    record ProfileSummary(
            String name,
            String description,
            String defaultProvider,
            String defaultModel,
            List<String> enabledToolsets,
            String reasoningEffort,
            boolean memoryEnabled,
            boolean voiceEnabled,
            String soulPreview) {}

    record AnalyticsSummary(
            int days,
            double actualCostUsd,
            double estimatedCostUsd,
            double avgTtftMs,
            double avgTps,
            double sessions,
            double apiCalls,
            double inputTokens,
            double outputTokens,
            double cacheReadTokens,
            double cacheWriteTokens,
            double reasoningTokens) {}

    static BrainCapabilities parseCapabilities(JSONObject response) {
        if (response == null) return BrainCapabilities.empty();
        JSONObject features = response.optJSONObject("features");
        if (features == null) return BrainCapabilities.empty();
        Map<String, Boolean> flags = new LinkedHashMap<>();
        Iterator<String> keys = features.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object raw = features.opt(key);
            if (raw instanceof Boolean enabled) {
                flags.put(key, enabled);
            }
        }
        return BrainCapabilities.fromFlags(flags);
    }

    static MemoryModel.Snapshot parseMemorySnapshot(JSONObject response) throws JSONException {
        if (response == null) throw new JSONException("Empty memory response");
        JSONObject root = response.optJSONObject("snapshot") != null ? response.optJSONObject("snapshot") : response;
        JSONObject memoryObj = root.optJSONObject(MemoryModel.TARGET_MEMORY);
        JSONObject userObj = root.optJSONObject(MemoryModel.TARGET_USER);
        if (memoryObj == null && userObj == null) throw new JSONException("Missing memory buckets");
        MemoryModel.Bucket memory = parseMemoryBucket(MemoryModel.TARGET_MEMORY, memoryObj);
        MemoryModel.Bucket user = parseMemoryBucket(MemoryModel.TARGET_USER, userObj);
        return new MemoryModel.Snapshot(memory, user);
    }

    private static MemoryModel.Bucket parseMemoryBucket(String target, JSONObject bucket) {
        if (bucket == null) {
            return new MemoryModel.Bucket(target, Collections.emptyList(), 0, MemoryModel.DEFAULT_CHAR_LIMIT, false);
        }
        JSONArray rows = bucket.optJSONArray("entries");
        List<MemoryModel.Entry> entries = new ArrayList<>();
        if (rows != null) {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                String id = PanelData.text(row, "id");
                String text = PanelData.text(row, "text");
                boolean pinned = row.optBoolean("pinned", row.optBoolean("pin", false));
                if (!id.isEmpty() && !text.isEmpty()) {
                    entries.add(new MemoryModel.Entry(id, text, pinned));
                }
            }
        }
        int chars = bucket.optInt("chars", 0);
        int limit = bucket.optInt("limit", MemoryModel.DEFAULT_CHAR_LIMIT);
        boolean enabled = bucket.optBoolean("enabled", true);
        return new MemoryModel.Bucket(target, entries, chars, limit, enabled);
    }

    static SearchResultModel.Page parseSearchPage(JSONObject response) throws JSONException {
        if (response == null) throw new JSONException("Empty search response");
        JSONArray data = response.optJSONArray("data");
        if (data == null) throw new JSONException("Missing search data array");
        List<SearchResultModel.Hit> hits = new ArrayList<>(data.length());
        int skipped = 0;
        for (int i = 0; i < data.length(); i++) {
            JSONObject row = data.optJSONObject(i);
            if (row == null) { skipped++; continue; }
            String sessionId = PanelData.text(row, "session_id");
            String threadId = PanelData.text(row, "thread_id");
            if (sessionId.isEmpty() && threadId.isEmpty()) { skipped++; continue; }
            String title = PanelData.text(row, "thread_title");
            String messageId = optScalarString(row, "message_id");
            String role = PanelData.text(row, "role");
            String rawSnippet = PanelData.text(row, "snippet");
            String timestamp = optScalarString(row, "timestamp");
            hits.add(new SearchResultModel.Hit(
                    sessionId,
                    threadId,
                    title,
                    messageId,
                    role,
                    SearchResultModel.parseSnippet(rawSnippet),
                    timestamp));
        }
        if (data.length() > 0 && hits.isEmpty() && skipped == data.length()) {
            throw new JSONException("All search rows malformed");
        }
        int total = response.optInt("total", hits.size());
        String query = PanelData.text(response, "query");
        return new SearchResultModel.Page(hits, total, query);
    }

    static SkillStudioModel.SkillDetail parseSkillDetail(JSONObject response) throws JSONException {
        if (response == null) throw new JSONException("Empty skill detail");
        String name = PanelData.text(response, "name");
        if (name.isEmpty()) throw new JSONException("Missing skill name");
        String category = PanelData.text(response, "category");
        String description = PanelData.text(response, "description");
        String body = response.opt("body") instanceof String s ? s : "";
        String raw = response.opt("raw") instanceof String s ? s : body;
        boolean enabled = response.optBoolean("enabled", true);
        boolean writable = response.optBoolean("writable", false);
        String source = PanelData.text(response, "source");
        List<String> tags = stringList(response.optJSONArray("tags"));
        List<String> supportingFiles = stringList(response.optJSONArray("supporting_files"));
        List<String> lintWarnings = parseLintWarnings(response.optJSONArray("lint_warnings"));
        return new SkillStudioModel.SkillDetail(
                name, category, description, tags, body, raw, enabled, writable, source, supportingFiles, lintWarnings);
    }

    static List<String> parseLintWarnings(JSONArray array) {
        if (array == null || array.length() == 0) return Collections.emptyList();
        List<String> warnings = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            Object item = array.opt(i);
            if (item instanceof String text && !text.trim().isEmpty()) {
                warnings.add(text.trim());
            } else if (item instanceof JSONObject obj) {
                String msg = PanelData.text(obj, "message");
                if (msg.isEmpty()) msg = PanelData.text(obj, "warning");
                if (!msg.isEmpty()) warnings.add(msg);
            }
        }
        return Collections.unmodifiableList(warnings);
    }

    static WorkspaceTree parseWorkspaceTree(JSONObject response) throws JSONException {
        if (response == null) throw new JSONException("Empty workspace tree");
        JSONArray rows = response.optJSONArray("entries");
        if (rows == null) throw new JSONException("Missing workspace entries");
        String rootLabel = PanelData.text(response, "root_label");
        List<WorkspaceEntry> entries = new ArrayList<>(rows.length());
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            String rawPath = PanelData.text(row, "path");
            if (rawPath.isEmpty()) continue;
            String safePath;
            try {
                safePath = WorkspacePathGuard.normalizeRelativePath(rawPath, false);
            } catch (IllegalArgumentException rejected) {
                Diagnostics.debug("workspace", "filtered_unsafe_entry");
                continue;
            }
            String kind = PanelData.text(row, "kind");
            if (kind.isEmpty()) kind = "file";
            long size = Math.max(0L, row.optLong("size", 0L));
            String lang = PanelData.text(row, "language");
            String gitStatus = PanelData.text(row, "git_status");
            entries.add(new WorkspaceEntry(safePath, kind, size, lang, gitStatus));
        }
        return new WorkspaceTree(rootLabel, Collections.unmodifiableList(entries));
    }

    static WorkspaceFile parseWorkspaceFile(JSONObject response) throws JSONException {
        if (response == null) throw new JSONException("Empty workspace file");
        String path = PanelData.text(response, "path");
        if (path.isEmpty()) throw new JSONException("Missing workspace file path");
        String safePath = WorkspacePathGuard.normalizeRelativePath(path, false);
        String language = WorkspacePathGuard.inferLanguage(safePath, PanelData.text(response, "language"));
        int totalLines = Math.max(0, response.optInt("total_lines", 0));
        boolean truncated = response.optBoolean("truncated", false);
        String content = extractFileContent(response);
        if (content.length() > WorkspacePathGuard.MAX_FILE_CHARS) truncated = true;
        return new WorkspaceFile(safePath, language, totalLines, WorkspacePathGuard.boundContent(content), truncated);
    }

    private static String extractFileContent(JSONObject response) {
        Object rawLines = response.opt("lines");
        if (rawLines instanceof String text) return text;
        if (rawLines instanceof JSONArray array) {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < array.length(); i++) {
                if (i > 0) out.append('\n');
                Object line = array.opt(i);
                if (line instanceof String s) out.append(s);
                else if (line instanceof JSONObject obj && obj.opt("text") instanceof String s) out.append(s);
                if (out.length() > WorkspacePathGuard.MAX_FILE_CHARS) break;
            }
            return out.toString();
        }
        return response.opt("content") instanceof String text ? text : "";
    }

    static WorkspaceDiff parseWorkspaceDiff(JSONObject response) throws JSONException {
        if (response == null) throw new JSONException("Empty workspace diff");
        String branch = PanelData.text(response, "branch");
        int ahead = Math.max(0, response.optInt("ahead", 0));
        int behind = Math.max(0, response.optInt("behind", 0));
        JSONArray rows = response.optJSONArray("files");
        List<DiffFile> files = new ArrayList<>();
        if (rows != null) {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                String rawPath = PanelData.text(row, "path");
                if (rawPath.isEmpty()) continue;
                String safePath;
                try {
                    safePath = WorkspacePathGuard.normalizeRelativePath(rawPath, false);
                } catch (IllegalArgumentException rejected) {
                    Diagnostics.debug("workspace", "filtered_unsafe_diff_entry");
                    continue;
                }
                String status = PanelData.text(row, "status");
                int adds = Math.max(0, row.optInt("additions", 0));
                int dels = Math.max(0, row.optInt("deletions", 0));
                files.add(new DiffFile(safePath, status, adds, dels));
            }
        }
        String diffText = response.opt("diff") instanceof String s ? WorkspacePathGuard.boundContent(s) : "";
        return new WorkspaceDiff(branch, ahead, behind, Collections.unmodifiableList(files), diffText);
    }

    /**
     * Parse models for a provider row from /api/model/options.
     * Supports BOTH legacy string arrays (`models: ["gpt-4o"]`) and enriched metadata
     * (`models: [{id, capabilities, pricing}]` or sibling `model_details`/`model_metadata` map).
     */
    static List<ModelCapabilityBadges.ModelInfo> parseProviderModels(JSONObject providerRow) {
        if (providerRow == null) return Collections.emptyList();
        JSONObject detailsMap = providerRow.optJSONObject("model_details");
        if (detailsMap == null) detailsMap = providerRow.optJSONObject("model_metadata");
        JSONArray models = providerRow.optJSONArray("models");
        if (models == null) return Collections.emptyList();

        List<ModelCapabilityBadges.ModelInfo> out = new ArrayList<>(models.length());
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < models.length(); i++) {
            Object entry = models.opt(i);
            if (entry instanceof String id) {
                String clean = id.trim();
                if (clean.isEmpty() || !seen.add(clean)) continue;
                JSONObject detail = detailsMap == null ? null : detailsMap.optJSONObject(clean);
                out.add(parseModelObject(clean, detail));
            } else if (entry instanceof JSONObject obj) {
                String id = PanelData.text(obj, "id");
                if (id.isEmpty()) id = PanelData.text(obj, "name");
                if (id.isEmpty() || !seen.add(id)) continue;
                out.add(parseModelObject(id, obj));
            }
        }
        return Collections.unmodifiableList(out);
    }

    static ModelCapabilityBadges.ModelInfo parseModelObject(String id, JSONObject obj) {
        if (obj == null) return ModelCapabilityBadges.ModelInfo.minimal(id);
        String label = PanelData.text(obj, "label");
        if (label.isEmpty()) label = PanelData.text(obj, "display_name");
        if (label.isEmpty()) label = id;
        JSONObject caps = obj.optJSONObject("capabilities");
        JSONObject pricing = obj.optJSONObject("pricing");
        int contextLength = caps != null ? caps.optInt("context_length", 0) : obj.optInt("context_length", 0);
        int maxOut = caps != null ? caps.optInt("max_output_tokens", 0) : obj.optInt("max_output_tokens", 0);
        boolean vision = caps != null ? caps.optBoolean("vision", false) : obj.optBoolean("vision", false);
        boolean tools = caps != null ? caps.optBoolean("tools", false) : obj.optBoolean("tools", false);
        boolean reasoning = caps != null ? caps.optBoolean("reasoning", false) : obj.optBoolean("reasoning", false);
        double inPrice = pricing != null ? PanelData.measure(pricing, "input_per_1m") : Double.NaN;
        double outPrice = pricing != null ? PanelData.measure(pricing, "output_per_1m") : Double.NaN;
        return new ModelCapabilityBadges.ModelInfo(
                id, label, contextLength, maxOut, vision, tools, reasoning, inPrice, outPrice);
    }

    static ProfileSummary parseProfileSummary(JSONObject response) throws JSONException {
        if (response == null) throw new JSONException("Empty profile response");
        JSONObject root = response.optJSONObject("profile") != null ? response.optJSONObject("profile") : response;
        String name = PanelData.text(root, "name");
        String description = PanelData.text(root, "description");
        String provider = PanelData.text(root, "default_provider");
        if (provider.isEmpty()) provider = PanelData.text(root, "provider");
        String model = PanelData.text(root, "default_model");
        if (model.isEmpty()) model = PanelData.text(root, "model");
        List<String> toolsets = stringList(root.optJSONArray("enabled_toolsets"));
        String effort = PanelData.text(root, "reasoning_effort");
        boolean memoryEnabled = root.optBoolean("memory_enabled", false);
        boolean voiceEnabled = root.optBoolean("voice_enabled", false);
        String soulPreview = PanelData.text(root, "soul_preview");
        return new ProfileSummary(
                name, description, provider, model, toolsets, effort, memoryEnabled, voiceEnabled, soulPreview);
    }

    static AnalyticsSummary parseAnalyticsSummary(JSONObject response, int requestedDays) {
        if (response == null) {
            return new AnalyticsSummary(requestedDays, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }
        int periodDays = response.has("period_days")
                ? response.optInt("period_days", requestedDays)
                : response.optInt("days", requestedDays);
        JSONObject totals = response.optJSONObject("totals") != null ? response.optJSONObject("totals") : response;
        return new AnalyticsSummary(
                periodDays,
                firstMeasure(totals, "total_actual_cost", "actual_cost_usd", "cost_usd"),
                firstMeasure(totals, "total_estimated_cost", "estimated_cost_usd"),
                firstMeasure(totals, "avg_ttft_ms", "ttft_ms"),
                firstMeasure(totals, "avg_tps", "tokens_per_sec"),
                firstMeasure(totals, "total_sessions", "sessions", "runs"),
                firstMeasure(totals, "total_api_calls", "api_calls"),
                firstMeasure(totals, "total_input", "input_tokens"),
                firstMeasure(totals, "total_output", "output_tokens"),
                firstMeasure(totals, "total_cache_read", "cache_read_tokens"),
                firstMeasure(totals, "total_cache_write", "cache_write_tokens"),
                firstMeasure(totals, "total_reasoning", "reasoning_tokens"));
    }

    private static double firstMeasure(JSONObject source, String... keys) {
        for (String key : keys) {
            double value = PanelData.measure(source, key);
            if (Double.isFinite(value)) return value;
        }
        return Double.NaN;
    }

    static UsageFormatter.RunTelemetry parseRunTelemetry(JSONObject timing) {
        if (timing == null) {
            return new UsageFormatter.RunTelemetry(Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }
        double ttft = PanelData.measure(timing, "ttft_ms");
        if (Double.isNaN(ttft)) ttft = PanelData.measure(timing, "first_ms");
        double elapsed = PanelData.measure(timing, "duration_ms");
        if (Double.isNaN(elapsed)) elapsed = PanelData.measure(timing, "elapsed_ms");
        double outTokens = PanelData.measure(timing, "output_tokens");
        double serverTps = PanelData.measure(timing, "tokens_per_sec");
        double reasoning = PanelData.measure(timing, "reasoning_tokens");
        double apiCalls = PanelData.measure(timing, "api_calls");
        return new UsageFormatter.RunTelemetry(ttft, elapsed, outTokens, serverTps, reasoning, apiCalls);
    }

    static List<NotesModel.Note> parseNotes(JSONObject response, boolean sharedList) throws JSONException {
        if (response == null) throw new JSONException("Empty notes response");
        JSONArray rows = response.optJSONArray("data");
        if (rows == null) rows = response.optJSONArray("notes");
        if (rows == null) throw new JSONException("Missing notes array");
        List<NotesModel.Note> list = new ArrayList<>(rows.length());
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            String id = PanelData.text(row, "id");
            if (id.isEmpty()) id = PanelData.text(row, "note_id");
            if (id.isEmpty()) continue;
            String title = PanelData.text(row, "title");
            String body = row.opt("body") instanceof String s ? s : PanelData.text(row, "content");
            List<String> tags = stringList(row.optJSONArray("tags"));
            boolean pinned = row.optBoolean("pinned", false);
            String updatedAt = optScalarString(row, "updated_at");
            String owner = PanelData.text(row, "owner_profile");
            String perm = PanelData.text(row, "permission");
            if (sharedList && perm.isEmpty()) perm = UniversalShareModel.PERM_READ;
            list.add(new NotesModel.Note(id, title, body, tags, pinned, updatedAt, owner, perm));
        }
        return Collections.unmodifiableList(list);
    }

    static List<UniversalShareModel.ShareGrant> parseShareList(JSONObject response) throws JSONException {
        if (response == null) throw new JSONException("Empty shares response");
        JSONArray rows = response.optJSONArray("data");
        if (rows == null) rows = response.optJSONArray("shares");
        if (rows == null) throw new JSONException("Missing shares array");
        List<UniversalShareModel.ShareGrant> list = new ArrayList<>(rows.length());
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            String shareId = PanelData.text(row, "share_id");
            if (shareId.isEmpty()) shareId = PanelData.text(row, "id");
            if (shareId.isEmpty()) continue;
            String kind = PanelData.text(row, "resource_kind");
            String resourceId = PanelData.text(row, "resource_id");
            String label = PanelData.text(row, "label");
            String owner = PanelData.text(row, "owner_profile");
            String target = PanelData.text(row, "target_profile");
            String perm = PanelData.text(row, "permission");
            String createdAt = optScalarString(row, "created_at");
            list.add(new UniversalShareModel.ShareGrant(
                    shareId, kind, resourceId, label, owner, target, perm, createdAt));
        }
        return Collections.unmodifiableList(list);
    }

    static List<NotesModel.HabitSuggestion> parseHabitSuggestions(JSONObject response) {
        if (response == null) return Collections.emptyList();
        JSONArray rows = response.optJSONArray("suggestions");
        if (rows == null) rows = response.optJSONArray("data");
        if (rows == null) return Collections.emptyList();
        List<NotesModel.HabitSuggestion> list = new ArrayList<>(rows.length());
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            String id = PanelData.text(row, "id");
            String category = PanelData.text(row, "category");
            String action = PanelData.text(row, "action");
            if (id.isEmpty() && (!category.isEmpty() || !action.isEmpty())) {
                id = category.isEmpty() ? action : category + ":" + action;
            }
            String label = PanelData.text(row, "label");
            if (label.isEmpty()) label = PanelData.text(row, "title");
            if (label.isEmpty()) label = NotesModel.friendlyHabitLabel(category, action);
            String hint = PanelData.text(row, "hint");
            String routeId = PanelData.text(row, "route_id");
            String draft = PanelData.text(row, "draft");
            if (draft.isEmpty()) draft = PanelData.text(row, "prompt");
            if (draft.isEmpty()) draft = NotesModel.friendlyHabitDraft(category, action);
            int count = row.optInt("count", 0);
            list.add(new NotesModel.HabitSuggestion(id, label, hint, routeId, draft, count));
        }
        return NotesModel.filterLearned(list);
    }

    private static List<String> stringList(JSONArray array) {
        if (array == null || array.length() == 0) return Collections.emptyList();
        List<String> list = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            Object raw = array.opt(i);
            if (raw instanceof String s && !s.trim().isEmpty()) list.add(s.trim());
        }
        return Collections.unmodifiableList(list);
    }

    private static String optScalarString(JSONObject obj, String key) {
        Object val = obj.opt(key);
        if (val == null || val == JSONObject.NULL) return "";
        if (val instanceof String s) return s.trim();
        if (val instanceof Number || val instanceof Boolean) return String.valueOf(val);
        return "";
    }
}
