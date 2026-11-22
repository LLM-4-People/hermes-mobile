package org.llm4people.hermes.push;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.net.HermesApi;
import org.llm4people.hermes.profile.Account;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Encrypted per-profile delivery inbox stored inside {@link Account} AES-GCM preferences.
 * Fetches full typed deliveries from {@code /api/mobile/deliveries} ONLY after profile unlock,
 * and acknowledges read items via {@code POST /api/mobile/deliveries/{id}/ack}.
 */
public final class DeliveryInbox {
    public static final String DELIVERIES_PATH = "/api/mobile/deliveries";
    static final String ACCOUNT_RECORD_KEY = "push/delivery_inbox_v1";
    static final int MAX_CACHED_ITEMS = 100;

    private static final class Snapshot {
        final long cursor;
        final LinkedHashMap<String, DeliveryItem> items;

        Snapshot(long cursor, LinkedHashMap<String, DeliveryItem> items) {
            this.cursor = Math.max(0L, cursor);
            this.items = items;
        }
    }

    public List<DeliveryItem> cached(Account account) {
        Objects.requireNonNull(account, "account");
        Snapshot snapshot = readSnapshot(account);
        return sorted(snapshot.items);
    }

    public int unreadCount(Account account) {
        int count = 0;
        for (DeliveryItem item : cached(account)) {
            if (!item.isAcknowledged()) count++;
        }
        return count;
    }

    public DeliveryItem find(Account account, String deliveryId) {
        Objects.requireNonNull(account, "account");
        if (!DeliveryItem.isValidId(deliveryId)) return null;
        return readSnapshot(account).items.get(deliveryId);
    }

    public List<DeliveryItem> sync(Account account, HermesApi api) throws IOException {
        return sync(account, api, DELIVERIES_PATH);
    }

