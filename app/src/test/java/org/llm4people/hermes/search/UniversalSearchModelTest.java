package org.llm4people.hermes.search;

import org.junit.Test;
import org.llm4people.hermes.panel.SearchResultModel;
import org.llm4people.hermes.panel.UniversalShareModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class UniversalSearchModelTest {
    private static List<UniversalSearchModel.LocalEntry> sampleLocal() {
        return List.of(
                UniversalSearchModel.LocalEntry.chat("t-1", "Budget Q4", "Revue des dépenses", true),
                UniversalSearchModel.LocalEntry.chat("t-2", "Résumé des tâches", "Plan hebdomadaire", false),
                UniversalSearchModel.LocalEntry.setting("appearance", "Paramètres d’apparence", "Thème et police", "theme dark font"),
                UniversalSearchModel.LocalEntry.setting("security", "Verrouillage et sécurité", "Biométrie et code", "lock pin biometric"),
                UniversalSearchModel.LocalEntry.tool("memory", "Mémoire de Hermes", "Souvenirs enregistrés", "facts profile")
        );
    }

    @Test
    public void matchesCaseAndAccentInsensitivelyAcrossMultipleTokens() {
        List<UniversalSearchModel.Result> hits = UniversalSearchModel.merge(
                "resume tache", UniversalSearchModel.Category.ALL, sampleLocal(), List.of());
        assertEquals(1, hits.size());
        assertEquals("t-2", hits.get(0).targetId());
        assertEquals(UniversalSearchModel.Category.CHATS, hits.get(0).category());

        List<UniversalSearchModel.Result> settingsHits = UniversalSearchModel.merge(
                "parametre theme", UniversalSearchModel.Category.ALL, sampleLocal(), List.of());
        assertEquals(1, settingsHits.size());
        assertEquals("appearance", settingsHits.get(0).actionId());
        assertEquals(UniversalSearchModel.Category.SETTINGS, settingsHits.get(0).category());
    }

    @Test
    public void deduplicatesLocalAndServerChatHitsWhileKeepingRicherServerSnippet() {
        List<SearchResultModel.Span> spans = List.of(new SearchResultModel.Span(8, 14));
        UniversalSearchModel.Result serverHit = new UniversalSearchModel.Result(
                "chat:t-1",
                UniversalSearchModel.Category.CHATS,
                "Budget Q4",
                "Le vrai budget validé hier",
                "t-1",
                "",
                "",
                UniversalShareModel.KIND_SESSION,
                "",
                "",
                true,
                75,
                spans);

        List<UniversalSearchModel.Result> merged = UniversalSearchModel.merge(
                "budget", UniversalSearchModel.Category.ALL, sampleLocal(), List.of(serverHit));
        assertEquals(1, merged.size());
        assertEquals("t-1", merged.get(0).targetId());
        assertEquals("Le vrai budget validé hier", merged.get(0).subtitle());
        assertEquals(1, merged.get(0).snippetHighlights().size());
        assertTrue(merged.get(0).fromServer());
    }

    @Test
    public void filtersStrictlyByCategoryAndGroupsDeterministically() {
        UniversalSearchModel.Result remoteNote = new UniversalSearchModel.Result(
                "note:n-1", UniversalSearchModel.Category.NOTES, "Budget courses", "Liste",
                "n-1", "", "", UniversalShareModel.KIND_NOTE, "", "owner", true, 30, List.of());
        UniversalSearchModel.Result remoteShare = new UniversalSearchModel.Result(
                "shared:s-1", UniversalSearchModel.Category.SHARED, "Budget équipe", "julie",
                "res-1", "", "s-1", UniversalShareModel.KIND_NOTE, "julie", UniversalShareModel.PERM_READ,
                true, 35, List.of());

        List<UniversalSearchModel.Result> all = UniversalSearchModel.merge(
                "budget", UniversalSearchModel.Category.ALL, sampleLocal(), List.of(remoteNote, remoteShare));
        assertEquals(3, all.size());
        Map<UniversalSearchModel.Category, List<UniversalSearchModel.Result>> grouped =
                UniversalSearchModel.groupByCategory(all);
        assertEquals(List.of(
                UniversalSearchModel.Category.CHATS,
                UniversalSearchModel.Category.NOTES,
                UniversalSearchModel.Category.SHARED), new ArrayList<>(grouped.keySet()));

        List<UniversalSearchModel.Result> onlyShared = UniversalSearchModel.merge(
                "budget", UniversalSearchModel.Category.SHARED, sampleLocal(), List.of(remoteNote, remoteShare));
        assertEquals(1, onlyShared.size());
        assertEquals("s-1", onlyShared.get(0).shareId());

        List<UniversalSearchModel.Result> onlySettings = UniversalSearchModel.merge(
                "biometric", UniversalSearchModel.Category.SETTINGS, sampleLocal(), List.of(remoteNote, remoteShare));
        assertEquals(1, onlySettings.size());
        assertEquals("security", onlySettings.get(0).actionId());

        List<UniversalSearchModel.Result> onlyTools = UniversalSearchModel.merge(
                "memoire", UniversalSearchModel.Category.TOOLS, sampleLocal(), List.of(remoteNote, remoteShare));
        assertEquals(1, onlyTools.size());
        assertEquals("memory", onlyTools.get(0).actionId());
    }

    @Test
    public void blankQueryReturnsSuggestedEntriesAndRemoteSharedCatalog() {
        UniversalSearchModel.Result sharedItem = new UniversalSearchModel.Result(
                "shared:s-9", UniversalSearchModel.Category.SHARED, "Guide projet", "alex",
                "doc-9", "", "s-9", UniversalShareModel.KIND_NOTE, "alex", UniversalShareModel.PERM_EDIT,
                true, 35, List.of());
        UniversalSearchModel.Result ftsHit = new UniversalSearchModel.Result(
                "chat:t-99", UniversalSearchModel.Category.CHATS, "Ancien fil", "snippet",
                "t-99", "", "", UniversalShareModel.KIND_SESSION, "", "", true, 70, List.of());

        List<UniversalSearchModel.Result> allBlank = UniversalSearchModel.merge(
                "   ", UniversalSearchModel.Category.ALL, sampleLocal(), List.of(sharedItem, ftsHit));
        assertFalse(allBlank.isEmpty());
        // Blank query must not include stale FTS5 server chat hits.
        assertTrue(allBlank.stream().noneMatch(r -> "t-99".equals(r.targetId())));
        // Recent local chat ranks above non-recent suggestions.
        assertEquals("t-1", allBlank.get(0).targetId());

        List<UniversalSearchModel.Result> sharedBlank = UniversalSearchModel.merge(
                "", UniversalSearchModel.Category.SHARED, sampleLocal(), List.of(sharedItem, ftsHit));
        assertEquals(1, sharedBlank.size());
        assertEquals("s-9", sharedBlank.get(0).shareId());
    }

    @Test
    public void noMatchReturnsEmptyAndEmptyGroupMap() {
        List<UniversalSearchModel.Result> none = UniversalSearchModel.merge(
                "zzzz-nonexistent-term", UniversalSearchModel.Category.ALL, sampleLocal(), List.of());
        assertTrue(none.isEmpty());
        assertTrue(UniversalSearchModel.groupByCategory(none).isEmpty());
    }

    @Test
    public void searchSessionDiscardsStaleRemoteEpochAndPreservesLocalHitsWhenDegraded() {
        UniversalSearchModel.SearchSession session = new UniversalSearchModel.SearchSession(sampleLocal());
        int epoch1 = session.setQuery("budget");
        assertTrue(session.isRemoteLoading());
        assertEquals(1, session.results().size()); // 0 ms local match available immediately

        int epoch2 = session.setQuery("securite");
        assertTrue(epoch2 > epoch1);

        // Stale epoch1 server response MUST be ignored.
        UniversalSearchModel.Result staleHit = new UniversalSearchModel.Result(
                "chat:old", UniversalSearchModel.Category.CHATS, "Old Budget", "stale",
                "old", "", "", UniversalShareModel.KIND_SESSION, "", "", true, 90, List.of());
        assertFalse(session.acceptRemote(epoch1, List.of(staleHit), List.of(), List.of(), false));
        assertTrue(session.isRemoteLoading());

        // Current epoch2 degraded offline response preserves instant local hits cleanly.
        assertTrue(session.acceptRemote(epoch2, List.of(), null, null, true));
        assertFalse(session.isRemoteLoading());
        assertTrue(session.isRemoteDegraded());
        List<UniversalSearchModel.Result> results = session.results();
        assertEquals(1, results.size());
        assertEquals("security", results.get(0).actionId());
    }

    @Test
    public void parsesServerSearchNotesAndSharesWirePayloadsSafely() {
        Map<String, Object> searchResp = Map.of(
                "data", List.of(
                        Map.of(
                                "thread_id", "root-1",
                                "session_id", "tip-1",
                                "thread_title", "Voyage Tokyo",
                                "snippet", "Prendre le <mark>train</mark> matin"),
                        Map.of(
                                "session_id", "solo-2",
                                "snippet", "Sans titre"),
                        Map.of("thread_title", "Missing ID skipped")));
        List<UniversalSearchModel.Result> searchHits =
                UniversalSearchModel.parseSessionSearchHitsMap(searchResp, "Untitled");
        assertEquals(2, searchHits.size());
        assertEquals("root-1", searchHits.get(0).targetId());
        assertEquals("Prendre le train matin", searchHits.get(0).subtitle());
        assertEquals(1, searchHits.get(0).snippetHighlights().size());
        assertEquals("Untitled", searchHits.get(1).title());

        Map<String, Object> notesResp = Map.of(
                "notes", List.of(
                        Map.of(
                                "id", "n-42",
                                "title", "Idées",
                                "content", "Ligne 1\nLigne 2"),
                        Map.of("title", "No id")));
        List<UniversalSearchModel.Result> notes = UniversalSearchModel.parseNotesResultsMap(notesResp, "Untitled note");
        assertEquals(1, notes.size());
        assertEquals("n-42", notes.get(0).targetId());
        assertEquals("Ligne 1 Ligne 2", notes.get(0).subtitle());

        Map<String, Object> sharesResp = Map.of(
                "shares", List.of(
                        Map.of(
                                "share_id", "sh-1",
                                "resource_kind", "session",
                                "resource_id", "sess-8",
                                "label", "Sprint plan",
                                "owner_profile", "alice",
                                "permission", "read"),
                        Map.of(
                                "id", "sh-2",
                                "resource_kind", "note",
                                "resource_id", "note-9")));
        List<UniversalSearchModel.Result> shares = UniversalSearchModel.parseSharedResultsMap(sharesResp);
        assertEquals(2, shares.size());
        assertEquals("sh-1", shares.get(0).shareId());
        assertEquals("alice", shares.get(0).ownerProfile());
        assertEquals("note-9", shares.get(1).title());
        assertEquals(UniversalShareModel.PERM_READ, shares.get(1).permission());

        assertTrue(UniversalSearchModel.parseSessionSearchHitsMap(null, "Untitled").isEmpty());
        assertTrue(UniversalSearchModel.parseNotesResultsMap(null, "Untitled").isEmpty());
        assertTrue(UniversalSearchModel.parseSharedResultsMap(null).isEmpty());
    }

    @Test
    public void uiContractsEnforceThemeHermesOfflineRetryEpochAndNoticePolarity() throws Exception {
        java.nio.file.Path cwd = java.nio.file.Path.of("").toAbsolutePath();
        java.nio.file.Path root = java.nio.file.Files.isDirectory(cwd.resolve("app/src/main/java"))
                ? cwd : cwd.getParent();

        String sheet = readUtf8(root.resolve(
                "app/src/main/java/org/llm4people/hermes/search/UniversalSearchSheet.java"));
        assertTrue(sheet.contains("new Dialog(activity, R.style.Theme_Hermes)"));
        assertFalse(sheet.contains("Theme_DeviceDefault_NoActionBar"));
        assertTrue(sheet.contains("catalogLoaded = !fetch.degraded;"));
        assertFalse(sheet.contains("catalogLoaded = true;"));

        String model = readUtf8(root.resolve(
                "app/src/main/java/org/llm4people/hermes/search/UniversalSearchModel.java"));
        assertTrue(model.contains("private volatile int epoch;"));

        String threadViews = readUtf8(root.resolve(
                "app/src/main/java/org/llm4people/hermes/thread/ThreadViews.java"));
        assertTrue(threadViews.contains("action != null ? R.color.warning_surface : R.color.surface_container"));

        String threadWorkspace = readUtf8(root.resolve(
                "app/src/main/java/org/llm4people/hermes/thread/ThreadWorkspace.java"));
        assertTrue(threadWorkspace.contains("int openEpoch = ++sharedOpenEpoch;"));
        assertTrue(threadWorkspace.contains("if (closed || openEpoch != sharedOpenEpoch || inboxFilter != ThreadInboxFilter.Filter.SHARED)"));

        String notesPanel = readUtf8(root.resolve(
                "app/src/main/java/org/llm4people/hermes/panel/NotesPanel.java"));
        assertTrue(notesPanel.contains("Ui.roundedRipple(panel.activity, Ui.CARD_CORNER_DP"));
        assertTrue(notesPanel.contains("R.color.disabled_ink), panel.activity.getColor(R.color.danger)"));
    }

    private static String readUtf8(java.nio.file.Path path) throws Exception {
        return new String(java.nio.file.Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8);
    }
}
