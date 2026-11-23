package org.llm4people.hermes.artifact;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.PersistableBundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Break preferably on a complete line and never split a surrogate pair. */
public final class CodeReader {
    private static final int PAGE_CHARS = 6000;
    private final String source;
    private final SyntaxHighlighter.Language language;
    private final List<Integer> cuts = new ArrayList<>();
    private final int[] pageFirstLines;
    private boolean showLineNumbers = true;
    private boolean horizontalScroll;
    private boolean searchVisible;
    private String searchQuery = "";
    private List<SyntaxHighlighter.SearchMatch> matches = Collections.emptyList();
    private int activeMatch = -1;
    private int currentPage;
    private LinearLayout section;
    private LinearLayout searchBar;
    private EditText searchInput;
    private TextView searchCounter;
    private Button lineNumbersButton;
    private Button scrollToggleButton;
    private Button prevMatchButton;
    private Button nextMatchButton;

    CodeReader(String source, String language) {
        this.source = source == null ? "" : source;
        this.language = SyntaxHighlighter.Language.fromTag(language);
        cuts.add(0);
        for (int at = 0; at < this.source.length();) {
            int target = Math.min(at + PAGE_CHARS, this.source.length());
            if (target < this.source.length()) {
                int newline = target - 1;
                while (newline >= at + PAGE_CHARS / 2 && this.source.charAt(newline) != '\n') newline--;
                if (newline >= at + PAGE_CHARS / 2) target = newline + 1;
                else if (Character.isHighSurrogate(this.source.charAt(target - 1))) target--;
            }
            cuts.add(target);
            at = target;
        }
        int pages = Math.max(1, cuts.size() - 1);
        pageFirstLines = new int[pages];
        int runningLine = 1;
        for (int p = 0; p < pages; p++) {
            pageFirstLines[p] = runningLine;
            int from = cuts.get(p);
            int to = cuts.size() > 1 ? cuts.get(p + 1) : 0;
            for (int i = from; i < to; i++) {
                if (this.source.charAt(i) == '\n') runningLine++;
            }
        }
    }

    /** The owner tracks and dismisses this dialog with the originating conversation. */
    public static AlertDialog.Builder dialog(Context context, String title, String language, String source) {
        LinearLayout box = Ui.column(context);
        Ui.pad(box, 16, 8);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(box);
        contents(box, title, language, source);
        return new AlertDialog.Builder(context).setTitle(title).setView(scroll).setNegativeButton(R.string.ws_close, null);
    }

    static void contents(LinearLayout box, String title, String language, String source) {
        CodeReader reader = new CodeReader(source, language);
        reader.mountWithToolbar(box, title);
    }

    void mount(LinearLayout parent, int page) {
        Context c = parent.getContext();
        section = Ui.column(c);
        parent.addView(section);
        render(c, page);
    }

    private void mountWithToolbar(LinearLayout box, String title) {
        Context c = box.getContext();
        LinearLayout actions = Ui.row(c);
        actions.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        Button copy = Ui.button(c, c.getString(R.string.code_copy_content), v -> copy(c, source, title));
        actions.addView(copy);
        Button searchToggle = Ui.button(c, c.getString(R.string.code_search), v -> toggleSearch(c));
        actions.addView(searchToggle);
        box.addView(actions);

        LinearLayout displayOptions = Ui.row(c);
        displayOptions.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        lineNumbersButton = Ui.button(c, c.getString(R.string.code_line_numbers), v -> {
            showLineNumbers = !showLineNumbers;
            updateOptionLabels(c);
            render(c, currentPage);
        });
        scrollToggleButton = Ui.button(c, c.getString(R.string.code_scroll_horizontal), v -> {
            horizontalScroll = !horizontalScroll;
            updateOptionLabels(c);
            render(c, currentPage);
        });
        updateOptionLabels(c);
        displayOptions.addView(lineNumbersButton);
        displayOptions.addView(scrollToggleButton);
        box.addView(displayOptions);

        searchBar = buildSearchBar(c);
        searchBar.setVisibility(View.GONE);
        box.addView(searchBar);

        mount(box, 0);
    }

    private void updateOptionLabels(Context c) {
        if (lineNumbersButton != null) {
            lineNumbersButton.setText(c.getString(R.string.code_line_numbers));
            lineNumbersButton.setContentDescription(c.getString(
                    showLineNumbers ? R.string.code_line_numbers_hide : R.string.code_line_numbers_show));
            lineNumbersButton.setTypeface(Typeface.DEFAULT, showLineNumbers ? Typeface.BOLD : Typeface.NORMAL);
        }
        if (scrollToggleButton != null) {
            scrollToggleButton.setText(c.getString(
                    horizontalScroll ? R.string.code_wrap_lines : R.string.code_scroll_horizontal));
        }
    }

