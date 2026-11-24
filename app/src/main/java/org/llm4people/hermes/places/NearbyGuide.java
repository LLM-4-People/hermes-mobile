package org.llm4people.hermes.places;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.llm4people.hermes.AdaptiveLayout;
import org.llm4people.hermes.AppLanguage;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Inline, read-only guide to returned map records. Selecting a record never contacts the gateway. */
public final class NearbyGuide extends LinearLayout {
    public enum Phase { OFFER, LOADING, READY, UNAVAILABLE }

    public record State(Phase phase, NearbyResult result, double latitude, double longitude, double accuracy,
                        PlaceSelection selection, NearbyCategory category, int radiusMeters) {
        public State(Phase phase, NearbyResult result, double latitude, double longitude, double accuracy,
                     PlaceSelection selection) {
            this(phase, result, latitude, longitude, accuracy, selection,
                    result != null && result.category != null ? result.category : NearbyCategory.RESTAURANT,
                    result != null ? result.radiusMeters : NearbyCategory.DEFAULT_RADIUS_M);
        }

        public NearbyCategory activeCategory() {
            if (category != null) return category;
            return result != null && result.category != null ? result.category : NearbyCategory.RESTAURANT;
        }

        public int activeRadius() {
            if (NearbyCategory.validRadius(radiusMeters)) return radiusMeters;
            return result != null ? result.radiusMeters : NearbyCategory.DEFAULT_RADIUS_M;
        }
    }

    public interface Actions {
        void locate();
        void manual();
        void select(int index, boolean toggleDetails);
        void list(boolean all);
        void cuisine();
        void compare();
        void map(View clicked, NearbyResult.Place place);
        void source(View clicked, NearbyResult.Place place);
        default void explore(NearbyCategory category, int radiusMeters) {}
        default void tagFilter() {}
        default void route(View clicked, NearbyResult.Place place, NearbyCategory.RouteMode mode) {}
    }

    private DateTimeFormatter dateFormatter() {
        Locale locale = AppLanguage.resolvedLocale(getContext());
        return DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'UTC'", locale)
                .withZone(ZoneOffset.UTC);
    }
    private final Actions actions;
    private State state;
    private boolean wide, renderedWide;
    private PlaceCompass chart;
    private Button more, cuisineButton, tagButton, compareButton, radiusButton;
    private TextView range, primaryFact, comparisonFact, difference;
    private LinearLayout comparisonFacts;
    private final java.util.ArrayList<Venue> venues = new java.util.ArrayList<>();

