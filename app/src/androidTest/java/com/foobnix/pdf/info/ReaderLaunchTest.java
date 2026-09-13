package com.foobnix.pdf.info;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.net.Uri;
import androidx.test.platform.app.InstrumentationRegistry;
import com.foobnix.model.AppSP;
import com.foobnix.model.AppState;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Records real reader dispatch intents without opening readers or modifying progress. */
public class ReaderLaunchTest {
    private File source;
    private Uri uri;
    private int priorMode;
    private RecordingContext context;
    private static class RecordingContext extends ContextWrapper {
        final List<Intent> intents = Collections.synchronizedList(new ArrayList<>());
        RecordingContext(Context context) { super(context); }
        @Override public void startActivity(Intent intent) { intents.add(intent); }
    }
    @Before public void setUp() throws Exception {
        context = new RecordingContext(InstrumentationRegistry.getInstrumentation().getTargetContext());
        source = File.createTempFile("reader-dispatch-", ".pdf", context.getCacheDir());
        uri = Uri.fromFile(source); priorMode = AppSP.get().readingMode;
    }
    @After public void tearDown() { source.delete(); AppSP.get().readingMode = priorMode; }
    private void checkMode(int mode, String activity) {
        AppSP.get().readingMode = mode;
        ExtUtils.showDocumentInner(context, uri, 0.25f, null, "content://fixture/book-a");
        ExtUtils.showDocumentInner(context, uri, 0, null, "content://fixture/book-b");
        ExtUtils.showDocumentInner(context, uri, 0, null);
        assertEquals(3, context.intents.size());
        assertEquals("content://fixture/book-a", context.intents.get(0).getStringExtra("SAF_ORIGINAL_URI"));
        assertEquals("content://fixture/book-b", context.intents.get(1).getStringExtra("SAF_ORIGINAL_URI"));
        assertFalse(context.intents.get(2).hasExtra("SAF_ORIGINAL_URI"));
        for (Intent intent : context.intents) { assertEquals(uri, intent.getData()); assertTrue(intent.getComponent().getClassName().endsWith(activity)); }
    }
    @Test public void scrollReaderDispatchKeepsPhysicalFileAndPerBookIdentity() { checkMode(AppState.READING_MODE_SCROLL, "VerticalViewActivity"); }
    @Test public void bookReaderDispatchKeepsPhysicalFileAndPerBookIdentity() { checkMode(AppState.READING_MODE_BOOK, "HorizontalViewActivity"); }
    @Test public void concurrentDispatchCannotStealAnotherBooksIdentity() throws Exception {
        AppSP.get().readingMode = AppState.READING_MODE_BOOK;
        var executor = Executors.newFixedThreadPool(3);
        try {
            for (int i=0; i<100; i++) {
                final String book = "content://fixture/book-" + i;
                executor.submit(() -> ExtUtils.showDocumentInner(context, uri, 0, null, book));
            }
            executor.shutdown(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(100, context.intents.size());
            assertEquals(100, context.intents.stream().map(intent -> intent.getStringExtra("SAF_ORIGINAL_URI")).distinct().count());
            for (Intent intent : context.intents) assertEquals(uri, intent.getData());
        } finally { executor.shutdownNow(); }
    }
}
