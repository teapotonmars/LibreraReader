package com.foobnix.work;

import android.database.ContentObserver;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import org.junit.Test;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class SafFolderQueryTest {
    private final Uri children = Uri.parse("content://fixture/tree/root/document/root/children");
    private static class Listing extends MatrixCursor {
        final Bundle extras = new Bundle();
        Listing(boolean loading) {
            super(new String[]{"name"});
            addRow(new Object[]{loading ? "old.epub" : "new.epub"});
            extras.putBoolean(DocumentsContract.EXTRA_LOADING, loading);
        }
        @Override public Bundle getExtras() { return extras; }
    }
    private static class Provider implements SafFolderQuery.Source {
        final List<Uri> observations = new ArrayList<>();
        final List<Listing> issued = new ArrayList<>();
        ContentObserver observer;
        int calls;
        boolean unregistered;
        boolean notify = true;
        boolean staysLoading;
        boolean failRequery;
        AtomicBoolean cancel;
        public void observe(Uri uri, ContentObserver observer) {
            observations.add(uri); this.observer = observer;
        }
        public void unobserve(ContentObserver observer) { unregistered = true; }
        public Cursor query(Uri uri, String[] columns) {
            assertEquals("Observe both URIs before first query", 2, observations.size());
            if (calls++ > 0) {
                assertFalse("Closing the old cursor cancels provider loading", issued.get(issued.size()-1).isClosed());
                if (failRequery) throw new SecurityException("permission revoked");
            }
            Listing listing = new Listing(calls == 1 || staysLoading);
            issued.add(listing);
            if (cancel != null) cancel.set(true);
            if (notify) observer.onChange(false); // Deliberately before query returns.
            return listing;
        }
    }
    @Test public void notificationBeforeQueryReturnsStillFindsNewBook() throws Exception {
        Provider provider = new Provider();
        try (Cursor result = SafFolderQuery.query(provider, children, null, () -> false, 1000)) {
            assertTrue(result.moveToFirst()); assertEquals("new.epub", result.getString(0));
            assertTrue(provider.issued.get(0).isClosed());
            assertFalse(result.isClosed()); assertTrue(provider.unregistered);
            assertEquals(Uri.parse("content://fixture/document/root"), provider.observations.get(0));
            assertEquals(children, provider.observations.get(1));
        }
    }
    @Test public void lostNotificationGetsFinalQueryRatherThanPartialListing() throws Exception {
        Provider provider = new Provider(); provider.notify = false;
        try (Cursor result = SafFolderQuery.query(provider, children, null, () -> false, 10)) {
            assertFalse(result.getExtras().getBoolean(DocumentsContract.EXTRA_LOADING));
            assertEquals(2, provider.calls);
        }
    }
    @Test public void timeoutClosesAllCursorsAndObservers() throws Exception {
        Provider provider = new Provider(); provider.notify = false; provider.staysLoading = true;
        IOException failure = assertThrows(IOException.class,
                () -> SafFolderQuery.query(provider, children, null, () -> false, 10));
        assertTrue(failure.getMessage().contains("Timed out"));
        for (Listing cursor : provider.issued) assertTrue(cursor.isClosed());
        assertTrue(provider.unregistered);
    }
    @Test public void cancellationNeverReturnsPartialRows() throws Exception {
        Provider provider = new Provider(); provider.cancel = new AtomicBoolean();
        assertThrows(IOException.class, () -> SafFolderQuery.query(provider, children, null,
                provider.cancel::get, 1000));
        assertTrue(provider.issued.get(0).isClosed()); assertTrue(provider.unregistered);
    }
    @Test public void permissionFailureDuringRequeryCleansUp() throws Exception {
        Provider provider = new Provider(); provider.failRequery = true;
        assertThrows(SecurityException.class,
                () -> SafFolderQuery.query(provider, children, null, () -> false, 1000));
        assertTrue(provider.issued.get(0).isClosed()); assertTrue(provider.unregistered);
    }
    @Test public void nullListingFailsInsteadOfLookingLikeEmptyLibrary() throws Exception {
        Provider provider = new Provider() {
            @Override public Cursor query(Uri uri, String[] columns) { return null; }
        };
        assertThrows(IOException.class, () -> SafFolderQuery.query(provider, children, null, () -> false, 100));
        assertTrue(provider.unregistered);
    }
    @Test public void providerErrorFailsAndClosesCursor() throws Exception {
        Provider provider = new Provider() {
            @Override public Cursor query(Uri uri, String[] columns) {
                Listing listing = (Listing) super.query(uri, columns);
                listing.extras.putString(DocumentsContract.EXTRA_ERROR, "offline"); return listing;
            }
        };
        assertThrows(IOException.class, () -> SafFolderQuery.query(provider, children, null, () -> false, 100));
        assertTrue(provider.issued.get(0).isClosed()); assertTrue(provider.unregistered);
    }
    @Test public void interruptionClosesLoadingCursor() throws Exception {
        Provider provider = new Provider(); provider.notify = false;
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class,
                    () -> SafFolderQuery.query(provider, children, null, () -> false, 1000));
        } finally { Thread.interrupted(); }
        assertTrue(provider.issued.get(0).isClosed()); assertTrue(provider.unregistered);
    }
}
