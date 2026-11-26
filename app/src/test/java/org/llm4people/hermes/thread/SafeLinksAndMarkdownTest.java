package org.llm4people.hermes.thread;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public final class SafeLinksAndMarkdownTest {

    @Test public void safeLinksAllowsValidHttpsAndRejectsDangerousOrMalformedSchemes() {
        SafeLinks.Validated ok = SafeLinks.validate("https://docs.example.org/guide?page=2#top");
        assertNotNull(ok);
        assertEquals("https://docs.example.org/guide?page=2#top", ok.url());
        assertEquals("docs.example.org", ok.host());

        assertNotNull(SafeLinks.validate("HTTPS://Example.COM:8443/path"));

        String[] rejected = {
                null,
                "",
                "   ",
                "javascript:alert(1)",
                "JAVASCRIPT:void(0)",
                "file:///etc/passwd",
                "data:text/html,<script>alert(1)</script>",
                "http://insecure.example.org",
                "intent://scan/#Intent;scheme=zxing;end",
                "tel:+15145550100",
                "mailto:user@example.org",
                "//evil.example.org/phish",
                "https://trusted.example.org@evil.example.org/login",
                "https://evil.example.org\\@trusted.example.org",
                "https://example.org/with space",
                "https://example.org/\nnewline",
                "https://example.org/\u00A0nbsp",
                "https://example.org/\u3000ideographic",
                "https://example.org/spoof\u202Etxt",
                "https://example.org/isolate\u2067end",
                "https://example.org/zero\u200Bwidth",
                "https://example.org/filler\u3164hide",
                "https://example.org/bad\uD800surrogate",
                "https:///missing-host",
                "https://..invalid..",
                "https://example.org:0/zero-port",
                "https://example.org/" + "a".repeat(SafeLinks.MAX_URL_LENGTH + 10)
        };
        for (String bad : rejected) {
            assertNull("Expected rejection for: " + bad, SafeLinks.validate(bad));
        }
    }

    @Test public void markdownTablesParseHeadersAlignmentsAndRowsWithBounds() {
        String md = "Intro\n| Nom | Score | Statut |\n| :--- | :---: | ---: |\n| Alice | 98 | OK |\n| Bob | 85 | En cours |\nFin";
        TranscriptPage page = new TranscriptPage(md, new TranscriptPage.Cursor(), true, TranscriptPage.PAGE_CHARS);

        assertEquals(1, page.tables.size());
        TranscriptPage.Table table = page.tables.get(0);
        assertEquals(List.of("Nom", "Score", "Statut"), table.headers);
        assertEquals(List.of(TranscriptPage.Align.LEFT, TranscriptPage.Align.CENTER, TranscriptPage.Align.RIGHT), table.alignments);
        assertEquals(2, table.rows.size());
        assertEquals(List.of("Alice", "98", "OK"), table.rows.get(0));
        assertEquals(List.of("Bob", "85", "En cours"), table.rows.get(1));
        assertTrue(page.text.toString().contains("Intro"));
        assertTrue(page.text.toString().contains("Fin"));
        assertFalse(page.text.toString().contains(":---"));

        // Malformed separator is NOT parsed as a table.
        String notATable = "| A | B |\n| not-a-sep | x |\n| 1 | 2 |";
        TranscriptPage bad = new TranscriptPage(notATable, new TranscriptPage.Cursor(), true, TranscriptPage.PAGE_CHARS);
        assertTrue(bad.tables.isEmpty());
    }

    @Test public void reviewedLinksExtractOnlySafeHttpsOutsideCode() {
        String md = "Voir [Doc](https://docs.example.org/a), [XSS](javascript:alert(1)), [Fichier](file:///etc/hosts), "
                + "[HTTP](http://plain.example.org), `[Code](https://ignored.example.org)` et [Doublon](https://docs.example.org/a).\n"
                + "```md\n[DansCode](https://code.example.org)\n```";
        TranscriptPage page = new TranscriptPage(md, new TranscriptPage.Cursor(), true, TranscriptPage.PAGE_CHARS);

        assertEquals(1, page.links.size());
        assertEquals("Doc", page.links.get(0).label);
        assertEquals("https://docs.example.org/a", page.links.get(0).url);
        assertEquals("docs.example.org", page.links.get(0).host);
        assertEquals(3, page.rejectedLinks);

        // Link labels strip bidi/zero-width/unpaired-surrogate/control chars and fall back to host when blank.
        String spoofed = "Voir [Safe\u202E\u2067\u200B\uD800  Doc](https://docs.example.org/clean) et "
                + "[\u3164\u200B\u202E](https://fallback.example.org/path).";
        TranscriptPage sanitized = new TranscriptPage(spoofed, new TranscriptPage.Cursor(), true, TranscriptPage.PAGE_CHARS);
        assertEquals(2, sanitized.links.size());
        assertEquals("Safe Doc", sanitized.links.get(0).label);
        assertEquals("fallback.example.org", sanitized.links.get(1).label);
    }

    @Test public void blockquotesAndNestedListsPreserveHierarchy() {
        String md = "> Citation **importante**\n> > Niveau deux\n- Parent\n  - Enfant\n    * Petit-enfant\n  1. Numéroté imbriqué";
        TranscriptPage page = new TranscriptPage(md, new TranscriptPage.Cursor(), true, TranscriptPage.PAGE_CHARS);

        assertTrue(page.marks.stream().anyMatch(m -> m.style == TranscriptPage.Style.QUOTE && m.level == 1));
        assertTrue(page.marks.stream().anyMatch(m -> m.style == TranscriptPage.Style.QUOTE && m.level == 2));
        assertTrue(page.marks.stream().anyMatch(m -> m.style == TranscriptPage.Style.NESTED_LIST && m.level == 1));
        assertTrue(page.marks.stream().anyMatch(m -> m.style == TranscriptPage.Style.NESTED_LIST && m.level == 2));
        assertTrue(page.text.toString().contains("◦ Enfant"));
        assertTrue(page.text.toString().contains("▪ Petit-enfant"));
        assertTrue(page.text.toString().contains("1. Numéroté imbriqué"));
    }

    @Test public void reasoningExtractionHandlesTagsStreamingAndProtectsFencedCode() {
        String reply = "<think>Étape 1\nÉtape 2</think>\nVoici la réponse.\n```xml\n<think>exemple littéral</think>\n```";
        var split = TranscriptPage.splitReasoning(reply);
        assertEquals("Étape 1\nÉtape 2", split.reasoning());
        assertFalse(split.open());
        assertTrue(split.visible().startsWith("Voici la réponse."));
        assertTrue(split.visible().contains("<think>exemple littéral</think>"));

        var streaming = TranscriptPage.splitReasoning("<reasoning>En train de réfléchir...", "Note SSE");
        assertTrue(streaming.open());
        assertTrue(streaming.reasoning().contains("Note SSE"));
        assertTrue(streaming.reasoning().contains("En train de réfléchir..."));
        assertEquals("", streaming.visible());

        // Huge reasoning stays strictly bounded without splitting surrogate pairs.
        String hugeThought = "<think>" + "a".repeat(TranscriptPage.MAX_REASONING_CHARS - 1) + "😀suite</think>\nOK";
        var bounded = TranscriptPage.splitReasoning(hugeThought);
        assertTrue(bounded.reasoning().length() <= TranscriptPage.MAX_REASONING_CHARS);
        assertFalse(Character.isHighSurrogate(bounded.reasoning().charAt(bounded.reasoning().length() - 1)));
        assertEquals("OK", bounded.visible());
    }
}
