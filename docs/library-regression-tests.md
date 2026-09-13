# Library and SAF regression tests

Run the JVM suite, build both APKs, and validate the arm64 native libraries:

```sh
scripts/test-library-regressions.sh --offline
```

Omit `--offline` if Gradle dependencies have not been downloaded. Android SDK and
the built arm64 MuPDF/LAME libraries must be available. The APK check reads Gradle's
output metadata and rejects missing, placeholder, or wrong-architecture native
libraries; it does not build those native dependencies.

Run the same checks and the complete Android fixture suite on an arm64 device:

```sh
scripts/test-library-regressions.sh --offline --device DEVICE_SERIAL
```

This installs the application and test APK with `adb install -r`, preserving data.
Instrumentation restarts the application. Tests use temporary files, isolated
preferences, and unique database rows that are removed afterward. They do not
scan configured library roots or write real progress/bookmark JSON files.
Tab fixtures explicitly request the tabs so the user's “open last book” preference
does not open a real book. Controller fixtures exercise reading-state methods
without opening the native codec.

The runner checks instrumentation results explicitly: `adb` can exit successfully
even when JUnit tests fail. Its full device log is
`app/build/library-regression-device.log`. JVM reports are under
`app/build/reports/tests/testFdroidDebugUnitTest/`.

## Coverage

| Area | Tests and guarantees |
| --- | --- |
| Provider listing | `SafFolderQueryTest`: observe before querying, plain document and tree-child URI registration, cursor kept open during refresh, early/lost notifications, timeout, cancellation, interruption, permission/error/null results, and cursor/observer cleanup. |
| Incremental Calibre scans | `CalibreLibraryIndexTest`: real 500-book SQLite manifests and cache JSON; unchanged rescans list only the root and publish no extraction work; additions list only the root, author and new book folder; metadata, cover and format revisions trigger refresh; removals omit only the removed entry; new format conversion and selected-format filtering. |
| Unsafe discovery/cache reuse | The Calibre fixtures reject outdated-size streams, changed provider properties with unchanged bodies, invalid SQLite and unsafe paths, corrupt caches, unavailable new formats, incomplete folder coverage, loose root-level books and replaced author identities. Generic roots without `metadata.db` remain generic. Cancellation cannot publish cached results. Source databases remain unchanged and private snapshots are removed. |
| Partially matching Calibre libraries | A renamed format leaves the other 450 fixture books indexed while its author subtree is checked again, including subsequent additions and metadata revisions. Resolved disagreements stop requiring author checks. New unindexed author directories are checked alongside indexed books. Unchecked author subtrees cannot replace the previous verified cache. Loose root-level books still require full traversal. |
| Rescan merge and history | `LibraryMergeTest`: unchanged rows retain entity identity and metadata; revisions are compared before updating stored properties; duplicate discoveries extract once; forced Calibre changes and eager extraction are respected; removals change library visibility while preserving Recents, progress and favourites. |
| Extraction revisions | `SearchAllBooksWorkerTest`: known/unknown SAF properties, local file changes, incomplete extraction retries, and legacy temporary-name metadata repairs. |
| Scheduling | `BoundedTasksTest`: completion-order publication, bounded concurrency with 500 items, cancellation including empty input, worker failure and caller interruption, and blocked-worker cleanup. |
| Cover warming | `CoverWarmupWorkerTest`: generic SAF covers warm before visibility, incomplete entries without sidecars wait for extraction, Calibre sidecars can warm early, disabled images avoid lookups, non-library rows are excluded, and queued requests snapshot revisions. |
| Actual cover cache | `CoverCacheTest`: real SAF sidecar extraction and Glide resource caching; a warmed cover remains available after deleting its source and clearing memory; the assertion disables memory caching and allows deferred disk encoding to commit. Book and sidecar revisions invalidate cached pixels; extraction preserves authoritative metadata. No ebook or OPF download is needed for the standard cover sidecar. |
| White cover regression | `CoverTransformationTest`: an opaque pooled bitmap cannot replace a cover with a blank bitmap; transparent pixels composite onto white without losing opaque pixels. |
| Extraction paths | `SafFileLinkTest`: native extraction paths retain original basenames, simultaneous equal names use independent descriptors/directories, and closing cleans up links without deleting sources. |
| Download staging | `SafStagingCacheTest`: complete downloads are reused by revision; changed or unknown revisions download anew; incomplete downloads preserve the previous good cache and remove their temporary files; concurrent opens overlap ten times and cannot delete one another's active downloads. Uses a local Android ContentProvider transport, not the user's provider. Requires API 29+. |
| Reader identity/title | `ReaderIdentityTest`: PDF/EPUB titles and original filename fallback, local-file compatibility, metadata lookup without creating staged rows, and reading settings keyed by original URI across staged versions. Legacy staged progress seeds an unused original entry; existing canonical progress, including explicitly saved 0%, is retained. |
| Reader dispatch | `ReaderLaunchTest`: both reader intents preserve the physical codec file and each request's original URI; local opens cannot inherit SAF identity; 100 concurrent dispatches cannot steal another request's identity. Intents are recorded rather than launched. |
| Reader lifecycle/restart | `ReaderLifecycleTest` exercises the controller's resume/progress, original metadata and export display name, bookmark creation across staged versions, and local-book identity reset. `ReaderRestartStateTest` verifies original URI and physical file serialization, removal of old SAF identity, and compatibility with older settings. |
| Progress and size display | `SharedProgressCacheTest` verifies that saving SAF progress replaces the full-path cache entry used by library loads, using an isolated progress file. `AppDbSnapshotTest` verifies progress-only updates reach cached and detached rows without changing metadata or favourites. `BookSizeTextTest` covers SAF byte counts without formatted labels, unknown sizes, page counts and existing local labels. |
| Progress loading cost | `SharedProgressBatchTest` verifies a cold 500-book batch reads profile snapshots once, a warm batch reads none, the latest synced progress retains local reading settings, and an older snapshot cannot overwrite a concurrent save. |
| Sidecars/EPUB metadata | `SidecarMetadataTest`: Fluent Python OPF title, author, language, absence of inferred series index, persistent sidecar URI/revision round trips, corrupt registry handling and older registry compatibility. |
| Processed EPUB cache | `EpubProcessingCacheTest`: replacement enable/hash, hyphen language and footer settings invalidate processing keys; language normalization/default override; failed processing leaves previous output/source intact; successful publication keeps source for different settings. |
| Processing concurrency | `EpubProcessingCacheTest`: captured settings survive live UI changes; a real EPUB ZIP transformation uses those options; processing destinations do not depend on shared source registration. |
| Cache lifetimes/publication | `SafCacheFilesTest`: pending opens, overlapping readers and metadata leases prevent eviction; atomic publication failure preserves good output; EPUB handoff cannot consume another reader's reservation; startup removes abandoned parts without deleting current parts. |
| WorkManager replacement | `MessageWorkerLifecycleTest` and `CoverWarmupSchedulingTest`: real execution with an isolated in-memory database and no system jobs. Failures release busy state; canceled older work cannot clear replacement state or publish delayed progress; replacing warmup reaches a newly added fixture book. |
| UI population | `InitialTabLoadTest`: requests made during `onCreateView` survive view attachment, covering the empty Recents regression. `TabPopulationTest`: 100 rapid refreshes coalesce and discard stale results; view recreation rejects old results; failed queries retain queued refreshes. |
| Row/database snapshots | `BookRowSnapshotTest`: mutable DAO metadata, reading state, favourites and cover revisions trigger content changes without changing identity. `AppDbSnapshotTest`: detached reads see SQL updates without evicting cached entities; 300-row batches preserve reading state; missing lookups do not create rows. |

