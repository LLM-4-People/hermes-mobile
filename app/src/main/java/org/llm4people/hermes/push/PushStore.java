package org.llm4people.hermes.push;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DataSetObservable;
import android.database.DataSetObserver;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.unifiedpush.android.connector.FailedReason;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class PushStore extends SQLiteOpenHelper {
    static final String PENDING = "pending", ACTIVE = "active", REMOVING = "removing", FAILED = "failed";
    static final String ENDPOINT_REJECTED = "endpoint_rejected", ACCOUNT_REQUIRED = "account_required", RENEW = "renew";
    static final long MAX_DELAY = 3_600_000L;
    private final DataSetObservable changes = new DataSetObservable();
    private static PushStore instance;

    static synchronized PushStore get(Context context) {
        if (instance == null) instance = new PushStore(context.getApplicationContext(), "push.db");
        return instance;
    }

    PushStore(Context context, String database) { super(context, database, null, 4); }

    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE devices(profile TEXT PRIMARY KEY, installation TEXT NOT NULL UNIQUE,"
                + "endpoint TEXT NOT NULL, secret BLOB NOT NULL, state TEXT NOT NULL, app TEXT NOT NULL,"
                + "api_key TEXT NOT NULL, project TEXT NOT NULL, sender TEXT NOT NULL)");
        addDistributorColumns(db);
        addRetryColumns(db);
        addDismissals(db);
        db.execSQL("CREATE TABLE seen(installation TEXT NOT NULL REFERENCES devices(installation) ON DELETE CASCADE,"
                + "event TEXT NOT NULL, kind TEXT NOT NULL, PRIMARY KEY(installation,event))");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 1 || newVersion != 4) throw new IllegalStateException("Unknown push schema");
        if (oldVersion < 2) addDistributorColumns(db);
        if (oldVersion < 3) {
            addRetryColumns(db);
            // A receipt could only be recorded on a previously active binding.
            db.execSQL("UPDATE devices SET established=1 WHERE distributor IS NOT NULL AND (state='active' "
                    + "OR state='pending' AND EXISTS (SELECT 1 FROM seen WHERE seen.installation=devices.installation))");
        }
        if (oldVersion < 4) addDismissals(db);
    }

    private static void addRetryColumns(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE devices ADD COLUMN failure TEXT");
        db.execSQL("ALTER TABLE devices ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE devices ADD COLUMN retry_at INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE devices ADD COLUMN established INTEGER NOT NULL DEFAULT 0");
    }

    private static void addDistributorColumns(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE devices ADD COLUMN distributor TEXT");
        db.execSQL("ALTER TABLE devices ADD COLUMN vapid TEXT");
        db.execSQL("ALTER TABLE devices ADD COLUMN subscription BLOB");
        db.execSQL("ALTER TABLE devices ADD COLUMN subscription_version TEXT");
    }

    private static void addDismissals(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE dismissed_questions(installation TEXT NOT NULL REFERENCES devices(installation)"
                + " ON DELETE CASCADE,event TEXT NOT NULL,received_at INTEGER NOT NULL,PRIMARY KEY(installation,event))");
        db.execSQL("CREATE INDEX dismissed_age ON dismissed_questions(received_at)");
    }

    void dismissPrompt(String installation, String event) {
        // Earlier requests can arrive for 28 days, after recent notification history rolls over.
        long now = System.currentTimeMillis() / 1000;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.execSQL("DELETE FROM dismissed_questions WHERE received_at<?", new Object[]{now - 29L * 86400});
            Device owner = forInstallation(installation);
            if (owner != null && owner.receives()) {
                db.execSQL("INSERT OR REPLACE INTO dismissed_questions(installation,event,received_at) VALUES(?,?,?)",
                        new Object[]{installation, event, now});
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    static final class Device {
        final String profile, installation, endpoint, state;
        final byte[] secret, subscription;
        final String distributor, vapid, subscriptionVersion, failure;
        final int attempts;
        final long retryAt;
        final boolean established;
        final PushConfig config;

        Device(Cursor row) {
            profile = row.getString(row.getColumnIndexOrThrow("profile"));
            installation = row.getString(row.getColumnIndexOrThrow("installation"));
            endpoint = row.getString(row.getColumnIndexOrThrow("endpoint"));
            state = row.getString(row.getColumnIndexOrThrow("state"));
            secret = row.getBlob(row.getColumnIndexOrThrow("secret"));
            distributor = row.getString(row.getColumnIndexOrThrow("distributor"));
            vapid = row.getString(row.getColumnIndexOrThrow("vapid"));
            subscription = row.getBlob(row.getColumnIndexOrThrow("subscription"));
            subscriptionVersion = row.getString(row.getColumnIndexOrThrow("subscription_version"));
            failure = row.getString(row.getColumnIndexOrThrow("failure"));
            attempts = row.getInt(row.getColumnIndexOrThrow("attempts"));
            retryAt = row.getLong(row.getColumnIndexOrThrow("retry_at"));
            established = row.getInt(row.getColumnIndexOrThrow("established")) != 0;
            config = distributor != null ? null : new PushConfig(row.getString(row.getColumnIndexOrThrow("app")),
                    row.getString(row.getColumnIndexOrThrow("api_key")),
                    row.getString(row.getColumnIndexOrThrow("project")),
                    row.getString(row.getColumnIndexOrThrow("sender")));
        }

        String managementKey() throws GeneralSecurityException, IOException {
            return DeviceSecret.open(secret, binding(profile, installation, endpoint));
        }

        boolean unified() { return distributor != null; }

        boolean receives() {
            return ACTIVE.equals(state) || established && (PENDING.equals(state)
                    || FAILED.equals(state) && ENDPOINT_REJECTED.equals(failure));
        }

        String subscriptionBinding() { return "web-push\n" + binding(profile, installation, endpoint); }

        String path() { return PushConnection.BASE + "/devices/" + installation; }
    }

    private static String binding(String profile, String installation, String endpoint) {
        return profile + "\n" + installation + "\n" + endpoint;
    }

    void observe(DataSetObserver observer) { changes.registerObserver(observer); }
    void stopObserving(DataSetObserver observer) { changes.unregisterObserver(observer); }

    Device forProfile(String id) { return one("profile=?", id); }
    Device forInstallation(String id) { return one("installation=?", id); }

    private Device one(String where, String value) {
        try (Cursor row = getReadableDatabase().query("devices", null, where, new String[]{value},
                null, null, null, "1")) {
            return row.moveToFirst() ? new Device(row) : null;
        }
    }

    List<Device> devices() {
        List<Device> result = new ArrayList<>();
        try (Cursor rows = getReadableDatabase().query("devices", null, null, null, null, null, null)) {
            while (rows.moveToNext()) result.add(new Device(rows));
        }
        return result;
    }

    Device prepare(String profile, String endpoint, PushConfig config)
            throws GeneralSecurityException, IOException {
        Device device = forProfile(profile);
        if (device != null) {
            if (device.unified() || !device.endpoint.equals(endpoint) || !device.config.matches(config.options())
                    || REMOVING.equals(device.state)) throw new IOException("enrollment changed");
            return device;
        }
        ContentValues data = new ContentValues();
        data.put("app", config.applicationId);
        data.put("api_key", config.apiKey);
        data.put("project", config.projectId);
        data.put("sender", config.senderId);
        return insert(profile, endpoint, data);
    }

    Device prepareUnified(String profile, String endpoint, String distributor, String vapid)
            throws GeneralSecurityException, IOException {
        Device device = forProfile(profile);
        if (device != null) {
            if (!distributor.equals(device.distributor) || REMOVING.equals(device.state)
                    || !endpoint.equals(device.endpoint)) throw new IOException("enrollment changed");
            ContentValues updated = new ContentValues();
            updated.put("vapid", vapid);
            updated.put("state", PENDING);
            updated.put("retry_at", 0);
            updated.put("attempts", 0);
            if (!vapid.equals(device.vapid) || FAILED.equals(device.state) && !ACCOUNT_REQUIRED.equals(device.failure)
                    || device.subscription == null && device.failure != null) {
                updated.put("failure", RENEW);
                updated.put("established", 0);
                updated.putNull("subscription");
                updated.putNull("subscription_version");
            } else {
                updated.putNull("failure");
                if (ACCOUNT_REQUIRED.equals(device.failure)) {
                    updated.put("established", 0);
                    updated.put("subscription_version", UUID.randomUUID().toString());
                }
            }
            getWritableDatabase().update("devices", updated, "installation=? AND state<>?",
                    new String[]{device.installation, REMOVING});
            return forProfile(profile);
        }
        ContentValues data = new ContentValues();
        data.put("distributor", distributor);
        data.put("vapid", vapid);
        for (String column : new String[]{"app", "api_key", "project", "sender"}) data.put(column, "");
        return insert(profile, endpoint, data);
    }

    boolean updateSubscription(Device device, String json) throws GeneralSecurityException, IOException {
        if (device.subscription != null && ACTIVE.equals(device.state)
                && json.equals(DeviceSecret.open(device.subscription, device.subscriptionBinding()))) return false;
        ContentValues data = new ContentValues();
        data.put("state", PENDING);
        data.put("subscription", DeviceSecret.seal(json, device.subscriptionBinding()));
        data.put("subscription_version", UUID.randomUUID().toString());
        data.putNull("failure");
        data.put("attempts", 0);
        return getWritableDatabase().update("devices", data,
                "installation=? AND state IN (?,?) AND retry_at=0 AND (failure IS NULL OR failure<>?)",
                new String[]{device.installation, PENDING, ACTIVE, RENEW}) == 1;
    }

    private Device insert(String profile, String endpoint, ContentValues data)
            throws GeneralSecurityException, IOException {
        String id = UUID.randomUUID().toString();
        data.put("profile", profile);
        data.put("installation", id);
        data.put("endpoint", endpoint);
        data.put("secret", DeviceSecret.seal(DeviceSecret.generate(), binding(profile,id,endpoint)));
        data.put("state", PENDING);
        getWritableDatabase().insertOrThrow("devices", null, data);
        return forProfile(profile);
    }

    void enable(Device device) {
        ContentValues data = new ContentValues();
        data.put("state", ACTIVE);
        String where = "installation=? AND state<>?";
        String[] args = {device.installation, REMOVING};
        if (device.unified()) {
            data.put("established", 1);
            // A late acknowledgement for an older endpoint must leave its replacement queued.
            where = "installation=? AND state=? AND subscription_version=?";
            args = new String[]{device.installation, PENDING, device.subscriptionVersion};
        }
        if (getWritableDatabase().update("devices", data, where, args) > 0) changes.notifyChanged();
    }

    // Ignore stale failures after NEW_ENDPOINT, including while the server upload is in flight.
    boolean registrationFailed(Device device, FailedReason reason, long now) {
        int attempts = reason.name().equals(device.failure) ? device.attempts : 0;
        boolean retry = reason == FailedReason.NETWORK || reason == FailedReason.INTERNAL_ERROR && attempts == 0;
        ContentValues data = new ContentValues();
        data.put("failure", reason.name());
        data.put("state", retry ? PENDING : FAILED);
        data.put("attempts", Math.min(8, attempts + 1));
        data.put("retry_at", retry ? now + Math.min(MAX_DELAY, 30_000L << Math.min(7, attempts)) : 0);
        if (getWritableDatabase().update("devices", data,
                "installation=? AND state=? AND subscription IS NULL AND retry_at=0 AND (failure IS NULL OR failure<>?)",
                new String[]{device.installation, PENDING, RENEW}) == 0) return false;
        changes.notifyChanged();
        return true;
    }

    void registered(Device device) {
        ContentValues data = new ContentValues();
        data.put("retry_at", 0);
        if (RENEW.equals(device.failure)) data.putNull("failure");
        getWritableDatabase().update("devices", data, "installation=? AND state=? AND subscription IS NULL",
                new String[]{device.installation, PENDING});
    }

    void uploadRejected(Device device, String cause) {
        ContentValues data = new ContentValues();
        data.put("state", FAILED);
        data.put("failure", cause);
        if (ACCOUNT_REQUIRED.equals(cause)) data.put("established", 0);
        if (getWritableDatabase().update("devices", data,
                "installation=? AND state=? AND subscription_version=?",
                new String[]{device.installation, PENDING, device.subscriptionVersion}) > 0) changes.notifyChanged();
    }

    void deactivate(String profile) {
        ContentValues data = new ContentValues();
        data.put("state", REMOVING);
        if (getWritableDatabase().update("devices", data, "profile=?", new String[]{profile}) > 0)
            changes.notifyChanged();
    }

    void pending(Device device) {
        ContentValues data = new ContentValues();
        data.put("state", PENDING);
        getWritableDatabase().update("devices", data, "installation=? AND state=?",
                new String[]{device.installation, ACTIVE});
    }

    void finishRemoval(Device device) {
        if (getWritableDatabase().delete("devices", "installation=? AND state=?",
                new String[]{device.installation, REMOVING}) > 0) changes.notifyChanged();
    }

    boolean remember(String installation, String event, String kind) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Device owner = forInstallation(installation);
            if (owner == null || !owner.receives()) return false;
            if (PushNotices.isPrompt(kind)) {
                try (Cursor marker = db.rawQuery("SELECT 1 FROM dismissed_questions WHERE installation=? AND event=?",
                        new String[]{installation, event})) {
                    if (marker.moveToFirst()) return false;
                }
            }
            ContentValues data = new ContentValues();
            data.put("installation", installation);
            data.put("event", event);
            data.put("kind", kind);
            if (db.insertWithOnConflict("seen", null, data, SQLiteDatabase.CONFLICT_IGNORE) < 0) return false;
            db.execSQL("DELETE FROM seen WHERE installation=? AND rowid NOT IN "
                    + "(SELECT rowid FROM seen WHERE installation=? ORDER BY rowid DESC LIMIT 512)",
                    new Object[]{installation, installation});
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    String knownKind(String installation, String event) {
        try (Cursor row = getReadableDatabase().rawQuery("SELECT kind FROM seen WHERE installation=? AND event=?",
                new String[]{installation,event})) {
            return row.moveToFirst() ? row.getString(0) : null;
        }
    }
}
