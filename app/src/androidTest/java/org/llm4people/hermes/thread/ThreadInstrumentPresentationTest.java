package org.llm4people.hermes.thread;

import android.content.Context;
import android.test.InstrumentationTestCase;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import org.llm4people.hermes.R;
import org.llm4people.hermes.artifact.ThreadInstruments;
import org.llm4people.hermes.profile.WorkspaceAccountFixture;
import java.util.List;

@SuppressWarnings("deprecation")
public final class ThreadInstrumentPresentationTest extends InstrumentationTestCase {
    public void testPartialAndMalformedWorkspaceRemainInspectibleWhileOtherStreamedCodeStaysVisible() throws Throwable {
        Context context = getInstrumentation().getTargetContext();
        runTestOnUiThread(() -> {
            LiveTranscript stream = new LiveTranscript();
            TextView body = new TextView(context);
            String partial = "Un essai.\n~~~hermes-workspace\n{pas du JSON";
            MessageText.updateLive(body, stream, partial);
            String marker = context.getString(R.string.instrument_stream_source);
            assertEquals("Un essai.\n" + marker, body.getText().toString());
            assertTrue(stream.foldedWorkspace);
            assertEquals(partial, stream.source());

            String complete = partial + "\n~~~\nEnsuite\n```python\nprint(2)\n```";
            MessageText.updateLive(body, stream, complete);
            assertEquals("Un essai.\n" + marker + "\nEnsuite\nprint(2)\n", body.getText().toString());
            TranscriptPage reader = new TranscriptPage(stream.source(), stream.readerStart(), true, 6000);
            assertTrue(MessageText.styled(context, reader).toString().contains("{pas du JSON"));

            String longFence = "~~~hermes-workspace\n" + "x".repeat(3 * LiveTranscript.PAGE_CHARS);
            MessageText.updateLive(body, stream, longFence);
            assertEquals("…\n" + marker, body.getText().toString());
            assertEquals(longFence, stream.source());

            String ordinary = "Un exemple\n````python\n```hermes-workspace\nlittéral\n````\n";
            MessageText.updateLive(body, stream, ordinary);
            assertFalse(stream.foldedWorkspace);
            assertTrue(body.getText().toString().contains("```hermes-workspace\nlittéral"));
            MessageText.updateLive(body, stream, "");
            assertFalse(stream.foldedWorkspace);
        });
    }

    public void testSpeechDescribesOnlyValidatedNamedToolsAtTheirAuthoredPositions() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        try (WorkspaceAccountFixture profile = new WorkspaceAccountFixture(context)) {
            String page = "{\"version\":1,\"mode\":\"replace\",\"surface\":\"page/notes\",\"title\":\"Carnet de voyage\","
                    + "\"blocks\":[{\"type\":\"value\",\"source\":\"thread.title\",\"title\":\"Discussion\"}]}";
            String prefix = "Le premier choix.\n```python\nprint(2)\n```\n";
            String original = prefix + "```hermes-workspace\n" + page + "\n```\nÀ vous de choisir.";
            JSONObject row = new JSONObject().put("id", 42).put("session_id", "root").put("role", "assistant")
                    .put("content", original);
            ThreadInstruments store = new ThreadInstruments(profile.account, "root");
            List<ThreadInstruments.Pin> proposals = store.proposals(new JSONArray().put(row));
            assertEquals(1, proposals.size());
            assertEquals(prefix + "Hermes propose l’outil « Carnet de voyage ».\nÀ vous de choisir.",
                    ThreadInstrumentPanel.spoken(context, row, proposals));

            String malformed = original.replace(page, "{non valide}");
            row.put("content", malformed);
            assertEquals(malformed, ThreadInstrumentPanel.spoken(context, row, proposals));
            row.put("role", "user").put("content", original);
            assertEquals(original, ThreadInstrumentPanel.spoken(context, row, store.proposals(new JSONArray().put(row))));

            String fence = "```hermes-workspace\n" + page + "\n```";
            row.put("role", "assistant").put("content", new JSONArray()
                    .put(new JSONObject().put("type", "input_text").put("text", fence))
                    .put(new JSONObject().put("type", "output_text").put("text", fence)));
            proposals = store.proposals(new JSONArray().put(row));
            assertEquals(1, proposals.size());
            assertEquals(fence + "\nHermes propose l’outil « Carnet de voyage ».",
                    ThreadInstrumentPanel.spoken(context, row, proposals));
        }
    }
}
