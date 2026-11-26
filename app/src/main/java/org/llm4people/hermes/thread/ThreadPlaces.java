package org.llm4people.hermes.thread;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import org.json.JSONObject;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.PlainTextSanitizer;
import org.llm4people.hermes.artifact.ArtifactDesk;
import org.llm4people.hermes.artifact.DeviceReview;
import org.llm4people.hermes.artifact.ThreadInstruments.Pin;
import org.llm4people.hermes.places.NearbyCategory;
import org.llm4people.hermes.places.NearbyGuide;
import org.llm4people.hermes.places.NearbyResult;
import org.llm4people.hermes.places.PlaceSelection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** A transcript proposal owns one ephemeral result set. Merely mounting or scrolling never starts a query. */
final class ThreadPlaces {
    private final ThreadWorkspace thread;
    private final ArtifactDesk.Host host;
    private final Predicate<Pin> valid;
    private final BooleanSupplier owner;
    private final List<Entry> entries = new ArrayList<>();
    private final Set<String> admitted = new HashSet<>();

    private static final class Entry {
        final Pin pin;
        final WeakHashMap<NearbyGuide, Boolean> views = new WeakHashMap<>();
        NearbyGuide.Phase phase = NearbyGuide.Phase.OFFER;
        NearbyCategory category;
        int radiusMeters;
        int limit;
        JSONObject lastArea;
        NearbyResult result;
        double lat, lon, accuracy;
        PlaceSelection selection;
        volatile boolean retired;
        long requestSerial;

        Entry(Pin pin) {
            this.pin = pin;
            NearbyCategory initial = NearbyCategory.fromId(pin.nearbyCategory);
            this.category = initial == null ? NearbyCategory.RESTAURANT : initial;
            this.radiusMeters = NearbyCategory.validRadius(pin.nearbyRadiusMeters)
                    ? pin.nearbyRadiusMeters : NearbyCategory.DEFAULT_RADIUS_M;
            this.limit = NearbyCategory.validLimit(pin.nearbyLimit)
                    ? pin.nearbyLimit : NearbyCategory.DEFAULT_LIMIT;
        }

        void update() {
            NearbyGuide.State state = new NearbyGuide.State(
                    phase, result, lat, lon, accuracy, selection, category, radiusMeters);
            for (NearbyGuide view : new ArrayList<>(views.keySet())) if (view != null) view.bind(state);
        }

        void retire() {
            retired = true;
            ++requestSerial;
            result = null;
            selection = null;
            lastArea = null;
            lat = lon = accuracy = 0;
            phase = NearbyGuide.Phase.OFFER;
            update();
            views.clear();
        }
    }

    ThreadPlaces(ThreadWorkspace thread, ArtifactDesk.Host host, Predicate<Pin> valid) {
        this.thread = thread;
        this.host = host;
        this.valid = valid;
        this.owner = thread.discussionOrigin();
    }

    private Entry entry(Pin pin) {
        for (Entry e : entries) if (pin.sameSource(e.pin)) return e;
        Entry next = new Entry(pin);
        entries.add(next);
        return next;
    }

    void reconcile() {
        entries.removeIf(e -> {
            if (valid.test(e.pin)) return false;
            e.retire();
            return true;
        });
    }

    void retire() {
        for (Entry e : entries) e.retire();
        entries.clear();
        admitted.clear();
    }

    void admitted(String runId, Pin pin, JSONObject approvedArea) {
        if (!ThreadData.validId(runId) || !owner.getAsBoolean() || !valid.test(pin) || !admitted.add(runId)) return;
        Entry e = entry(pin);
        if (e.phase == NearbyGuide.Phase.OFFER) load(e, approvedArea);
    }

