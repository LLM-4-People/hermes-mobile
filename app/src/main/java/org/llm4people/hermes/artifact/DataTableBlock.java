package org.llm4people.hermes.artifact;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.JsonReader;
import android.util.JsonToken;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.IOException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.llm4people.hermes.AppLanguage;
import java.util.Set;
import org.llm4people.hermes.Ui;

import static org.llm4people.hermes.artifact.WorkspaceLayout.invalid;
import static org.llm4people.hermes.artifact.WorkspaceLayout.label;
import static org.llm4people.hermes.artifact.WorkspaceLayout.require;

/** Interactive sortable and filterable native data table. Tapping a row only prepares an editable draft. */
public final class DataTableBlock {
    public static final int MAX_COLUMNS = 6;
    public static final int MAX_ROWS = 50;
    public static final int MAX_CELL_CHARS = 120;

    public static final class Column {
        public final String key;
        public final String label;
        public final boolean numeric;

        public Column(String key, String label, boolean numeric) {
            this.key = key;
            this.label = label;
            this.numeric = numeric;
        }

        @Override public boolean equals(Object o) {
            return o instanceof Column other && numeric == other.numeric
                    && key.equals(other.key) && label.equals(other.label);
        }

        @Override public int hashCode() { return Objects.hash(key, label, numeric); }
    }

    /** Pure-Java deterministic table model with sorting, filtering, and safe draft preparation. */
    public static final class Model {
        public final List<Column> columns;
        public final List<List<String>> rows;
        public final boolean sortable;
        public final boolean filterable;
        public final String draftPrompt;

        private int sortColumn = -1;
        private boolean ascending = true;
        private String filterQuery = "";

        public Model(List<Column> columns, List<List<String>> rows, boolean sortable,
                     boolean filterable, String draftPrompt) throws IOException {
            require(columns != null && !columns.isEmpty() && columns.size() <= MAX_COLUMNS);
            require(rows != null && rows.size() <= MAX_ROWS);
            Set<String> seenKeys = new HashSet<>();
            for (Column col : columns) {
                require(col != null && DraftForm.validId(col.key) && seenKeys.add(col.key)
                        && col.label != null && !col.label.isEmpty());
            }
            List<List<String>> cleanRows = new ArrayList<>(rows.size());
            for (List<String> row : rows) {
                require(row != null && row.size() == columns.size());
                List<String> cleanCells = new ArrayList<>(row.size());
                for (String cell : row) {
                    String safe = cell == null ? "" : cell;
                    require(safe.length() <= MAX_CELL_CHARS);
                    cleanCells.add(safe);
                }
                cleanRows.add(Collections.unmodifiableList(cleanCells));
            }
            this.columns = Collections.unmodifiableList(new ArrayList<>(columns));
            this.rows = Collections.unmodifiableList(cleanRows);
            this.sortable = sortable;
            this.filterable = filterable;
            this.draftPrompt = draftPrompt == null ? "" : draftPrompt;
        }

        public int sortColumn() { return sortColumn; }
        public boolean ascending() { return ascending; }
        public String filterQuery() { return filterQuery; }

        public void toggleSort(int columnIndex) {
            if (!sortable || columnIndex < 0 || columnIndex >= columns.size()) return;
            if (sortColumn == columnIndex) {
                ascending = !ascending;
            } else {
                sortColumn = columnIndex;
                ascending = true;
            }
        }

        public void setFilter(String query) {
            setFilter(query, Locale.getDefault());
        }

        public void setFilter(String query, Locale locale) {
            if (!filterable) return;
            Locale effective = locale == null ? Locale.getDefault() : locale;
            filterQuery = query == null ? "" : query.trim().toLowerCase(effective);
        }

        public List<List<String>> visibleRows() {
            return visibleRows(Locale.getDefault());
        }

        public List<List<String>> visibleRows(Locale locale) {
            Locale effective = locale == null ? Locale.getDefault() : locale;
            List<List<String>> result = new ArrayList<>();
            for (List<String> row : rows) {
                if (filterQuery.isEmpty() || matches(row, filterQuery, effective)) result.add(row);
            }
            if (sortColumn >= 0 && sortColumn < columns.size()) {
                int col = sortColumn;
                boolean numeric = columns.get(col).numeric;
                Collator collator = Collator.getInstance(effective);
                collator.setStrength(Collator.PRIMARY);
                Comparator<List<String>> cmp = (a, b) -> compareCells(a.get(col), b.get(col), numeric, collator);
                result.sort(ascending ? cmp : cmp.reversed());
            }
            return Collections.unmodifiableList(result);
        }

