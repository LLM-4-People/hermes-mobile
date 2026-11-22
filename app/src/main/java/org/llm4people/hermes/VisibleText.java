package org.llm4people.hermes;

import android.icu.lang.UCharacter;
import android.icu.lang.UProperty;

/** Checks for visible input without changing the text the user entered. */
public final class VisibleText {
    private VisibleText() {}

    public static boolean isBlank(String value) {
        for (int i = 0; i < value.length();) {
            int cp = value.codePointAt(i);
            // Older Android ICU tables predate Mongolian free variation selector four (Unicode 14).
            if (!Character.isWhitespace(cp) && !Character.isSpaceChar(cp) && !Character.isISOControl(cp)
                    && Character.getType(cp) != Character.FORMAT
                    && cp != 0x180f && !UCharacter.hasBinaryProperty(cp, UProperty.VARIATION_SELECTOR)) return false;
            i += Character.charCount(cp);
        }
        return true;
    }
}
