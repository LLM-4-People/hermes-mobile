package org.llm4people.hermes.voice;

import org.llm4people.hermes.R;

import java.util.Locale;

/** Single Source of Truth for dictation and offline speech playback language selection. */
public enum VoiceLocale {
    FRENCH("fr", "fr-FR", R.string.voice_locale_french),
    ENGLISH("en", "en-US", R.string.voice_locale_english),
    SYSTEM("", "", R.string.voice_locale_system);

    private final String languageCode;
    private final String defaultTag;
    public final int labelRes;

    VoiceLocale(String languageCode, String defaultTag, int labelRes) {
        this.languageCode = languageCode;
        this.defaultTag = defaultTag;
        this.labelRes = labelRes;
    }

    public String storageKey() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static VoiceLocale fromStorageKey(String key) {
        if (key == null) return FRENCH;
        return switch (key.trim().toLowerCase(Locale.ROOT)) {
            case "english", "en" -> ENGLISH;
            case "system", "auto" -> SYSTEM;
            default -> FRENCH;
        };
    }

    /** Resolve BCP-47 tag for Android speech recognition, preserving regional variant when matching. */
    public String resolveTag(Locale systemLocale) {
        Locale fallback = systemLocale == null ? Locale.FRANCE : systemLocale;
        if (this == SYSTEM) {
            String tag = fallback.toLanguageTag();
            return tag == null || tag.isEmpty() || "und".equals(tag) ? "fr-FR" : tag;
        }
        if (languageCode.equalsIgnoreCase(fallback.getLanguage())) {
            String tag = fallback.toLanguageTag();
            if (tag != null && !tag.isEmpty() && !"und".equals(tag)) return tag;
        }
        return defaultTag;
    }

    /** Resolve ISO-639 language code for offline TTS voice matching. */
    public String resolveLanguage(Locale systemLocale) {
        if (this != SYSTEM) return languageCode;
        if (systemLocale != null && systemLocale.getLanguage() != null && !systemLocale.getLanguage().isEmpty()) {
            return systemLocale.getLanguage().toLowerCase(Locale.ROOT);
        }
        return "fr";
    }
}
