package org.llm4people.hermes.push;

import org.junit.Test;

import static org.junit.Assert.*;

public class WebPushExpiryTest {
    @Test public void cachedAlertsAreSuppressedAtTheAuthenticatedDeadline() {
        long deadline = 1_800_000_000_001L;
        for (String kind : new String[]{"run_completed", "run_failed", "reminder", "question_requested", "approval_requested"}) {
            assertTrue(WebPushExpiry.mayDispatch(kind, deadline, deadline - 1));
            assertFalse(WebPushExpiry.mayDispatch(kind, deadline, deadline));
            assertFalse(WebPushExpiry.mayDispatch(kind, deadline, deadline + 60_000));
        }
        for (String kind : new String[]{"question_settled", "approval_settled"}) {
            assertTrue(WebPushExpiry.mayDispatch(kind, deadline, deadline + 60_000));
            assertFalse(WebPushExpiry.mayDispatch(kind, 0, deadline));
        }
    }

    @Test public void acceptsOnlyPositiveJsonIntegers() {
        for (Object invalid : new Object[]{null, "1700000000000", 123.4, true, 0, -1L}) {
            assertEquals(0L, WebPushExpiry.read(invalid));
        }
        assertEquals(1234L, WebPushExpiry.read(1234));
        assertEquals(1_800_000_000_000L, WebPushExpiry.read(1_800_000_000_000L));
    }
}
