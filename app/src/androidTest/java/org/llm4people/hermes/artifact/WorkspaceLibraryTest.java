package org.llm4people.hermes.artifact;

import android.content.Context;
import android.test.InstrumentationTestCase;
import org.llm4people.hermes.profile.WorkspaceAccountFixture;

@SuppressWarnings("deprecation")
public class WorkspaceLibraryTest extends InstrumentationTestCase {
    public void testApplyReviseRemoveRestoreAndForgetStayScopedToOneAccount() {
        Context app = getInstrumentation().getTargetContext();
        try (WorkspaceAccountFixture one = new WorkspaceAccountFixture(app);
             WorkspaceAccountFixture two = new WorkspaceAccountFixture(app)) {
            String id = "page/revue";
            LayoutHistory writer = new LayoutHistory(one.account,id);
            LayoutHistory reopened = new LayoutHistory(one.account,id);
            LayoutHistory other = new LayoutHistory(two.account,id);
            String first = LiveLayoutContractTest.REVIEW;
            String second = first.replace("\"Revue\"","\"Revue finale\"");
            writer.replace(first);
            writer.replace(second);
            assertEquals("Revue finale", PageLayouts.titles(one.account,false).get(id));
            assertTrue(PageLayouts.titles(two.account,false).isEmpty());
            assertEquals("",other.current());
            reopened.undo();
            assertEquals(first,writer.current());
            writer.replace("");
            assertTrue(PageLayouts.titles(one.account,false).isEmpty());
            assertTrue(PageLayouts.titles(one.account,true).containsKey(id));
            reopened.undo();
            assertEquals(first,writer.current());
            reopened.replace("");
            writer.forgetRemoved();
            assertTrue(PageLayouts.titles(one.account,true).isEmpty());
            assertFalse(reopened.canUndo());
            one.account.close();
            try { writer.current(); fail("Read after profile lock"); } catch (IllegalStateException expected) { }
        }
    }
}
