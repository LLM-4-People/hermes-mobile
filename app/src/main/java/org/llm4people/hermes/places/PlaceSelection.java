package org.llm4people.hermes.places;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** All exploration of a returned result set is local; IDs stay attached to their original OSM records. */
public final class PlaceSelection {
    private final NearbyResult result;
    public final List<String> cuisines;
    public final List<String> tags;
    private Set<Integer> visible;
    private int selected, comparison = -1;
    private String cuisine = "";
    private String tag = "";
    private boolean details = true, all;

    public PlaceSelection(NearbyResult result) {
        this.result = result;
        Set<String> types = new LinkedHashSet<>();
        Set<String> extra = new LinkedHashSet<>();
        for (NearbyResult.Place p : result.places) {
            types.addAll(p.cuisines);
            extra.addAll(p.tags);
        }
        cuisines = Collections.unmodifiableList(new ArrayList<>(types));
        tags = Collections.unmodifiableList(new ArrayList<>(extra));
        recompute();
    }

    public int selected() { return selected; }
    public int comparison() { return comparison; }
    public String cuisine() { return cuisine; }
    public String tag() { return tag; }
    public boolean details() { return details; }
    public boolean all() { return all; }
    public Set<Integer> visible() { return visible; }
    public void all(boolean value) { all = value; }

    public void filter(String value) {
        if (visible != null && value.equals(cuisine) || !value.isEmpty() && !cuisines.contains(value)) return;
        cuisine = value;
        recompute();
    }

    public void filterTag(String value) {
        if (visible != null && value.equals(tag) || !value.isEmpty() && !tags.contains(value)) return;
        tag = value;
        recompute();
    }

    private void recompute() {
        Set<Integer> indexes = new LinkedHashSet<>();
        for (int i = 0; i < result.places.size(); i++) {
            NearbyResult.Place place = result.places.get(i);
            boolean cuisineMatch = cuisine.isEmpty() || place.cuisines.contains(cuisine);
            boolean tagMatch = tag.isEmpty() || place.tags.contains(tag);
            if (cuisineMatch && tagMatch) indexes.add(i);
        }
        visible = Collections.unmodifiableSet(indexes);
        if (!visible.isEmpty() && !visible.contains(selected)) {
            selected = visible.iterator().next();
            details = true;
        }
        if (!visible.contains(comparison) || comparison == selected) comparison = -1;
        all = false;
    }

    public void choose(int index, boolean toggle) {
        if (!visible.contains(index)) return;
        if (index == comparison) comparison = selected;
        details = !(toggle && index == selected && details);
        selected = index;
    }

    public void compare(int index) {
        if (index == -1 || index != selected && visible.contains(index)) comparison = index;
    }
}
