package com.foobnix.pdf.info;

import androidx.test.platform.app.InstrumentationRegistry;
import com.foobnix.model.AppBook;
import com.foobnix.model.AppBookmark;
import com.foobnix.model.AppProfile;
import org.ebookdroid.common.settings.books.SharedBooks;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.io.File;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class ReadingKeyTest {
    private File root, oldProfiles, oldProgress, oldBookmarks;
    @Before public void isolateReadingState() throws Exception {
        AppProfile.init(InstrumentationRegistry.getInstrumentation().getTargetContext());
        oldProfiles = AppProfile.SYNC_FOLDER_PROFILE;
        oldProgress = AppProfile.syncProgress; oldBookmarks = AppProfile.syncBookmarks;
        root = Files.createTempDirectory(InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getCacheDir().toPath(), "reading-key-").toFile();
        File device = new File(root, AppProfile.DEVICE_PREFIX + "fixture"); assertTrue(device.mkdir());
        AppProfile.SYNC_FOLDER_PROFILE = root;
        AppProfile.syncProgress = new File(device, AppProfile.APP_PROGRESS_JSON);
        AppProfile.syncBookmarks = new File(device, AppProfile.APP_BOOKMARKS_JSON);
        SharedBooks.cache.clear(); SharedBooks.written.clear();
    }
    @After public void restoreReadingState() {
        AppProfile.SYNC_FOLDER_PROFILE = oldProfiles;
        AppProfile.syncProgress = oldProgress; AppProfile.syncBookmarks = oldBookmarks;
        SharedBooks.cache.clear(); SharedBooks.written.clear();
        BookCacheLeases.evictTree(root);
    }
    @Test public void localKeysKeepTheExactFilenameBehavior() {
        assertEquals("Dune.epub", ReadingKey.of("/sdcard/Books/Dune.epub"));
        for (String path : new String[]{"", "Dune.epub", "/a/", "/a/%20.epub", "a\\b.epub"})
            assertEquals(ExtUtils.getFileName(path), ReadingKey.of(path));
        assertEquals(ExtUtils.getFileName(null), ReadingKey.of(null));
    }
    @Test public void archiveKeysUseTheDecodedEntryFilename() {
        String identity = ArchiveMemberIdentity.create("/books/classics.zip", "Tolstoy/War and Peace.fb2");
        assertEquals("War and Peace.fb2", ReadingKey.of(identity));
    }
    @Test public void providerKeysUseDisplayNamesAndKeepAuthoritiesIndependent() {
        ReadingKey.remember("content://a/document/42", "Dune.epub");
        ReadingKey.remember("content://b/document/42", "Foundation.epub");
        assertEquals("Dune.epub", ReadingKey.of("content://a/document/42"));
        assertEquals("Foundation.epub", ReadingKey.of("content://b/document/42"));
        save("content://a/document/42", 0.7f);
        SharedBooks.cache.clear();
        assertEquals(0.7f, SharedBooks.load("/sdcard/Books/Dune.epub").p, 0.001f);
        assertEquals(0, SharedBooks.load("content://b/document/42").p, 0.001f);
    }
    @Test public void masterFilenameProgressAndBookmarksSurviveProviderAndArchiveOpening() {
        ReadingKey.remember("content://browse/document/42", "Dune.epub");
        assertMasterState("/Attachments/Dune.epub", "content://browse/document/42");
        assertMasterState("/Recent/War and Peace.fb2",
                ArchiveMemberIdentity.create("/books/classics.zip", "Tolstoy/War and Peace.fb2"));
    }
    private void assertMasterState(String masterPath, String identity) {
        save(masterPath, 0.6f);
        BookmarksData.get().add(new AppBookmark(masterPath, "master bookmark", 0.4f));
        SharedBooks.cache.clear();
        assertEquals(0.6f, SharedBooks.load(identity).p, 0.001f);
        var bookmarks = BookmarksData.get().getBookmarksByBook(identity);
        assertEquals(1, bookmarks.size()); assertEquals(0.4f, bookmarks.get(0).p, 0.001f);
        assertEquals(identity, bookmarks.get(0).path);
    }
    private void save(String path, float progress) {
        AppBook book = new AppBook(path); book.p = progress; book.t = System.currentTimeMillis();
        SharedBooks.saveAsync(book); // This existing API writes synchronously.
        assertTrue(AppProfile.syncProgress.isFile());
    }
    @Test public void unknownProviderNameFallsBackToDecodedDocumentId() {
        assertEquals("account/row", ReadingKey.of("content://unknown/document/account%2Frow"));
    }
}
