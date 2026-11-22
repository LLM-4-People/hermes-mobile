package org.llm4people.hermes.thread;

import junit.framework.TestCase;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ThreadStartersTest extends TestCase {
    public void testOnlyConfirmedEmptyConversationsWithAnUntouchedDraftCanShowExamples() {
        List<String> defaults = Collections.emptyList();
        assertTrue(ThreadStarters.available(true, "", false, defaults));
        assertFalse(ThreadStarters.available(false, "", false, defaults));
        assertFalse(ThreadStarters.available(true, "brouillon existant", false, defaults));
        assertFalse(ThreadStarters.available(true, " ", false, defaults));
        assertFalse(ThreadStarters.available(true, "", true, defaults));
    }

    public void testPublishedLayoutsMustExposeBothMessageListAndComposer() {
        assertFalse(ThreadStarters.available(true, "", false, Collections.singletonList("transcript")));
        assertFalse(ThreadStarters.available(true, "", false, Collections.singletonList("composer")));
        assertTrue(ThreadStarters.available(true, "", false, Arrays.asList("composer", "transcript")));
    }
}
