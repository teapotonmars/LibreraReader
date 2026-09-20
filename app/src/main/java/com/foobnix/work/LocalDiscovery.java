package com.foobnix.work;

import com.foobnix.dao2.FileMeta;
import com.foobnix.model.AppState;
import com.foobnix.pdf.info.io.SearchCore;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** Local listing that distinguishes a complete empty folder from an unreadable one. */
final class LocalDiscovery {
    private LocalDiscovery() {}

    static void collect(File root, List<String> extensions, List<FileMeta> output,
                        BooleanSupplier stopped) throws IOException {
        collect(root, extensions, output, stopped, File::listFiles);
    }

    static void collect(File root, List<String> extensions, List<FileMeta> output,
                        BooleanSupplier stopped, Function<File, File[]> list) throws IOException {
        if (!root.isDirectory()) throw new IOException("Scan root unavailable: " + root);
        if ("/".equals(root.getPath())) throw new IOException("Root directory scan is disabled");
        walk(root, extensions, output, stopped, new boolean[1], list);
    }

    private static void walk(File folder, List<String> extensions, List<FileMeta> output,
                             BooleanSupplier stopped, boolean[] skippedAndroidData,
                             Function<File, File[]> list) throws IOException {
        if (stopped.getAsBoolean()) throw new IOException("Local scan cancelled");
        File[] children = list.apply(folder);
        if (children == null) throw new IOException("Cannot list local folder: " + folder);
        for (File child : children) {
            if (stopped.getAsBoolean()) throw new IOException("Local scan cancelled");
            if (child.isHidden()) continue;
            if (child.isDirectory()) {
                if (AppState.get().isSkipFolderWithNOMEDIA
                        && new File(child, SearchCore.NOMEDIA).isFile()) continue;
                if (!skippedAndroidData[0] && child.getPath().endsWith("/Android/data")) {
                    skippedAndroidData[0] = true;
                    continue;
                }
                walk(child, extensions, output, stopped, skippedAndroidData, list);
            } else if (child.isFile()) {
                if (child.length() > 0 && SearchCore.endWith(child.getName(), extensions)) {
                    FileMeta book = new FileMeta(child.getPath());
                    book.setTitle(child.getName());
                    output.add(book);
                }
            } else {
                // A path returned by the parent but no longer readable is not an absence proof.
                throw new IOException("Cannot inspect local entry: " + child);
            }
        }
    }
}
