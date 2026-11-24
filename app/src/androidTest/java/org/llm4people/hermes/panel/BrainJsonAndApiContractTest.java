package org.llm4people.hermes.panel;

import android.content.Context;
import android.test.InstrumentationTestCase;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.llm4people.hermes.R;
import org.llm4people.hermes.net.Exchange;
import org.llm4people.hermes.net.HermesApi;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

@SuppressWarnings("deprecation")
public final class BrainJsonAndApiContractTest extends InstrumentationTestCase {
    private static final String JSON = "Content-Type: application/json\r\n";

    public void testCapabilitiesMemorySearchAndNotesParsing() throws Exception {
        BrainCapabilities caps = BrainJson.parseCapabilities(new JSONObject("""
                {"features":{
                  "memory_api":true,"session_search":true,"skill_studio":true,
                  "workspace_browser":true,"profile_defaults":true,"smart_notes":true,
                  "universal_sharing":true,"habit_suggestions":true,"usage_analytics":false}}
                """));
        assertTrue(caps.memoryApi());
        assertTrue(caps.workspaceFiles());
        assertTrue(caps.smartNotes());
        assertTrue(caps.universalSharing());
        assertFalse(caps.usageAnalytics());

        MemoryModel.Snapshot mem = BrainJson.parseMemorySnapshot(new JSONObject("""
                {"memory":{"entries":[{"id":"a1","text":"Aime le thé","pinned":true}],"chars":11,"limit":4000,"enabled":true},
                 "user":{"entries":[{"id":"u1","text":"Développeur Android","pinned":false}],"chars":19,"limit":2000,"enabled":true}}
                """));
        assertEquals(1, mem.memory().entries().size());
        assertTrue(mem.memory().entries().get(0).pinned());
        assertEquals("Développeur Android", mem.user().entries().get(0).text());

        SearchResultModel.Page search = BrainJson.parseSearchPage(new JSONObject("""
                {"query":"montreal","total":2,"data":[
                  {"session_id":"s1","thread_id":"t1","thread_title":"Voyage","message_id":9,"role":"user",
                   "snippet":"Départ pour <mark>Montréal</mark>","timestamp":"2026-11-22T14:00:00Z"}]}
                """));
        assertEquals(1, search.hits().size());
        assertEquals("t1", search.hits().get(0).targetSessionId());
        assertEquals("Départ pour Montréal", search.hits().get(0).snippet().plainText());
        assertTrue(search.hasMore(1));

        // Malformed search payload fails closed instead of silently returning empty.
        try {
            BrainJson.parseSearchPage(new JSONObject("{\"data\":[{\"role\":\"user\"}]}"));
            fail("Expected malformed search payload rejection");
        } catch (JSONException expected) {}

        // Empty valid search payload succeeds cleanly.
        assertTrue(BrainJson.parseSearchPage(new JSONObject("{\"data\":[],\"total\":0,\"query\":\"rien\"}")).hits().isEmpty());

        // Habit suggestions drop count < 3 and map fork-brain (category, action) rows into localized resources.
        Context ctx = getInstrumentation().getTargetContext();
        List<NotesModel.HabitSuggestion> habits = BrainJson.parseHabitSuggestions(new JSONObject("""
                {"object":"list","threshold":3,"data":[
                  {"category":"share","action":"summarize","count":2},
                  {"category":"share","action":"save_note","count":4}]}
                """));
        assertEquals(1, habits.size());
        assertEquals("share:save_note", habits.get(0).id());
        assertEquals(ctx.getString(R.string.ws_habit_save_note), habits.get(0).displayLabel(ctx));

        // Universal share content unwraps nested content objects, text_preview, binary metadata, and spec JSON.
        String sharedNoteText = ShareWithUserSheet.extractReadableContent(ctx, new JSONObject("""
                {"object":"hermes.share.content","share":{"share_id":"sh1"},
                 "content":{"id":"n1","title":"Courses","content":"Pain et café"}}
                """));
        assertEquals("Pain et café", sharedNoteText);

        String artifactPreview = ShareWithUserSheet.extractReadableContent(ctx, new JSONObject("""
                {"object":"hermes.share.content","content":{"filename":"notes.txt","text_preview":"Ligne 1\\nLigne 2"}}
                """));
        assertEquals("Ligne 1\nLigne 2", artifactPreview);

        String binaryArtifact = ShareWithUserSheet.extractReadableContent(ctx, new JSONObject("""
                {"object":"hermes.share.content","content":{"filename":"rapport.pdf","mime_type":"application/pdf","size":4096,"is_binary":true}}
                """));
        assertEquals("rapport.pdf · application/pdf · " + ctx.getString(R.string.ws_share_bytes, 4096L), binaryArtifact);

        String surfaceSpec = ShareWithUserSheet.extractReadableContent(ctx, new JSONObject("""
                {"object":"hermes.share.content","content":{"title":"Tableau météo","spec":{"kind":"widget","metric":"22°C"}}}
                """));
        assertTrue(surfaceSpec.startsWith("Tableau météo\n\n"));
        assertTrue(surfaceSpec.contains("\"metric\": \"22°C\""));

        // Notes parser maps Fork's `access` field when `permission` is absent.
        List<NotesModel.Note> sharedNotes = BrainJson.parseNotes(new JSONObject("""
                {"data":[
                  {"id":"n1","title":"Partagée modifiable","body":"Texte","owner_profile":"julie","access":"edit"},
                  {"id":"n2","title":"Partagée lecture","body":"Texte","owner_profile":"julie","access":"read"}]}
                """), true);
        assertEquals(2, sharedNotes.size());
        assertTrue(sharedNotes.get(0).canEdit());
        assertFalse(sharedNotes.get(0).canDelete());
        assertFalse(sharedNotes.get(1).canEdit());

        // Search pagination respects explicit has_more even when total == page size.
        SearchResultModel.Page paged = BrainJson.parseSearchPage(new JSONObject("""
                {"query":"montreal","total":1,"has_more":true,"data":[
                  {"session_id":"s1","thread_id":"t1","thread_title":"Voyage","message_id":9,"role":"user",
                   "snippet":"<mark>Montréal</mark>","timestamp":"2026-11-22T14:00:00Z"}]}
                """));
        assertTrue(paged.hasMore(1));
    }

