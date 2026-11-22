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
    static final int NO_REPAIR = 0, WAITING_ENDPOINT = 1, RETRY_REPAIR = 2, REPAIR_ACTION = 3;
    static final long MAX_DELAY = 3_600_000L;
    private final DataSetObservable changes = new DataSetObservable();
    private static PushStore instance;

    static synchronized PushStore get(Context context) {
        if (instance == null) instance = new PushStore(context.getApplicationContext(), "push.db");
        return instance;
    }

    private static final int DATABASE_VERSION = 6;

    PushStore(Context context, String database) { super(context, database, null, DATABASE_VERSION); }

    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE devices(profile TEXT PRIMARY KEY, installation TEXT NOT NULL UNIQUE,"
                + "endpoint TEXT NOT NULL, secret BLOB NOT NULL, state TEXT NOT NULL, app TEXT NOT NULL,"
                + "api_key TEXT NOT NULL, project TEXT NOT NULL, sender TEXT NOT NULL)");
        addDistributorColumns(db);
        addRetryColumns(db);
        addDismissals(db);
        addRecovery(db);
        addRepair(db);
        db.execSQL("CREATE TABLE seen(installation TEXT NOT NULL REFERENCES devices(installation) ON DELETE CASCADE,"
                + "event TEXT NOT NULL, kind TEXT NOT NULL, PRIMARY KEY(installation,event))");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 1 || oldVersion >= newVersion || newVersion != DATABASE_VERSION)
            throw new IllegalStateException("Unknown push schema");
        if (oldVersion < 2) addDistributorColumns(db);
        if (oldVersion < 3) {
            addRetryColumns(db);
            // A receipt could only be recorded on a previously active binding.
            db.execSQL("UPDATE devices SET established=1 WHERE distributor IS NOT NULL AND (state='active' "
                    + "OR state='pending' AND EXISTS (SELECT 1 FROM seen WHERE seen.installation=devices.installation))");
        }
        // Both v4 and v5 have different historical schemas. Preserve each installed feature.
        addDismissals(db);
        addRecovery(db);
        addRepair(db);
    }

    private static void addRepair(SQLiteDatabase db) {
        if (!hasDeviceColumn(db, "repair"))
            db.execSQL("ALTER TABLE devices ADD COLUMN repair INTEGER NOT NULL DEFAULT 0");
    }

    private static boolean hasDeviceColumn(SQLiteDatabase db, String name) {
        try (Cursor columns = db.rawQuery("SELECT * FROM devices LIMIT 0", null)) {
            return columns.getColumnIndex(name) >= 0;
        }
    }

    private static void addRecovery(SQLiteDatabase db) {
        if (!hasDeviceColumn(db, "signer")) db.execSQL("ALTER TABLE devices ADD COLUMN signer TEXT");
        db.execSQL("CREATE TABLE IF NOT EXISTS deferred(installation TEXT NOT NULL REFERENCES devices(installation) ON DELETE CASCADE,"
                + "version TEXT NOT NULL,event TEXT NOT NULL,kind TEXT NOT NULL,expires INTEGER NOT NULL,"
                + "discard_at INTEGER NOT NULL,PRIMARY KEY(installation,version,event))");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS discard_deferred AFTER UPDATE OF state,subscription_version ON devices BEGIN "
                + "DELETE FROM deferred WHERE installation=NEW.installation AND "
                + "(NEW.state IN ('failed','removing') OR version IS NOT NEW.subscription_version); END");
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
        db.execSQL("CREATE TABLE IF NOT EXISTS dismissed_questions(installation TEXT NOT NULL REFERENCES devices(installation)"
                + " ON DELETE CASCADE,event TEXT NOT NULL,received_at INTEGER NOT NULL,PRIMARY KEY(installation,event))");
        db.execSQL("CREATE INDEX IF NOT EXISTS dismissed_age ON dismissed_questions(received_at)");
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
        final String distributor, signer, vapid, subscriptionVersion, failure;
        final int attempts, repair;
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
            signer = row.getString(row.getColumnIndexOrThrow("signer"));
            vapid = row.getString(row.getColumnIndexOrThrow("vapid"));
            subscription = row.getBlob(row.getColumnIndexOrThrow("subscription"));
            subscriptionVersion = row.getString(row.getColumnIndexOrThrow("subscription_version"));
            failure = row.getString(row.getColumnIndexOrThrow("failure"));
            repair = row.getInt(row.getColumnIndexOrThrow("repair"));
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
        boolean retryScheduled() { return retryAt != 0; }

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

    Device prepareUnified(String profile, String endpoint, String distributor, String signer, String vapid)
            throws GeneralSecurityException, IOException {
        Device device = forProfile(profile);
        if (device != null) {
            if (!distributor.equals(device.distributor) || !signer.equals(device.signer) || REMOVING.equals(device.state)
                    || !endpoint.equals(device.endpoint)) throw new IOException("enrollment changed");
            if (device.repair != NO_REPAIR && vapid.equals(device.vapid)) return device;
            ContentValues updated = new ContentValues();
            updated.put("vapid", vapid);
            updated.put("state", PENDING);
            updated.put("repair", NO_REPAIR);
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
        data.put("signer", signer);
        data.put("vapid", vapid);
        for (String column : new String[]{"app", "api_key", "project", "sender"}) data.put(column, "");
        return insert(profile, endpoint, data);
    }

    boolean updateSubscription(Device device, String json) throws GeneralSecurityException, IOException {
        if (device.subscription != null && (ACTIVE.equals(device.state) || PENDING.equals(device.state))
                && json.equals(DeviceSecret.open(device.subscription, device.subscriptionBinding()))) {
            if (device.repair != NO_REPAIR) {
                ContentValues done = new ContentValues();
                done.put("repair", NO_REPAIR);
                done.put("attempts", 0);
                done.put("retry_at", 0);
                done.putNull("failure");
                if (getWritableDatabase().update("devices", done, "installation=? AND state=? AND subscription_version=?",
                        new String[]{device.installation, device.state, device.subscriptionVersion}) > 0) {
                    changes.notifyChanged();
                    // A pre-reset upload response has an obsolete revision. Confirm the unchanged
                    // endpoint under this repair's version before releasing its held receipts.
                    return PENDING.equals(device.state);
                }
            }
            return false;
        }
        ContentValues data = new ContentValues();
        data.put("state", PENDING);
        data.put("subscription", DeviceSecret.seal(json, device.subscriptionBinding()));
        data.put("subscription_version", UUID.randomUUID().toString());
        data.put("repair", NO_REPAIR);
        data.put("retry_at", 0);
        data.putNull("failure");
        data.put("attempts", 0);
        boolean changed = getWritableDatabase().update("devices", data,
                "installation=? AND state IN (?,?) AND (retry_at=0 OR repair<>0) AND (failure IS NULL OR failure<>?)",
                new String[]{device.installation, PENDING, ACTIVE, RENEW}) == 1;
        if (changed) changes.notifyChanged();
        return changed;
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
            where = "installation=? AND state=? AND subscription_version=? AND repair=0";
            args = new String[]{device.installation, PENDING, device.subscriptionVersion};
        }
        if (getWritableDatabase().update("devices", data, where, args) > 0) changes.notifyChanged();
    }

    boolean beginRepair(Device device, long now, boolean explicit) {
        if (!(ACTIVE.equals(device.state) || PENDING.equals(device.state)) || device.subscriptionVersion == null) return false;
        if (!explicit && (device.repair == REPAIR_ACTION || device.retryAt > now && device.retryAt - now <= MAX_DELAY))
            return false;
        int attempts = explicit ? 1 : Math.min(8, device.attempts + 1);
        ContentValues data = new ContentValues();
        data.put("repair", WAITING_ENDPOINT);
        data.put("attempts", attempts);
        data.put("retry_at", now + repairDelay(attempts));
        if (explicit) data.putNull("failure");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (PENDING.equals(device.state) && device.repair == NO_REPAIR) {
                // Fence the already running bridge PUT. Keep opaque receipts private in this
                // revision until the distributor confirms the same endpoint and its PUT succeeds.
                String next = UUID.randomUUID().toString();
                data.put("subscription_version", next);
                db.execSQL("UPDATE deferred SET version=? WHERE installation=? AND version=?",
                        new Object[]{next, device.installation, device.subscriptionVersion});
            }
            if (db.update("devices", data,
                    "installation=? AND state=? AND subscription_version=? AND repair=? AND retry_at=?",
                    new String[]{device.installation, device.state, device.subscriptionVersion,
                            Integer.toString(device.repair), Long.toString(device.retryAt)}) == 0) return false;
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        if (device.repair != WAITING_ENDPOINT) changes.notifyChanged();
        return true;
    }

    private static long repairDelay(int attempts) { return Math.min(MAX_DELAY, 30_000L << Math.min(7, Math.max(0, attempts - 1))); }

    // A repair REGISTER can fail even though its old endpoint remains a valid, authorized binding.
    // Only an outstanding REGISTER owns a failure. A matching NEW_ENDPOINT clears that ownership.
    private boolean repairFailed(Device device, FailedReason reason, long now) {
        if (device.repair != WAITING_ENDPOINT) return false;
        boolean retry = reason == FailedReason.NETWORK || reason == FailedReason.INTERNAL_ERROR && device.attempts == 1;
        ContentValues data = new ContentValues();
        data.put("failure", reason.name());
        data.put("repair", retry ? RETRY_REPAIR : REPAIR_ACTION);
        data.put("retry_at", retry ? now + repairDelay(device.attempts) : 0);
        if (getWritableDatabase().update("devices", data,
                "installation=? AND state=? AND subscription_version=? AND repair=? AND retry_at=?",
                new String[]{device.installation, device.state, device.subscriptionVersion,
                        Integer.toString(WAITING_ENDPOINT), Long.toString(device.retryAt)}) == 0) return false;
        changes.notifyChanged();
        return true;
    }

    // Ignore stale failures after NEW_ENDPOINT, including while the server upload is in flight.
    boolean registrationFailed(Device device, FailedReason reason, long now) {
        if (device.repair != NO_REPAIR || ACTIVE.equals(device.state)) return repairFailed(device, reason, now);
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
        if (!device.retryScheduled() && !RENEW.equals(device.failure)) return;
        ContentValues data = new ContentValues();
        data.put("retry_at", 0);
        if (RENEW.equals(device.failure)) data.putNull("failure");
        if (getWritableDatabase().update("devices", data,
                "installation=? AND state=? AND subscription IS NULL AND retry_at=? AND failure=?",
                new String[]{device.installation, PENDING, Long.toString(device.retryAt), device.failure}) > 0)
            changes.notifyChanged();
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
                    if (marker.moveToFirst()) {
                        // A suppressed prompt is a successful no-op even inside a deferred drain.
                        db.setTransactionSuccessful();
                        return false;
                    }
                }
            }
            ContentValues data = new ContentValues();
            data.put("installation", installation);
            data.put("event", event);
            data.put("kind", kind);
            if (db.insertWithOnConflict("seen", null, data, SQLiteDatabase.CONFLICT_IGNORE) < 0) {
                db.setTransactionSuccessful();
                return false;
            }
            db.execSQL("DELETE FROM seen WHERE installation=? AND rowid NOT IN "
                    + "(SELECT rowid FROM seen WHERE installation=? ORDER BY rowid DESC LIMIT 512)",
                    new Object[]{installation, installation});
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    // Only opaque receipts for a known, unconfirmed binding enter this queue.
    void defer(Device owner, String event, String kind, long expires, long now) {
        if (!PENDING.equals(owner.state) || owner.established || owner.subscriptionVersion == null
                || !PushNotices.validIds(owner.installation, event) || !WebPushExpiry.mayDispatch(kind, expires, now)) return;
        String request = switch (kind) {
            case PushNotices.DISMISS_APPROVAL -> "approval_requested";
            case PushNotices.DISMISS_QUESTION -> "question_requested";
            default -> null;
        };
        if (request == null && PushNotices.title(kind) == null) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Device current = forInstallation(owner.installation);
            if (current == null || !PENDING.equals(current.state) || current.established
                    || !owner.subscriptionVersion.equals(current.subscriptionVersion)) return;
            db.execSQL("DELETE FROM deferred WHERE discard_at<=?", new Object[]{now});
            long discardAt = now + 24 * 3_600_000L;
            db.execSQL("INSERT OR IGNORE INTO deferred(installation,version,event,kind,expires,discard_at) "
                    + "VALUES(?,?,?,?,?,?)", new Object[]{owner.installation, owner.subscriptionVersion, event, kind, expires, discardAt});
            if (request != null) {
                // The settlement uses the original event ID. Preserve it whichever callback arrived first.
                db.execSQL("UPDATE deferred SET kind=?,expires=?,discard_at=? WHERE installation=? AND version=? "
                        + "AND event=? AND kind=?", new Object[]{kind, expires, discardAt, owner.installation,
                                owner.subscriptionVersion, event, request});
            }
            db.execSQL("DELETE FROM deferred WHERE installation=? AND rowid NOT IN "
                    + "(SELECT rowid FROM deferred WHERE installation=? ORDER BY rowid DESC LIMIT 64)",
                    new Object[]{owner.installation, owner.installation});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    // A crash between confirmation and presentation leaves the receipt available at next startup.
    // remember() participates in this SQLite transaction; notification tags are stable on replay.
    void drain(Context context, Device device) {
        synchronized (PushNotices.class) { drainLocked(context, device); }
    }

    private void drainLocked(Context context, Device device) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Device owner = forInstallation(device.installation);
            if (owner == null || !ACTIVE.equals(owner.state) || owner.subscriptionVersion == null) return;
            String[] binding = {owner.installation, owner.subscriptionVersion};
            try (Cursor rows = db.rawQuery("SELECT event,kind,expires,discard_at FROM deferred "
                    + "WHERE installation=? AND version=? ORDER BY rowid", binding)) {
                while (rows.moveToNext()) {
                    long now = System.currentTimeMillis();
                    String kind = rows.getString(1);
                    if (rows.getLong(3) > now && WebPushExpiry.mayDispatch(kind, rows.getLong(2), now))
                        PushNotices.post(context, owner, rows.getString(0), kind);
                }
            }
            db.delete("deferred", "installation=? AND version=?", binding);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    String knownKind(String installation, String event) {
        try (Cursor row = getReadableDatabase().rawQuery("SELECT kind FROM seen WHERE installation=? AND event=?",
                new String[]{installation,event})) {
            return row.moveToFirst() ? row.getString(0) : null;
        }
    }
}
