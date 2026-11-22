package org.llm4people.hermes.share;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.test.InstrumentationTestCase;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.artifact.ArtifactDesk;
import org.llm4people.hermes.artifact.DeviceAction;
import org.llm4people.hermes.artifact.DeviceReview;
import org.llm4people.hermes.camera.ThreadCameraCapture;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

@SuppressWarnings("deprecation")
public final class DeviceBridgeContractTest extends InstrumentationTestCase {
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

        DeviceBridgeFlow.BridgeRequest navReq = DeviceBridgeFlow.parseRequest(new JSONObject()
                .put("request_id", "req_nav")
                .put("action_type", "maps_handoff")
                .put("params", new JSONObject().put("destination", "Gare Centrale").put("mode", "walking")));
        assertNotNull(navReq);
        assertEquals(DeviceAction.Kind.NAVIGATION, DeviceBridgeFlow.toDeviceAction(navReq).kind());

        JSONObject denied = DeviceBridgeFlow.buildResultPayload("req_42", DeviceBridgeFlow.Status.DENIED,
                new JSONObject().put("ignored", "value"));
        assertEquals("denied", denied.getString("status"));
        assertFalse(denied.has("data"));

        JSONObject completed = DeviceBridgeFlow.buildResultPayload("req_42", DeviceBridgeFlow.Status.COMPLETED,
                new JSONObject().put("launched", true));
        assertEquals("completed", completed.getString("status"));
        assertTrue(completed.getJSONObject("data").getBoolean("launched"));
    }

    public void testDetachedOriginReportsUnavailableInsteadOfHangingOrDenied() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        View detached = new View(context);
        ArtifactDesk.Host inactiveHost = new ArtifactDesk.Host() {
            @Override public void onAction(String id) {}
            @Override public void onLayoutChanged() {}
            @Override public void openWorkspace(String pageId) {}
            @Override public void threadSlots(List<String> order) {}
            @Override public BooleanSupplier deviceActionOrigin() { return () -> false; }
            @Override public String workspaceValue(String source) { return ""; }
            @Override public void stageDraft(String text) {}
        };
        DeviceBridgeFlow.BridgeRequest req = DeviceBridgeFlow.parseRequest(new JSONObject()
                .put("request_id", "req_detached")
                .put("action_type", "alarm_timer")
                .put("params", new JSONObject().put("seconds", 60)));
        AtomicReference<DeviceBridgeFlow.Status> outcome = new AtomicReference<>();
        new DeviceBridgeFlow().promptUser(null, inactiveHost, detached, req, null, (s, d) -> outcome.set(s));
        assertEquals(DeviceBridgeFlow.Status.UNAVAILABLE, outcome.get());

        AtomicReference<String> reviewOutcome = new AtomicReference<>();
        DeviceReview.review(null, inactiveHost, detached, DeviceBridgeFlow.toDeviceAction(req),
                () -> reviewOutcome.set("completed"),
                () -> reviewOutcome.set("cancelled"),
                () -> reviewOutcome.set("unavailable"));
        assertEquals("unavailable", reviewOutcome.get());
    }

    public void testCameraCapturePersistsPendingSlotAndSetsClipDataGrant() {
        String slotStr = "content://org.llm4people.hermes.capture/capture/11111111-1111-4111-8111-111111111111.jpg";
        Bundle saved = new Bundle();
        saved.putInt("camera_request", 0x5401);
        saved.putString("camera_nonce", "nonce-1");
        saved.putString("camera_owner", "owner-proof");
        saved.putString("camera_thread", "thread-proof");
        saved.putString("camera_slot", slotStr);

        ThreadCameraCapture restored = new ThreadCameraCapture(null, saved);
        assertTrue(restored.onActivityResult(0x5401, Activity.RESULT_OK));
        assertTrue(restored.isReady());

        Bundle resaved = new Bundle();
        restored.save(resaved);
        assertEquals(slotStr, resaved.getString("camera_slot"));

        Intent intent = ThreadCameraCapture.captureIntentFor(Uri.parse(slotStr));
        assertNotNull(intent.getClipData());
        assertEquals(Uri.parse(slotStr), intent.getClipData().getItemAt(0).getUri());
        assertTrue((intent.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0);
        assertTrue((intent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
    }

    public void testEveryExecutableCapabilityIsBackedByActionOrClipboardExecutor() throws Exception {
        ClipboardPastePill pill = new ClipboardPastePill(new ClipboardPastePill.ClipboardSource() {
            @Override public boolean hasPlainTextClip() { return true; }
            @Override public long clipTimestamp() { return 100L; }
            @Override public CharSequence readPrimaryText() { return "Notes importantes"; }
        });
        DeviceBridgeFlow.Executor executor = DeviceBridgeFlow.clipboardExecutor(pill);
        List<String> caps = DeviceBridgeFlow.executableCapabilities(true, true, true);
        for (String cap : caps) {
            JSONObject params = switch (cap) {
                case "calendar_draft" -> new JSONObject()
                        .put("title", "Réunion").put("start", "2026-11-23T10:00:00Z").put("end", "2026-11-23T11:00:00Z");
                case "alarm_timer" -> new JSONObject().put("seconds", 120);
                case "maps_handoff" -> new JSONObject().put("query", "Montréal");
                case "share_sheet" -> new JSONObject().put("text", "Bonjour");
                case "dial_draft" -> new JSONObject().put("number", "+1 514-555-0100");
                case "email_draft" -> new JSONObject().put("recipient", "a@exemple.fr").put("subject", "Sujet");
                default -> new JSONObject();
            };
            DeviceBridgeFlow.BridgeRequest req = DeviceBridgeFlow.parseRequest(new JSONObject()
                    .put("request_id", "cap_" + cap).put("action_type", cap).put("params", params));
            assertNotNull("Capability must parse cleanly: " + cap, req);
            DeviceAction action = DeviceBridgeFlow.toDeviceAction(req);
            if (action == null) {
                AtomicReference<DeviceBridgeFlow.Status> status = new AtomicReference<>();
                AtomicReference<JSONObject> payload = new AtomicReference<>();
                executor.execute(req, (s, d) -> { status.set(s); payload.set(d); });
                assertEquals("Non-DeviceAction executable capability must complete: " + cap,
                        DeviceBridgeFlow.Status.COMPLETED, status.get());
                assertEquals("Notes importantes", payload.get().getString("text"));
            }
        }
    }

    public void testStagedBodyPreservesAttachmentsAcrossReplaySerialization() throws Exception {
        JSONObject body = new JSONObject().put("session_id", "s1").put("input", "Résume ce fichier");
        List<AttachmentUploader.UploadReceipt> receipts = List.of(
                new AttachmentUploader.UploadReceipt("upl_abc123", "doc.pdf", "application/pdf", 1024, "deadbeef"));
        AttachmentUploader.attachToRunBody(body, receipts);
        // Simulates durable journal.stage(body) -> reload -> replay on retry.
        JSONObject replayed = new JSONObject(body.toString());
        assertEquals(1, replayed.getJSONArray("attachments").length());
        assertEquals("upl_abc123", replayed.getJSONArray("attachments").getString(0));
    }

    public void testRejectsInvalidBridgeRequests() throws Exception {
        assertNull(DeviceBridgeFlow.parseRequest(null));
        assertNull(DeviceBridgeFlow.parseRequest(new JSONObject()
                .put("request_id", "../bad")
                .put("action_type", "camera")));
        assertNull(DeviceBridgeFlow.parseRequest(new JSONObject()
                .put("request_id", "req_1")
                .put("action_type", "arbitrary_shell_exec")));
        assertNull(DeviceBridgeFlow.parseRequest(new JSONObject()
                .put("request_id", "req_2")
                .put("action_type", "map_navigation")));
    }

    public void testMediaStatusCommandAndCalendarDaysAheadJsonParsing() throws Exception {
        assertTrue(DeviceBridgeFlow.isMediaStatusCommand(new JSONObject()));
        assertTrue(DeviceBridgeFlow.isMediaStatusCommand(new JSONObject().put("command", "status")));
        assertTrue(DeviceBridgeFlow.isMediaStatusCommand(new JSONObject().put("action", "STATUS")));
        assertFalse(DeviceBridgeFlow.isMediaStatusCommand(new JSONObject().put("command", "pause")));

        assertEquals(7, DeviceBridgeFlow.resolveCalendarDays(new JSONObject()));
        assertEquals(14, DeviceBridgeFlow.resolveCalendarDays(new JSONObject().put("days_ahead", 14)));
        assertEquals(3, DeviceBridgeFlow.resolveCalendarDays(new JSONObject().put("days", 3)));
        assertEquals(1, DeviceBridgeFlow.resolveCalendarDays(new JSONObject().put("days_ahead", 1).put("days", 10)));
    }
}
