package com.foobnix.pdf.info;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.util.UUID;
import android.widget.TextView;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeNotNull;

public class SafPathLabelsTest {
    /** Optional verification against an already granted document on a development device. */
    @Test public void suppliedDeviceDocumentResolvesItsFullLocation() {
        String path = InstrumentationRegistry.getArguments().getString("verifySafPath");
        assumeNotNull(path);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String label = SafPathLabels.resolve(context, path, true);
        assertFalse(label, label.contains("…"));
        assertFalse(label, label.startsWith("content:"));
        assertTrue(label, label.split(" / ").length >= 4);
        android.os.Bundle result = new android.os.Bundle();
        result.putString("stream", "Resolved SAF location: " + label + "\n");
        InstrumentationRegistry.getInstrumentation().sendStatus(0, result);
    }
    @Test public void browsingDescendantsQueriesTheCurrentDocumentNotTheTreeRoot() {
        Uri tree = DocumentsContract.buildTreeDocumentUri("labels.fixture", "opaque-account/13");
        Uri rootDocument = DocumentsContract.buildDocumentUriUsingTree(tree, "opaque-account/13");
        Uri child = DocumentsContract.buildDocumentUriUsingTree(tree, "opaque-account/27");
        assertEquals(rootDocument, SafPathLabels.documentUri(tree.toString()));
        assertEquals(child, SafPathLabels.documentUri(child.toString()));
        Uri canonical = SafDocumentIdentity.canonical(child);
        assertEquals(canonical, SafPathLabels.documentUri(canonical.toString()));
        assertEquals(child, SafPathLabels.documentUri(child.toString().replace("content://", "content:/")));
    }

    @Test public void unavailableProviderPreservesCachedReadableNameAndOriginalIdentity() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String path = DocumentsContract.buildDocumentUri("labels.fixture", UUID.randomUUID().toString()).toString();
        var cache = context.getSharedPreferences("saf-folder-labels", Context.MODE_PRIVATE);
        cache.edit().putString(path, "Nextcloud / Books / Example.epub").commit();
        try {
            assertEquals("Nextcloud / Books / Example.epub", SafPathLabels.resolve(context, path));
            assertEquals("Nextcloud / Books / Example.epub", SafPathLabels.displayName(context, path));
            assertEquals(path, SafPathLabels.documentUri(path).toString());
        } finally {
            cache.edit().remove(path).commit();
        }
    }

    @Test public void localPathsDoNotRequireProviderMetadata() {
        assertEquals("/storage/emulated/0/Books", SafPathLabels.displayName(null, "/storage/emulated/0/Books"));
        assertEquals("", SafPathLabels.displayName(null, null));
    }

    @Test public void recordedHierarchyUsesFolderNamesRatherThanOpaqueIds() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String namespace = UUID.randomUUID().toString();
        Uri tree = DocumentsContract.buildTreeDocumentUri("labels.fixture", namespace + "/13");
        Uri root = SafPathLabels.documentUri(tree.toString());
        Uri author = DocumentsContract.buildDocumentUriUsingTree(tree, namespace + "/27");
        Uri title = DocumentsContract.buildDocumentUriUsingTree(tree, namespace + "/51");
        Uri book = DocumentsContract.buildDocumentUriUsingTree(tree, namespace + "/86");
        var hierarchy = context.getSharedPreferences("saf-document-hierarchy", Context.MODE_PRIVATE);
        String rootKey = SafDocumentIdentity.canonical(root).toString();
        hierarchy.edit().putString(rootKey + "|name", "library").commit();
        try {
            SafPathLabels.rememberChildren(context, tree, java.util.Collections.singletonMap(author, "Will Storr"));
            SafPathLabels.rememberChildren(context, author, java.util.Collections.singletonMap(title, "The Status Game"));
            SafPathLabels.rememberChildren(context, title, java.util.Collections.singletonMap(book, "The Status Game.epub"));
            assertEquals("library / Will Storr / The Status Game / The Status Game.epub",
                    SafPathLabels.recordedPath(context, book, "The Status Game.epub"));
            assertEquals("library / Will Storr", SafPathLabels.recordedPath(context, author, "Will Storr"));
            assertEquals("library", SafPathLabels.recordedPath(context, root, "library"));
            assertEquals("… / Unknown.epub", SafPathLabels.recordedPath(context,
                    DocumentsContract.buildDocumentUriUsingTree(tree, namespace + "/999"), "Unknown.epub"));
        } finally {
            var editor = hierarchy.edit();
            for (Uri uri : new Uri[]{root, author, title, book}) {
                String key = SafDocumentIdentity.canonical(uri).toString();
                editor.remove(key + "|name").remove(key + "|parent");
            }
            editor.commit();
        }
    }

    @Test public void recycledRowsIgnoreLateProviderLabels() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        String path = DocumentsContract.buildDocumentUri("labels.fixture", UUID.randomUUID().toString()).toString();
        var cache = context.getSharedPreferences("saf-folder-labels", Context.MODE_PRIVATE);
        cache.edit().putString(path, "Nextcloud / Books").commit();
        AtomicReference<TextView> row = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        try {
            instrumentation.runOnMainSync(() -> {
                TextView view = new TextView(context);
                row.set(view);
                SafPathLabels.bind(view, path, " (12)");
                assertEquals("Nextcloud / Books (12)", view.getText().toString());
                SafPathLabels.bind(view, "/storage/emulated/0/Other");
                SafPathLabels.refresh(context, path, ignored -> completed.countDown());
            });
            assertTrue(completed.await(10, TimeUnit.SECONDS));
            instrumentation.runOnMainSync(() ->
                    assertEquals("/storage/emulated/0/Other", row.get().getText().toString()));
        } finally {
            cache.edit().remove(path).commit();
        }
    }
}
