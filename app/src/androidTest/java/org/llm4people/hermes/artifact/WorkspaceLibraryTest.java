package org.llm4people.hermes.artifact;

import android.content.Context;
import android.test.InstrumentationTestCase;
import org.llm4people.hermes.profile.WorkspaceAccountFixture;
import org.llm4people.hermes.profile.Account;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@SuppressWarnings("deprecation")
public class WorkspaceLibraryTest extends InstrumentationTestCase {
    public void testConcurrentHandlesRetainEveryAppliedPage() throws Exception {
        try (WorkspaceAccountFixture fixture = new WorkspaceAccountFixture(getInstrumentation().getTargetContext());
             Account second = fixture.reopen()) {
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService workers = Executors.newFixedThreadPool(3);
            try {
                List<Future<?>> writes = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    String id = "page/revue-" + i;
                    Account owner = i % 2 == 0 ? fixture.account : second;
                    writes.add(workers.submit(() -> {
                        start.await();
                        new LayoutHistory(owner,id).replace(LiveLayoutContractTest.REVIEW.replace("page/revue",id));
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> write : writes) write.get();
                assertEquals(8, PageLayouts.titles(fixture.account,false).size());
            } finally { workers.shutdownNow(); }
        }
    }

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
