package org.llm4people.hermes.thread;

import org.junit.Test;
import static org.junit.Assert.*;

public final class TranscriptPageTest {
    private static TranscriptPage first(String source) {
        return new TranscriptPage(source, new TranscriptPage.Cursor(), true, TranscriptPage.PAGE_CHARS);
    }

    private static String marked(TranscriptPage page, TranscriptPage.Style style) {
        return page.marks.stream().filter(mark -> mark.style == style)
                .map(mark -> page.text.substring(mark.start, mark.end)).findFirst().orElse("");
    }

    @Test public void mixedFrenchReplyKeepsSelectableCodeDiffAndListOrder() {
        var reply = first("## Vérification\n- Lancez **les tests** avec `./gradlew test`.\n" +
                "Un *seul* changement :\n```python file=src/app.py\n    return n ** 2\n```\n" +
                "~~~diff\n-    return 2 * n\n+    return n ** 2\n~~~");
        assertFalse(reply.hasMore());
        assertEquals("Vérification\n• Lancez les tests avec ./gradlew test.\nUn seul changement :\n" +
                "    return n ** 2\n-    return 2 * n\n+    return n ** 2\n", reply.text.toString());
        assertEquals("Vérification", marked(reply, TranscriptPage.Style.HEADING));
        assertEquals("les tests", marked(reply, TranscriptPage.Style.BOLD));
        assertEquals("./gradlew test", marked(reply, TranscriptPage.Style.INLINE_CODE));
        assertEquals("seul", marked(reply, TranscriptPage.Style.ITALIC));
        assertEquals("    return n ** 2", marked(reply, TranscriptPage.Style.CODE));
        assertEquals("-    return 2 * n", marked(reply, TranscriptPage.Style.DELETION));
        assertEquals("+    return n ** 2", marked(reply, TranscriptPage.Style.INSERTION));
    }

    @Test public void adjacentAndUnclosedExcerptsResolveTheirOwnOriginalSource() {
        String source = "Avant\n````md file=guide.md\r\n  a\r\n\r\n```\r\n````\r\n"
                + "~~~python\n# src/carre.py\n  return n * n\n~~~\n" + "```\nen cours";
        TranscriptPage page = first(source);
        assertEquals(3, page.blocks.size());
        assertEquals("guide.md · md", page.blocks.get(0).title(source));
        assertEquals("  a\r\n\r\n```", page.blocks.get(0).content(source));
        assertEquals("src/carre.py · python", page.blocks.get(1).title(source));
        assertEquals("# src/carre.py\n  return n * n", page.blocks.get(1).content(source));
        assertEquals("", page.blocks.get(2).title(source));
        assertEquals("en cours", page.blocks.get(2).content(source));
        assertTrue(first("```\n```").blocks.isEmpty());
    }

    @Test public void aPageBeginningInsideALongFenceCanReadTheWholeBlock() {
        String body = "    ligne 🚲\n".repeat(1300);
        String source = "```txt file=journal.txt\n" + body + "```\nAprès";
        TranscriptPage page = first(source);
        int count = 0;
        while (!page.blocks.isEmpty()) {
            assertEquals("journal.txt · txt", page.blocks.get(0).title(source));
            assertEquals(body.substring(0, body.length() - 1), page.blocks.get(0).content(source));
            count++;
            if (!page.hasMore()) break;
            page = new TranscriptPage(source, page.next, true, TranscriptPage.PAGE_CHARS);
        }
        assertTrue(count >= 3);
    }

    @Test public void dataUrlsConsumeNoPayloadBudgetAndKeepTheSurroundingProse() {
        String payload = "Qm9uam91".repeat(600000);
        var page = first("Avant\n![image](data:image/webp;base64," + payload + ")\nAprès **ici**.");
        assertFalse(page.hasMore());
        assertEquals("Avant\n[Image jointe]\nAprès ici.", page.text.toString());
        assertEquals("ici", marked(page, TranscriptPage.Style.BOLD));
        assertEquals("[Image jointe]", first("data:image/jpeg;base64,YQ==").text.toString());
        assertEquals("![image](data:image/webp;base64,YQ==)\n",
                first("```md\n![image](data:image/webp;base64,YQ==)\n```").text.toString());
    }

    @Test public void emphasizedImagesStayCollapsedInTheReaderAndLivePreview() {
        String image = "![croquis](data:image/png;base64,iVBORw0KGgo=)";
        for (String marker : new String[]{"**", "__", "*", "_"}) {
            String source = "Avant " + marker + "Voir " + image + " et " + image + marker + " après.";
            TranscriptPage page = first(source);
            assertEquals("Avant Voir [Image jointe] et [Image jointe] après.", page.text.toString());
            TranscriptPage.Style style = marker.length() == 2 ? TranscriptPage.Style.BOLD : TranscriptPage.Style.ITALIC;
            assertEquals("Voir [Image jointe] et [Image jointe]", marked(page, style));
            LiveTranscript live = new LiveTranscript();
            live.update(source);
            assertEquals(page.text.toString(), live.latest.text.toString());
        }
        String crossing = "Préface **" + image + " fin**.";
        var cursor = new TranscriptPage.Cursor();
        var rendered = new StringBuilder();
        do {
            var page = new TranscriptPage(crossing, cursor, true, 24);
            rendered.append(page.text);
            assertTrue(page.next.offset > cursor.offset);
            cursor = page.next;
        } while (cursor.offset < crossing.length());
        assertEquals("Préface **[Image jointe] fin**.", rendered.toString());
    }

    @Test public void regionBoundariesKeepToolOutputAndMalformedMarkupLiteral() {
        String source = "Produit **incomplet; code_snake_case; [fichier](https://exemple.fr/a)\n" +
                "<table><tr>42</tr></table>\n\\*littéral*\n3. Continuer";
        assertEquals(source, first(source).text.toString());
        assertEquals("**warning**\n-2", new TranscriptPage("**warning**\n-2",
                new TranscriptPage.Cursor(), false, 300).text.toString());
        assertEquals("un mot", marked(first("`un mot` et ``échec``"), TranscriptPage.Style.INLINE_CODE));
        assertEquals("`**littéral**`\n", first("````text\n`**littéral**`\n````").text.toString());
    }

    @Test public void imageHeaderCrossingPageBoundaryKeepsThePayloadOffEveryPage() {
        String padding = "x".repeat(290);
        String source = padding + "![image](data:image/png;base64," + "VG8=".repeat(6000) + ") suite";
        TranscriptPage page = new TranscriptPage(source, new TranscriptPage.Cursor(), true, 300);
        TranscriptPage finalPage = new TranscriptPage(source, page.next, true, 300);
        assertEquals(padding + "[Image jointe] suite", page.text.toString() + finalPage.text);
        assertFalse(finalPage.hasMore());
    }

    @Test public void pagesRetainCodeStateUntilMatchingFence() {
        String line = "    + ** bruts ** 😁".repeat(1200);
        String source = "~~~~diff\n" + line + "\n~~~\n+1\n~~~~\n**fin**";
        var cursor = new TranscriptPage.Cursor();
        var out = new StringBuilder();
        int count = 0;
        do {
            var page = new TranscriptPage(source, cursor, true, 900);
            assertFalse(Character.isHighSurrogate(page.text.charAt(page.text.length() - 1)));
            assertTrue(page.text.length() <= 902);
            out.append(page.text);
            assertTrue(page.next.offset > cursor.offset);
            cursor = page.next;
            count++;
        } while (cursor.offset < source.length());
        assertEquals(line + "\n~~~\n+1\nfin", out.toString());
        assertTrue(count > 1);
    }
}
