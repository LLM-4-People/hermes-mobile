package org.llm4people.hermes.places;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
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
                        PlaceSelection selection) {}
    public interface Actions {
        void locate();
        void manual();
        void select(int index, boolean toggleDetails);
        void list(boolean all);
        void cuisine();
        void compare();
        void map(View clicked, NearbyResult.Place place);
        void source(View clicked, NearbyResult.Place place);
    }

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'UTC'", Locale.FRANCE)
            .withZone(ZoneOffset.UTC);
    private final Actions actions;
    private State state;
    private boolean wide, renderedWide;
    private PlaceCompass chart;
    private Button more, cuisineButton, compareButton;
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
        boolean split = w >= dp(620);
        if (wide != split) {
            wide = split;
            post(() -> { if (isAttachedToWindow() && state != null) bind(state); });
        }
    }

    public void bind(State next) {
        boolean sameResult = state != null && state.phase == Phase.READY && next.phase == Phase.READY
                && next.result == state.result && chart != null && wide == renderedWide;
        state = next;
        if (sameResult) {
            updateVenues();
            return;
        }
        renderedWide = wide;
        chart = null;
        more = null;
        cuisineButton = null;
        compareButton = null;
        venues.clear();
        View focused = findFocus();
        Object focusKey = focused == null ? null : focused.getTag();
        removeAllViews();
        addView(Ui.divider(getContext()));
        TextView heading = text("Restaurants dans la zone", 24, R.color.ink);
        heading.setTypeface(Typeface.create(Typeface.DEFAULT, 650, false));
        heading.setLetterSpacing(-.025f);
        heading.setAccessibilityHeading(true);
        heading.setPaddingRelative(0, dp(10), 0, dp(3));
        addView(heading);
        if (next.phase == Phase.READY) showResult(next);
        else {
            String message = switch (next.phase) {
                case OFFER -> "Choisissez la zone où chercher avec OpenStreetMap.";
                case LOADING -> "Recherche des restaurants dans la zone partagée…";
                default -> "Le guide des restaurants est indisponible. Vous pouvez choisir à nouveau une zone.";
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

    private void showResult(State s) {
        NearbyResult result = s.result;
        int count = result.places.size();
        String scope = "Rayon de " + distance(result.radiusMeters) + " depuis le centre de la zone";
        if (count == 0) {
            TextView empty = text("Aucun restaurant répertorié dans ce rayon.", 17, R.color.ink);
            empty.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            addView(empty);
            addView(text(scope, 14, R.color.muted));
            provenance(s);
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
        TextView caption = text("Distances approximatives à vol d’oiseau depuis le centre", 13, R.color.muted);
        Ui.pad(caption, 0, 3);
        overview.addView(caption);
        LinearLayout filters = new LinearLayout(getContext());
        boolean inline = getResources().getConfiguration().fontScale <= 1.2f;
        filters.setOrientation(inline ? HORIZONTAL : VERTICAL);
        cuisineButton = action("Cuisine", "places-cuisine", v -> actions.cuisine());
        compareButton = action("Comparer", "places-compare", v -> actions.compare());
        filters.addView(cuisineButton, inline ? new LayoutParams(0,-2,1) : new LayoutParams(-1,-2));
        filters.addView(compareButton, inline ? new LayoutParams(0,-2,1) : new LayoutParams(-1,-2));
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
        provenance(s);
    }

    private void updateVenues() {
        PlaceSelection choice = state.selection;
        chart.setSelection(choice.selected(), choice.comparison(), choice.visible());
        int count = choice.visible().size();
        String number = count + (count == 1 ? " adresse" : " adresses");
        if (count != venues.size()) number += " sur " + venues.size();
        range.setText(number + " · rayon de " + distance(state.result.radiusMeters));
        cuisineButton.setVisibility(choice.cuisines.isEmpty() ? GONE : VISIBLE);
        cuisineButton.setText(choice.cuisine().isEmpty() ? "Cuisine" : "Cuisine · " + choice.cuisine());
        cuisineButton.setContentDescription("Filtrer par cuisine, " + (choice.cuisine().isEmpty() ? "toutes les cuisines" : choice.cuisine()));
        compareButton.setVisibility(count < 2 ? GONE : VISIBLE);
        compareButton.setText(choice.comparison() < 0 ? "Comparer" : "Comparaison");
        compareButton.setContentDescription(choice.comparison() < 0 ? "Comparer deux restaurants" : "Modifier la comparaison");
        comparisonFacts.setVisibility(choice.comparison() >= 0 ? VISIBLE : GONE);
        if (choice.comparison() >= 0) {
            NearbyResult.Place a = state.result.places.get(choice.selected());
            NearbyResult.Place b = state.result.places.get(choice.comparison());
            primaryFact.setText(a.name + " · " + distance(a.distanceMeters));
            comparisonFact.setText(b.name + " · " + distance(b.distanceMeters));
            difference.setText("Écart des distances au centre : " + distance(Math.abs(a.distanceMeters - b.distanceMeters)));
        }
        int visibleRank = 0;
        for (Venue row : venues) {
            boolean inFilter = choice.visible().contains(row.index);
            row.update(inFilter, visibleRank);
            if (inFilter) visibleRank++;
        }
        if (more != null) {
            more.setVisibility(count > 2 ? VISIBLE : GONE);
            more.setText(choice.all() ? "Réduire la liste" : "Afficher les " + count + " adresses");
        }
    }

    private final class Venue {
        final int index;
        final NearbyResult.Place place;
        final LinearLayout group, top, detail;
        final TextView name, pairNote;
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
            name = text(place.name, 16, R.color.ink);
            TextView dist = text(distance(place.distanceMeters), 15, R.color.accent);
            dist.setPaddingRelative(dp(8), 0, 0, 0);
            dist.setTypeface(Typeface.create(Typeface.DEFAULT, 600, false));
            top.addView(name, new LayoutParams(0,-2,1));
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
            if (!place.address.isEmpty()) detail.addView(text(place.address, 15, R.color.ink));
            LinearLayout links = new LinearLayout(getContext());
            // Text buttons stack when font scaling leaves insufficient width.
            boolean row = getResources().getConfiguration().fontScale <= 1.2f;
            links.setOrientation(row ? HORIZONTAL : VERTICAL);
            Button map = action("Voir sur la carte", "map-" + place.id, v -> actions.map(v, place));
            Button source = action("Fiche OSM", "source-" + place.id, v -> actions.source(v, place));
            source.setContentDescription("Fiche OpenStreetMap de " + place.name);
            links.addView(map, row ? new LayoutParams(0, -2, 1.1f) : new LayoutParams(-1, -2));
            links.addView(source, row ? new LayoutParams(0, -2, 1) : new LayoutParams(-1, -2));
            detail.addView(links);
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
                String note = "En comparaison";
                if (!place.cuisine.isEmpty()) note += " · " + place.cuisine;
                pairNote.setText(note);
            }
            divider.setVisibility(visible && !selected ? VISIBLE : GONE);
            top.setSelected(selected);
            top.setContentDescription(place.name + ", environ " + distance(place.distanceMeters) + " du centre. "
                    + (paired ? "En comparaison. " : "") + (expanded ? "Replier les détails" : "Afficher les détails"));
            name.setTextSize(selected ? 18 : 16);
            name.setTypeface(Typeface.create(Typeface.DEFAULT, selected ? 650 : 500, false));
            detail.setVisibility(expanded ? VISIBLE : GONE);
        }
    }

    private void provenance(State s) {
        String precision = "Centre de la zone approximative";
        if (Double.isFinite(s.accuracy) && s.accuracy > 0) precision += " · incertitude annoncée ± " + distance((int)Math.ceil(s.accuracy));
        TextView note = text(precision + "\n" + s.result.attribution + " · données du "
                + DATE.format(Instant.parse(s.result.sourceDate)), 13, R.color.muted);
        note.setPaddingRelative(0, dp(6), 0, dp(4));
        addView(note);
    }
    private void alternatives() {
        addView(action("Utiliser ma zone approximative", "places-locate", v -> actions.locate()));
        addView(action("Préciser un quartier", "places-manual", v -> actions.manual()));
    }
    static String distance(int meters) {
        if (meters < 1000) return meters + " m";
        NumberFormat format = NumberFormat.getNumberInstance(Locale.FRANCE);
        format.setMaximumFractionDigits(1);
        return format.format(meters / 1000d) + " km";
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