    public void testAnalyticsSummaryParsesForkBrainTotalsAndPeriodDays() throws Exception {
        BrainJson.AnalyticsSummary summary = BrainJson.parseAnalyticsSummary(new JSONObject("""
                {"object":"hermes.analytics.usage","period_days":30,"session_id":null,"totals":{
                  "total_input":12400,"total_output":3200,"total_cache_read":8000,"total_cache_write":400,
                  "total_reasoning":512,"total_estimated_cost":0.145,"total_actual_cost":0.132,
                  "total_sessions":9,"total_api_calls":27}}
                """), 7);
        assertEquals(30, summary.days());
        assertEquals(0.132, summary.actualCostUsd(), 1e-9);
        assertEquals(0.145, summary.estimatedCostUsd(), 1e-9);
        assertEquals(9.0, summary.sessions(), 0);
        assertEquals(27.0, summary.apiCalls(), 0);
        assertEquals(12400.0, summary.inputTokens(), 0);
        assertEquals(3200.0, summary.outputTokens(), 0);
        assertEquals(8000.0, summary.cacheReadTokens(), 0);
        assertEquals(400.0, summary.cacheWriteTokens(), 0);
        assertEquals(512.0, summary.reasoningTokens(), 0);
        assertTrue(Double.isNaN(summary.avgTtftMs()));
        assertTrue(Double.isNaN(summary.avgTps()));

        // Missing/null actual cost stays NaN (never 0) while preserving known estimated cost.
        BrainJson.AnalyticsSummary partial = BrainJson.parseAnalyticsSummary(new JSONObject("""
                {"period_days":14,"totals":{"total_actual_cost":null,"total_estimated_cost":0.05,"total_sessions":2}}
                """), 30);
        assertEquals(14, partial.days());
        assertTrue(Double.isNaN(partial.actualCostUsd()));
        assertEquals(0.05, partial.estimatedCostUsd(), 1e-9);
        assertEquals(2.0, partial.sessions(), 0);
        assertTrue(Double.isNaN(partial.inputTokens()));
    }

