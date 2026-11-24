package org.llm4people.hermes.ambient;

import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.assertEquals;

public final class AmbientJsonTest {

    @Test
    public void quoteUsesRootLocaleForControlCharactersRegardlessOfDefaultLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG-u-nu-arab"));
            assertEquals("\"a\\u0007b\"", AmbientJson.quote("a\u0007b"));
            assertEquals("null", AmbientJson.quote(null));
            assertEquals("\"line1\\n\\\"quoted\\\"\"", AmbientJson.quote("line1\n\"quoted\""));
        } finally {
            Locale.setDefault(previous);
        }
    }
}
