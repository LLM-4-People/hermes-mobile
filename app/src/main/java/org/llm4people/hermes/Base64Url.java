package org.llm4people.hermes;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Single source of truth for unpadded URL-safe Base64 UTF-8 string encoding/decoding used by
 * compact pure-Java persistence codecs ({@link org.llm4people.hermes.customization.AppCustomization},
 * {@link org.llm4people.hermes.os.HermesWidgetSpec}).
 */
public final class Base64Url {
    private Base64Url() {}

    public static String encode(String text) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
    }

    public static String decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) return "";
        return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
    }
}
