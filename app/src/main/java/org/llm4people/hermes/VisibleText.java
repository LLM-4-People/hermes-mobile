package org.llm4people.hermes;

/** Checks for visible input without changing the text the user entered. */
public final class VisibleText {
    private VisibleText() {}

    public static boolean isBlank(String value) {
        return !PlainTextSanitizer.hasVisibleText(value);
    }
}