        public String draftForRow(List<String> row) {
            if (draftPrompt.isEmpty() || row == null || row.size() != columns.size()) return "";
            StringBuilder sb = new StringBuilder(draftPrompt);
            for (int i = 0; i < columns.size(); i++) {
                String value = row.get(i);
                if (!value.isEmpty()) sb.append("\n").append(columns.get(i).label).append(" : ").append(value);
            }
            return sb.toString();
        }

        private static boolean matches(List<String> row, String query, Locale locale) {
            for (String cell : row) {
                if (cell.toLowerCase(locale).contains(query)) return true;
            }
            return false;
        }

        private static int compareCells(String a, String b, boolean numeric, Collator collator) {
            if (numeric) {
                Double da = parseLooseNumber(a);
                Double db = parseLooseNumber(b);
                if (da != null && db != null) return Double.compare(da, db);
                if (da != null) return -1;
                if (db != null) return 1;
            }
            return collator.compare(a, b);
        }

        private static Double parseLooseNumber(String raw) {
            if (raw == null || raw.isEmpty()) return null;
            String normalized = raw.replace('\u00A0', ' ').replace(" ", "").replace(',', '.');
            StringBuilder num = new StringBuilder();
            for (int i = 0; i < normalized.length(); i++) {
                char c = normalized.charAt(i);
                if ((c >= '0' && c <= '9') || c == '.' || (num.length() == 0 && (c == '-' || c == '+'))) {
                    num.append(c);
                } else if (num.length() > 0) {
                    break;
                }
            }
            if (num.length() == 0) return null;
            try {
                double d = Double.parseDouble(num.toString());
                return Double.isFinite(d) ? d : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        @Override public boolean equals(Object o) {
            return o instanceof Model other && sortable == other.sortable && filterable == other.filterable
                    && draftPrompt.equals(other.draftPrompt) && columns.equals(other.columns) && rows.equals(other.rows);
        }

        @Override public int hashCode() {
            return Objects.hash(columns, rows, sortable, filterable, draftPrompt);
        }
    }

    private DataTableBlock() {}

    static List<Column> readColumns(JsonReader in) throws IOException {
        List<Column> cols = new ArrayList<>();
        in.beginArray();
        while (in.hasNext()) {
            require(cols.size() < MAX_COLUMNS);
            String key = null, colLabel = null;
            boolean numeric = false;
            Set<String> seen = new HashSet<>();
            in.beginObject();
            while (in.hasNext()) {
                String k = in.nextName();
                require(seen.add(k));
                switch (k) {
                    case "key" -> { key = label(in, 36); require(DraftForm.validId(key)); }
                    case "label" -> colLabel = label(in, 48);
                    case "numeric" -> { require(in.peek() == JsonToken.BOOLEAN); numeric = in.nextBoolean(); }
                    default -> throw invalid();
                }
            }
            in.endObject();
            require(key != null && colLabel != null);
            cols.add(new Column(key, colLabel, numeric));
        }
        in.endArray();
        require(!cols.isEmpty());
        return cols;
    }

    static List<List<String>> readRows(JsonReader in, int expectedColumns) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        in.beginArray();
        while (in.hasNext()) {
            require(rows.size() < MAX_ROWS);
            List<String> cells = new ArrayList<>();
            in.beginArray();
            while (in.hasNext()) {
                require(cells.size() < MAX_COLUMNS);
                if (in.peek() == JsonToken.NUMBER) {
                    double d = in.nextDouble();
                    require(Double.isFinite(d));
                    cells.add(d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : Double.toString(d));
                } else if (in.peek() == JsonToken.STRING) {
                    String s = in.nextString();
                    require(s.length() <= MAX_CELL_CHARS);
                    validateCellChars(s);
                    cells.add(s.trim());
                } else {
                    throw invalid();
                }
            }
            in.endArray();
            if (expectedColumns > 0) require(cells.size() == expectedColumns);
            rows.add(cells);
        }
        in.endArray();
        return rows;
    }

    private static void validateCellChars(String s) throws IOException {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int kind = Character.getType(cp);
            require(!Character.isISOControl(cp) && kind != Character.FORMAT && kind != Character.SURROGATE
                    && kind != Character.LINE_SEPARATOR && kind != Character.PARAGRAPH_SEPARATOR);
            i += Character.charCount(cp);
        }
    }

