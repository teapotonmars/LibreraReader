package com.foobnix.pdf.info;

import android.content.Context;
import android.content.UriPermission;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;

/** A provider document has one reading identity even when several tree grants reach it. */
public final class SafDocumentIdentity {
    private static final String PREFS = "SafDocumentAccess";

    private SafDocumentIdentity() {}

    public static Uri canonical(Uri uri) {
        if (uri == null || !"content".equals(uri.getScheme())) return uri;
        try {
            String documentId = DocumentsContract.getDocumentId(uri);
            if (documentId == null) return uri;
            return DocumentsContract.buildDocumentUri(uri.getAuthority(), documentId);
        } catch (IllegalArgumentException invalid) {
            return uri;
        }
    }

    /** Remember a grant-bearing address; the identity itself is deliberately grant-neutral. */
    public static void remember(Context context, Uri access) {
        rememberAll(context, java.util.Collections.singletonList(access));
    }

    public static synchronized void rememberAll(Context context, Iterable<Uri> accesses) {
        var prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        var editor = prefs.edit();
        Map<String, Set<String>> updated = new LinkedHashMap<>();
        boolean changed = false;
        for (Uri access : accesses) {
        Uri identity = canonical(access);
        if (identity == null || identity.equals(access)) continue;
        String key = identity.toString();
        Set<String> known = updated.computeIfAbsent(key, ignored -> new LinkedHashSet<>(
                prefs.getStringSet(key + "|all", java.util.Collections.emptySet())));
        known.add(access.toString());
        editor.putString(key, access.toString());
        changed = true;
        }
        for (Map.Entry<String, Set<String>> entry : updated.entrySet())
            editor.putStringSet(entry.getKey() + "|all", entry.getValue());
        if (changed) editor.apply();
    }

    /** Candidates are tried by the caller because a saved tree grant may have been revoked. */
    public static List<Uri> accessCandidates(Context context, Uri identity) {
        Set<Uri> candidates = new LinkedHashSet<>();
        if (identity == null) return new ArrayList<>();
        Uri canonical = canonical(identity);
        if (!canonical.equals(identity)) candidates.add(identity);
        String last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(canonical.toString(), null);
        if (last != null) candidates.add(Uri.parse(last));
        for (String known : context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(canonical.toString() + "|all", java.util.Collections.emptySet()))
            candidates.add(Uri.parse(known));
        try {
            String documentId = DocumentsContract.getDocumentId(canonical);
            for (UriPermission permission : context.getContentResolver().getPersistedUriPermissions()) {
                Uri grant = permission.getUri();
                if (permission.isReadPermission() && canonical.getAuthority().equals(grant.getAuthority())) {
                    try {
                        DocumentsContract.getTreeDocumentId(grant);
                        candidates.add(DocumentsContract.buildDocumentUriUsingTree(grant, documentId));
                    } catch (IllegalArgumentException notTreeGrant) {
                        if (canonical.equals(grant)) candidates.add(grant);
                    }
                }
            }
        } catch (IllegalArgumentException notDocument) {
            // Other content providers keep their original URI as their identity.
        }
        candidates.add(canonical);
        return new ArrayList<>(candidates);
    }

    public static Uri readableAccess(Context context, Uri identity) throws IOException {
        IOException lastFailure = null;
        for (Uri candidate : accessCandidates(context, identity)) {
            try (ParcelFileDescriptor descriptor = context.getContentResolver()
                    .openFileDescriptor(candidate, "r")) {
                if (descriptor != null) {
                    remember(context, candidate);
                    return candidate;
                }
            } catch (Exception failure) {
                lastFailure = new IOException("Cannot read " + candidate, failure);
            }
        }
        if (lastFailure != null) throw lastFailure;
        throw new IOException("Cannot read " + identity);
    }
}
