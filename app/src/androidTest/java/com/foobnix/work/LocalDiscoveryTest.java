package com.foobnix.work;

import androidx.test.platform.app.InstrumentationRegistry;
import com.foobnix.dao2.FileMeta;
import com.foobnix.pdf.info.model.BookCSS;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class LocalDiscoveryTest {
    @Test public void selectedButUnavailableFolderKeepsItsSelection() {
        String missing = "/unavailable-selected-" + System.nanoTime();
        assertEquals(Collections.singletonList(missing),
                BookCSS.filtered(Collections.singletonList(missing)));
        assertTrue(BookCSS.filtered(Collections.emptyList()).isEmpty());
    }

    @Test public void unreadableChildDoesNotProduceACompleteListing() throws Exception {
        File root = new File(InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getCacheDir(), "scan-test-" + System.nanoTime());
        File child = new File(root, "books");
        assertTrue(child.mkdirs());
        try {
            List<FileMeta> found = new ArrayList<>();
            try {
                LocalDiscovery.collect(root, Collections.singletonList(".epub"), found,
                        () -> false, folder -> folder.equals(child) ? null : folder.listFiles());
                fail("Unreadable child must invalidate removal evidence");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("Cannot list"));
            }
        } finally {
            child.delete();
            root.delete();
        }
    }

    @Test public void unavailableSelectedRootIsNotAnEmptyLibrary() throws Exception {
        File absent = new File(InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getCacheDir(), "missing-scan-" + System.nanoTime());
        try {
            LocalDiscovery.collect(absent, Collections.singletonList(".epub"),
                    new ArrayList<>(), () -> false);
            fail("Unavailable root must not authorize removals");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("unavailable"));
        }
    }
}
