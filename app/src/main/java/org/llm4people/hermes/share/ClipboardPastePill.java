package org.llm4people.hermes.share;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.Editable;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;

import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.profile.IncomingShare;

/**
 * Opt-in, everyday-French clipboard quick-paste pill.
 * Privacy invariant: NEVER reads clipboard text until the user explicitly taps the pill.
 * Uses ClipDescription metadata (MIME + timestamp) only to decide whether to offer the pill.
 */
public final class ClipboardPastePill {
    public interface ClipboardSource {
        boolean hasPlainTextClip();
        long clipTimestamp();
        CharSequence readPrimaryText();
    }

    private final ClipboardSource source;
    private long dismissedTimestamp = Long.MIN_VALUE;

    public ClipboardPastePill(Context context) {
        this(androidSource(context));
    }

    public ClipboardPastePill(ClipboardSource source) {
        this.source = source;
    }

    /** Returns true when a fresh plain-text clip exists without reading clipboard contents. */
    public boolean shouldOffer() {
        try {
            if (!source.hasPlainTextClip()) return false;
            long ts = source.clipTimestamp();
            return ts <= 0 || ts != dismissedTimestamp;
        } catch (RuntimeException unavailable) {
            Diagnostics.debug("share", "clipboard_probe_failed", unavailable);
            return false;
        }
    }

    /** Reads, validates, and sanitizes clipboard text ONLY on explicit user tap. */
    public String consumeOnUserTap() {
        try {
            if (!source.hasPlainTextClip()) return null;
            long ts = source.clipTimestamp();
            CharSequence raw = source.readPrimaryText();
            String sanitized = sanitizeClipboardText(raw);
            if (ts > 0) dismissedTimestamp = ts;
            return sanitized;
        } catch (RuntimeException unavailable) {
            Diagnostics.warning("share", "clipboard_read_failed", unavailable);
            return null;
        }
    }

    public void dismissCurrent() {
        try {
            long ts = source.clipTimestamp();
            if (ts > 0) dismissedTimestamp = ts;
        } catch (RuntimeException unavailable) {
            Diagnostics.debug("share", "clipboard_dismiss_failed", unavailable);
        }
    }

    public static String sanitizeClipboardText(CharSequence raw) {
        if (raw == null || raw.length() == 0) return null;
        int limit = Math.min(raw.length(), IncomingShare.MAX_TEXT);
        String text = raw.subSequence(0, limit).toString();
        if (limit < raw.length() && Character.isHighSurrogate(text.charAt(limit - 1))) {
            text = text.substring(0, limit - 1);
        }
        StringBuilder clean = new StringBuilder(text.length());
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            boolean allowed = cp == '\n' || cp == '\r' || cp == '\t'
                    || (!Character.isISOControl(cp) && Character.getType(cp) != Character.SURROGATE);
            if (allowed) clean.appendCodePoint(cp);
            i += Character.charCount(cp);
        }
        String result = clean.toString();
        return isBlankText(result) ? null : result;
    }

    private static boolean isBlankText(String value) {
        for (int i = 0; i < value.length();) {
            int cp = value.codePointAt(i);
            if (!Character.isWhitespace(cp) && cp != 0x200B && cp != 0x200C && cp != 0x200D && cp != 0xFEFF) {
                return false;
            }
            i += Character.charCount(cp);
        }
        return true;
    }

    /** Create a calm tonal pill that pastes into the target editor at cursor selection (never auto-sends). */
    public Button createView(Context context, EditText editor) {
        Button pill = Ui.button(context, "Coller le presse-papiers", null);
        pill.setContentDescription("Coller le texte copié dans votre message");
        pill.setTextColor(context.getColor(R.color.accent));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Ui.dp(context, 16));
        bg.setColor(context.getColor(R.color.accent_surface));
        bg.setStroke(Ui.dp(context, 1), context.getColor(R.color.line));
        pill.setBackground(bg);
        Ui.pad(pill, 12, 6);
        pill.setMinimumHeight(Ui.dp(context, 48));
        pill.setVisibility(shouldOffer() ? View.VISIBLE : View.GONE);
        pill.setOnClickListener(v -> {
            String text = consumeOnUserTap();
            pill.setVisibility(View.GONE);
            if (text == null || editor == null) return;
            Editable draft = editor.getText();
            int start = Math.max(0, editor.getSelectionStart());
            int end = Math.max(0, editor.getSelectionEnd());
            int from = Math.min(start, end);
            int to = Math.max(start, end);
            if (draft != null) {
                draft.replace(from, to, text);
            }
            editor.requestFocus();
        });
        return pill;
    }

    private static ClipboardSource androidSource(Context context) {
        ClipboardManager manager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        return new ClipboardSource() {
            @Override public boolean hasPlainTextClip() {
                if (manager == null || !manager.hasPrimaryClip()) return false;
                ClipDescription desc = manager.getPrimaryClipDescription();
                return desc != null && (desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)
                        || desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML));
            }

            @Override public long clipTimestamp() {
                if (manager == null || Build.VERSION.SDK_INT < 26) return 0L;
                ClipDescription desc = manager.getPrimaryClipDescription();
                return desc == null ? 0L : desc.getTimestamp();
            }

            @Override public CharSequence readPrimaryText() {
                if (manager == null) return null;
                ClipData clip = manager.getPrimaryClip();
                if (clip == null || clip.getItemCount() == 0) return null;
                return clip.getItemAt(0).coerceToText(context);
            }
        };
    }
}