The former JVM storage tests now run as Android `TestSync`: external-storage
relative-path round trips, null/unrelated paths, metadata timestamps and path
identity. Three former cases had no assertions; their replacements assert actual
behaviour. The existing regex test's expected whitespace now matches its replacement
expression.

## Real-provider diagnostics and performance

Two tests skip by default. They need an explicitly supplied, already granted root:

```sh
adb -s DEVICE_SERIAL shell am instrument -w -r \
  -e class com.foobnix.work.SafLibraryProbeTest \
  -e rootUri 'CONTENT_TREE_URI' \
  com.foobnix.pro.pdf.reader.test/androidx.test.runner.AndroidJUnitRunner
```

The probe lists root sidecars, attempts optional SAF refresh, reads provider
properties and the returned database, and logs recent Calibre records. It only
writes a private temporary snapshot.

```sh
adb -s DEVICE_SERIAL shell am instrument -w -r \
  -e class com.foobnix.work.SafRescanBenchmarkTest \
  -e rootUri 'ALREADY_CONFIGURED_CONTENT_TREE_URI' \
  com.foobnix.pro.pdf.reader.test/androidx.test.runner.AndroidJUnitRunner
```

The benchmark **runs the real scan twice and changes the library index**. It logs
elapsed time/index availability and verifies that Recent history count is retained.
Add `-e requireFastIndex true` to require a saved Calibre index. A stale provider
database, incomplete folder coverage or loose root-level books prevent that path.
Path disagreements inside author subtrees preserve the matching index and require
those subtrees to be checked again. The logs report the number of author folders
checked alongside the index; index availability alone does not prove scan latency.

Add `-e enableFastScan true` to temporarily enable the Calibre fast path for the
benchmark. The prior in-process preference is restored afterward; its stored value
is not changed. Active journals or WAL-mode database headers require safe folder
fallback, even if a provider omits the journal from its listing.

Deterministic tests assert folder-query and scheduling budgets rather than brittle
wall-clock limits. They prove algorithmic work reductions for 500 books, not the
latency of a particular cloud provider.

## Limits

These fixtures do not prove behaviour of every Nextcloud/custom provider build,
notification delivery through the Android content service, warmup networking and
timing against the user's provider, native reader navigation and annotation editing, physical
process-death recovery, TTS playback/notifications, sharing/deletion against a
remote provider, or full RecyclerView binding/scrolling under load. Controller and
serialization tests cover portions of restart/progress behaviour without claiming
complete UI flows.

Before accepting performance on a device, measure actual cold and warm scans,
new-book cover arrival, and prolonged scrolling during extraction. The repaired
Calibre library still requires verification with fast scanning enabled;
fixture fast-path results do not establish near-instant rescans on that phone.
