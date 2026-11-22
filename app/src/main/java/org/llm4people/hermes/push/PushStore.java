package org.llm4people.hermes.push;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class PushStore extends SQLiteOpenHelper {
    static final String PENDING = "pending", ACTIVE = "active", REMOVING = "removing";
    private static PushStore instance;

    static synchronized PushStore get(Context context) {
        if (instance == null) instance = new PushStore(context.getApplicationContext(), "push.db");
        return instance;
    }

    PushStore(Context context, String database) { super(context, database, null, 2); }

    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE devices(profile TEXT PRIMARY KEY, installation TEXT NOT NULL UNIQUE,"
                + "endpoint TEXT NOT NULL, secret BLOB NOT NULL, state TEXT NOT NULL, app TEXT NOT NULL,"
                + "api_key TEXT NOT NULL, project TEXT NOT NULL, sender TEXT NOT NULL)");
        addDistributorColumns(db);
        db.execSQL("CREATE TABLE seen(installation TEXT NOT NULL REFERENCES devices(installation) ON DELETE CASCADE,"
                + "event TEXT NOT NULL, kind TEXT NOT NULL, PRIMARY KEY(installation,event))");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion == 1 && newVersion == 2) addDistributorColumns(db);
        else throw new IllegalStateException("Unknown push schema");
    }

    private static void addDistributorColumns(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE devices ADD COLUMN distributor TEXT");
        db.execSQL("ALTER TABLE devices ADD COLUMN vapid TEXT");
        db.execSQL("ALTER TABLE devices ADD COLUMN subscription BLOB");
        db.execSQL("ALTER TABLE devices ADD COLUMN subscription_version TEXT");
    }

    static final class Device {
        final String profile, installation, endpoint, state;
        final byte[] secret, subscription;
        final String distributor, vapid, subscriptionVersion;
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
            config = distributor != null ? null : new PushConfig(row.getString(row.getColumnIndexOrThrow("app")),
                    row.getString(row.getColumnIndexOrThrow("api_key")),
                    row.getString(row.getColumnIndexOrThrow("project")),
                    row.getString(row.getColumnIndexOrThrow("sender")));
        }

        String managementKey() throws GeneralSecurityException, IOException {
            return DeviceSecret.open(secret, binding(profile, installation, endpoint));
        }

        boolean unified() { return distributor != null; }

        String subscriptionBinding() { return "web-push\n" + binding(profile, installation, endpoint); }

        String path() { return PushConnection.BASE + "/devices/" + installation; }
    }

    private static String binding(String profile, String installation, String endpoint) {
        return profile + "\n" + installation + "\n" + endpoint;
    }

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
        return getWritableDatabase().update("devices", data, "installation=? AND state<>?",
                new String[]{device.installation, REMOVING}) == 1;
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
            // A late acknowledgement for an older endpoint must leave its replacement queued.
            where += " AND subscription_version=?";
            args = new String[]{device.installation, REMOVING, device.subscriptionVersion};
        }
        getWritableDatabase().update("devices", data, where, args);
    }

    void deactivate(String profile) {
        ContentValues data = new ContentValues();
        data.put("state", REMOVING);
        getWritableDatabase().update("devices", data, "profile=?", new String[]{profile});
    }

    void pending(Device device) {
        ContentValues data = new ContentValues();
        data.put("state", PENDING);
        getWritableDatabase().update("devices", data, "installation=? AND state=?",
                new String[]{device.installation, ACTIVE});
    }

    void finishRemoval(Device device) {
        getWritableDatabase().delete("devices", "installation=? AND state=?",
                new String[]{device.installation, REMOVING});
    }

    boolean remember(String installation, String event, String kind) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Device owner = forInstallation(installation);
            if (owner == null || !ACTIVE.equals(owner.state)) return false;
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
