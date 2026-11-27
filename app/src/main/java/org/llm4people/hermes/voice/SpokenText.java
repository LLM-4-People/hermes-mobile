package org.llm4people.hermes.voice;

import android.content.Context;

import org.llm4people.hermes.AppLanguage;
import org.llm4people.hermes.R;
import org.llm4people.hermes.artifact.Fences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/** Keeps local playback bounded even when a reply contains an attachment or a generated program. */
final class SpokenText {
    private static final int SCAN_LIMIT = 96_000;
    private static final int SPEECH_LIMIT = 16_000;
    private static final int CHUNK = 1800;
    private static final Pattern PRIVATE_BLOCK = Pattern.compile(
            "(?is)<(think|thinking|analysis|reasoning|tool_call)\\b[^>]*>.*?(?:</\\1\\s*>|\\z)");
    private static final Pattern DATA = Pattern.compile("(?i)data:[^\\s,]{0,150};base64,[a-z0-9+/=_-]*");
    private static final Pattern BLOB = Pattern.compile("[A-Za-z0-9+/_=-]{48,}|\\b[A-Za-z0-9+/_-]{2,}={1,2}(?![=\\w])");
    private static final Pattern LINK = Pattern.compile("!?\\[([^]\\n]{0,240})\\]\\([^\\s)]{1,2048}\\)");
    private static final Pattern URL = Pattern.compile("(?i)https?://\\S+");
    private static final Pattern MARKUP = Pattern.compile("[`*_]+|^\\s{0,3}[#>]+\\s*");

    static String prose(Context context, String source) {
        String suffix = context == null
                ? null
                : AppLanguage.wrapContext(context).getString(R.string.voice_spoken_continued_on_screen);
        return prose(source, suffix);
    }

    static String prose(String source, String continuationSuffix) {
        if (source == null || source.isEmpty()) return "";
        boolean shortened = source.length() > SCAN_LIMIT;
        String text = source.substring(0, Math.min(source.length(), SCAN_LIMIT));
        if (shortened) {
            // Drop the partial token so an attachment cut at the scan limit stays private.
            int boundary = text.length();
            while (boundary > 0 && !Character.isWhitespace(text.charAt(boundary - 1))) boundary--;
            text = text.substring(0, boundary);
        }
        text = PRIVATE_BLOCK.matcher(text).replaceAll(" ");
        StringBuilder spoken = new StringBuilder();
        Fences.Marker open = null;
        for (int start = 0; start < text.length();) {
            int end = text.indexOf('\n', start);
            if (end < 0) end = text.length();
            Fences.Marker marker = Fences.marker(text, start, end);
            if (open != null) {
                if (marker != null && marker.closes(open)) open = null;
            } else if (marker != null) {
                open = marker;
            } else if (!text.startsWith("    ", start) && !text.startsWith("\t", start)) {
                String line = text.substring(start, end).trim();
                if (!line.startsWith("{") && !line.startsWith("[{") && !line.startsWith("diff --git ")) {
                    line = LINK.matcher(line).replaceAll("$1");
                    line = DATA.matcher(line).replaceAll(" ");
                    line = BLOB.matcher(line).replaceAll(" ");
                    line = URL.matcher(line).replaceAll(" ");
                    line = MARKUP.matcher(line).replaceAll("").trim();
                    if (!line.isEmpty()) {
                        if (spoken.length() > 0) spoken.append('\n');
                        spoken.append(line);
                        if (spoken.length() > SPEECH_LIMIT) {
                            shortened = true;
                            int cut = spoken.lastIndexOf(" ", SPEECH_LIMIT);
                            spoken.setLength(cut >= SPEECH_LIMIT - 120 ? cut : SPEECH_LIMIT);
                            break;
                        }
                    }
                }
            }
            start = end + 1;
        }
        String suffix = continuationSuffix == null ? "" : continuationSuffix.trim();
        if (shortened && spoken.length() > 0 && !suffix.isEmpty()) {
            spoken.append('\n').append(suffix);
        }
        return spoken.toString();
    }

    static List<String> chunks(String text) {
        List<String> result = new ArrayList<>();
        for (int start = 0; start < text.length();) {
            int end = Math.min(start + CHUNK, text.length());
            if (end < text.length()) {
                int split = end;
                while (split > start && !Character.isWhitespace(text.charAt(split - 1))) split--;
                if (split > start) end = split;
                else if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
            }
            result.add(text.substring(start, end));
            start = end;
        }
        return result;
    }

    /**
     * Incrementally extracts completed prose sentences from a streaming reply for low-latency TTS.
     * Skips private reasoning blocks, code fences, and raw URLs using {@link #prose(String)}.
     */
    static final class SentenceStream {
        private int emittedChars;

        void reset() { emittedChars = 0; }

        List<String> push(String cumulativeRaw) {
            String clean = prose(cumulativeRaw, (String) null);
            if (clean.length() <= emittedChars) return Collections.emptyList();
            boolean endsWithSpace = cumulativeRaw != null && !cumulativeRaw.isEmpty()
                    && Character.isWhitespace(cumulativeRaw.charAt(cumulativeRaw.length() - 1));
            List<String> out = new ArrayList<>();
            int cursor = emittedChars;
            while (cursor < clean.length()) {
                int split = nextSentenceBoundary(clean, cursor, endsWithSpace);
                if (split < 0) break;
                String sentence = clean.substring(cursor, split).trim();
                if (!sentence.isEmpty()) out.add(sentence);
                cursor = split;
                while (cursor < clean.length() && Character.isWhitespace(clean.charAt(cursor))) cursor++;
            }
            emittedChars = cursor;
            return out;
        }

        List<String> flush(String finalRaw) {
            String clean = prose(finalRaw, (String) null);
            if (clean.length() <= emittedChars) return Collections.emptyList();
            List<String> out = new ArrayList<>(push(finalRaw));
            if (emittedChars < clean.length()) {
                String tail = clean.substring(emittedChars).trim();
                if (!tail.isEmpty()) {
                    out.addAll(chunks(tail));
                }
                emittedChars = clean.length();
            }
            return out;
        }

        private static int nextSentenceBoundary(String text, int start, boolean allowEndBoundary) {
            for (int i = start; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\n') {
                    return i + 1;
                }
                if (c == '.' || c == '!' || c == '?' || c == '…') {
                    int next = i + 1;
                    while (next < text.length() && (text.charAt(next) == '.' || text.charAt(next) == '!'
                            || text.charAt(next) == '?' || text.charAt(next) == '»' || text.charAt(next) == '"')) {
                        next++;
                    }
                    if ((next < text.length() && Character.isWhitespace(text.charAt(next)))
                            || (next == text.length() && allowEndBoundary)) {
                        return next;
                    }
                }
            }
            return -1;
        }
    }

    private SpokenText() {}
}
