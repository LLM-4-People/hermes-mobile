package org.llm4people.hermes.update;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class ReleaseVersionTest {
    @Test
    public void parsesValidSemVerTagsAndComparesPrecedenceAccurately() {
        ReleaseVersion v010 = ReleaseVersion.parse("v0.1.0");
        ReleaseVersion v010Plain = ReleaseVersion.parse("0.1.0");
        ReleaseVersion v020 = ReleaseVersion.parse("V0.2");
        ReleaseVersion withBuild = ReleaseVersion.parse("1.2.3+build.42");
        ReleaseVersion plain123 = ReleaseVersion.parse("1.2.3");

        assertEquals(v010, v010Plain);
        assertEquals("0.1.0", v010.canonical);
        assertEquals("0.2.0", v020.canonical);
        assertEquals(0, withBuild.compareTo(plain123));
        assertTrue(v020.isNewerThan(v010));
        assertFalse(v010.isNewerThan(v010Plain));
        assertFalse(v010.isNewerThan(v020));
    }

    @Test
    public void preReleasePrecedenceFollowsSemVerAndNeverPromptsOnEqualOrOlder() {
        String[] ordered = {
                "v1.0.0-alpha",
                "v1.0.0-alpha.1",
                "v1.0.0-alpha.beta",
                "v1.0.0-beta",
                "v1.0.0-beta.2",
                "v1.0.0-beta.11",
                "v1.0.0-rc.1",
                "v1.0.0",
                "v1.0.1",
                "v1.1.0",
                "v2.0.0"
        };
        for (int i = 0; i < ordered.length; i++) {
            ReleaseVersion a = ReleaseVersion.parse(ordered[i]);
            assertFalse("Equal must not be newer: " + ordered[i], a.isNewerThan(ReleaseVersion.parse(ordered[i])));
            for (int j = i + 1; j < ordered.length; j++) {
                ReleaseVersion b = ReleaseVersion.parse(ordered[j]);
                assertTrue(ordered[j] + " must be newer than " + ordered[i], b.isNewerThan(a));
                assertFalse(ordered[i] + " must not be newer than " + ordered[j], a.isNewerThan(b));
            }
        }
    }

    @Test
    public void malformedVersionsAreRejectedFailClosed() {
        String[] invalid = {
                null, "", "   ", "v", "1", "1.2.3.4", "01.2.3", "1.02.3",
                "-1.0.0", "1.0.0-", "1.0.0+", "1.0.0-01", "1.0.0-a..b",
                "1.0.0-bad_char", "999999999999.0.0", "v1.0.0\n"
        };
        for (String raw : invalid) {
            assertNull("Expected null for invalid version: " + raw, ReleaseVersion.parseOrNull(raw));
            try {
                ReleaseVersion.parse(raw);
                fail("Expected IllegalArgumentException for: " + raw);
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
        assertFalse(ReleaseVersion.parse("1.0.0").isNewerThan(null));
    }
}
