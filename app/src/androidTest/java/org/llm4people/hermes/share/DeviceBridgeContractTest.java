package org.llm4people.hermes.share;

import junit.framework.TestCase;
import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.artifact.DeviceAction;

import java.util.List;

public final class DeviceBridgeContractTest extends TestCase {
    public void testParsesPendingRequestsMapsDeviceActionsAndBuildsResults() throws Exception {
        JSONObject req = new JSONObject()
                .put("request_id", "req_42")
                .put("action_type", "alarm_timer")
                .put("reason", "Lancer un minuteur de 5 minutes")
                .put("expires_at", 4_000_000_000L)
                .put("params", new JSONObject().put("seconds", 300).put("label", "Thé"));
        JSONObject status = new JSONObject()
                .put("pending_device_requests", new JSONArray().put(req));

        List<DeviceBridgeFlow.BridgeRequest> parsed = DeviceBridgeFlow.parsePending(status);
        assertEquals(1, parsed.size());
        DeviceBridgeFlow.BridgeRequest first = parsed.get(0);
        assertEquals("req_42", first.requestId());
        assertFalse(first.expiredAt(1_000_000L));
        assertTrue(first.expiredAt(4_000_000_001L));

        DeviceAction mapped = DeviceBridgeFlow.toDeviceAction(first);
        assertNotNull(mapped);
        assertEquals(DeviceAction.Kind.TIMER, mapped.kind());

        JSONObject denied = DeviceBridgeFlow.buildResultPayload("req_42", DeviceBridgeFlow.Status.DENIED,
                new JSONObject().put("ignored", "value"));
        assertEquals("denied", denied.getString("status"));
        assertFalse(denied.has("data"));

        JSONObject completed = DeviceBridgeFlow.buildResultPayload("req_42", DeviceBridgeFlow.Status.COMPLETED,
                new JSONObject().put("launched", true));
        assertEquals("completed", completed.getString("status"));
        assertTrue(completed.getJSONObject("data").getBoolean("launched"));
    }

    public void testRejectsInvalidBridgeRequests() throws Exception {
        assertNull(DeviceBridgeFlow.parseRequest(null));
        assertNull(DeviceBridgeFlow.parseRequest(new JSONObject()
                .put("request_id", "../bad")
                .put("action_type", "camera")));
        assertNull(DeviceBridgeFlow.parseRequest(new JSONObject()
                .put("request_id", "req_1")
                .put("action_type", "arbitrary_shell_exec")));
    }
}