    NearbyGuide view(Pin pin, BooleanSupplier surface) {
        Entry e = entry(pin);
        NearbyGuide[] mounted = new NearbyGuide[1];
        BooleanSupplier active = () -> owner.getAsBoolean() && surface.getAsBoolean() && !e.retired && valid.test(pin)
                && mounted[0] != null && mounted[0].isAttachedToWindow() && mounted[0].isShown();
        NearbyGuide guide = new NearbyGuide(thread.activity, new NearbyGuide.Actions() {
            public void locate() {
                if (active.getAsBoolean() && e.phase != NearbyGuide.Phase.LOADING)
                    thread.nearby.chooseGuide(active, e.category, area -> { if (active.getAsBoolean()) load(e, area); });
            }

            public void manual() { if (active.getAsBoolean()) neighborhood(active, e.category); }

            public void explore(NearbyCategory nextCategory, int nextRadius) {
                if (!active.getAsBoolean() || nextCategory == null || !NearbyCategory.validRadius(nextRadius)) return;
                if (e.category == nextCategory && e.radiusMeters == nextRadius && e.phase == NearbyGuide.Phase.READY) return;
                e.category = nextCategory;
                e.radiusMeters = nextRadius;
                if (e.lastArea != null) {
                    load(e, e.lastArea);
                } else {
                    e.update();
                }
            }

            public void select(int i, boolean toggle) {
                if (!active.getAsBoolean() || e.result == null || i < 0 || i >= e.result.places.size()) return;
                e.selection.choose(i, toggle);
                e.update();
            }

            public void list(boolean all) { if (active.getAsBoolean()) { e.selection.all(all); e.update(); } }

            public void cuisine() {
                if (!active.getAsBoolean() || e.selection == null) return;
                List<String> terms = new ArrayList<>(e.selection.cuisines);
                terms.add(0, "");
                List<String> labels = new ArrayList<>(terms);
                labels.set(0, thread.activity.getString(R.string.places_all_cuisines));
                choose(thread.activity.getString(R.string.places_choose_cuisine), labels,
                        terms.indexOf(e.selection.cuisine()), active,
                        i -> { e.selection.filter(terms.get(i)); e.update(); });
            }

            public void tagFilter() {
                if (!active.getAsBoolean() || e.selection == null || e.selection.tags.isEmpty()) return;
                List<String> terms = new ArrayList<>(e.selection.tags);
                terms.add(0, "");
                List<String> labels = new ArrayList<>(terms);
                labels.set(0, thread.activity.getString(R.string.places_all_places));
                choose(thread.activity.getString(R.string.places_filter_by_attribute), labels,
                        terms.indexOf(e.selection.tag()), active,
                        i -> { e.selection.filterTag(terms.get(i)); e.update(); });
            }

            public void compare() {
                if (!active.getAsBoolean() || e.selection == null) return;
                List<Integer> ids = new ArrayList<>(e.selection.visible());
                ids.remove(Integer.valueOf(e.selection.selected()));
                if (e.selection.comparison() >= 0) ids.add(0, -1);
                if (ids.isEmpty()) return;
                List<String> labels = new ArrayList<>();
                for (int i : ids) {
                    if (i < 0) labels.add(thread.activity.getString(R.string.places_stop_comparison));
                    else {
                        NearbyResult.Place place = e.result.places.get(i);
                        labels.add(place.name + " · " + NearbyGuide.distance(thread.activity, place.distanceMeters));
                    }
                }
                String name = e.result.places.get(e.selection.selected()).name;
                choose(thread.activity.getString(R.string.places_compare_with, name), labels,
                        ids.indexOf(e.selection.comparison()), active,
                        i -> { e.selection.compare(ids.get(i)); e.update(); });
            }

            public void map(View clicked, NearbyResult.Place place) {
                if (selected(place)) DeviceReview.placeMap(thread.activity, host, clicked, place);
            }

            public void source(View clicked, NearbyResult.Place place) {
                if (selected(place)) DeviceReview.placeSource(thread.activity, host, clicked, place);
            }

            public void route(View clicked, NearbyResult.Place place, NearbyCategory.RouteMode mode) {
                if (!selected(place) || mode == null) return;
                reviewRoute(active, place, mode);
            }

            private boolean selected(NearbyResult.Place place) {
                return active.getAsBoolean() && e.result != null
                        && e.result.places.get(e.selection.selected()) == place && e.selection.details();
            }
        });
        mounted[0] = guide;
        e.views.put(guide, true);
        guide.bind(new NearbyGuide.State(e.phase, e.result, e.lat, e.lon, e.accuracy, e.selection, e.category, e.radiusMeters));
        return guide;
    }

