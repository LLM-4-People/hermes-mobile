package org.llm4people.hermes.profile;
import android.content.Context;
import java.security.SecureRandom;
import java.util.UUID;

public final class AdverseAccountFixture implements AutoCloseable {
    private final AccountIndexCommitTest.IndexContext prefs;
    public final Account account;
    public AdverseAccountFixture(Context context,String url) {
        prefs=new AccountIndexCommitTest.IndexContext(context);
        byte[] key=new byte[Account.KEY_BYTES];new SecureRandom().nextBytes(key);
        account=new Account(UUID.randomUUID().toString(),"Relecture",url,"fixture",prefs.index,key);
    }
    public void rejectNextSave(){prefs.failNext=true;}
    @Override public void close(){account.close();prefs.memory.clear();prefs.disk.clear();}
}
