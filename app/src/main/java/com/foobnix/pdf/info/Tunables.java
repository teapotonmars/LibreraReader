package com.foobnix.pdf.info;

/**
 * Central knobs for concurrency-heavy work. Values chosen to be gentle on remote SAF
 * providers (Nextcloud, WebDAV proxies) since every SAF ContentResolver call is a
 * network round-trip. Bump these if you know the backend can handle more.
 */
public final class Tunables {
    private Tunables() {}

    /** Six concurrent listings overlap remote refresh latency in nested Calibre libraries. */
    public static final int SAF_DISCOVERY_PARALLELISM = 6;

    /** Fixed pool size for SearchAllBooksWorker's per-book metadata extraction pass. */
    public static final int METADATA_EXTRACTION_PARALLELISM = 2;

    /** Glide source-executor thread count for on-demand cover rendering. */
    public static final int COVER_EXTRACTION_PARALLELISM = 2;
}
