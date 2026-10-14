package com.foobnix.pdf.info;

import android.app.AlertDialog;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.inspector.WindowInspector;
import android.widget.CheckBox;
import android.widget.ListView;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import com.foobnix.dao2.FileMeta;
import com.foobnix.model.AppBook;
import com.foobnix.model.AppData;
import com.foobnix.model.AppProfile;
import com.foobnix.model.AppBookmark;
import com.foobnix.model.Tags2;
import com.foobnix.pdf.info.widget.FileInformationDialog;
import com.foobnix.pdf.info.widget.ShareDialog;
import com.foobnix.ui2.AppDB;
import com.foobnix.ui2.adapter.DefaultListeners;
import com.foobnix.ui2.adapter.FileMetaAdapter;
import org.ebookdroid.common.settings.books.SharedBooks;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.io.File;
import java.io.FileNotFoundException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@SdkSuppress(minSdkVersion = 29)
public class ProviderBookActionsTest {
    private ProviderActionsTestActivity activity;
    private Provider provider;
    private File root, staged;
    private FileMeta book;
    private final Map<Field, File> oldFiles = new HashMap<>();
    private AlertDialog menu;
    private FileMetaAdapter adapter;

    private static class Provider extends ContentProvider {
        File remote;
        volatile Uri deleted;
        String mime = "application/epub+zip";
        @Override public boolean onCreate() { return true; }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
            MatrixCursor cursor = new MatrixCursor(projection);
            Object[] row = new Object[projection.length];
            for (int i = 0; i < projection.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(projection[i])) row[i] = "Dune.epub";
                if (OpenableColumns.SIZE.equals(projection[i])) row[i] = 3;
            }
            cursor.addRow(row); return cursor;
        }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            return ParcelFileDescriptor.open(remote, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        @Override public String getType(Uri uri) { return mime; }
        @Override public Bundle call(String method, String arg, Bundle extras) {
            assertEquals("android:deleteDocument", method);
            deleted = extras.getParcelable("uri");
            return new Bundle();
        }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String selection, String[] args) { throw new AssertionError("Use deleteDocument"); }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    }

    @Before public void setUp() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var target = instrumentation.getTargetContext();
        AppProfile.init(target);
        root = Files.createTempDirectory(target.getCacheDir().toPath(), "provider-actions-").toFile();
        File profile = new File(root, AppProfile.DEVICE_PREFIX + "fixture"); assertTrue(profile.mkdir());
        Map<String, String> profileNames = new HashMap<>();
        profileNames.put("syncProgress", AppProfile.APP_PROGRESS_JSON);
        profileNames.put("syncBookmarks", AppProfile.APP_BOOKMARKS_JSON);
        profileNames.put("syncFavorite", AppProfile.APP_FAVORITE_JSON);
        profileNames.put("syncRecent", AppProfile.APP_RECENT_JSON);
        profileNames.put("syncExclude", AppProfile.APP_EXCLUDE_JSON);
        profileNames.put("syncTags2", AppProfile.APP_TAGS_JSON2);
        for (String name : new String[]{"SYNC_FOLDER_PROFILE", "SYNC_FOLDER_DEVICE_PROFILE", "syncProgress",
                "syncBookmarks", "syncFavorite", "syncRecent", "syncExclude", "syncTags2", "syncPlaylist"}) {
            Field field = AppProfile.class.getField(name); oldFiles.put(field, (File) field.get(null));
            File replacement = name.equals("SYNC_FOLDER_PROFILE") ? root
                    : name.equals("SYNC_FOLDER_DEVICE_PROFILE") ? profile : new File(profile, profileNames.getOrDefault(name, name));
            field.set(null, replacement);
        }
        assertTrue(AppProfile.syncPlaylist.mkdir());
        provider = new Provider(); provider.remote = new File(root, "remote.epub");
        Files.write(provider.remote.toPath(), new byte[]{1, 2, 3});
        ProviderInfo info = new ProviderInfo(); info.authority = "actions.fixture";
        provider.attachInfo(target, info);
        ProviderActionsTestActivity.resolver = ContentResolver.wrap(provider);
        activity = (ProviderActionsTestActivity) instrumentation.startActivitySync(
                new Intent(target, ProviderActionsTestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        String path = "content://actions.fixture/document/profile." + UUID.randomUUID();
        book = AppDB.get().getOrCreate(path); book.setPathTxt("Dune.epub"); book.setTitle("Dune");
        book.setIsSearchBook(true); book.setIsStar(false); AppDB.get().save(book);
        staged = new File(root, "source-hash-3-1000.epub"); Files.write(staged.toPath(), new byte[]{1, 2, 3});
        adapter = new FileMetaAdapter(); adapter.getItemsList().add(book);
    }

    @After public void tearDown() throws Exception {
        main(() -> {
            if (menu != null) menu.dismiss();
            for (View window : WindowInspector.getGlobalWindowViews()) {
                View close = window.findViewById(android.R.id.button2);
                if (close != null) close.performClick();
            }
            activity.finish();
        });
        AppDB.get().deleteBy(book.getPath());
        ProviderActionsTestActivity.resolver = null;
        for (var old : oldFiles.entrySet()) old.getKey().set(null, old.getValue());
        SharedBooks.cache.clear(); SharedBooks.written.clear(); BookCacheLeases.evictTree(root);
    }

    private void main(Runnable action) {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.runOnMainSync(action);
        instrumentation.waitForIdleSync();
    }
    private void show(boolean reader) {
        main(() -> {
            if (menu != null) menu.dismiss();
            Intent launch = new Intent().setData(Uri.fromFile(staged));
            if (reader) SafReaderLaunch.attach(activity, launch, book.getPath());
            activity.setIntent(launch);
            menu = reader ? ShareDialog.show(activity, staged, () -> fail("Staged delete callback used"), -1, null, null)
                    : ShareDialog.show(activity, book, () -> ExtUtils.deleteProviderBook(activity, book), -1, null, null);
        });
        assertNotNull(menu);
    }
    private int position(int stringId) {
        String label = activity.getString(stringId);
        for (int i = 0; i < menu.getListView().getAdapter().getCount(); i++)
            if (menu.getListView().getAdapter().getItem(i).toString().contains(label)) return i;
        return -1;
    }
    private void click(int stringId) {
        main(() -> {
            int position = position(stringId); assertTrue("Missing action " + activity.getString(stringId), position >= 0);
            menu.getListView().performItemClick(null, position, position);
        });
    }
    private Intent handoff() throws Exception {
        Intent intent = activity.handoffs.poll(5, TimeUnit.SECONDS); assertNotNull(intent);
        if (Intent.ACTION_CHOOSER.equals(intent.getAction())) intent = intent.getParcelableExtra(Intent.EXTRA_INTENT);
        return intent;
    }
    private void checkProviderIntent(Intent intent, boolean send) {
        assertEquals(send ? Intent.ACTION_SEND : Intent.ACTION_VIEW, intent.getAction());
        assertEquals(Uri.parse(book.getPath()), send ? intent.getParcelableExtra(Intent.EXTRA_STREAM) : intent.getData());
        assertTrue((intent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
        assertEquals(0, intent.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        assertNotNull(intent.getClipData()); assertEquals(Uri.parse(book.getPath()), intent.getClipData().getItemAt(0).getUri());
        assertEquals("application/epub+zip", intent.getType());
        assertFalse(intent.toUri(0).contains("FileProvider"));
    }
    @Test public void libraryAndReaderSendAndOpenTheProviderUriWithReadGrants() throws Exception {
        for (boolean reader : new boolean[]{false, true}) {
            show(reader); click(R.string.send_file); checkProviderIntent(handoff(), true);
            show(reader); click(R.string.open_with); checkProviderIntent(handoff(), false);
        }
        provider.mime = null;
        assertEquals(ExtUtils.getMimeType("Dune.epub"), ExtUtils.providerSendIntent(activity, book).getType());
    }
    @Test public void libraryAndReaderHideWritingActionsAndOfferProviderLibraryActions() {
        for (boolean reader : new boolean[]{false, true}) {
            show(reader);
            main(() -> {
                for (int id : new int[]{R.string.copy, R.string.sync_book, R.string.upload_to_cloud, R.string.edit, R.string.convert_to})
                    assertEquals(activity.getString(id), -1, position(id));
                for (int id : new int[]{R.string.delete, R.string.remove_from_library, R.string.add_tags,
                        R.string.add_to_playlist, R.string.delete_reading_progress, R.string.file_info, R.string.add_to_favorites})
                    assertTrue(activity.getString(id), position(id) >= 0);
            });
        }
    }
    @Test public void libraryAndReaderRemoveTheCanonicalLibraryRowAndDeleteFilenameProgress() {
        for (boolean reader : new boolean[]{false, true}) {
            AppData.get().removeExcluded(book);
            book.setIsSearchBook(true); AppDB.get().save(book);
            Tags2.createTag("retained tag");
            Tags2.setTagsByPath(book.getPath(), java.util.Collections.singletonList("retained tag"));
            Tags2.updateTagsDB();
            show(reader); click(R.string.remove_from_library);
            assertFalse(AppDB.get().load(book.getPath()).getIsSearchBook());
            AppBook progress = new AppBook(book.getPath()); progress.p = 0.7f; progress.t = System.currentTimeMillis();
            SharedBooks.saveAsync(progress);
            show(reader); click(R.string.delete_reading_progress);
            assertEquals(0, SharedBooks.load("/books/Dune.epub").p, 0.001f);
            assertFalse(com.foobnix.android.utils.IO.readJsonObject(AppProfile.syncProgress).has("Dune.epub"));
        }
    }
    @Test public void libraryAndReaderStoreFavoritesTagsAndPlaylistsUnderTheLibraryIdentity() throws Exception {
        for (boolean reader : new boolean[]{false, true}) {
            book.setIsStar(false); AppDB.get().save(book);
            show(reader); click(R.string.add_to_favorites);
            assertTrue(AppDB.get().load(book.getPath()).getIsStar());
            assertEquals(book.getPath(), AppData.getSimpleMeta(AppProfile.syncFavorite).get(0).getPath());
            show(reader); click(R.string.remove_from_favorites);
            assertFalse(AppDB.get().load(book.getPath()).getIsStar());
            assertTrue(AppData.getSimpleMeta(AppProfile.syncFavorite).isEmpty());
            Tags2.createTag("fixture");
            show(reader); click(R.string.add_tags);
            main(() -> {
                View window = windowWith(R.id.listView1);
                ListView list = window.findViewById(R.id.listView1);
                View row = list.getAdapter().getView(0, null, list);
                ((CheckBox) row.findViewById(R.id.tagName)).setChecked(true);
                window.findViewById(android.R.id.button1).performClick();
            });
            assertTrue(Tags2.getAllFilesByTag("fixture").contains(book.getPath()));
            assertTrue(AppDB.get().load(book.getPath()).getTag().contains("fixture"));
            File playlist = new File(AppProfile.syncPlaylist, "fixture" + Playlists.L_PLAYLIST);
            assertTrue(playlist.exists() || playlist.createNewFile());
            show(reader); click(R.string.add_to_playlist);
            main(() -> {
                ListView list = windowWith(R.id.listView1).findViewById(R.id.listView1);
                list.getAdapter().getView(0, null, list).performClick();
            });
            assertTrue(new String(Files.readAllBytes(playlist.toPath()), java.nio.charset.StandardCharsets.UTF_8).contains(book.getPath()));
        }
    }
    @Test public void cachedCloudActionsKeepTheirExistingMetadataPathAndHideTags() throws Exception {
        File cached = new File(root, "cloud-cache.epub"); Files.write(cached.toPath(), new byte[]{1, 2, 3});
        FileMeta local = AppDB.get().getOrCreate(cached.getPath()); local.setTitle("Cached cloud title");
        AppDB.get().save(local);
        FileMeta cloud = new FileMeta("cloud-dropbox:/Dune.epub"); cloud.setTitle("Remote title");
        try {
            main(() -> menu = ShareDialog.show(activity, cached, cloud, null, -1, null, null));
            main(() -> assertEquals(-1, position(R.string.add_tags)));
            click(R.string.file_info);
            main(() -> assertEquals("Cached cloud title", ((android.widget.TextView)
                    windowWith(R.id.bookName).findViewById(R.id.bookName)).getText().toString()));
        } finally { AppDB.get().deleteBy(cached.getPath()); }
    }

    @Test public void bookmarkExportUsesTheProviderFilenameFromLibraryAndReader() throws Exception {
        BookmarksData.get().add(new AppBookmark("/Attachments/Dune.epub", "Exported bookmark", 0.4f));
        for (boolean reader : new boolean[]{false, true}) {
            show(reader);
            main(() -> {
                if (reader) ExtUtils.sendBookmarksTo(activity, staged);
                else ExtUtils.sendBookmarksTo(activity, book);
            });
            String exported = handoff().getStringExtra(Intent.EXTRA_TEXT);
            assertNotNull(exported); assertTrue(exported.startsWith("Dune.epub"));
            assertTrue(exported.contains("Exported bookmark")); assertFalse(exported.contains("source-hash"));
        }
    }

    @Test public void localFolderTagsKeepTheirExistingRowAndCountBehavior() throws Exception {
        File folder = new File(root, "local-folder"); assertTrue(folder.mkdir());
        try {
            Tags2.createTag("folder tag");
            Tags2.setTags(folder, java.util.Collections.singletonList("folder tag"));
            Tags2.updateTagsDB();
            assertTrue(AppDB.get().load(folder.getPath()).getTag().contains("folder tag"));
            assertEquals(Integer.valueOf(1), Tags2.getAllTagsWithCount().stream()
                    .filter(tag -> "folder tag".equals(tag.first)).findFirst().get().second);
        } finally { AppDB.get().deleteBy(folder.getPath()); }
    }

    private View windowWith(int id) {
        var windows = WindowInspector.getGlobalWindowViews();
        for (int i = windows.size() - 1; i >= 0; i--)
            if (windows.get(i).findViewById(id) != null) return windows.get(i);
        throw new AssertionError("Missing dialog view " + id);
    }
    @Test public void libraryAndReaderFileInfoShowsCanonicalMetadataAndProviderActions() {
        for (boolean reader : new boolean[]{false, true}) {
            show(reader); click(R.string.file_info);
            main(() -> {
                View info = windowWith(R.id.bookName);
                assertEquals("Dune", ((android.widget.TextView) info.findViewById(R.id.bookName)).getText().toString());
                assertFalse(((android.widget.TextView) info.findViewById(R.id.path)).getText().toString().contains("source-hash"));
                assertEquals(View.VISIBLE, info.findViewById(R.id.openWith).getVisibility());
                assertEquals(View.VISIBLE, info.findViewById(R.id.sendFile).getVisibility());
                assertEquals(View.GONE, info.findViewById(R.id.convertFile).getVisibility());
                assertEquals(View.GONE, info.findViewById(R.id.editTitle).getVisibility());
                assertEquals(View.VISIBLE, info.findViewById(R.id.tagIcon).getVisibility());
            });
        }
    }
    @Test public void deleteRequiresConfirmationAndNamesTheProvider() {
        for (boolean reader : new boolean[]{false, true}) {
            show(reader); click(R.string.delete);
            assertNull(provider.deleted);
            main(() -> windowWith(android.R.id.message).findViewById(android.R.id.button2).performClick());
            assertNull(provider.deleted); assertNotNull(AppDB.get().load(book.getPath()));
        }
        assertTrue(FileInformationDialog.providerDeleteMessage(activity, book).contains("actions.fixture"));
        assertTrue(FileInformationDialog.providerDeleteMessage(activity, book).contains("Dune.epub"));
        show(false); click(R.string.delete);
        main(() -> windowWith(android.R.id.message).findViewById(android.R.id.button1).performClick());
        assertEquals(Uri.parse(book.getPath()), provider.deleted); assertNull(AppDB.get().load(book.getPath()));
        assertTrue(staged.exists());
    }
    @Test public void registeredProviderLabelIsUsedInTheDeleteConfirmation() {
        String authority = activity.getPackageName() + ".provider";
        FileMeta registered = new FileMeta("content://" + authority + "/document/42");
        registered.setPathTxt("Dune.epub");
        ProviderInfo info = activity.getPackageManager().resolveContentProvider(authority, 0);
        assertNotNull(info);
        String message = FileInformationDialog.providerDeleteMessage(activity, registered);
        assertTrue(message.contains(info.loadLabel(activity.getPackageManager()).toString()));
    }

    @Test public void readerConfirmedDeleteUsesProviderInsteadOfStagedCopy() {
        show(true); click(R.string.delete);
        assertNull(provider.deleted);
        main(() -> windowWith(android.R.id.message).findViewById(android.R.id.button1).performClick());
        assertEquals(Uri.parse(book.getPath()), provider.deleted); assertNull(AppDB.get().load(book.getPath()));
        assertTrue(staged.exists());
    }
    private ListView findList(View view) {
        if (view instanceof ListView) return (ListView) view;
        if (view instanceof android.view.ViewGroup) {
            var group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                ListView list = findList(group.getChildAt(i));
                if (list != null) return list;
            }
        }
        return null;
    }

    @Test public void defaultLibraryEntryPointsUseTheRowForMenuAndInformation() {
        main(() -> DefaultListeners.getOnMenuClick(activity, adapter).onResultRecive(book));
        main(() -> {
            boolean found = false;
            for (View window : WindowInspector.getGlobalWindowViews()) {
                ListView list = findList(window);
                if (list == null) continue;
                for (int i = 0; i < list.getAdapter().getCount(); i++)
                    found |= list.getAdapter().getItem(i).toString().contains(activity.getString(R.string.send_file));
            }
            assertTrue("Library menu did not offer Send", found);
        });
        main(() -> DefaultListeners.getOnItemLongClickListenerFileInfo(activity, adapter).onResultRecive(book));
        main(() -> assertNotNull(windowWith(R.id.bookName)));
    }
}