    private void load(Entry e, JSONObject area) {
        if (!NearbyTurn.validDevice(area) || !"device".equals(area.opt("source")) || e.retired) return;
        e.lastArea = area;
        e.lat = area.optDouble("latitude");
        e.lon = area.optDouble("longitude");
        e.accuracy = area.opt("accuracy_m") instanceof Number n ? n.doubleValue() : Double.NaN;
        e.phase = NearbyGuide.Phase.LOADING;
        e.result = null;
        long serial = ++e.requestSerial;
        NearbyCategory category = e.category;
        int radius = e.radiusMeters;
        int limit = e.limit;
        e.update();
        JSONObject query = ThreadData.put(ThreadData.put(new JSONObject(), "latitude", e.lat), "longitude", e.lon);
        ThreadData.put(query, "category", category.id);
        ThreadData.put(query, "radius_m", radius);
        ThreadData.put(query, "limit", limit);
        thread.request(() -> {
            if (!owner.getAsBoolean() || e.retired || e.requestSerial != serial) throw new CancellationException();
            return NearbyResult.parse(thread.activity, thread.api.post("/api/mobile/places/nearby", query));
        }, result -> {
            if (e.retired || e.requestSerial != serial || !valid.test(e.pin)) return;
            e.result = result;
            e.phase = NearbyGuide.Phase.READY;
            e.selection = new PlaceSelection(result);
            e.update();
        }, failure -> {
            if (e.retired || e.requestSerial != serial || !valid.test(e.pin)) return;
            Diagnostics.warning("places", "nearby_lookup_failed");
            e.phase = NearbyGuide.Phase.UNAVAILABLE;
            e.update();
        });
    }

    private void reviewRoute(BooleanSupplier active, NearbyResult.Place place, NearbyCategory.RouteMode mode) {
        String preview = thread.activity.getString(R.string.places_route_preview,
                place.name, mode.chipLabel(thread.activity), place.coordinates());
        thread.screen.show(new AlertDialog.Builder(thread.activity)
                .setTitle(mode.title(thread.activity))
                .setMessage(preview)
                .setNegativeButton(R.string.ws_cancel, null)
                .setPositiveButton(mode.openLabel(thread.activity), (dialog, which) -> {
                    if (!active.getAsBoolean()) return;
                    Intent nav = new Intent(Intent.ACTION_VIEW, Uri.parse(mode.navigationUri(place.latitude, place.longitude)));
                    try {
                        thread.activity.startActivity(nav);
                    } catch (ActivityNotFoundException | SecurityException firstFailure) {
                        try {
                            Intent fallback = new Intent(Intent.ACTION_VIEW,
                                    Uri.parse(mode.fallbackGeoUri(place.latitude, place.longitude)));
                            thread.activity.startActivity(fallback);
                        } catch (ActivityNotFoundException | SecurityException secondFailure) {
                            Diagnostics.warning("places", "maps_unavailable");
                            thread.screen.show(new AlertDialog.Builder(thread.activity)
                                    .setTitle(R.string.places_map_unavailable_title)
                                    .setMessage(R.string.places_map_unavailable_body)
                                    .setPositiveButton(R.string.ws_close, null));
                        }
                    }
                }));
    }

    private void choose(String title, List<String> labels, int selected, BooleanSupplier active, java.util.function.IntConsumer action) {
        thread.screen.show(new AlertDialog.Builder(thread.activity).setTitle(title)
                .setSingleChoiceItems(labels.toArray(new String[0]), selected, (dialog, index) -> {
                    if (active.getAsBoolean()) action.accept(index);
                    dialog.dismiss();
                }).setNegativeButton(R.string.ws_cancel, null));
    }

    private void neighborhood(BooleanSupplier active, NearbyCategory category) {
        EditText field = new EditText(thread.activity);
        Ui.styleDialogInput(thread.activity, field);
        field.setSingleLine(true);
        field.setSaveEnabled(false);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        field.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setHint(R.string.places_neighborhood_hint);
        android.widget.LinearLayout form = Ui.column(thread.activity);
        Ui.pad(form, 20, 4);
        form.addView(field);
        AlertDialog dialog = thread.screen.show(new AlertDialog.Builder(thread.activity)
                .setTitle(R.string.places_guide_specify_neighborhood)
                .setView(form).setNegativeButton(R.string.ws_cancel, null)
                .setPositiveButton(R.string.places_prepare_draft, null));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!active.getAsBoolean() || !dialog.isShowing()) { dialog.dismiss(); return; }
            String area = field.getText().toString();
            if (!PlainTextSanitizer.hasVisibleText(area)) {
                field.setError(thread.activity.getString(R.string.places_enter_neighborhood));
                return;
            }
            dialog.dismiss();
            NearbyCategory activeCat = category == null ? NearbyCategory.RESTAURANT : category;
            host.stageDraft(thread.activity.getString(R.string.places_search_draft,
                    activeCat.draftPlural(thread.activity), area));
        });
    }
}
