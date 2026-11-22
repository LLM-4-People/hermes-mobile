package org.llm4people.hermes.artifact;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.PersistableBundle;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;

/** Break preferably on a complete line and never split a surrogate pair. */
public final class CodeReader {
    private static final int PAGE_CHARS = 6000;
    private final String source;
    private final boolean diff;
    private final List<Integer> cuts = new ArrayList<>();

    CodeReader(String source, String language) {
        this.source = source;
        diff = "diff".equals(language) || "patch".equals(language);
        cuts.add(0);
        for (int at = 0; at < source.length();) {
            int target = Math.min(at + PAGE_CHARS, source.length());
            if (target < source.length()) {
                int newline = target - 1;
                while (newline >= at + PAGE_CHARS / 2 && source.charAt(newline) != '\n') newline--;
                if (newline >= at + PAGE_CHARS / 2) target = newline + 1;
                else if (Character.isHighSurrogate(source.charAt(target - 1))) target--;
            }
            cuts.add(target);
            at = target;
        }
    }

    /** The owner tracks and dismisses this dialog with the originating conversation. */
    public static AlertDialog.Builder dialog(Context context, String title, String language, String source) {
        LinearLayout box = Ui.column(context);
        Ui.pad(box, 16, 8);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(box);
        contents(box, title, language, source);
        return new AlertDialog.Builder(context).setTitle(title).setView(scroll).setNegativeButton("Fermer", null);
    }

    static void contents(LinearLayout box, String title, String language, String source) {
        Context context = box.getContext();
        box.addView(Ui.button(context, "Copier le contenu", v -> copy(context, source, title)));
        new CodeReader(source, language).mount(box, 0);
    }

    void mount(LinearLayout parent, int page) {
        Context c = parent.getContext();
        LinearLayout section = Ui.column(c);
        parent.addView(section);
        render(c, section, page);
    }

    private void render(Context c, LinearLayout section, int page) {
        section.removeAllViews();
        int pages = Math.max(1, cuts.size() - 1);
        if (pages > 1) {
            section.addView(Ui.text(c, "Page " + (page + 1) + " sur " + pages, 14));
            LinearLayout nav = Ui.row(c);
            if (page > 0) nav.addView(Ui.button(c, "Précédente", v -> render(c, section, page - 1)));
            if (page < pages - 1) nav.addView(Ui.button(c, "Suivante", v -> render(c, section, page + 1)));
            section.addView(nav);
        }
        int start = cuts.get(page);
        String text = source.substring(start, cuts.size() > 1 ? cuts.get(page + 1) : 0);
        TextView code = Ui.text(c, "", 14);
        code.setTypeface(Typeface.MONOSPACE);
        code.setTextDirection(TextView.TEXT_DIRECTION_LTR);
        code.setTextIsSelectable(true);
        code.setHorizontallyScrolling(false);
        code.setLineSpacing(0, 1.14f);
        code.setBackgroundColor(c.getColor(R.color.surface));
        Ui.pad(code, 10, 8);
        code.setText(diff ? highlight(c, text, start) : text);
        section.addView(code);
    }

    private CharSequence highlight(Context c, String text, int offset) {
        SpannableString marked = new SpannableString(text);
        for (int p = 0; p < text.length();) {
            int next = text.indexOf('\n', p);
            int end = next < 0 ? text.length() : next;
            if (end > p && (p > 0 || offset == 0 || source.charAt(offset - 1) == '\n')) {
                int sign = text.charAt(p);
                if (sign == '+' || sign == '-') marked.setSpan(new ForegroundColorSpan(
                        c.getColor(sign == '+' ? R.color.accent : R.color.danger)), p, end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            p = end + 1;
        }
        return marked;
    }

    private static void copy(Context activity, String content, String title) {
        ClipboardManager clipboard = (ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText(title, content);
        PersistableBundle extras = new PersistableBundle();
        extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
        clip.getDescription().setExtras(extras);
        try {
            clipboard.setPrimaryClip(clip);
            if (android.os.Build.VERSION.SDK_INT < 33)
                Toast.makeText(activity, "Contenu copié", Toast.LENGTH_SHORT).show();
        } catch (RuntimeException e) {
            Diagnostics.warning("artifact", "copy_failed");
            Toast.makeText(activity, "La copie a échoué. Sélectionnez une partie du texte.", Toast.LENGTH_LONG).show();
        }
    }
}
