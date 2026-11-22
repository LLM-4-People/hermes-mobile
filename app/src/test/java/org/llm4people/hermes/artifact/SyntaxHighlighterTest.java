package org.llm4people.hermes.artifact;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public final class SyntaxHighlighterTest {

    private static boolean hasToken(List<SyntaxHighlighter.Token> tokens, String text,
                                    SyntaxHighlighter.Kind kind, String expected) {
        return tokens.stream().anyMatch(t -> t.kind() == kind && expected.equals(text.substring(t.start(), t.end())));
    }

    @Test public void languageTagNormalizationCoversAllSupportedLanguagesAndRejectsUnknown() {
        assertEquals(SyntaxHighlighter.Language.JAVA, SyntaxHighlighter.Language.fromTag("Java"));
        assertEquals(SyntaxHighlighter.Language.PYTHON, SyntaxHighlighter.Language.fromTag("py"));
        assertEquals(SyntaxHighlighter.Language.PYTHON, SyntaxHighlighter.Language.fromTag("python3"));
        assertEquals(SyntaxHighlighter.Language.KOTLIN, SyntaxHighlighter.Language.fromTag("kt"));
        assertEquals(SyntaxHighlighter.Language.JAVASCRIPT, SyntaxHighlighter.Language.fromTag("tsx"));
        assertEquals(SyntaxHighlighter.Language.JAVASCRIPT, SyntaxHighlighter.Language.fromTag("typescript"));
        assertEquals(SyntaxHighlighter.Language.JSON, SyntaxHighlighter.Language.fromTag("jsonc"));
        assertEquals(SyntaxHighlighter.Language.YAML, SyntaxHighlighter.Language.fromTag("yml"));
        assertEquals(SyntaxHighlighter.Language.BASH, SyntaxHighlighter.Language.fromTag("sh"));
        assertEquals(SyntaxHighlighter.Language.SQL, SyntaxHighlighter.Language.fromTag("postgresql"));
        assertEquals(SyntaxHighlighter.Language.DIFF, SyntaxHighlighter.Language.fromTag("patch"));
        assertEquals(SyntaxHighlighter.Language.PLAIN, SyntaxHighlighter.Language.fromTag(""));
        assertEquals(SyntaxHighlighter.Language.PLAIN, SyntaxHighlighter.Language.fromTag(null));
        assertEquals(SyntaxHighlighter.Language.PLAIN, SyntaxHighlighter.Language.fromTag("unknown-lang"));
    }

    @Test public void tokenizesJavaKotlinPythonAndTypeScriptAccurately() {
        String java = "@Override public final class App { /* doc */ int n = 0x2A; String s = \"a\\\"b\"; // end\n}";
        var javaTokens = SyntaxHighlighter.tokenize(java, SyntaxHighlighter.Language.JAVA);
        assertTrue(hasToken(javaTokens, java, SyntaxHighlighter.Kind.ANNOTATION, "@Override"));
        assertTrue(hasToken(javaTokens, java, SyntaxHighlighter.Kind.KEYWORD, "public"));
        assertTrue(hasToken(javaTokens, java, SyntaxHighlighter.Kind.TYPE, "App"));
        assertTrue(hasToken(javaTokens, java, SyntaxHighlighter.Kind.COMMENT, "/* doc */"));
        assertTrue(hasToken(javaTokens, java, SyntaxHighlighter.Kind.NUMBER, "0x2A"));
        assertTrue(hasToken(javaTokens, java, SyntaxHighlighter.Kind.STRING, "\"a\\\"b\""));
        assertTrue(hasToken(javaTokens, java, SyntaxHighlighter.Kind.COMMENT, "// end"));

        String py = "@dataclass\ndef calc(x):\n    \"\"\"doc\"\"\"\n    return x + 3.14 # ok\n";
        var pyTokens = SyntaxHighlighter.tokenize(py, SyntaxHighlighter.Language.PYTHON);
        assertTrue(hasToken(pyTokens, py, SyntaxHighlighter.Kind.ANNOTATION, "@dataclass"));
        assertTrue(hasToken(pyTokens, py, SyntaxHighlighter.Kind.KEYWORD, "def"));
        assertTrue(hasToken(pyTokens, py, SyntaxHighlighter.Kind.KEYWORD, "return"));
        assertTrue(hasToken(pyTokens, py, SyntaxHighlighter.Kind.STRING, "\"\"\"doc\"\"\""));
        assertTrue(hasToken(pyTokens, py, SyntaxHighlighter.Kind.NUMBER, "3.14"));
        assertTrue(hasToken(pyTokens, py, SyntaxHighlighter.Kind.COMMENT, "# ok"));

        String kt = "suspend fun fetch(): List<String> = listOf(\"ok\")";
        var ktTokens = SyntaxHighlighter.tokenize(kt, SyntaxHighlighter.Language.KOTLIN);
        assertTrue(hasToken(ktTokens, kt, SyntaxHighlighter.Kind.KEYWORD, "suspend"));
        assertTrue(hasToken(ktTokens, kt, SyntaxHighlighter.Kind.KEYWORD, "fun"));
        assertTrue(hasToken(ktTokens, kt, SyntaxHighlighter.Kind.TYPE, "List"));

        String ts = "export const run = async (): Promise<void> => `hi ${1}`;";
        var tsTokens = SyntaxHighlighter.tokenize(ts, SyntaxHighlighter.Language.JAVASCRIPT);
        assertTrue(hasToken(tsTokens, ts, SyntaxHighlighter.Kind.KEYWORD, "export"));
        assertTrue(hasToken(tsTokens, ts, SyntaxHighlighter.Kind.KEYWORD, "async"));
        assertTrue(hasToken(tsTokens, ts, SyntaxHighlighter.Kind.TYPE, "Promise"));
        assertTrue(hasToken(tsTokens, ts, SyntaxHighlighter.Kind.STRING, "`hi ${1}`"));
    }

    @Test public void tokenizesJsonYamlBashSqlAndDiff() {
        String json = "{\"enabled\": true, \"count\": -42, \"name\": \"hermes\"}";
        var jsonTokens = SyntaxHighlighter.tokenize(json, SyntaxHighlighter.Language.JSON);
        assertTrue(hasToken(jsonTokens, json, SyntaxHighlighter.Kind.JSON_KEY, "\"enabled\""));
        assertTrue(hasToken(jsonTokens, json, SyntaxHighlighter.Kind.KEYWORD, "true"));
        assertTrue(hasToken(jsonTokens, json, SyntaxHighlighter.Kind.NUMBER, "-42"));
        assertTrue(hasToken(jsonTokens, json, SyntaxHighlighter.Kind.STRING, "\"hermes\""));

        String yaml = "# config\nmodel: \"claude\" # note\nretries: 3\nactive: true\n";
        var yamlTokens = SyntaxHighlighter.tokenize(yaml, SyntaxHighlighter.Language.YAML);
        assertTrue(hasToken(yamlTokens, yaml, SyntaxHighlighter.Kind.COMMENT, "# config"));
        assertTrue(hasToken(yamlTokens, yaml, SyntaxHighlighter.Kind.JSON_KEY, "model"));
        assertTrue(hasToken(yamlTokens, yaml, SyntaxHighlighter.Kind.STRING, "\"claude\""));
        assertTrue(hasToken(yamlTokens, yaml, SyntaxHighlighter.Kind.NUMBER, "3"));
        assertTrue(hasToken(yamlTokens, yaml, SyntaxHighlighter.Kind.KEYWORD, "true"));

        String bash = "if [ -f \"app.sh\" ]; then echo 42; fi # done";
        var bashTokens = SyntaxHighlighter.tokenize(bash, SyntaxHighlighter.Language.BASH);
        assertTrue(hasToken(bashTokens, bash, SyntaxHighlighter.Kind.KEYWORD, "if"));
        assertTrue(hasToken(bashTokens, bash, SyntaxHighlighter.Kind.KEYWORD, "then"));
        assertTrue(hasToken(bashTokens, bash, SyntaxHighlighter.Kind.STRING, "\"app.sh\""));
        assertTrue(hasToken(bashTokens, bash, SyntaxHighlighter.Kind.COMMENT, "# done"));

        String sql = "SELECT id, 'ok' FROM runs WHERE count > 10 -- latest";
        var sqlTokens = SyntaxHighlighter.tokenize(sql, SyntaxHighlighter.Language.SQL);
        assertTrue(hasToken(sqlTokens, sql, SyntaxHighlighter.Kind.KEYWORD, "SELECT"));
        assertTrue(hasToken(sqlTokens, sql, SyntaxHighlighter.Kind.KEYWORD, "WHERE"));
        assertTrue(hasToken(sqlTokens, sql, SyntaxHighlighter.Kind.STRING, "'ok'"));
        assertTrue(hasToken(sqlTokens, sql, SyntaxHighlighter.Kind.NUMBER, "10"));
        assertTrue(hasToken(sqlTokens, sql, SyntaxHighlighter.Kind.COMMENT, "-- latest"));

        String diff = "diff --git a/a.txt b/a.txt\n@@ -1,2 +1,2 @@\n-old\n+new\n ctx";
        var diffTokens = SyntaxHighlighter.tokenize(diff, SyntaxHighlighter.Language.DIFF);
        assertTrue(hasToken(diffTokens, diff, SyntaxHighlighter.Kind.KEYWORD, "diff --git a/a.txt b/a.txt"));
        assertTrue(hasToken(diffTokens, diff, SyntaxHighlighter.Kind.DIFF_HUNK, "@@ -1,2 +1,2 @@"));
        assertTrue(hasToken(diffTokens, diff, SyntaxHighlighter.Kind.DIFF_DEL, "-old"));
        assertTrue(hasToken(diffTokens, diff, SyntaxHighlighter.Kind.DIFF_ADD, "+new"));
    }

    @Test public void hugeInputsAndMalformedTokensStayBoundedAndRespectSurrogatePairs() {
        StringBuilder huge = new StringBuilder();
        while (huge.length() < SyntaxHighlighter.MAX_HIGHLIGHT_CHARS - 1) huge.append("a");
        huge.append("😀"); // Surrogate pair straddling MAX_HIGHLIGHT_CHARS
        huge.append(" return 42;".repeat(5000));
        String source = huge.toString();

        int bound = SyntaxHighlighter.boundedEnd(source, SyntaxHighlighter.MAX_HIGHLIGHT_CHARS);
        assertTrue(bound <= SyntaxHighlighter.MAX_HIGHLIGHT_CHARS);
        assertFalse(Character.isHighSurrogate(source.charAt(bound - 1)));

        var tokens = SyntaxHighlighter.tokenize(source, SyntaxHighlighter.Language.JAVA);
        for (var t : tokens) {
            assertTrue(t.end() <= bound);
            assertTrue(t.start() < t.end());
        }

        // Unterminated strings & block comments never throw or hang.
        assertFalse(SyntaxHighlighter.tokenize("String s = \"unterminated", SyntaxHighlighter.Language.JAVA).isEmpty());
        assertFalse(SyntaxHighlighter.tokenize("/* unclosed comment", SyntaxHighlighter.Language.JAVA).isEmpty());
        assertTrue(SyntaxHighlighter.tokenize("", SyntaxHighlighter.Language.JAVA).isEmpty());
        assertTrue(SyntaxHighlighter.tokenize(null, SyntaxHighlighter.Language.JAVA).isEmpty());
        assertTrue(SyntaxHighlighter.tokenize("plain text", SyntaxHighlighter.Language.PLAIN).isEmpty());
    }

    @Test public void inFileSearchAndLineNumberHelpersAreDeterministicAndBounded() {
        String source = "Alpha beta\nALPHA gamma\nalpha";
        var matches = SyntaxHighlighter.findMatches(source, "alpha");
        assertEquals(3, matches.size());
        assertEquals(0, matches.get(0).start());
        assertEquals(11, matches.get(1).start());
        assertEquals(23, matches.get(2).start());
        assertEquals(1, SyntaxHighlighter.lineNumberAt(source, 0));
        assertEquals(2, SyntaxHighlighter.lineNumberAt(source, 11));
        assertEquals(3, SyntaxHighlighter.lineNumberAt(source, 23));
        assertEquals(3, SyntaxHighlighter.lineCount(source));

        assertTrue(SyntaxHighlighter.findMatches(source, "").isEmpty());
        assertTrue(SyntaxHighlighter.findMatches(source, null).isEmpty());
        assertTrue(SyntaxHighlighter.findMatches(null, "a").isEmpty());

        String repeated = "x".repeat(5000);
        assertEquals(SyntaxHighlighter.MAX_SEARCH_MATCHES, SyntaxHighlighter.findMatches(repeated, "x").size());

        // Unicode case-expansion (Turkish dotted İ -> i + combining dot in toLowerCase) must not shift offsets.
        String unicode = "\u0130stanbul alpha \u0130 ALPHA";
        var uniMatches = SyntaxHighlighter.findMatches(unicode, "alpha");
        assertEquals(2, uniMatches.size());
        assertEquals("alpha", unicode.substring(uniMatches.get(0).start(), uniMatches.get(0).end()));
        assertEquals("ALPHA", unicode.substring(uniMatches.get(1).start(), uniMatches.get(1).end()));
    }
}
