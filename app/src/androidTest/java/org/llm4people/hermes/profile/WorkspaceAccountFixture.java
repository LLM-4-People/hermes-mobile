package org.llm4people.hermes.profile;

import android.content.Context;
import java.security.SecureRandom;
import java.util.UUID;

/** Distinct disposable unlocked accounts for exercising native features above the credential gate. */
public final class WorkspaceAccountFixture implements AutoCloseable {
    private final String prefs;
    private final String id;
    private final String endpoint;
    private final byte[] keys = new byte[Account.KEY_BYTES];
    private final Context context;
    public final Account account;

    public WorkspaceAccountFixture(Context context) {
        this(context, "https://example.invalid");
    }

    public WorkspaceAccountFixture(Context context, String endpoint) {
        this.context = context;
        this.endpoint = endpoint;
        id = UUID.randomUUID().toString();
        prefs = "workspace.test." + id;
        new SecureRandom().nextBytes(keys);
        account = reopen();
    }

    public Account reopen() {
        return new Account(id,"Essai",endpoint,"fixture",context.getSharedPreferences(
                prefs,Context.MODE_PRIVATE),keys.clone());
    }

    @Override public void close() {
        account.close();
        java.util.Arrays.fill(keys,(byte)0);
        context.deleteSharedPreferences(prefs);
    }
}
