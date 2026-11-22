package org.llm4people.hermes.voice;

import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import junit.framework.TestCase;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;

public final class InstalledReadingVoiceTest extends TestCase {
    public void testOnlyInstalledOfflineFrenchVoiceMayReceiveContent() {
        assertTrue(AndroidReplySpeech.installedFrench(voice(Locale.CANADA_FRENCH, false, Collections.emptySet())));
        assertFalse(AndroidReplySpeech.installedFrench(voice(Locale.FRANCE, true, Collections.emptySet())));
        assertFalse(AndroidReplySpeech.installedFrench(voice(Locale.FRANCE, false,
                Collections.singleton(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))));
        assertFalse(AndroidReplySpeech.installedFrench(voice(Locale.ENGLISH, false, Collections.emptySet())));
        assertFalse(AndroidReplySpeech.installedFrench(null));
    }

    private static Voice voice(Locale locale, boolean network, Set<String> features) {
        return new Voice("lecture", locale, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, network, features);
    }
}
