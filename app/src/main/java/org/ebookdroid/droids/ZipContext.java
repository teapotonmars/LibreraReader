package org.ebookdroid.droids;

import com.foobnix.android.utils.LOG;
import com.foobnix.ext.CacheZipUtils;
import com.foobnix.pdf.info.SafCacheFiles;
import com.foobnix.sys.ArchiveEntry;
import com.foobnix.sys.ZipArchiveInputStream;

import org.ebookdroid.BookType;
import org.ebookdroid.core.codec.CodecContext;
import org.ebookdroid.core.codec.CodecDocument;
import org.ebookdroid.droids.mupdf.codec.PdfContext;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import androidx.core.util.Pair;

public class ZipContext extends PdfContext {

    @Override
    public CodecDocument openDocumentInner(String fileName, String password) {
        LOG.d("ZipContext begin", fileName);


        Pair<Boolean, String> member = CacheZipUtils.isSingleAndSupportEntryInner(fileName);
        if (!member.first || member.second == null) return null;
        try {
            try (SafCacheFiles.PublishedFile extracted = extractMember(fileName, member.second)) {
                String path = extracted.file.getPath();
                // The archive revision and full member path are in this name;
                // pruning may touch the file mtime without changing its content.
                SafCacheFiles.registerImmutableRevisionNamedSource(extracted.file);
                CodecContext ctx = BookType.getCodecContextByPath(path);
                LOG.d("ZipContext", "open", path);
                return ctx.openDocument(path, password);
            }
        } catch (Exception failure) {
            LOG.e(failure);
            return null;
        }
    }

    private static SafCacheFiles.PublishedFile extractMember(String archive, String member) throws Exception {
        CacheZipUtils.createAllCacheDirs();
        String suffix = member.substring(member.lastIndexOf('.') + 1);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] key = digest.digest((new File(archive).getAbsolutePath()
                + sourceRevisionKey(archive) + "|entry=" + member)
                .getBytes(StandardCharsets.UTF_8));
        StringBuilder name = new StringBuilder("archive-member-");
        for (byte value : key) name.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        File output = new File(CacheZipUtils.CACHE_BOOK_DIR, name + "." + suffix);
        return SafCacheFiles.buildFile(output, temporary -> {
            ZipArchiveInputStream input = new ZipArchiveInputStream(archive);
            try {
                ArchiveEntry entry;
                while ((entry = input.getNextEntry()) != null) {
                    if (!member.equals(entry.getName())) continue;
                    try (FileOutputStream out = new FileOutputStream(temporary)) {
                        byte[] buffer = new byte[16 * 1024];
                        int count;
                        while ((count = input.read(buffer)) != -1) out.write(buffer, 0, count);
                        out.getFD().sync();
                    }
                    return;
                }
                throw new IOException("Archive member disappeared: " + member);
            } finally {
                input.close();
            }
        });
    }

}
