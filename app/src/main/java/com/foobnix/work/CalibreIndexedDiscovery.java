package com.foobnix.work;

import android.net.Uri;
import com.foobnix.dao2.FileMeta;
import com.foobnix.pdf.info.ExtUtils;
import com.foobnix.pdf.info.SafOpfRegistry;
import com.foobnix.pdf.info.io.SearchCore;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/** Records exactly the folders and files a completed SAF traversal can validate. */
final class CalibreIndexedDiscovery {
    private CalibreIndexedDiscovery() {}

    static void collect(Uri folder, String relative, List<FileMeta> output,
                        Map<String, SafOpfRegistry.Entry> sidecars, BooleanSupplier stopped,
                        SafDiscovery.DirectoryListing listing, CalibreLibraryIndex index,
                        SafDiscovery.BatchListener listener)
            throws IOException, InterruptedException {
        Map<Uri, String> relativeByUri = new ConcurrentHashMap<>();
        relativeByUri.put(folder, relative);
        SafDiscovery.collect(folder, output, sidecars, stopped, (parent, cancelled) -> {
            List<SafDocuments.Document> children = listing.list(parent, cancelled);
            if (index != null) {
                String base = relativeByUri.get(parent);
                if (base == null) throw new IOException("Unknown indexed SAF folder: " + parent);
                index.folder(base, parent);
                for (SafDocuments.Document child : children) {
                    if (child.name == null) continue;
                    String childPath = base.isEmpty() ? child.name : base + "/" + child.name;
                    if (child.directory) relativeByUri.put(child.uri, childPath);
                    else if (SearchCore.endWith(child.name, ExtUtils.seachExts))
                        index.file(childPath, child.book());
                }
            }
            return children;
        }, listener);
    }
}
