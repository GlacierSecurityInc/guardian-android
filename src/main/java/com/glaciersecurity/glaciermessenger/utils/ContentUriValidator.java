package com.glaciersecurity.glaciermessenger.utils;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.net.Uri;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.InvalidPathException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether a {@link Uri} that reached us from outside the app is safe to hand to a
 * {@link ContentResolver}.
 *
 * <p>A {@code ContentResolver} call runs with our identity, so resolving a Uri that another app
 * chose makes us its deputy. A {@code file://} Uri can name our own private storage -- internal
 * ({@code /data/data/<package>/...}) or the external directories only we can reach under scoped
 * storage, which is where conversation media lives -- and a {@code content://} Uri can name a
 * provider of ours that was never exported. Neither is something a document picker hands back, so
 * refusing them costs no real functionality. This is CodeQL's
 * {@code java/android/unsafe-content-uri-resolution}.
 *
 * <p>The two schemes are checked differently on purpose. A {@code file://} path is a filesystem
 * path, so it is normalised and canonicalised first -- that is what stops
 * {@code /sdcard/../../data/data/...} and a symlink pointing at the same place. A
 * {@code content://} path is an opaque provider-defined route, not a path, so only its authority
 * means anything.
 */
public final class ContentUriValidator {

    /** Roots that only ever hold system or app-private state, whichever app owns them. */
    private static final String[] PRIVATE_ROOTS = {"/data", "/system", "/proc", "/sys"};

    private ContentUriValidator() {}

    /**
     * @return true when {@code uri} may be passed to a {@link ContentResolver}. A null Uri, a
     *     scheme we have no business opening, a path inside private or system storage, and a
     *     provider owned by this app all return false.
     */
    public static boolean isSafeToResolve(final Context context, @Nullable final Uri uri) {
        if (uri == null) {
            return false;
        }
        final String scheme = uri.getScheme();
        if (scheme == null) {
            return false;
        }
        switch (scheme.toLowerCase(Locale.ROOT)) {
            case ContentResolver.SCHEME_FILE:
                return !namesPrivateStorage(context, uri.getPath());
            case ContentResolver.SCHEME_CONTENT:
                return !isOwnProvider(context, uri.getAuthority());
            default:
                // android.resource://, http(s):// and anything else: not a document anyone
                // picked, and nothing here needs to read one.
                return false;
        }
    }

    /** Both forms of private storage: the global roots, and the directories only we can reach. */
    private static boolean namesPrivateStorage(final Context context, @Nullable final String path) {
        if (path == null) {
            // A file: Uri with no path opens nothing; there is no legitimate caller for it.
            return true;
        }
        final String canonical = canonicalPath(path);
        if (pointsIntoPrivateStorage(path) || pointsIntoPrivateStorage(canonical)) {
            return true;
        }
        for (final String directory : appPrivateDirectories(context)) {
            if (isUnder(path, directory) || isUnder(canonical, directory)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Normalises {@code path} and reports whether it lands under one of {@link #PRIVATE_ROOTS}.
     * Package-visible and String-typed so the root matching can be tested on its own.
     */
    static boolean pointsIntoPrivateStorage(@Nullable final String path) {
        for (final String root : PRIVATE_ROOTS) {
            if (isUnder(path, root)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The directories this app owns that no other app can reach: its internal data directory and
     * its slices of external storage. Scoped storage keeps other apps out of these, which is
     * exactly why a malicious picker result naming one is worth something to an attacker.
     */
    private static List<String> appPrivateDirectories(final Context context) {
        final List<File> directories = new ArrayList<>();
        directories.add(context.getDataDir());
        directories.add(context.getFilesDir());
        directories.add(context.getCacheDir());
        // getExternalFilesDirs()/getExternalCacheDirs() sit inside Android/data/<package> on each
        // volume; take the parent so the whole per-package directory is covered at once.
        addParents(directories, context.getExternalFilesDirs(null));
        addParents(directories, context.getExternalCacheDirs());
        Collections.addAll(directories, context.getExternalMediaDirs());

        final Set<String> paths = new LinkedHashSet<>();
        for (final File directory : directories) {
            if (directory == null) {
                continue;
            }
            paths.add(directory.getAbsolutePath());
            paths.add(canonicalPath(directory.getAbsolutePath()));
        }
        return new ArrayList<>(paths);
    }

    private static void addParents(final List<File> into, @Nullable final File[] directories) {
        if (directories == null) {
            return;
        }
        for (final File directory : directories) {
            if (directory != null) {
                into.add(directory.getParentFile());
            }
        }
    }

    /** Whether the normalised {@code path} is {@code root} itself or sits beneath it. */
    private static boolean isUnder(@Nullable final String path, @Nullable final String root) {
        if (path == null || root == null) {
            return false;
        }
        final String normalizedPath = normalize(path);
        final String normalizedRoot = normalize(root);
        if (normalizedPath == null || normalizedRoot == null) {
            // A path the platform cannot even parse is not one a picker produced.
            return normalizedPath == null;
        }
        // The trailing separator matters: /database is not under /data.
        return normalizedPath.equals(normalizedRoot)
                || normalizedPath.startsWith(normalizedRoot + File.separator);
    }

    @Nullable
    private static String normalize(final String path) {
        try {
            return FileSystems.getDefault().getPath(path).normalize().toString();
        } catch (final InvalidPathException e) {
            return null;
        }
    }

    @Nullable
    private static String canonicalPath(@Nullable final String path) {
        if (path == null) {
            return null;
        }
        try {
            return new File(path).getCanonicalPath();
        } catch (final IOException e) {
            return path;
        }
    }

    private static boolean isOwnProvider(final Context context, @Nullable final String authority) {
        if (authority == null) {
            return true;
        }
        // A content Uri may carry an Android user id, as in content://0@com.example.files/x.
        // ContentResolver strips it and routes to the provider underneath; resolveContentProvider
        // does not, and answers null for the qualified form -- which would read as "not ours".
        final int userIdSeparator = authority.lastIndexOf('@');
        final String bare =
                userIdSeparator < 0 ? authority : authority.substring(userIdSeparator + 1);
        for (final String own : ownAuthorities(context)) {
            if (own.equalsIgnoreCase(bare)) {
                return true;
            }
        }
        final ProviderInfo providerInfo =
                context.getPackageManager().resolveContentProvider(bare, 0);
        return providerInfo != null && context.getPackageName().equals(providerInfo.packageName);
    }

    /**
     * Our own declared provider authorities, read from our manifest rather than resolved, so the
     * answer does not depend on what package visibility lets us look up.
     */
    private static Set<String> ownAuthorities(final Context context) {
        final Set<String> authorities = new LinkedHashSet<>();
        final PackageInfo packageInfo;
        try {
            packageInfo =
                    context.getPackageManager()
                            .getPackageInfo(
                                    context.getPackageName(), PackageManager.GET_PROVIDERS);
        } catch (final PackageManager.NameNotFoundException e) {
            return authorities;
        }
        if (packageInfo.providers == null) {
            return authorities;
        }
        for (final ProviderInfo providerInfo : packageInfo.providers) {
            if (providerInfo == null || providerInfo.authority == null) {
                continue;
            }
            // One provider may declare several authorities, separated by semicolons.
            Collections.addAll(authorities, providerInfo.authority.split(";"));
        }
        return authorities;
    }
}
