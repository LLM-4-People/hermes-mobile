package org.llm4people.hermes.thread;

import android.app.AlertDialog;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import org.json.JSONObject;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.VisibleText;
import org.llm4people.hermes.artifact.ArtifactDesk;
import org.llm4people.hermes.artifact.DeviceReview;
import org.llm4people.hermes.artifact.ThreadInstruments.Pin;
import org.llm4people.hermes.places.NearbyGuide;
import org.llm4people.hermes.places.NearbyResult;
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
        final WeakHashMap<NearbyGuide,Boolean> views = new WeakHashMap<>();
        NearbyGuide.Phase phase = NearbyGuide.Phase.OFFER;
        NearbyResult result;
        double lat, lon, accuracy;
        int selected;
        boolean details = true, all;
        volatile boolean retired;
        Entry(Pin pin) { this.pin = pin; }
        void update() {
            NearbyGuide.State state = new NearbyGuide.State(phase, result, lat, lon, accuracy, selected, details, all);
            for (NearbyGuide view : new ArrayList<>(views.keySet())) if (view != null) view.bind(state);
        }
        void retire() {
            retired = true;
            result = null;
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
                    thread.chooseGuideArea(active, area -> { if (active.getAsBoolean()) load(e, area); });
            }
            public void manual() { if (active.getAsBoolean()) neighborhood(active); }
            public void select(int i, boolean toggle) {
                if (!active.getAsBoolean() || e.result == null || i < 0 || i >= e.result.places.size()) return;
                e.details = !(toggle && i == e.selected && e.details);
                e.selected = i;
                e.update();
            }
            public void list(boolean all) { if (active.getAsBoolean()) { e.all = all; e.update(); } }
            public void map(View clicked, NearbyResult.Place place) {
                if (selected(place)) DeviceReview.placeMap(thread.activity, host, clicked, place);
            }
            public void source(View clicked, NearbyResult.Place place) {
                if (selected(place)) DeviceReview.placeSource(thread.activity, host, clicked, place);
            }
            private boolean selected(NearbyResult.Place place) {
                return active.getAsBoolean() && e.result != null && e.result.places.get(e.selected) == place && e.details;
            }
        });
        mounted[0] = guide;
        e.views.put(guide, true);
        guide.bind(new NearbyGuide.State(e.phase, e.result, e.lat, e.lon, e.accuracy, e.selected, e.details, e.all));
        return guide;
    }

    private void load(Entry e, JSONObject area) {
        if (!NearbyTurn.validDevice(area) || !"device".equals(area.opt("source")) || e.retired
                || e.phase == NearbyGuide.Phase.LOADING) return;
        e.lat = area.optDouble("latitude");
        e.lon = area.optDouble("longitude");
        e.accuracy = area.opt("accuracy_m") instanceof Number n ? n.doubleValue() : Double.NaN;
        e.phase = NearbyGuide.Phase.LOADING;
        e.result = null;
        e.update();
        JSONObject query = ThreadData.put(ThreadData.put(new JSONObject(), "latitude", e.lat), "longitude", e.lon);
        thread.request(() -> {
            if (!owner.getAsBoolean() || e.retired) throw new CancellationException();
            return NearbyResult.parse(thread.api.post("/api/mobile/places/nearby", query));
        }, result -> {
            if (e.retired || !valid.test(e.pin)) return;
            e.result = result;
            e.phase = NearbyGuide.Phase.READY;
            e.selected = 0;
            e.details = true;
            e.all = false;
            e.update();
        }, ignored -> {
            if (e.retired || !valid.test(e.pin)) return;
            e.phase = NearbyGuide.Phase.UNAVAILABLE;
            e.update();
        });
    }

    private void neighborhood(BooleanSupplier active) {
        EditText field = new EditText(thread.activity);
        field.setSingleLine(true);
        field.setSaveEnabled(false);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        field.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setHint("Quartier ou ville");
        android.widget.LinearLayout form = Ui.column(thread.activity);
        Ui.pad(form, 20, 4); form.addView(field);
        AlertDialog dialog = thread.screen.show(new AlertDialog.Builder(thread.activity).setTitle("Préciser un quartier")
                .setView(form).setNegativeButton("Annuler", null).setPositiveButton("Préparer le brouillon", null));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!active.getAsBoolean() || !dialog.isShowing()) { dialog.dismiss(); return; }
            String area = field.getText().toString();
            if (VisibleText.isBlank(area)) { field.setError("Indiquez un quartier ou une ville."); return; }
            dialog.dismiss();
            host.stageDraft("Cherche des restaurants dans ce quartier ou cette ville : " + area);
        });
    }
}
