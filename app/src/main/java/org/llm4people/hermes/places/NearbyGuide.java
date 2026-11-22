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
                        int selected, boolean details, boolean all) {}
    public interface Actions {
        void locate();
        void manual();
        void select(int index, boolean toggleDetails);
        void list(boolean all);
        void map(View clicked, NearbyResult.Place place);
        void source(View clicked, NearbyResult.Place place);
    }

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'UTC'", Locale.FRANCE)
            .withZone(ZoneOffset.UTC);
    private final Actions actions;
    private State state;
    private boolean wide;

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
        View focused = findFocus();
        Object focusKey = focused == null ? null : focused.getTag();
        state = next;
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
        addView(text(count + (count == 1 ? " adresse" : " adresses") + " · " + scope, 15, R.color.muted));
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
        PlaceCompass compass = new PlaceCompass(getContext(), result, s.latitude, s.longitude,
                s.selected, index -> actions.select(index, false));
        compass.setTag("places-compass");
        overview.addView(compass, new LayoutParams(-1, dp(wide ? 185 : 136)));
        TextView caption = text("À vol d’oiseau depuis le centre · nord en haut", 13, R.color.muted);
        Ui.pad(caption, 0, 3);
        overview.addView(caption);
        List<NearbyResult.Place> places = result.places;
        for (int i = 0; i < places.size(); i++) {
            if (s.all || i < 2 || i == s.selected) venue(rows, places.get(i), i, s);
        }
        if (places.size() > 2) rows.addView(action(s.all ? "Réduire la liste" : "Afficher les " + count + " adresses",
                "places-list", clicked -> actions.list(!s.all)));
        provenance(s);
    }

    private void venue(LinearLayout rows, NearbyResult.Place place, int index, State s) {
        boolean selected = index == s.selected, expanded = selected && s.details;
        LinearLayout group = Ui.column(getContext());
        if (selected) {
            GradientDrawable tint = new GradientDrawable();
            tint.setColor(getContext().getColor(R.color.accent_surface));
            tint.setCornerRadius(dp(7));
            group.setBackground(tint);
        }
        Ui.pad(group, 8, 2);
        LinearLayout top = Ui.row(getContext());
        top.setBaselineAligned(false);
        top.setMinimumHeight(dp(48));
        top.setTag("places-row-" + place.id);
        top.setFocusable(true);
        top.setSelected(selected);
        android.util.TypedValue appearance = new android.util.TypedValue();
        getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, appearance, true);
        top.setForeground(getContext().getDrawable(appearance.resourceId));
        TextView name = text(place.name, selected ? 18 : 16, R.color.ink);
        name.setTypeface(Typeface.create(Typeface.DEFAULT, selected ? 650 : 500, false));
        TextView dist = text(distance(place.distanceMeters), 15, R.color.accent);
        dist.setPaddingRelative(dp(8), 0, 0, 0);
        dist.setTypeface(Typeface.create(Typeface.DEFAULT, 600, false));
        top.addView(name, new LayoutParams(0,-2,1));
        top.addView(dist);
        String description = place.name + ", " + distance(place.distanceMeters) + " du centre. "
                + (expanded ? "Replier les détails" : "Afficher les détails");
        top.setContentDescription(description);
        top.setAccessibilityDelegate(new AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View view, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(view, info);
                info.setClassName(Button.class.getName());
            }
        });
        top.setOnClickListener(v -> actions.select(index, true));
        group.addView(top);
        if (expanded) {
            if (!place.cuisine.isEmpty()) group.addView(text(place.cuisine, 15, R.color.ink));
            if (!place.address.isEmpty()) group.addView(text(place.address, 15, R.color.ink));
            LinearLayout links = new LinearLayout(getContext());
            // Text buttons stack when font scaling leaves insufficient width.
            boolean row = getResources().getConfiguration().fontScale <= 1.2f;
            links.setOrientation(row ? HORIZONTAL : VERTICAL);
            Button map = action("Voir sur la carte", "map-" + place.id, v -> actions.map(v, place));
            Button source = action("Fiche OSM", "source-" + place.id, v -> actions.source(v, place));
            source.setContentDescription("Fiche OpenStreetMap de " + place.name);
            links.addView(map, row ? new LayoutParams(0, -2, 1.1f) : new LayoutParams(-1, -2));
            links.addView(source, row ? new LayoutParams(0, -2, 1) : new LayoutParams(-1, -2));
            group.addView(links);
        }
        rows.addView(group);
        if (!selected) rows.addView(Ui.divider(getContext()));
    }

    private void provenance(State s) {
        String precision = "Zone arrondie à 0,01°";
        if (Double.isFinite(s.accuracy) && s.accuracy > 0) precision += " · précision annoncée ± " + distance((int)Math.ceil(s.accuracy));
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
