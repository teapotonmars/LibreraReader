package com.foobnix.work;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class SidecarRevisionTest {
    @Test public void listingOrderDoesNotChangeRevision() {
        String opf = SidecarRevision.file("metadata.opf", 10L, 20L);
        String cover = SidecarRevision.file("cover.jpg", 30L, 40L);
        assertEquals(SidecarRevision.combine(Arrays.asList(opf, cover)),
                SidecarRevision.combine(Arrays.asList(cover, opf)));
    }
    @Test public void metadataEditChangesRevisionWithoutBookEdit() {
        assertNotEquals(SidecarRevision.file("metadata.opf", 10L, 20L),
                SidecarRevision.file("metadata.opf", 10L, 21L));
    }
    @Test public void coverReplacementChangesRevision() {
        assertNotEquals(SidecarRevision.file("cover.jpg", 10L, 20L),
                SidecarRevision.file("cover.jpg", 11L, 20L));
    }
    @Test public void UnknownTimestampDoesNotClaimUnchangedMetadata() {
        assertNotEquals(SidecarRevision.file("metadata.opf", 10L, 0L),
                SidecarRevision.file("metadata.opf", 10L, 0L));
    }
}