    public void testWorkspaceTreeFiltersUnsafeEntriesAndModelBadgesHandleBothShapes() throws Exception {
        BrainJson.WorkspaceTree tree = BrainJson.parseWorkspaceTree(new JSONObject("""
                {"root_label":"hermes-mobile","entries":[
                  {"path":"app/src/Main.java","kind":"file","size":1200,"git_status":"M"},
                  {"path":"../etc/passwd","kind":"file","size":50},
                  {"path":".env","kind":"file","size":20},
                  {"path":".git/config","kind":"file","size":100}]}
                """));
        assertEquals(1, tree.entries().size());
        assertEquals("app/src/Main.java", tree.entries().get(0).path());

        List<ModelCapabilityBadges.ModelInfo> legacy = BrainJson.parseProviderModels(
                new JSONObject("{\"slug\":\"openai\",\"models\":[\"gpt-4o\",\"o3-mini\"]}"));
        assertEquals(2, legacy.size());
        assertEquals("gpt-4o", legacy.get(0).toString());

        Context context = getInstrumentation().getTargetContext();
        List<ModelCapabilityBadges.ModelInfo> rich = BrainJson.parseProviderModels(new JSONObject("""
                {"slug":"anthropic","models":[
                  {"id":"claude-sonnet","capabilities":{"context_length":200000,"vision":true,"tools":true},
                   "pricing":{"input_per_1m":3.0,"output_per_1m":15.0}}]}
                """));
        assertEquals(1, rich.size());
        assertTrue(rich.get(0).badges(context).contains(context.getString(R.string.ws_badge_vision)));
        assertTrue(rich.get(0).badges(context).contains(context.getString(R.string.ws_badge_tools)));
        assertTrue(rich.get(0).badges(context).contains(context.getString(R.string.ws_badge_long_context, "200k")));

        // Fork /api/model/options emits sibling `capabilities` and `pricing` maps keyed by model ID.
        List<ModelCapabilityBadges.ModelInfo> forkShape = BrainJson.parseProviderModels(new JSONObject("""
                {"slug":"anthropic",
                 "models":["claude-sonnet","local-free","unknown-price"],
                 "capabilities":{
                   "claude-sonnet":{"context_length":200000,"max_output_tokens":8192,"vision":true,"tools":true,"reasoning":true},
                   "local-free":{"context_length":32000,"vision":false,"tools":true,"reasoning":false}},
                 "pricing":{
                   "claude-sonnet":{"input_per_1m":3.0,"output_per_1m":15.0,"free":false},
                   "local-free":{"free":true},
                   "unknown-price":{"input":"","output":"","free":false}}}
                """));
        assertEquals(3, forkShape.size());
        assertTrue(forkShape.get(0).badges(context).contains(context.getString(R.string.ws_badge_vision)));
        assertTrue(forkShape.get(0).badges(context).contains(context.getString(R.string.ws_badge_tools)));
        assertTrue(forkShape.get(0).badges(context).contains(context.getString(R.string.ws_badge_long_context, "200k")));
        assertTrue(forkShape.get(0).badges(context).contains(context.getString(R.string.ws_badge_reasoning)));
        assertEquals(3.0, forkShape.get(0).inputPer1mUsd(), 1e-6);
        assertEquals(15.0, forkShape.get(0).outputPer1mUsd(), 1e-6);
        assertTrue(forkShape.get(1).badges(context).contains(context.getString(R.string.ws_badge_pricing_included)));
        assertTrue(Double.isNaN(forkShape.get(2).inputPer1mUsd()));
        assertTrue(Double.isNaN(forkShape.get(2).outputPer1mUsd()));
    }

    public void testHermesApiThreadActionsAndRelativePathEncoding() throws Exception {
        assertEquals("travail/revue%20v2", HermesApi.relativePath("travail/revue v2"));
        assertEquals("caf%C3%A9%20chaud", HermesApi.queryValue("café chaud"));
        try { HermesApi.relativePath("travail/../secret"); fail("Accepted traversal"); }
        catch (IllegalArgumentException expected) {}

        try (Exchange server = new Exchange("200 OK", JSON, "{\"session\":{\"id\":\"s1\",\"pinned\":true}}")) {
            JSONObject res = new HermesApi(server.endpoint(), "test-only").setSessionPinned("s1", true);
            assertTrue(res.getJSONObject("session").getBoolean("pinned"));
            String wire = server.observed();
            assertTrue(wire.startsWith("PATCH /p/travail/api/sessions/s1 HTTP/1.1"));
            assertTrue(wire.contains("\"pinned\":true"));
        }

        try (Exchange server = new Exchange("201 Created", JSON, "{\"session\":{\"id\":\"fork-2\"}}")) {
            JSONObject forked = new HermesApi(server.endpoint(), "test-only").forkSession("s1", 42L, true);
            assertEquals("fork-2", forked.getJSONObject("session").getString("id"));
            String wire = server.observed();
            assertTrue(wire.startsWith("POST /p/travail/api/sessions/s1/fork HTTP/1.1"));
            assertTrue(wire.contains("\"up_to_message_id\":42"));
            assertTrue(wire.contains("\"keep_source_open\":true"));
        }

        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (Exchange server = new Exchange("200 OK", "Content-Type: text/markdown\r\n", "# Discussion\nBonjour")) {
            long bytes = new HermesApi(server.endpoint(), "test-only").exportSession("s1", "markdown", sink, 4096);
            assertEquals("# Discussion\nBonjour", sink.toString(StandardCharsets.UTF_8));
            assertEquals(sink.size(), bytes);
            assertTrue(server.observed().startsWith("GET /p/travail/api/sessions/s1/export?format=markdown HTTP/1.1"));
        }

        Context context = getInstrumentation().getTargetContext();
        List<ThreadActionModel.TurnChoice> turns = ThreadActions.extractTurnChoices(context, new JSONArray("""
                [{"id":10,"session_id":"seg-older","role":"user","content":"Question initiale"},
                 {"id":11,"role":"assistant","content":"Réponse détaillée"}]
                """));
        assertEquals(2, turns.size());
        assertEquals(10L, turns.get(0).messageId());
        assertEquals("seg-older", turns.get(0).targetSessionId("seg-current"));
        assertEquals("seg-current", turns.get(1).targetSessionId("seg-current"));
        assertEquals(context.getString(R.string.ws_role_user) + " · Question initiale", turns.get(0).displayLabel());
    }

