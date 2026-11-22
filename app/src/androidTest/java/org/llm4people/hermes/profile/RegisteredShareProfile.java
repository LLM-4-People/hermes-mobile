package org.llm4people.hermes.profile;

import android.content.Context;
import java.util.UUID;

/** A disposable real account so an intent journey must pass the normal passphrase gate. */
public final class RegisteredShareProfile implements AutoCloseable {
    private final AccountStore store;
    public final String label = "Partage " + UUID.randomUUID();
    public final String passphrase = UUID.randomUUID().toString();
    public final Account account;

    public RegisteredShareProfile(Context context, String endpoint) throws Exception {
        store = new AccountStore(context);
        account = store.create(label, endpoint, "local-fixture", passphrase.toCharArray());
    }

    @Override public void close() throws Exception {
        account.close();
        store.forget(account.id);
    }
}