    public List<DeliveryItem> sync(Account account, HermesApi api, String basePath) throws IOException {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(api, "api");
        String prefix = normalizePath(basePath);
        Snapshot state = readSnapshot(account);
        long cursor = state.cursor;
        LinkedHashMap<String, DeliveryItem> merged = new LinkedHashMap<>(state.items);
        String query = cursor > 0L
                ? prefix + "?since=" + cursor + "&include_acked=true&limit=50"
                : prefix + "?include_acked=true&limit=50";
        JSONObject response = api.get(query);
        JSONArray array = response.optJSONArray("deliveries");
        if (array == null) array = response.optJSONArray("data");
        long maxCreated = cursor;
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject row = array.optJSONObject(i);
                if (row == null) continue;
                try {
                    DeliveryItem incoming = DeliveryItem.fromJson(row);
                    DeliveryItem existing = merged.get(incoming.id);
                    if (existing != null && existing.isAcknowledged() && !incoming.isAcknowledged()) {
                        incoming = incoming.withAcknowledged(existing.ackedAtEpochSec);
                    }
                    merged.put(incoming.id, incoming);
                    if (incoming.createdAtEpochSec > maxCreated) {
                        maxCreated = incoming.createdAtEpochSec;
                    }
                } catch (IllegalArgumentException malformed) {
                    Diagnostics.warning("push", "delivery_malformed");
                }
            }
        }
        if (maxCreated > cursor) cursor = maxCreated;
        writeSnapshot(account, new Snapshot(cursor, trim(merged)));
        Diagnostics.event("push", "inbox_synced");
        return sorted(merged);
    }

    public DeliveryItem fetchOne(Account account, HermesApi api, String deliveryId) throws IOException {
        return fetchOne(account, api, deliveryId, DELIVERIES_PATH);
    }

    public DeliveryItem fetchOne(Account account, HermesApi api, String deliveryId, String basePath) throws IOException {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(api, "api");
        if (!DeliveryItem.isValidId(deliveryId)) throw new IllegalArgumentException("Invalid delivery id");
        String prefix = normalizePath(basePath);
        JSONObject response = api.get(prefix + "/" + HermesApi.segment(deliveryId));
        JSONObject body = unwrapDelivery(response);
        DeliveryItem item = DeliveryItem.fromJson(body);
        if (!deliveryId.equals(item.id)) throw new IOException("Delivery identifier mismatch");
        upsert(account, item);
        return item;
    }

    public DeliveryItem acknowledge(Account account, HermesApi api, String deliveryId) throws IOException {
        return acknowledge(account, api, deliveryId, DELIVERIES_PATH);
    }

    public DeliveryItem acknowledge(Account account, HermesApi api, String deliveryId, String basePath) throws IOException {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(api, "api");
        if (!DeliveryItem.isValidId(deliveryId)) throw new IllegalArgumentException("Invalid delivery id");
        String prefix = normalizePath(basePath);
        JSONObject response = api.post(prefix + "/" + HermesApi.segment(deliveryId) + "/ack", new JSONObject());
        JSONObject body = unwrapDelivery(response);
        Snapshot state = readSnapshot(account);
        DeliveryItem updated;
        if (body.has("delivery_id") || body.has("id") || body.has("event_id")) {
            updated = DeliveryItem.fromJson(body);
        } else {
            DeliveryItem existing = state.items.get(deliveryId);
            if (existing == null) return null;
            updated = existing.withAcknowledged(System.currentTimeMillis() / 1000L);
        }
        LinkedHashMap<String, DeliveryItem> merged = new LinkedHashMap<>(state.items);
        merged.put(updated.id, updated);
        writeSnapshot(account, new Snapshot(state.cursor, trim(merged)));
        Diagnostics.event("push", "delivery_acked");
        return updated;
    }

    private static JSONObject unwrapDelivery(JSONObject response) {
        JSONObject wrapped = response.optJSONObject("delivery");
        return wrapped != null ? wrapped : response;
    }

    public void upsert(Account account, DeliveryItem item) {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(item, "item");
        Snapshot state = readSnapshot(account);
        LinkedHashMap<String, DeliveryItem> merged = new LinkedHashMap<>(state.items);
        merged.put(item.id, item);
        writeSnapshot(account, new Snapshot(state.cursor, trim(merged)));
    }

    public void clear(Account account) {
        Objects.requireNonNull(account, "account");
        account.remove(ACCOUNT_RECORD_KEY);
        Diagnostics.debug("push", "inbox_cleared");
    }

    private Snapshot readSnapshot(Account account) {
        try {
            Snapshot parsed = account.read(ACCOUNT_RECORD_KEY, DeliveryInbox::decodeSnapshot);
            return parsed == null ? new Snapshot(0L, new LinkedHashMap<>()) : parsed;
        } catch (Account.UnreadableRecord corrupt) {
            Diagnostics.warning("push", "inbox_corrupt");
            return new Snapshot(0L, new LinkedHashMap<>());
        }
    }

    private void writeSnapshot(Account account, Snapshot snapshot) {
        JSONObject root = new JSONObject();
        JSONArray arr = new JSONArray();
        for (DeliveryItem item : sorted(snapshot.items)) {
            arr.put(item.toJson());
        }
        try {
            root.put("version", 1);
            root.put("cursor", snapshot.cursor);
            root.put("items", arr);
        } catch (JSONException impossible) {
            throw new IllegalStateException("Failed to encode inbox snapshot", impossible);
        }
        account.write(ACCOUNT_RECORD_KEY, root.toString());
    }

    private static Snapshot decodeSnapshot(String raw) {
        if (raw == null || raw.trim().isEmpty()) return new Snapshot(0L, new LinkedHashMap<>());
        try {
            JSONObject root = new JSONObject(raw);
            long cursor = Math.max(0L, root.optLong("cursor", 0L));
            JSONArray arr = root.optJSONArray("items");
            LinkedHashMap<String, DeliveryItem> map = new LinkedHashMap<>();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject row = arr.optJSONObject(i);
                    if (row == null) continue;
                    try {
                        DeliveryItem item = DeliveryItem.fromJson(row);
                        map.put(item.id, item);
                    } catch (IllegalArgumentException skipped) {
                        Diagnostics.warning("push", "inbox_item_skipped");
                    }
                }
            }
            return new Snapshot(cursor, trim(map));
        } catch (JSONException malformed) {
            throw new IllegalArgumentException("Malformed delivery inbox", malformed);
        }
    }

    private static LinkedHashMap<String, DeliveryItem> trim(LinkedHashMap<String, DeliveryItem> source) {
        if (source.size() <= MAX_CACHED_ITEMS) return source;
        List<DeliveryItem> ordered = new ArrayList<>(source.values());
        ordered.sort(Comparator.comparingLong((DeliveryItem d) -> d.createdAtEpochSec).reversed());
        LinkedHashMap<String, DeliveryItem> kept = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(MAX_CACHED_ITEMS, ordered.size()); i++) {
            DeliveryItem item = ordered.get(i);
            kept.put(item.id, item);
        }
        return kept;
    }

    private static List<DeliveryItem> sorted(Map<String, DeliveryItem> items) {
        List<DeliveryItem> list = new ArrayList<>(items.values());
        list.sort(Comparator.comparingLong((DeliveryItem d) -> d.createdAtEpochSec).reversed()
                .thenComparing(d -> d.id));
        return Collections.unmodifiableList(list);
    }

    private static String normalizePath(String basePath) {
        if (basePath == null || basePath.trim().isEmpty() || !basePath.startsWith("/")) {
            throw new IllegalArgumentException("Invalid deliveries base path");
        }
        return basePath.endsWith("/") ? basePath.substring(0, basePath.length() - 1) : basePath;
    }
}
