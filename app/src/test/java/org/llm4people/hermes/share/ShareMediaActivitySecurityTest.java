package org.llm4people.hermes.share;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ShareMediaActivitySecurityTest {
    @Test
    public void rejectsHermesSelfOwnedAuthoritiesIncludingSubAuthoritiesAndMultiUserPrefixes() {
        String pkg = "org.llm4people.hermes";
        assertTrue(ShareMediaActivity.isSelfOwnedAuthority(pkg, "org.llm4people.hermes"));
        assertTrue(ShareMediaActivity.isSelfOwnedAuthority(pkg, "org.llm4people.hermes.capture"));
        assertTrue(ShareMediaActivity.isSelfOwnedAuthority(pkg, "org.llm4people.hermes.updates"));
        assertTrue(ShareMediaActivity.isSelfOwnedAuthority(pkg, "ORG.LLM4PEOPLE.HERMES.CAPTURE"));
        assertTrue(ShareMediaActivity.isSelfOwnedAuthority(pkg, "0@org.llm4people.hermes.capture"));
        assertTrue(ShareMediaActivity.isSelfOwnedAuthority(pkg, "10@org.llm4people.hermes.updates:443"));

        // External authorities and prefix lookalikes without dot separator must not be rejected as self-owned.
        assertFalse(ShareMediaActivity.isSelfOwnedAuthority(pkg, "com.android.providers.media.documents"));
        assertFalse(ShareMediaActivity.isSelfOwnedAuthority(pkg, "com.google.android.apps.photos.contentprovider"));
        assertFalse(ShareMediaActivity.isSelfOwnedAuthority(pkg, "org.llm4people.hermesother.provider"));
        assertFalse(ShareMediaActivity.isSelfOwnedAuthority(pkg, ""));
        assertFalse(ShareMediaActivity.isSelfOwnedAuthority(pkg, null));
        assertFalse(ShareMediaActivity.isSelfOwnedAuthority(null, "org.llm4people.hermes.capture"));
    }
}