    private LinearLayout buildSearchBar(Context c) {
        LinearLayout bar = Ui.column(c);
        Ui.pad(bar, 0, 4);
        searchInput = new EditText(c);
        searchInput.setSaveEnabled(false);
        searchInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        searchInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        searchInput.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING | EditorInfo.IME_ACTION_SEARCH);
        searchInput.setSingleLine(true);
        searchInput.setMinHeight(Ui.dp(c, 48));
        searchInput.setHint(R.string.code_search_hint);
        searchInput.setTextColor(c.getColor(R.color.ink));
        searchInput.setHintTextColor(c.getColor(R.color.muted));
        searchInput.setBackgroundResource(R.drawable.profile_input);
        Ui.pad(searchInput, 10, 6);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                applySearch(c, s.toString());
            }
        });
        bar.addView(searchInput, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout nav = Ui.row(c);
        searchCounter = Ui.text(c, "", 14);
        searchCounter.setTextColor(c.getColor(R.color.muted));
        searchCounter.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        prevMatchButton = Ui.button(c, c.getString(R.string.code_search_previous), v -> stepMatch(c, -1));
        nextMatchButton = Ui.button(c, c.getString(R.string.code_search_next), v -> stepMatch(c, 1));
        nav.addView(searchCounter, new LinearLayout.LayoutParams(0, -2, 1));
        nav.addView(prevMatchButton);
        nav.addView(nextMatchButton);
        bar.addView(nav);
        updateSearchCounter(c);
        return bar;
    }

    private void toggleSearch(Context c) {
        searchVisible = !searchVisible;
        searchBar.setVisibility(searchVisible ? View.VISIBLE : View.GONE);
        if (searchVisible) {
            searchInput.requestFocus();
        } else {
            searchInput.setText("");
        }
    }

    private void applySearch(Context c, String query) {
        searchQuery = query == null ? "" : query;
        if (searchQuery.isEmpty()) {
            matches = Collections.emptyList();
            activeMatch = -1;
        } else {
            matches = SyntaxHighlighter.findMatches(source, searchQuery);
            activeMatch = matches.isEmpty() ? -1 : 0;
            if (activeMatch >= 0) {
                int targetPage = pageForOffset(matches.get(activeMatch).start());
                if (targetPage != currentPage) currentPage = targetPage;
            }
        }
        updateSearchCounter(c);
        render(c, currentPage);
    }

    private void stepMatch(Context c, int delta) {
        if (matches.isEmpty()) return;
        activeMatch = (activeMatch + delta + matches.size()) % matches.size();
        currentPage = pageForOffset(matches.get(activeMatch).start());
        updateSearchCounter(c);
        render(c, currentPage);
    }

    private void updateSearchCounter(Context c) {
        boolean hasQuery = !searchQuery.isEmpty();
        boolean hasMatches = !matches.isEmpty();
        if (!hasQuery) searchCounter.setText("");
        else if (!hasMatches) searchCounter.setText(c.getString(R.string.code_search_none));
        else searchCounter.setText(c.getString(R.string.code_search_count, activeMatch + 1, matches.size()));
        prevMatchButton.setEnabled(hasMatches);
        nextMatchButton.setEnabled(hasMatches);
    }

    int pageForOffset(int offset) {
        int pages = Math.max(1, cuts.size() - 1);
        for (int p = 0; p < pages; p++) {
            int end = cuts.size() > 1 ? cuts.get(p + 1) : source.length();
            if (offset < end) return p;
        }
        return pages - 1;
    }

    private void render(Context c, int page) {
        currentPage = Math.max(0, Math.min(page, Math.max(1, cuts.size() - 1) - 1));
        section.removeAllViews();
        int pages = Math.max(1, cuts.size() - 1);
        if (pages > 1) {
            section.addView(Ui.text(c, c.getString(R.string.code_page_of, currentPage + 1, pages), 14));
            LinearLayout nav = Ui.row(c);
            if (currentPage > 0) nav.addView(Ui.button(c, c.getString(R.string.transcript_previous), v -> render(c, currentPage - 1)));
            if (currentPage < pages - 1) nav.addView(Ui.button(c, c.getString(R.string.transcript_next), v -> render(c, currentPage + 1)));
            section.addView(nav);
        }
        int start = cuts.get(currentPage);
        String text = source.substring(start, cuts.size() > 1 ? cuts.get(currentPage + 1) : 0);
        if (text.length() > SyntaxHighlighter.MAX_HIGHLIGHT_CHARS) {
            Diagnostics.event("artifact", "highlight_bounded");
        }
        TextView code = Ui.text(c, "", 14);
        code.setTypeface(Typeface.MONOSPACE);
        code.setTextDirection(TextView.TEXT_DIRECTION_LTR);
        code.setTextIsSelectable(true);
        code.setHorizontallyScrolling(horizontalScroll);
        code.setLineSpacing(0, 1.14f);
        code.setBackgroundColor(c.getColor(R.color.surface));
        Ui.pad(code, 10, 8);
        int firstLine = pageFirstLines[currentPage];
        code.setText(SyntaxHighlighter.render(c, text, language, start, showLineNumbers, firstLine, matches, activeMatch));
        if (horizontalScroll) {
            HorizontalScrollView hScroll = new HorizontalScrollView(c);
            hScroll.setFillViewport(true);
            hScroll.setBackgroundColor(c.getColor(R.color.surface));
            hScroll.addView(code, new HorizontalScrollView.LayoutParams(-2, -2));
            section.addView(hScroll, new LinearLayout.LayoutParams(-1, -2));
        } else {
            section.addView(code, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private static void copy(Context activity, String content, String title) {
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText(title, content);
        PersistableBundle extras = new PersistableBundle();
        extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
        clip.getDescription().setExtras(extras);
        try {
            clipboard.setPrimaryClip(clip);
            if (android.os.Build.VERSION.SDK_INT < 33)
                Toast.makeText(activity, R.string.code_copied, Toast.LENGTH_SHORT).show();
        } catch (RuntimeException e) {
            Diagnostics.warning("artifact", "copy_failed");
            Toast.makeText(activity, R.string.code_copy_failed, Toast.LENGTH_LONG).show();
        }
    }
}
