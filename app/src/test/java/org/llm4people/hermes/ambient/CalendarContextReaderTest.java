package org.llm4people.hermes.ambient;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class CalendarContextReaderTest {
    private static final long HOUR = 60L * 60L * 1000L;

    @Test
    public void clampsWindowDaysAndEnforcesHardEventCapAndSorting() {
        long now = 1_700_000_000_000L;
        CalendarContextReader.Window def = CalendarContextReader.resolveWindow(now, 0);
        assertEquals(CalendarContextReader.DEFAULT_WINDOW_DAYS, def.days());
        assertEquals(now + 7 * CalendarContextReader.MILLIS_PER_DAY, def.endMillis());

        CalendarContextReader.Window clamped = CalendarContextReader.resolveWindow(now, 90);
        assertEquals(CalendarContextReader.MAX_WINDOW_DAYS, clamped.days());

        List<CalendarContextReader.AgendaEvent> raw = new ArrayList<>();
        // Out-of-window events (before and after)
        raw.add(new CalendarContextReader.AgendaEvent("Past", now - 2 * HOUR, now - HOUR, false));
        raw.add(new CalendarContextReader.AgendaEvent("Far future", def.endMillis() + HOUR, def.endMillis() + 2 * HOUR, false));
        for (int i = 35; i >= 1; i--) {
            raw.add(new CalendarContextReader.AgendaEvent("Meeting " + i, now + i * HOUR, now + (i + 1) * HOUR, false));
        }

        List<CalendarContextReader.AgendaEvent> bounded = CalendarContextReader.boundAndSort(
                raw, def.startMillis(), def.endMillis(), 99);
        assertEquals(CalendarContextReader.MAX_EVENTS, bounded.size());
        assertEquals("Meeting 1", bounded.get(0).title());
        assertEquals("Meeting 25", bounded.get(24).title());
        assertTrue(bounded.get(0).toSanitizedJson().contains("\"title\":\"Meeting 1\""));
    }

    @Test
    public void mediaSelectsPlayingSessionAndFailsClosedOnMissingTarget() {
        MediaContextBridge.NowPlaying paused = new MediaContextBridge.NowPlaying(
                "com.example.podcast", "Podcasts", "Episode 1", "Host", "",
                MediaContextBridge.PlaybackKind.PAUSED, 1000, 60000);
        MediaContextBridge.NowPlaying playing = new MediaContextBridge.NowPlaying(
                "com.example.music", "Music", "Clair de Lune", "Debussy", "Suite",
                MediaContextBridge.PlaybackKind.PLAYING, 5000, 300000);
        assertEquals(playing, MediaContextBridge.selectPrimarySession(List.of(paused, playing)));
        assertTrue(playing.toSanitizedJson().contains("\"state\":\"playing\""));
        // Confirmed media control target lookup must fail closed when target package disappeared.
        assertEquals(paused, MediaContextBridge.selectTargetSession(List.of(paused, playing), "com.example.podcast"));
        org.junit.Assert.assertNull(
                MediaContextBridge.selectTargetSession(List.of(paused, playing), "com.spotify.music"));
    }
}
