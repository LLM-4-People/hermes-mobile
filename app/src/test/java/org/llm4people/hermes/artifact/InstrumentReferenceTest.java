package org.llm4people.hermes.artifact;

import static org.junit.Assert.*;
import org.junit.Test;

public final class InstrumentReferenceTest {
    @Test public void usesBoundedOriginInTheCurrentThread() {
        String reference = WorkspaceLayout.pinnedReference("page/revue-code", "519", 2);
        assertTrue(reference.contains("surface=page/revue-code, sourceMessage=519, fenceIndex=2"));
    }

    @Test public void excludesArbitraryIdentifiersFromRunInstructions() {
        assertEquals("", WorkspaceLayout.pinnedReference("page/revue\nextra", "5", 0));
        assertEquals("", WorkspaceLayout.pinnedReference("home", "5", 0));
        assertEquals("", WorkspaceLayout.pinnedReference("page/revue", "5", -1));
        String reference = WorkspaceLayout.pinnedReference("page/revue", "sensitive origin text", 0);
        assertTrue(reference.contains("surface=page/revue"));
        assertFalse(reference.contains("sensitive"));
        assertFalse(reference.contains("sourceMessage"));
    }
}
