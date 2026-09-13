package com.foobnix.ui2.adapter;

import com.foobnix.dao2.FileMeta;
import java.util.Arrays;
import java.util.List;

/** Immutable values allow diffing rows even when the DAO reuses mutable entities. */
public final class BookRowSnapshot {
    public final List<?> identity;
    public final List<?> content;

    public BookRowSnapshot(FileMeta row) {
        identity = row.getPath() == null
                ? Arrays.asList(row.getCusType(), row.getTitle(), row.getSequence())
                : Arrays.asList(row.getCusType(), row.getPath());
        content = Arrays.asList(row.getTitle(), row.getAuthor(), row.getSequence(), row.getGenre(),
                row.getChild(), row.getAnnotation(), row.getSIndex(), row.getCusType(), row.getExt(),
                row.getSize(), row.getDate(), row.getDateTxt(), row.getSizeTxt(), row.getPathTxt(),
                row.getIsStar(), row.getIsStarTime(), row.getIsRecent(), row.getIsRecentTime(),
                row.getIsRecentProgress(), row.getIsSearchBook(), row.getLang(), row.getTag(),
                row.getPages(), row.getKeyword(), row.getYear(), row.getState(), row.getPublisher(),
                row.getIsbn(), row.getParentPath(), row.getFilesCount(), row.getReadCount());
    }
}
