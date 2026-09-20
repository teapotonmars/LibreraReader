package com.foobnix.pdf.info;

import android.net.Uri;
import androidx.test.platform.app.InstrumentationRegistry;
import com.foobnix.dao2.FileMeta;
import com.foobnix.model.AppProfile;
import com.foobnix.model.AppState;
import com.foobnix.ui2.AppDB;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.UUID;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class CoverCacheSignatureTest {
    @Before public void setUp() {
        AppProfile.init(InstrumentationRegistry.getInstrumentation().getTargetContext());
    }

    @Test public void sourceRevisionChangesForPathOnlyCallers() throws Exception {
        File local = new File(InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getCacheDir(), "cover-revision-" + UUID.randomUUID() + ".epub");
        String saf = "content://cover-revision/document/" + UUID.randomUUID();
        try {
            try (FileOutputStream out = new FileOutputStream(local)) { out.write(1); }
            String localBefore = IMG.currentSourceRevision(local.getPath());
            try (FileOutputStream out = new FileOutputStream(local, true)) { out.write(2); }
            assertNotEquals(localBefore, IMG.currentSourceRevision(local.getPath()));

            FileMeta book = new FileMeta(saf);
            book.setSize(100L);
            book.setDate(123L);
            AppDB.get().saveAll(Collections.singletonList(book));
            String safBefore = IMG.currentSourceRevision(saf);
            book.setDate(124L);
            AppDB.get().update(book);
            assertNotEquals(safBefore, IMG.currentSourceRevision(saf));
        } finally {
            local.delete();
            AppDB.get().deleteBy(saf);
        }
    }

    @Test public void sidecarAndCoverSettingsChangeGlideSignature() {
        String path = "content://cover-revision/document/" + UUID.randomUUID();
        String url = IMG.getCoverUrl(path);
        boolean previousCalibre = AppState.get().isUseCalibreOpf;
        boolean previousEffect = AppState.get().isBookCoverEffect;
        try {
            SafOpfRegistry.Entry first = new SafOpfRegistry.Entry(Uri.parse(path + "/metadata.opf"),
                    Collections.emptyMap(), "cover-one");
            SafOpfRegistry.Entry second = new SafOpfRegistry.Entry(first.opfUri,
                    Collections.emptyMap(), "cover-two");
            SafOpfRegistry.register(path, first);
            String firstKey = IMG.coverCacheSignature(url, "100:123",
                    SafOpfRegistry.get(path).revision);
            SafOpfRegistry.register(path, second);
            String secondKey = IMG.coverCacheSignature(url, "100:123",
                    SafOpfRegistry.get(path).revision);
            assertNotEquals(firstKey, secondKey);
            AppState.get().isUseCalibreOpf = !previousCalibre;
            assertNotEquals(secondKey, IMG.coverCacheSignature(url, "100:123",
                    SafOpfRegistry.get(path).revision));
            AppState.get().isUseCalibreOpf = previousCalibre;
            AppState.get().isBookCoverEffect = !previousEffect;
            assertNotEquals(secondKey, IMG.coverCacheSignature(url, "100:123",
                    SafOpfRegistry.get(path).revision));
        } finally {
            AppState.get().isUseCalibreOpf = previousCalibre;
            AppState.get().isBookCoverEffect = previousEffect;
            SafOpfRegistry.unregister(path);
        }
    }
}
