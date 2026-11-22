package org.llm4people.hermes.profile;

import android.content.Context;
import java.security.SecureRandom;
import java.util.UUID;

/** Distinct disposable unlocked accounts for exercising native features above the credential gate. */
public final class WorkspaceAccountFixture implements AutoCloseable {
    private final String prefs;
    private final Context context;
    public final Account account;

    public WorkspaceAccountFixture(Context context) {
        this.context = context;
        String id = UUID.randomUUID().toString();
        prefs = "workspace.test." + id;
        byte[] keys = new byte[Account.KEY_BYTES];
        new SecureRandom().nextBytes(keys);
        account = new Account(id,"Essai","https://example.invalid","fixture",context.getSharedPreferences(
                prefs,Context.MODE_PRIVATE),keys);
    }

    @Override public void close() {
        account.close();
        context.deleteSharedPreferences(prefs);
    }
}
