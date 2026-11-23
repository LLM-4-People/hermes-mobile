package org.llm4people.hermes.share;

import org.junit.Test;
import org.llm4people.hermes.camera.CapturedMedia;
import org.llm4people.hermes.perf.ThumbnailCache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Regression tests for ShareDraftTray ThumbnailCache keying and invalid payload handling.
 */
public final class ShareDraftTrayThumbKeyTest {

    @Test
    public void thumbnailCacheKeyUsesDraftSha256PrefixAndHandlesMissingDigest() {
        CapturedMedia.Item image = new CapturedMedia.Item(
                CapturedMedia.Kind.IMAGE, "photo.jpg", "image/jpeg", new byte[]{1, 2, 3},
                "abc123def456", "AQID");
        assertEquals("draft:abc123def456", ShareDraftTray.thumbnailCacheKey(image));

        CapturedMedia.Item emptyDigest = new CapturedMedia.Item(
                CapturedMedia.Kind.IMAGE, "photo.jpg", "image/jpeg", new byte[]{1},
                "", "AQ==");
        assertNull(ShareDraftTray.thumbnailCacheKey(emptyDigest));
        assertNull(ShareDraftTray.thumbnailCacheKey(null));
        assertNull(ThumbnailCache.decodeSampled(new byte[0], ThumbnailCache.DEFAULT_THUMB_MAX_EDGE));
    }
}
