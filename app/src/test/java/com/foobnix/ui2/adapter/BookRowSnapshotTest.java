package com.foobnix.ui2.adapter;

import com.foobnix.dao2.FileMeta;
import org.junit.Test;
import static org.junit.Assert.*;

public class BookRowSnapshotTest {
    @Test public void readingProgressFavoritesAndCoverRevisionsTriggerRowUpdates() {
        FileMeta book = new FileMeta("content://provider/book");
        java.util.List<java.util.function.Consumer<FileMeta>> edits = java.util.Arrays.asList(
                row -> row.setIsRecentProgress(0.5f), row -> row.setIsStar(true),
                row -> row.setIsRecent(true), row -> row.setSize(200L), row -> row.setDate(300L),
                row -> row.setLang("en"), row -> row.setState(2), row -> row.setAnnotation("new annotation"));
        for (java.util.function.Consumer<FileMeta> edit : edits) {
            BookRowSnapshot before = new BookRowSnapshot(book); edit.accept(book);
            BookRowSnapshot after = new BookRowSnapshot(book);
            assertEquals(before.identity, after.identity); assertNotEquals(before.content, after.content);
        }
    }

    @Test public void identicalDetachedRowsHaveIdenticalSnapshots() {
        FileMeta first = new FileMeta("book"); first.setTitle("Title"); first.setAuthor("Author");
        FileMeta second = new FileMeta("book"); second.setTitle("Title"); second.setAuthor("Author");
        assertEquals(new BookRowSnapshot(first).identity, new BookRowSnapshot(second).identity);
        assertEquals(new BookRowSnapshot(first).content, new BookRowSnapshot(second).content);
    }
    @Test public void detectsMetadataChangeOnSameDaoEntity() {
        FileMeta book = new FileMeta("content://provider/book");
        book.setTitle("book.epub");
        BookRowSnapshot before = new BookRowSnapshot(book);
        book.setTitle("Actual title");
        book.setAuthor("Author");
        BookRowSnapshot after = new BookRowSnapshot(book);
        assertEquals(before.identity, after.identity);
        assertNotEquals(before.content, after.content);
        assertEquals("book.epub", before.content.get(0));
    }

    @Test public void headersAndDistinctBooksHaveDistinctIdentities() {
        assertNotEquals(new BookRowSnapshot(new FileMeta("book-a")).identity,
                new BookRowSnapshot(new FileMeta("book-b")).identity);
        FileMeta first = new FileMeta();
        first.setTitle("English");
        FileMeta second = new FileMeta();
        second.setTitle("French");
        assertNotEquals(new BookRowSnapshot(first).identity, new BookRowSnapshot(second).identity);
    }
}
