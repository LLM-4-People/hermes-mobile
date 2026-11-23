package org.llm4people.hermes.push;

import android.content.Context;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.test.InstrumentationTestCase;

import java.util.UUID;

@SuppressWarnings("deprecation")
public final class PushStorePerformanceTest extends InstrumentationTestCase {
    private static final String INSTALLATION = "00000000-0000-4000-8000-000000000099";

    public void testWalEnabledIndexesCreatedAndPruneExpiredReclaimsOnlyStaleRows() {
        Context context = getInstrumentation().getTargetContext();
        String name = "push-perf-" + UUID.randomUUID() + ".db";
        try (PushStore store = new PushStore(context, name)) {
            SQLiteDatabase db = store.getWritableDatabase();
            assertTrue("WAL mode must be enabled for concurrent push store access",
                    db.isWriteAheadLoggingEnabled());
            assertEquals(1L, DatabaseUtils.longForQuery(db,
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='deferred_discard'", null));
            assertEquals(1L, DatabaseUtils.longForQuery(db,
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='devices_state_retry'", null));

            db.execSQL("INSERT INTO devices(profile,installation,endpoint,secret,state,app,api_key,project,sender,"
                            + "distributor,subscription_version,established) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    new Object[]{"p1", INSTALLATION, "https://example.invalid", new byte[]{1, 2}, "active",
                            "", "", "", "", "dist.pkg", "v1", 1});

            long nowMs = 2_000_000_000_000L;
            long nowSec = nowMs / 1000L;
            // One expired deferred row, one active deferred row.
            db.execSQL("INSERT INTO deferred(installation,version,event,kind,expires,discard_at,urgency) "
                            + "VALUES(?,?,?,?,?,?,?)",
                    new Object[]{INSTALLATION, "v1", "evt-expired", "notice", nowMs - 10_000L, nowMs - 1_000L, "normal"});
            db.execSQL("INSERT INTO deferred(installation,version,event,kind,expires,discard_at,urgency) "
                            + "VALUES(?,?,?,?,?,?,?)",
                    new Object[]{INSTALLATION, "v1", "evt-active", "notice", nowMs + 60_000L, nowMs + 120_000L, "high"});
            // One stale (>29d) dismissal, one recent dismissal.
            db.execSQL("INSERT INTO dismissed_questions(installation,event,received_at) VALUES(?,?,?)",
                    new Object[]{INSTALLATION, "q-stale", nowSec - 30L * 86400L});
            db.execSQL("INSERT INTO dismissed_questions(installation,event,received_at) VALUES(?,?,?)",
                    new Object[]{INSTALLATION, "q-fresh", nowSec - 3600L});

            int removed = store.pruneExpired(nowMs);
            assertEquals(2, removed);
            assertEquals(1L, DatabaseUtils.longForQuery(db,
                    "SELECT COUNT(*) FROM deferred WHERE event='evt-active'", null));
            assertEquals(0L, DatabaseUtils.longForQuery(db,
                    "SELECT COUNT(*) FROM deferred WHERE event='evt-expired'", null));
            assertEquals(1L, DatabaseUtils.longForQuery(db,
                    "SELECT COUNT(*) FROM dismissed_questions WHERE event='q-fresh'", null));
            assertEquals(0L, DatabaseUtils.longForQuery(db,
                    "SELECT COUNT(*) FROM dismissed_questions WHERE event='q-stale'", null));
        } finally {
            context.deleteDatabase(name);
        }
    }
}
