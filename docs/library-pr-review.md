# Library feature PR review — 2026-09-13

The identified code blockers and WorkManager integration gaps are fixed. Live
new-book arrival and scrolling during extraction remain to be verified before
accepting the full performance requirement. Scope is the combined patch against
`2b277c87091f`, including currently untracked helpers and tests.

## Changes

- Failed or incomplete workers return failure and release the busy UI. Active
  workers are counted so a canceled older worker cannot clear its replacement's
  running state. Stopped scans cannot send delayed searching/extracting messages.
- Cover warmup replaces running work and takes a fresh library snapshot.
- Full discovery detects changed sidecar revisions and unregisters removed OPFs.
  Generic revisions include metadata and cover properties independently of ebooks.
- Library receivers follow the view lifecycle. Grouped results discard flat-list
  positions and pending diff state.
- EPUB cache destinations are selected during processing, with captured options
  and replacement rules used throughout transformation. There is no shared
  source-to-destination registration. Temporary output names are unique, and
  failed or canceled processing does not publish an incomplete output.
- Hyphenator and tokenizer state is per thread; the shared word cache includes
  language in its key and supports concurrent access.
- SAF cache files have leases and reservations covering pending opens, processing,
  readers and asynchronous metadata reads. Eviction respects these lifetimes.
  Publication uses atomic rename, preserving existing output when replacement
  fails. Abandoned temporary files are cleaned before new temporary work starts.
  The cache size limit is soft while files remain in use.
- Active or unknown-size Calibre journals trigger folder scanning. WAL-mode DBs
  also trigger fallback based on the SQLite header, even if the provider omits
  the journal. Synced database files are never modified.

## Verification

44 JVM tests pass. Both APKs build and the arm64 APK passes native-library
validation. Builds were installed using `adb install -r`, preserving data.

The final full Android suite passed: 110 fixture tests passed and two optional
provider checks skipped. After the preference-file teardown fix, both affected
classes passed again (31 tests).

New tests exercise real EPUB ZIP transformation after live settings change,
independent processing destinations, leased-file eviction, failed atomic
publication, temporary-file recovery, journal fallback and WAL omitted by a
provider. WorkManager integration uses an in-memory database and a greedy
scheduler, without touching the application's WorkManager database or scheduling
system jobs. Replacement warms a newly added fixture book; a canceled worker
with simulated noninterruptible I/O cannot clear its replacement's busy state.
Fixture shutdown drains completion callbacks before closing its database.

## Real-library results

The opt-in phone benchmark passed with fast scanning temporarily enabled; the
prior preference was restored. Both rescans reused the index for 468 files,
with zero changed folders and zero exception-author folders:

- First rescan: 9,158 ms.
- Second rescan: 3,547 ms.
- Reading-progress processing: 144 ms and 102 ms.
- Recent history count was preserved after both scans.

These are index reuse measurements, not measurements of first-time index
construction. The Library scrolling attempt produced no valid Librera frames
because another application was showing; gesture testing was stopped. No
scrolling performance result is claimed. The awake Recent view showed Fluent
Python with its correct author, title, 1,011 pages and 80% progress.

## Remaining live checks

Measure discovery and cover arrival for a freshly imported Calibre book after
server sync, and prolonged Library scrolling during extraction. Fixture addition
coverage verifies folder-query budgets and warmup replacement, not server sync
latency. The repaired library now uses the complete fast path, but the measured
3.5-second rescan is not instantaneous. Root/provider/database refresh remains
part of a correct rescan.

Run `scripts/test-library-regressions.sh --offline --device DEVICE_SERIAL`, followed
by the provider benchmark and interactive checks in `library-regression-tests.md`.
The runner validates existing native libraries; it does not build them.

## Final verification — 2026-09-20

The offline JVM suite, both APK builds, and arm64 native-library validation passed.
The full Android fixture run passed 110 tests and skipped the two opt-in provider
tests. The provider probe then passed against the configured Nextcloud Calibre root:
the returned SQLite database contained 444 books and 477 formats. Two real rescans
with fast scanning temporarily enabled passed in 4,714 ms and 3,650 ms. Both retained
the Calibre index and Recent history. The original fast-scan preference was restored.

The fixture run exposed two test classes that cleared temporary preferences but
left empty preference files. Their teardown now deletes the files; the affected
classes were rerun after the fix.

No new book was synced during this session, so server-sync-to-cover latency remains
unmeasured. Prolonged scrolling during extraction also remains unmeasured because
there was no active extraction to overlap with scrolling. These are field performance
checks, not failures of the completed automated and real-library rescan checks.
