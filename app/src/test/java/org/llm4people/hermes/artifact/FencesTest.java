package org.llm4people.hermes.artifact;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
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

    @Test public void stopsAtCallerBudgetAndPreservesEmbeddedBackticks() {
        List<String> result = new ArrayList<>();
        Fences.scan("````md\n```js\nlet n=1\n```\n````\n~~~html\nb\n~~~", (l,f,c,o) -> {
            result.add(c); return false;
        });
        assertEquals(List.of("```js\nlet n=1\n```"), result);
    }

    private List<String> scan(String source) {
        List<String> result = new ArrayList<>();
        Fences.scan(source, (l,f,c,o) -> { result.add(l+"|"+f+"|"+c); return true; });
        return result;
    }
}