    public void testBrainJsonSaturatesOverflowNegativeFractionalAndNonFiniteCounts() throws Exception {
        MemoryModel.Snapshot mem = BrainJson.parseMemorySnapshot(new JSONObject()
                .put("memory", new JSONObject()
                        .put("entries", new JSONArray())
                        .put("chars", 4_294_967_296L)
                        .put("limit", -4_294_967_200L))
                .put("user", new JSONObject()
                        .put("entries", new JSONArray())
                        .put("chars", "NaN")
                        .put("limit", 4_294_967_296L)));
        assertEquals(Integer.MAX_VALUE, mem.memory().chars());
        assertEquals(MemoryModel.DEFAULT_CHAR_LIMIT, mem.memory().limit());
        assertEquals(0, mem.user().chars());
        assertEquals(Integer.MAX_VALUE, mem.user().limit());

        SearchResultModel.Page page = BrainJson.parseSearchPage(new JSONObject()
                .put("data", new JSONArray())
                .put("total", 4_294_967_296L));
        assertEquals(Integer.MAX_VALUE, page.total());
        assertTrue(page.hasMoreFlag());

        BrainJson.WorkspaceFile file = BrainJson.parseWorkspaceFile(new JSONObject()
                .put("path", "src/Main.java")
                .put("total_lines", 4_294_967_296L)
                .put("content", "class Main {}"));
        assertEquals(Integer.MAX_VALUE, file.totalLines());

        BrainJson.WorkspaceDiff diff = BrainJson.parseWorkspaceDiff(new JSONObject()
                .put("branch", "main")
                .put("ahead", 4_294_967_296L)
                .put("behind", -4_294_967_200L)
                .put("files", new JSONArray().put(new JSONObject()
                        .put("path", "src/Main.java")
                        .put("status", "M")
                        .put("additions", 4_294_967_296L)
                        .put("deletions", -4_294_967_200L))));
        assertEquals(Integer.MAX_VALUE, diff.ahead());
        assertEquals(0, diff.behind());
        assertEquals(Integer.MAX_VALUE, diff.files().get(0).additions());
        assertEquals(0, diff.files().get(0).deletions());

        ModelCapabilityBadges.ModelInfo model = BrainJson.parseModelObject("m1", new JSONObject()
                .put("context_length", 4_294_967_296L)
                .put("max_output_tokens", 3.6d));
        assertEquals(Integer.MAX_VALUE, model.contextLength());
        assertEquals(4, model.maxOutputTokens());

        BrainJson.AnalyticsSummary analytics = BrainJson.parseAnalyticsSummary(
                new JSONObject().put("period_days", 4_294_967_296L), 30);
        assertEquals(Integer.MAX_VALUE, analytics.days());
        BrainJson.AnalyticsSummary analyticsFallback = BrainJson.parseAnalyticsSummary(
                new JSONObject().put("days", "NaN"), 30);
        assertEquals(30, analyticsFallback.days());

        List<NotesModel.HabitSuggestion> habits = BrainJson.parseHabitSuggestions(new JSONObject()
                .put("suggestions", new JSONArray().put(new JSONObject()
                        .put("id", "h1")
                        .put("label", "Daily brief")
                        .put("draft", "Prepare my brief")
                        .put("count", 4_294_967_296L))));
        assertEquals(1, habits.size());
        assertEquals(Integer.MAX_VALUE, habits.get(0).count());
    }
}
