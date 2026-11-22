package org.llm4people.hermes.push;

import android.content.Context;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.test.InstrumentationTestCase;

import java.util.Arrays;
import java.util.UUID;

@SuppressWarnings("deprecation")
public final class PushStoreMigrationTest extends InstrumentationTestCase {
    private static final String INSTALLATION = "00000000-0000-4000-8000-000000000007";
    private static final String SEEN = "00000000000000000000000000000001";
    private static final String DISMISSED = "00000000000000000000000000000002";
    private static final String DEFERRED = "00000000000000000000000000000003";

    public void testBothVersionFourSchemasKeepTheirData() {
        checkDatabase(4, true);
        checkDatabase(4, false);
    }

    public void testEarlierSchemasAndFreshInstall() {
        for (int version = 0; version <= 3; version++) checkDatabase(version, false);
    }

    private void checkDatabase(int version, boolean hadRecovery) {
        Context context = getInstrumentation().getTargetContext();
        String name = "push-migration-" + UUID.randomUUID() + ".db";
        try {
            if (version > 0) {
                try (SQLiteDatabase db = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)) {
                    seed(db, version, hadRecovery);
                    db.setVersion(version);
                }
            }
            try (PushStore store = new PushStore(context, name)) {
                SQLiteDatabase db = store.getWritableDatabase();
                assertEquals(5, db.getVersion());
                assertEquals(1, count(db, "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='dismissed_age'"));
                assertEquals(1, count(db, "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name='discard_deferred'"));
                if (version == 0) {
                    assertEquals(0, count(db, "SELECT COUNT(signer) FROM devices"));
                    assertEquals(0, count(db, "SELECT COUNT(*) FROM dismissed_questions"));
                    assertEquals(0, count(db, "SELECT COUNT(*) FROM deferred"));
                    return;
                }
                PushStore.Device device = store.forProfile("account");
                assertEquals(INSTALLATION, device.installation);
                assertEquals("https://example.invalid", device.endpoint);
                assertTrue(Arrays.equals(new byte[]{1, 2, 3}, device.secret));
                assertEquals("active", device.state);
                assertEquals(version >= 2, device.established);
                assertEquals(hadRecovery ? "saved-signer" : null, device.signer);
                if (version >= 2) {
                    assertEquals("transport.pkg", device.distributor);
                    assertEquals("v1", device.subscriptionVersion);
                    assertTrue(Arrays.equals(new byte[]{4, 5}, device.subscription));
                } else assertEquals("saved-project", device.config.projectId);
                assertEquals("run_completed", store.knownKind(INSTALLATION, SEEN));
                boolean hadDismissals = version == 4 && !hadRecovery;
                if (hadDismissals) assertFalse(store.remember(INSTALLATION, DISMISSED, "question_requested"));
                else store.dismissPrompt(INSTALLATION, DISMISSED);
                assertFalse(store.remember(INSTALLATION, DISMISSED, "question_requested"));
                if (hadDismissals)
                    assertEquals(42, count(db, "SELECT received_at FROM dismissed_questions WHERE event=?", DISMISSED));
                if (hadRecovery) {
                    assertEquals(999, count(db, "SELECT expires FROM deferred WHERE installation=? AND version='v1'"
                            + " AND event=? AND kind='run_completed' AND discard_at=1000", INSTALLATION, DEFERRED));
                } else {
                    assertEquals(0, count(db, "SELECT COUNT(*) FROM deferred"));
                    insertDeferred(db);
                }
                db.execSQL("UPDATE devices SET subscription_version='v2' WHERE installation=?", new Object[]{INSTALLATION});
                assertEquals(0, count(db, "SELECT COUNT(*) FROM deferred"));
                assertEquals("run_completed", store.knownKind(INSTALLATION, SEEN));
                assertEquals(1, count(db, "SELECT COUNT(*) FROM dismissed_questions"));
            }
        } finally {
            context.deleteDatabase(name);
        }
    }

    private static long count(SQLiteDatabase db, String query, String... args) {
        return DatabaseUtils.longForQuery(db, query, args);
    }

    private static void insertDeferred(SQLiteDatabase db) {
        db.execSQL("INSERT INTO deferred(installation,version,event,kind,expires,discard_at)"
                + " VALUES(?,'v1',?,'run_completed',999,1000)", new Object[]{INSTALLATION, DEFERRED});
    }

    // Recreate the historical DDL independently of the current helper.
    private static void seed(SQLiteDatabase db, int version, boolean hadRecovery) {
        db.execSQL("CREATE TABLE devices(profile TEXT PRIMARY KEY,installation TEXT NOT NULL UNIQUE,"
                + "endpoint TEXT NOT NULL,secret BLOB NOT NULL,state TEXT NOT NULL,app TEXT NOT NULL,"
                + "api_key TEXT NOT NULL,project TEXT NOT NULL,sender TEXT NOT NULL)");
        db.execSQL("CREATE TABLE seen(installation TEXT NOT NULL REFERENCES devices(installation) ON DELETE CASCADE,"
                + "event TEXT NOT NULL,kind TEXT NOT NULL,PRIMARY KEY(installation,event))");
        db.execSQL("INSERT INTO devices VALUES('account',?,'https://example.invalid',X'010203','active',"
                + "'saved-app','saved-key','saved-project','123')", new Object[]{INSTALLATION});
        db.execSQL("INSERT INTO seen VALUES(?,?,'run_completed')", new Object[]{INSTALLATION, SEEN});
        if (version >= 2) {
            db.execSQL("ALTER TABLE devices ADD COLUMN distributor TEXT");
            db.execSQL("ALTER TABLE devices ADD COLUMN vapid TEXT");
            db.execSQL("ALTER TABLE devices ADD COLUMN subscription BLOB");
            db.execSQL("ALTER TABLE devices ADD COLUMN subscription_version TEXT");
            db.execSQL("UPDATE devices SET distributor='transport.pkg',vapid='saved-vapid',subscription=X'0405',subscription_version='v1'");
        }
        if (version >= 3) {
            db.execSQL("ALTER TABLE devices ADD COLUMN failure TEXT");
            db.execSQL("ALTER TABLE devices ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE devices ADD COLUMN retry_at INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE devices ADD COLUMN established INTEGER NOT NULL DEFAULT 0");
            db.execSQL("UPDATE devices SET established=1");
        }
        if (hadRecovery) {
            db.execSQL("ALTER TABLE devices ADD COLUMN signer TEXT");
            db.execSQL("UPDATE devices SET signer='saved-signer'");
            db.execSQL("CREATE TABLE deferred(installation TEXT NOT NULL REFERENCES devices(installation) ON DELETE CASCADE,"
                    + "version TEXT NOT NULL,event TEXT NOT NULL,kind TEXT NOT NULL,expires INTEGER NOT NULL,"
                    + "discard_at INTEGER NOT NULL,PRIMARY KEY(installation,version,event))");
            db.execSQL("CREATE TRIGGER discard_deferred AFTER UPDATE OF state,subscription_version ON devices BEGIN "
                    + "DELETE FROM deferred WHERE installation=NEW.installation AND "
                    + "(NEW.state IN ('failed','removing') OR version IS NOT NEW.subscription_version); END");
            insertDeferred(db);
        } else if (version == 4) {
            db.execSQL("CREATE TABLE dismissed_questions(installation TEXT NOT NULL REFERENCES devices(installation)"
                    + " ON DELETE CASCADE,event TEXT NOT NULL,received_at INTEGER NOT NULL,PRIMARY KEY(installation,event))");
            db.execSQL("CREATE INDEX dismissed_age ON dismissed_questions(received_at)");
            db.execSQL("INSERT INTO dismissed_questions VALUES(?,?,42)", new Object[]{INSTALLATION, DISMISSED});
        }
    }
}
