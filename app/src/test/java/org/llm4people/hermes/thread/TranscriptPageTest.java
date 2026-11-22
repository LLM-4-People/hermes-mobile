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