    public NearbyGuide(Context context, Actions actions) {
        super(context);
        this.actions = actions;
        setOrientation(VERTICAL);
        setSaveFromParentEnabled(false);
        Ui.pad(this, 2, 5);
    }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        float density = getResources().getDisplayMetrics().density;
        boolean split = AdaptiveLayout.splitNearbyGuide(w / density);
        if (wide != split) {
            wide = split;
            post(() -> { if (isAttachedToWindow() && state != null) bind(state); });
        }
    }

    public void bind(State next) {
        boolean sameResult = state != null && state.phase == Phase.READY && next.phase == Phase.READY
                && next.result == state.result && chart != null && wide == renderedWide
                && next.activeCategory() == state.activeCategory() && next.activeRadius() == state.activeRadius();
        state = next;
        if (sameResult) {
            updateVenues();
            return;
        }
        renderedWide = wide;
        chart = null;
        more = null;
        cuisineButton = null;
        tagButton = null;
        compareButton = null;
        radiusButton = null;
        venues.clear();
        View focused = findFocus();
        Object focusKey = focused == null ? null : focused.getTag();
        removeAllViews();
        addView(Ui.divider(getContext()));
        NearbyCategory category = next.activeCategory();
        TextView heading = text(category.heading(getContext()), 24, R.color.ink);
        heading.setTypeface(Typeface.create(Typeface.DEFAULT, 650, false));
        heading.setLetterSpacing(-.025f);
        heading.setAccessibilityHeading(true);
        heading.setPaddingRelative(0, dp(10), 0, dp(3));
        addView(heading);
        addView(categoryBar(category, next.activeRadius()));
        if (next.phase == Phase.READY) showResult(next);
        else {
            String message = switch (next.phase) {
                case OFFER -> getContext().getString(R.string.places_guide_choose_area);
                case LOADING -> category.loadingMessage(getContext());
                default -> category.unavailableMessage(getContext());
            };
            TextView note = text(message, 16, R.color.muted);
            note.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            addView(note);
            if (next.phase != Phase.LOADING) alternatives();
        }
        if (focusKey != null) {
            View replacement = findViewWithTag(focusKey);
            if (replacement != null) replacement.requestFocus();
        }
    }

    private View categoryBar(NearbyCategory active, int activeRadius) {
        LinearLayout wrapper = Ui.column(getContext());
        HorizontalScrollView scroll = new HorizontalScrollView(getContext());
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = Ui.row(getContext());
        Ui.pad(chips, 0, 4);
        for (NearbyCategory cat : NearbyCategory.values()) {
            boolean selected = cat == active;
            String catLabel = cat.label(getContext());
            Button chip = chipButton(catLabel, selected, "places-cat-" + cat.id,
                    v -> actions.explore(cat, activeRadius));
            chip.setContentDescription(getContext().getString(
                    selected ? R.string.places_guide_cat_selected_desc : R.string.places_guide_cat_desc, catLabel));
            LayoutParams lp = new LayoutParams(-2, -2);
            lp.setMarginEnd(dp(8));
            chips.addView(chip, lp);
        }
        scroll.addView(chips);
        wrapper.addView(scroll);

        LinearLayout radiusRow = Ui.row(getContext());
        String distText = distance(getContext(), activeRadius);
        radiusButton = action(getContext().getString(R.string.places_guide_radius_chip, distText),
                "places-radius", v -> cycleRadius(active, activeRadius));
        radiusButton.setContentDescription(getContext().getString(R.string.places_guide_radius_desc, distText));
        radiusRow.addView(radiusButton);
        wrapper.addView(radiusRow);
        return wrapper;
    }

    private void cycleRadius(NearbyCategory active, int currentRadius) {
        List<Integer> options = NearbyCategory.RADIUS_CHOICES;
        int idx = options.indexOf(currentRadius);
        int next = options.get((idx + 1) % options.size());
        actions.explore(active, next);
    }

    private Button chipButton(String label, boolean selected, String tag, OnClickListener click) {
        Button b = Ui.button(getContext(), label, click);
        b.setTag(tag);
        b.setTextSize(14);
        b.setMinHeight(dp(48));
        b.setMinimumHeight(dp(48));
        Ui.pad(b, 12, 6);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(Ui.BUTTON_CORNER_DP));
        if (selected) {
            bg.setColor(getContext().getColor(R.color.accent));
            b.setTextColor(getContext().getColor(R.color.on_accent));
        } else {
            bg.setColor(getContext().getColor(R.color.surface));
            bg.setStroke(Math.max(1, dp(1)), getContext().getColor(R.color.line));
            b.setTextColor(getContext().getColor(R.color.ink));
        }
        b.setBackground(Ui.roundedRipple(getContext(), Ui.BUTTON_CORNER_DP, bg));
        b.setSelected(selected);
        return b;
    }

    private void showResult(State s) {
        NearbyResult result = s.result;
        NearbyCategory category = s.activeCategory();
        int count = result.places.size();
        String scope = getContext().getString(R.string.places_guide_radius_from_center,
                distance(getContext(), result.radiusMeters));
        if (count == 0) {
            TextView empty = text(category.emptyMessage(getContext()), 17, R.color.ink);
            empty.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            addView(empty);
            addView(text(scope, 14, R.color.muted));
            provenance(this, s);
            alternatives();
            return;
        }
        range = text("", 15, R.color.muted);
        addView(range);
        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(wide ? HORIZONTAL : VERTICAL);
        layout.setBaselineAligned(false);
        layout.setPaddingRelative(0, dp(8), 0, 0);
        addView(layout);
        LinearLayout overview = Ui.column(getContext());
        LinearLayout rows = Ui.column(getContext());
        if (wide) {
            LayoutParams left = new LayoutParams(0, -2, 1);
            left.setMarginEnd(dp(18));
            layout.addView(overview, left);
            layout.addView(rows, new LayoutParams(0, -2, 1.15f));
        } else {
            layout.addView(overview);
            layout.addView(rows);
        }
        chart = new PlaceCompass(getContext(), result, s.latitude, s.longitude,
                s.selection.selected(), index -> actions.select(index, false));
        chart.setTag("places-compass");
        overview.addView(chart, new LayoutParams(-1, dp(wide ? 185 : 136)));
        TextView caption = text(getContext().getString(R.string.places_guide_straight_line_note), 13, R.color.muted);
        Ui.pad(caption, 0, 3);
        overview.addView(caption);
        provenance(overview, s);
        LinearLayout filters = new LinearLayout(getContext());
        boolean inline = getResources().getConfiguration().fontScale <= 1.2f;
        filters.setOrientation(inline ? HORIZONTAL : VERTICAL);
        cuisineButton = action(getContext().getString(R.string.places_guide_cuisine), "places-cuisine", v -> actions.cuisine());
        tagButton = action(getContext().getString(R.string.places_guide_filters), "places-tags", v -> actions.tagFilter());
        compareButton = action(getContext().getString(R.string.places_guide_compare), "places-compare", v -> actions.compare());
        filters.addView(cuisineButton, inline ? new LayoutParams(0, -2, 1) : new LayoutParams(-1, -2));
        filters.addView(tagButton, inline ? new LayoutParams(0, -2, 1) : new LayoutParams(-1, -2));
        filters.addView(compareButton, inline ? new LayoutParams(0, -2, 1) : new LayoutParams(-1, -2));
        rows.addView(filters);
        comparisonFacts = Ui.column(getContext());
        Ui.pad(comparisonFacts, 4, 4);
        primaryFact = text("", 16, R.color.accent);
        comparisonFact = text("", 16, R.color.places_compare_ink);
        Typeface emphasis = Typeface.create(Typeface.DEFAULT, 600, false);
        primaryFact.setTypeface(emphasis);
        comparisonFact.setTypeface(emphasis);
        difference = text("", 14, R.color.muted);
        comparisonFacts.addView(primaryFact);
        comparisonFacts.addView(comparisonFact);
        comparisonFacts.addView(difference);
        rows.addView(comparisonFacts);
        List<NearbyResult.Place> places = result.places;
        for (int i = 0; i < places.size(); i++) venues.add(new Venue(rows, places.get(i), i));
        if (places.size() > 2) {
            more = action("", "places-list", clicked -> actions.list(!state.selection.all()));
            rows.addView(more);
        }
        updateVenues();
    }

    private void updateVenues() {
        PlaceSelection choice = state.selection;
        chart.setSelection(choice.selected(), choice.comparison(), choice.visible());
        int count = choice.visible().size();
        String number = getResources().getQuantityString(R.plurals.places_guide_count, count, count);
        if (count != venues.size()) {
            number = getContext().getString(R.string.places_guide_count_of, number, venues.size());
        }
        range.setText(getContext().getString(R.string.places_guide_summary_radius,
                number, distance(getContext(), state.result.radiusMeters)));
        cuisineButton.setVisibility(choice.cuisines.isEmpty() ? GONE : VISIBLE);
        cuisineButton.setText(choice.cuisine().isEmpty()
                ? getContext().getString(R.string.places_guide_cuisine)
                : getContext().getString(R.string.places_guide_cuisine_selected, choice.cuisine()));
        cuisineButton.setContentDescription(getContext().getString(R.string.places_guide_cuisine_desc,
                choice.cuisine().isEmpty()
                        ? getContext().getString(R.string.places_guide_all_cuisines_lower)
                        : choice.cuisine()));
        tagButton.setVisibility(choice.tags.isEmpty() ? GONE : VISIBLE);
        tagButton.setText(choice.tag().isEmpty()
                ? getContext().getString(R.string.places_guide_filters)
                : getContext().getString(R.string.places_guide_filter_selected, choice.tag()));
        tagButton.setContentDescription(getContext().getString(R.string.places_guide_filter_desc,
                choice.tag().isEmpty()
                        ? getContext().getString(R.string.places_guide_all_places_lower)
                        : choice.tag()));
        compareButton.setVisibility(count < 2 ? GONE : VISIBLE);
        compareButton.setText(getContext().getString(
                choice.comparison() < 0 ? R.string.places_guide_compare : R.string.places_guide_comparison));
        compareButton.setContentDescription(getContext().getString(
                choice.comparison() < 0 ? R.string.places_guide_compare_desc : R.string.places_guide_compare_edit_desc));
        comparisonFacts.setVisibility(choice.comparison() >= 0 ? VISIBLE : GONE);
        if (choice.comparison() >= 0) {
            NearbyResult.Place a = state.result.places.get(choice.selected());
            NearbyResult.Place b = state.result.places.get(choice.comparison());
            primaryFact.setText(a.name + " · " + distance(getContext(), a.distanceMeters));
            comparisonFact.setText(b.name + " · " + distance(getContext(), b.distanceMeters));
            difference.setText(getContext().getString(R.string.places_guide_distance_gap,
                    distance(getContext(), Math.abs(a.distanceMeters - b.distanceMeters))));
        }
        int visibleRank = 0;
        for (Venue row : venues) {
            boolean inFilter = choice.visible().contains(row.index);
            row.update(inFilter, visibleRank);
            if (inFilter) visibleRank++;
        }
        if (more != null) {
            more.setVisibility(count > 2 ? VISIBLE : GONE);
            more.setText(choice.all()
                    ? getContext().getString(R.string.places_guide_collapse_list)
                    : getContext().getString(R.string.places_guide_show_all_places, count));
        }
    }

    private final class Venue {
        final int index;
        final NearbyResult.Place place;
        final LinearLayout group, top, detail;
        final TextView marker, name, pairNote;
        final View divider;
        final GradientDrawable tint = new GradientDrawable();

        Venue(LinearLayout rows, NearbyResult.Place place, int index) {
            this.index = index;
            this.place = place;
            group = Ui.column(getContext());
            tint.setColor(getContext().getColor(R.color.accent_surface));
            tint.setCornerRadius(dp(7));
            Ui.pad(group, 8, 2);
            top = Ui.row(getContext());
            top.setBaselineAligned(false);
            top.setMinimumHeight(dp(48));
            top.setTag("places-row-" + place.id);
            top.setFocusable(true);
            android.util.TypedValue appearance = new android.util.TypedValue();
            getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, appearance, true);
            top.setForeground(getContext().getDrawable(appearance.resourceId));
            marker = text(Integer.toString(index + 1), 14, R.color.muted);
            marker.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            marker.setFontFeatureSettings("tnum");
            marker.setMinWidth(dp(24));
            marker.setPaddingRelative(0, 0, dp(6), 0);
            marker.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            top.addView(marker);
            name = text(place.name, 16, R.color.ink);
            TextView dist = text(distance(getContext(), place.distanceMeters), 15, R.color.accent);
            dist.setPaddingRelative(dp(8), 0, 0, 0);
            dist.setTypeface(Typeface.create(Typeface.DEFAULT, 600, false));
            top.addView(name, new LayoutParams(0, -2, 1));
            top.addView(dist);
            top.setAccessibilityDelegate(new AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View view, android.view.accessibility.AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(view, info);
                    info.setClassName(Button.class.getName());
                }
            });
            top.setOnClickListener(v -> actions.select(index, true));
            group.addView(top);
            pairNote = text("", 14, R.color.ink);
            Ui.pad(pairNote, 0, 3);
            group.addView(pairNote);
            detail = Ui.column(getContext());
            group.addView(detail);
            if (!place.cuisine.isEmpty()) detail.addView(text(place.cuisine, 15, R.color.ink));
            if (!place.tags.isEmpty()) detail.addView(text(String.join(" · ", place.tags), 14, R.color.muted));
            if (!place.address.isEmpty()) detail.addView(text(place.address, 15, R.color.ink));
            if (!place.openingHours.isEmpty()) {
                detail.addView(text(getContext().getString(R.string.places_guide_hours, place.openingHours), 14, R.color.muted));
            }
            boolean row = getResources().getConfiguration().fontScale <= 1.2f;
            LinearLayout links = new LinearLayout(getContext());
            links.setOrientation(row ? HORIZONTAL : VERTICAL);
            Button map = action(getContext().getString(R.string.places_guide_view_map), "map-" + place.id, v -> actions.map(v, place));
            Button source = action(getContext().getString(R.string.places_guide_osm_card), "source-" + place.id, v -> actions.source(v, place));
            source.setContentDescription(getContext().getString(R.string.places_guide_osm_card_desc, place.name));
            links.addView(map, row ? new LayoutParams(0, -2, 1.1f) : new LayoutParams(-1, -2));
            links.addView(source, row ? new LayoutParams(0, -2, 1) : new LayoutParams(-1, -2));
            detail.addView(links);
            LinearLayout routes = new LinearLayout(getContext());
            routes.setOrientation(row ? HORIZONTAL : VERTICAL);
            Button walk = action(NearbyCategory.RouteMode.WALKING.title(getContext()), "walk-" + place.id,
                    v -> actions.route(v, place, NearbyCategory.RouteMode.WALKING));
            Button drive = action(NearbyCategory.RouteMode.DRIVING.title(getContext()), "drive-" + place.id,
                    v -> actions.route(v, place, NearbyCategory.RouteMode.DRIVING));
            routes.addView(walk, row ? new LayoutParams(0, -2, 1) : new LayoutParams(-1, -2));
            routes.addView(drive, row ? new LayoutParams(0, -2, 1) : new LayoutParams(-1, -2));
            detail.addView(routes);
            rows.addView(group);
            divider = Ui.divider(getContext());
            rows.addView(divider);
        }

        void update(boolean inFilter, int rank) {
            PlaceSelection choice = state.selection;
            boolean selected = index == choice.selected(), expanded = selected && choice.details();
            boolean paired = index == choice.comparison();
            boolean visible = inFilter && (choice.all() || rank < 2 || selected || paired);
            group.setVisibility(visible ? VISIBLE : GONE);
            group.setBackground(selected ? tint : null);
            pairNote.setVisibility(paired ? VISIBLE : GONE);
            if (paired) {
                String note = getContext().getString(R.string.places_guide_in_comparison);
                if (!place.cuisine.isEmpty()) note += " · " + place.cuisine;
                pairNote.setText(note);
            }
            divider.setVisibility(visible && !selected ? VISIBLE : GONE);
            top.setSelected(selected);
            marker.setTextColor(getContext().getColor(selected ? R.color.accent
                    : paired ? R.color.places_compare_ink : R.color.muted));
            String toggleLabel = getContext().getString(
                    expanded ? R.string.places_guide_collapse_details : R.string.places_guide_expand_details);
            top.setContentDescription(getContext().getString(
                    paired ? R.string.places_guide_place_row_compared_desc : R.string.places_guide_place_row_desc,
                    index + 1, place.name, distance(getContext(), place.distanceMeters), toggleLabel));
            name.setTextSize(selected ? 18 : 16);
            name.setTypeface(Typeface.create(Typeface.DEFAULT, selected ? 650 : 500, false));
            detail.setVisibility(expanded ? VISIBLE : GONE);
        }
    }

    private void provenance(LinearLayout parent, State s) {
        String precision = getContext().getString(R.string.places_guide_approx_center);
        if (Double.isFinite(s.accuracy) && s.accuracy > 0) {
            precision += " · " + getContext().getString(R.string.places_guide_uncertainty,
                    distance(getContext(), (int) Math.ceil(s.accuracy)));
        }
        TextView note = text(precision + "\n" + s.result.attribution + " · "
                + getContext().getString(R.string.places_guide_source_date,
                        dateFormatter().format(Instant.parse(s.result.sourceDate))), 13, R.color.muted);
        note.setPaddingRelative(0, dp(6), 0, dp(4));
        parent.addView(note);
    }

    private void alternatives() {
        addView(action(getContext().getString(R.string.places_guide_use_approx_area), "places-locate", v -> actions.locate()));
        addView(action(getContext().getString(R.string.places_guide_specify_neighborhood), "places-manual", v -> actions.manual()));
    }

    public static String distance(Context context, int meters) {
        Locale locale = context == null
                ? Locale.getDefault()
                : AppLanguage.resolvedLocale(context);
        if (meters < 1000) {
            String value = Integer.toString(meters);
            return context == null ? value + " m" : context.getString(R.string.places_distance_meters, value);
        }
        NumberFormat format = NumberFormat.getNumberInstance(locale);
        format.setMaximumFractionDigits(1);
        String formatted = format.format(meters / 1000d);
        return context == null ? formatted + " km" : context.getString(R.string.places_distance_km, formatted);
    }

    public static String distance(int meters) {
        return distance(null, meters);
    }

    private Button action(String label, String tag, OnClickListener click) {
        Button b = Ui.button(getContext(), label, click);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setSingleLine(false);
        b.setTag(tag);
        Ui.pad(b, 4, 4);
        return b;
    }

    private TextView text(String label, int sp, int color) {
        TextView t = Ui.text(getContext(), label, sp);
        t.setTextColor(getContext().getColor(color));
        return t;
    }

    private int dp(int value) { return Ui.dp(getContext(), value); }
}
