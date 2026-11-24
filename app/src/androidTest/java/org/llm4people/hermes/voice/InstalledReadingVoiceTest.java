package org.llm4people.hermes.voice;

import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import junit.framework.TestCase;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;

public final class InstalledReadingVoiceTest extends TestCase {
    public void testOnlyInstalledOfflineFrenchVoiceMayReceiveContent() {
        assertTrue(AndroidReplySpeech.installedForLanguage(voice(Locale.CANADA_FRENCH, false, Collections.emptySet()), "fr"));
        assertFalse(AndroidReplySpeech.installedForLanguage(voice(Locale.FRANCE, true, Collections.emptySet()), "fr"));
        assertFalse(AndroidReplySpeech.installedForLanguage(voice(Locale.FRANCE, false,
                Collections.singleton(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)), "fr"));
        assertFalse(AndroidReplySpeech.installedForLanguage(voice(Locale.ENGLISH, false, Collections.emptySet()), "fr"));
        assertFalse(AndroidReplySpeech.installedForLanguage(null, "fr"));

        assertTrue(AndroidReplySpeech.installedForLanguage(voice(Locale.US, false, Collections.emptySet()), "en"));
        assertFalse(AndroidReplySpeech.installedForLanguage(voice(Locale.US, true, Collections.emptySet()), "en"));
        assertFalse(AndroidReplySpeech.installedForLanguage(voice(Locale.CANADA_FRENCH, false, Collections.emptySet()), "en"));
    }

    private static Voice voice(Locale locale, boolean network, Set<String> features) {
        return new Voice("lecture", locale, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, network, features);
    }
}
