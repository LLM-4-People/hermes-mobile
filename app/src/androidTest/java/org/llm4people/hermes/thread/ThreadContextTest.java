package org.llm4people.hermes.thread;

import junit.framework.TestCase;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ThreadContextTest extends TestCase {
    public void testLegacyProjectionUsesInboxOrRootTitleAcrossCompression() throws Exception {
        JSONObject root = new JSONObject("{session:{id:'root',title:'Continuité',message_count:2}}");
        JSONObject tip = new JSONObject("{session:{id:'tip',title:null,message_count:1}}");
        List<String> reads = new ArrayList<>();
        ThreadContext.Read read = path -> {
            reads.add(path);
            return path.equals("/api/sessions/root") ? root : tip;
        };
        assertEquals("Continuité", ThreadContext.legacyTitle("root", "tip", read));
        assertEquals(Arrays.asList("/api/sessions/tip", "/api/sessions/root"), reads);

        tip.getJSONObject("session").put("title", "Renommée");
        reads.clear();
        assertEquals("Renommée", ThreadContext.legacyTitle("root", "tip", read));
        assertEquals(Collections.singletonList("/api/sessions/tip"), reads);

        JSONArray listing = new JSONArray("[{id:'tip',_lineage_root_id:'root',title:'Continuité'},"
                + "{id:'fork',parent_session_id:'root',title:'Branche'}]");
        assertEquals("Continuité", ThreadIdentity.find(listing, "root").label);
        assertEquals("Branche", ThreadIdentity.find(listing, "fork").label);
        reads.clear();
        tip.getJSONObject("session").put("title", JSONObject.NULL);
        assertEquals("", ThreadContext.legacyTitle("fork", "fork", read));
        assertEquals(Collections.singletonList("/api/sessions/fork"), reads);
    }

    public void testMetadataResolvesMiddleSegmentWithoutSearchingTheInbox() throws Exception {
        ThreadIdentity id = ThreadIdentity.fromTranscript(new JSONObject("{thread_id:'root',session_id:'tip',thread_title:'Continuité'}"));
        assertEquals("root", id.root);
        assertEquals("tip", id.tip);
        assertEquals("Continuité", id.label);
        assertNull(ThreadIdentity.fromTranscript(new JSONObject("{session_id:'tip'}")));
        assertNull(ThreadIdentity.fromTranscript(new JSONObject("{thread_id:'../wrong',session_id:'tip'}")));
    }

    public void testAggregateSpansPagesAndDoesNotAddOverlappingRows() throws Exception {
        JSONObject page = new JSONObject("{session_id:'tip',thread_title:'Continuité',pagination:{visible_only:true,total:83,returned:40}}");
        assertEquals("Continuité", ThreadContext.title(page, "thread_title", "Conversation"));
        long total = ThreadContext.visibleTotal(page);
        assertEquals(83L, ThreadContext.messageCount(total, 40, 40, ThreadData.PAGE));
        assertEquals(83L, ThreadContext.messageCount(total, 40, 79, ThreadData.PAGE));
        assertEquals(83L, ThreadContext.messageCount(total, 3, 82, ThreadData.PAGE));
        page.put("thread_title", JSONObject.NULL);
        assertEquals("Aperçu du premier message", ThreadContext.title(page, "thread_title", "Aperçu du premier message"));
    }

    public void testLegacyPaginationWaitsForLastPageAndUsesDistinctRows() throws Exception {
        JSONObject page = new JSONObject("{session_id:'tip',pagination:{returned:40}}");
        assertEquals(-1L, ThreadContext.visibleTotal(page));
        assertEquals(-1L, ThreadContext.messageCount(-1, 40, 40, ThreadData.PAGE));
        assertEquals(43L, ThreadContext.messageCount(-1, 4, 43, ThreadData.PAGE));
        assertEquals(0L, ThreadContext.messageCount(-1, 0, 0, ThreadData.PAGE));
        assertEquals(3L, ThreadContext.messageCount(-1, 3, 3, ThreadData.PAGE));
        assertEquals(-1L, ThreadContext.messageCount(-1, 20, 20, 20));
        assertEquals(19L, ThreadContext.messageCount(-1, 19, 19, 20));
        assertEquals(40L, ThreadContext.messageCount(-1, 40, 40, 60));
        assertEquals(-1L, ThreadContext.messageCount(-1, 60, 60, 60));
        page.getJSONObject("pagination").put("total", 4);
        assertEquals(-1L, ThreadContext.visibleTotal(page));
        page.getJSONObject("pagination").put("visible_only", false);
        assertEquals(-1L, ThreadContext.visibleTotal(page));
        page.getJSONObject("pagination").put("visible_only", true);
        for (Object invalid : new Object[]{-1, "4", 1.2, JSONObject.NULL}) {
            page.getJSONObject("pagination").put("total", invalid);
            assertEquals(-1L, ThreadContext.visibleTotal(page));
        }
    }
}
