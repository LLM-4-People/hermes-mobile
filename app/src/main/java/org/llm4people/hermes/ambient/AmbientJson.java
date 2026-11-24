package org.llm4people.hermes.ambient;

import org.llm4people.hermes.JsonValues;

/** Minimal pure-Java JSON string escaper shared across host JVM and Android ambient payloads. */
final class AmbientJson {
    private AmbientJson() {}

    static String quote(String raw) {
        return JsonValues.quote(raw);
    }
}