    static View build(Context context, Model model, ThemePalette palette, ArtifactDesk.Host host) {
        ThemePalette colors = palette == null
                ? ThemePalette.resolve(ThemePalette.OverrideSpec.defaults(), ThemePalette.isNight(context))
                : palette;
        LinearLayout container = Ui.column(context);
        LinearLayout tableBody = Ui.column(context);

        if (model.filterable && host != null) {
            EditText search = WorkspaceForms.editor(context, false, 80, colors);
            search.setHint(org.llm4people.hermes.R.string.ws_table_filter_hint);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = Ui.dp(context, 8);
            container.addView(search, lp);
            search.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void afterTextChanged(Editable s) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    model.setFilter(s.toString(), AppLanguage.resolvedLocale(context));
                    renderTable(context, tableBody, model, colors, host);
                }
            });
        }

        HorizontalScrollView hScroll = new HorizontalScrollView(context);
        hScroll.setFillViewport(true);
        GradientDrawable frame = new GradientDrawable();
        frame.setCornerRadius(Ui.dp(context, Ui.CARD_CORNER_DP));
        frame.setColor(colors.surface);
        frame.setStroke(Math.max(1, Ui.dp(context, 1)), colors.line);
        hScroll.setBackground(frame);
        hScroll.setClipToOutline(true);
        hScroll.addView(tableBody);
        container.addView(hScroll);

        renderTable(context, tableBody, model, colors, host);
        return container;
    }

    private static void renderTable(Context c, LinearLayout tableBody, Model model,
                                    ThemePalette colors, ArtifactDesk.Host host) {
        tableBody.removeAllViews();
        int colWidth = Ui.dp(c, 128);
        LinearLayout headerRow = Ui.row(c);
        headerRow.setBackgroundColor(colors.accentSurface);
        for (int i = 0; i < model.columns.size(); i++) {
            int colIndex = i;
            Column col = model.columns.get(i);
            boolean activeSort = model.sortColumn() == i;
            Button head = Ui.button(c, col.label, v -> {
                model.toggleSort(colIndex);
                renderTable(c, tableBody, model, colors, host);
            });
            head.setEnabled(model.sortable);
            head.setTextColor(activeSort ? colors.accent : colors.ink);
            head.setTextSize(colors.scaledSp(14));
            head.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            head.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0,
                    activeSort ? (model.ascending() ? android.R.drawable.arrow_up_float : android.R.drawable.arrow_down_float) : 0,
                    0);
            if (activeSort) {
                head.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(colors.accent));
                head.setCompoundDrawablePadding(Ui.dp(c, 4));
            }
            head.setGravity((col.numeric ? Gravity.END : Gravity.START) | Gravity.CENTER_VERTICAL);
            head.setMinimumWidth(colWidth);
            head.setMinWidth(colWidth);
            Ui.pad(head, 10, 6);
            if (model.sortable) {
                String state = model.sortColumn() == i
                        ? c.getString(model.ascending()
                                ? org.llm4people.hermes.R.string.ws_table_sort_asc
                                : org.llm4people.hermes.R.string.ws_table_sort_desc)
                        : "";
                head.setContentDescription(c.getString(org.llm4people.hermes.R.string.ws_table_column_a11y,
                        col.label, state));
            }
            headerRow.addView(head, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        tableBody.addView(headerRow);
        tableBody.addView(themedDivider(c, colors));

        List<List<String>> rows = model.visibleRows(AppLanguage.resolvedLocale(c));
        if (rows.isEmpty()) {
            TextView empty = Ui.text(c, c.getString(org.llm4people.hermes.R.string.ws_table_empty), colors.scaledSp(14));
            empty.setTextColor(colors.muted);
            Ui.pad(empty, 12, 10);
            tableBody.addView(empty);
            return;
        }
        for (int r = 0; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            LinearLayout rowView = Ui.row(c);
            rowView.setMinimumHeight(Ui.dp(c, 48));
            for (int colIdx = 0; colIdx < model.columns.size(); colIdx++) {
                Column col = model.columns.get(colIdx);
                TextView cell = Ui.text(c, row.get(colIdx), colors.scaledSp(15));
                cell.setTextColor(colors.ink);
                if (col.numeric) {
                    cell.setTypeface(Typeface.MONOSPACE);
                    cell.setFontFeatureSettings("tnum");
                    cell.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
                }
                cell.setMinWidth(colWidth);
                Ui.pad(cell, 10, 8);
                rowView.addView(cell, new LinearLayout.LayoutParams(0, -2, 1f));
            }
            if (host != null && !model.draftPrompt.isEmpty()) {
                rowView.setBackground(Ui.roundedRipple(c, 0, null));
                rowView.setClickable(true);
                rowView.setFocusable(true);
                rowView.setOnClickListener(v -> host.stageDraft(model.draftForRow(row)));
                rowView.setContentDescription(c.getString(org.llm4people.hermes.R.string.ws_table_row_draft_a11y,
                        String.join(", ", row)));
            }
            tableBody.addView(rowView);
            if (r + 1 < rows.size()) tableBody.addView(themedDivider(c, colors));
        }
    }

    private static View themedDivider(Context c, ThemePalette colors) {
        View divider = Ui.divider(c);
        divider.setBackgroundColor(colors.line);
        return divider;
    }
}
