package org.llm4people.hermes.artifact;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class FencesTest {
    @Test public void recognizesMatchingCompleteFencesAndNames() {
        String s = "  ~~~~diff file=src/view.patch\r\n-a\r\n+b\r\n~~~\r\n~~~~\r\n" +
                "```python\n# foo.py\ndef f(): pass\n```\n" + "```js\npartial";
        List<String> result = scan(s);
        assertEquals(2, result.size());
        assertEquals("diff|src/view.patch|-a\r\n+b\r\n~~~", result.get(0));
        assertEquals("python|foo.py|# foo.py\ndef f(): pass", result.get(1));
    }

    @Test public void rejectsTraversalFilenamesAndUnclosedFences() {
        List<String> items = scan("```py file=../../a.py\na\n```\n```py\nb\n~~~");
        assertEquals(1, items.size());
        assertEquals("py||a", items.get(0));
        assertTrue(scan("    ```sh\na\n    ```").isEmpty());
        assertTrue(scan("```java\n```").isEmpty());
    }

    @Test public void explicitNamesAcceptRelativeFilesWithAndWithoutExtensions() {
        String[] names = {"Dockerfile", "Makefile", "packages/widget/LICENSE", ".dockerignore", ".config/aliases"};
        for (String name : names) {
            assertEquals(List.of("sh|" + name + "|example"),
                    scan("```sh file=" + name + "\nexample\n```"));
        }
        assertEquals(List.of("|Dockerfile|example"), scan("```file=Dockerfile\nexample\n```"));
        assertEquals(List.of("sh||# Example\nx"), scan("```sh\n# Example\nx\n```"));
        String[] invalid = {"/Dockerfile", "../Makefile", "x/../y", "x/./z", "x//Makefile", ".", "test/"};
        for (String name : invalid) {
            assertEquals(List.of("sh||example"), scan("```sh file=" + name + "\nexample\n```"));
        }
    }

    @Test public void stopsAtCallerBudgetAndPreservesEmbeddedBackticks() {
        List<String> result = new ArrayList<>();
        Fences.scan("````md\n```js\nlet n=1\n```\n````\n~~~html\nb\n~~~", (l,f,c,o) -> {
            result.add(c); return false;
        });
        assertEquals(List.of("```js\nlet n=1\n```"), result);
    }

    @Test public void hidesOnlySelectedCompletedProposalFencesWithoutEditingOtherSource() {
        String code = "```python\r\nvalue = 1\r\n```\r\n";
        String empty = "```hermes-workspace\r\n```\r\n";
        String first = "  ~~~~hermes-workspace\r\nexample\r\n~~~~\r\n";
        String second = "```hermes-workspace\nother\n```\n";
        String incomplete = "```hermes-workspace\nstill typing";
        String source = "Avant\r\n" + code + empty + first + second + "Après\n" + incomplete;
        assertEquals("Avant\r\n" + code + empty + second + "Après\n" + incomplete,
                Fences.without(source, Set.of(0, 1, 3)));
        assertSame(source, Fences.without(source, Set.of(4)));
        assertEquals("", Fences.without(first.stripTrailing(), Set.of(0)));
    }

    @Test public void readerUsesBoundedFirstLineNamingFromSourceOffset() {
        String source = "```python\n# demo.py\nprint(1)\n```";
        Fences.Marker marker = Fences.marker(source, 0, source.indexOf('\n'));
        assertEquals("demo.py", marker.filename(source, source.indexOf('\n') + 1));
        assertEquals("", marker.filename("# " + "x".repeat(161) + ".py", 0));
        Fences.Marker named = Fences.marker("```sh file=Dockerfile", 0, "```sh file=Dockerfile".length());
        assertEquals("Dockerfile", named.filename("# ignored.py", 0));
    }

    private List<String> scan(String source) {
        List<String> result = new ArrayList<>();
        Fences.scan(source, (l,f,c,o) -> { result.add(l+"|"+f+"|"+c); return true; });
        return result;
    }
}
