package com.glaciersecurity.glaciermessenger.utils;

import android.annotation.SuppressLint;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.List;

import com.glaciersecurity.glaciermessenger.persistance.FileBackend;

public class FileUtils {

    private static final Uri PUBLIC_DOWNLOADS = Uri.parse("content://downloads/public_downloads");

    /**
     * Get a file path from a Uri. This will get the path for Storage Access
     * Framework Documents, as well as the _data field for the MediaStore and
     * other file-based ContentProviders.
     *
     * @param context The context.
     * @param uri     The Uri to query.
     * @author paulburke
     */
    @SuppressLint("NewApi")
    public static String getPath(final Context context, final Uri uri) {
        if (uri == null) {
            return null;
        }

        // DocumentProvider
        if (DocumentsContract.isDocumentUri(context, uri)) {
            // ExternalStorageProvider
            if (isExternalStorageDocument(uri)) {
                final String docId = DocumentsContract.getDocumentId(uri);
                final String[] split = docId.split(":");
                if (split.length < 2) {
                    return null;
                }
                final String type = split[0];

                if ("primary".equalsIgnoreCase(type)) {
                    return canonicalizeUnderExternalStorage(Environment.getExternalStorageDirectory() + "/" + split[1]);
                }

            }
            // DownloadsProvider
            else if (isDownloadsDocument(uri)) {
                final String id = DocumentsContract.getDocumentId(uri);
                try {
                    final Uri contentUri = ContentUris.withAppendedId(PUBLIC_DOWNLOADS, Long.valueOf(id));
                    return getDataColumn(context, contentUri, null, null);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            // MediaProvider
            else if (isMediaDocument(uri)) {
                final String docId = DocumentsContract.getDocumentId(uri);
                final String[] split = docId.split(":");
                final String type = split[0];

                Uri contentUri = null;
                if ("image".equals(type)) {
                    contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                } else if ("video".equals(type)) {
                    contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                } else if ("audio".equals(type)) {
                    contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                }

                final String selection = "_id=?";
                final String[] selectionArgs = new String[]{split[1]};

                return getDataColumn(context, contentUri, selection, selectionArgs);
            }
        }
        // MediaStore (and general)
        else if ("content".equalsIgnoreCase(uri.getScheme())) {
            List<String> segments = uri.getPathSegments();
            String path;
            if (FileBackend.getAuthority(context).equals(uri.getAuthority()) && segments.size() > 1 && segments.get(0).equals("external")) {
                path = canonicalizeUnderExternalStorage(Environment.getExternalStorageDirectory().getAbsolutePath() + uri.getPath().substring(segments.get(0).length() + 1));
            } else {
                path = getDataColumn(context, uri, null, null);
            }
            if (path != null) {
                File file = new File(path);
                if (!file.canRead()) {
                    return null;
                }
            }
            return path;
        }
        // File
        else if ("file".equalsIgnoreCase(uri.getScheme())) {
            return canonicalizeUnderExternalStorage(uri.getPath());
        }

        return null;
    }

    /**
     * Get a file path from a content URI (Storage Access Framework variant).
     *
     * <p>{@code raw:} document IDs are now
     * validated through {@link #resolveRawDocumentId(Context, String)} before
     * being returned. That helper applies two gates in sequence:
     * <ol>
     *   <li><em>Private-data-dir exclusion</em> – rejects any path whose
     *       canonical form falls inside the app's private data directory
     *       ({@code /data/data/<pkg>/}, {@code /data/user/<id>/<pkg>/}, etc.).</li>
     *   <li><em>External-storage confinement</em> – delegates to
     *       {@link #canonicalizeUnderExternalStorage(String)}, which only
     *       allows paths under {@link Environment#getExternalStorageDirectory()}.</li>
     * </ol>
     * Both gates must pass; the path is rejected if either fails.
     */
    @SuppressLint("NewApi")
    public static String getRealPathFromURI(Context context, Uri uri) {
        String filePath = null;

        final String docId = DocumentsContract.isDocumentUri(context, uri)
                ? DocumentsContract.getDocumentId(uri)
                : null;
        // A document Uri whose id does not resolve still gets the generic getDataColumn2
        // attempt below; returning null here instead would drop a path this used to find.
        if (docId != null) {

            // Delegate raw: paths to the two-gate validator.
            if (docId.startsWith("raw:")) {
                return resolveRawDocumentId(context, docId.substring(4));
            }

            String[] split = docId.split(":");
            if (split.length < 2) {
                return null;
            }
            String type = split[0];

            if ("image".equals(type) || "video".equals(type)) {
                Uri contentUri = "video".equals(type)
                        ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                String selection = "_id=?";
                String[] selectionArgs = new String[]{split[1]};
                filePath = getDataColumn2(context, contentUri, selection, selectionArgs);
            } else if ("com.android.providers.downloads.documents".equals(uri.getAuthority())) {
                try {
                    long downloadId = Long.parseLong(split[1]);
                    Uri contentUri = ContentUris.withAppendedId(
                            Uri.parse("content://downloads/public_downloads"), downloadId);
                    filePath = getDataColumn2(context, contentUri, null, null);
                } catch (NumberFormatException e) {
                    filePath = null;
                }
            }
        }

        if (filePath == null) {
            filePath = getDataColumn2(context, uri, null, null);
        }

        return canonicalizeUnderExternalStorage(filePath);
    }

    /**
     * two-gate validator for {@code raw:} document IDs.
     *
     * <p>Gate 1 — rejects paths that canonicalize into the app's private data
     * directory. This is the primary path-traversal defence.
     *
     * <p>Gate 2 — delegates to {@link #canonicalizeUnderExternalStorage(String)},
     * which only permits paths under external storage.
     *
     * @param context application context
     * @param rawPath filesystem path after stripping the {@code "raw:"} prefix
     * @return canonical external-storage path, or {@code null} if either gate rejects it
     */
    @Nullable
    private static String resolveRawDocumentId(final Context context, final String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return null;
        }

        // Gate 1: reject paths that resolve into the app's private data directory.
        try {
            final String canonicalRaw = new File(rawPath).getCanonicalPath();
            final String privateDataDir = getPrivateDataDirCanonical(context);
            if (privateDataDir != null
                    && (canonicalRaw.equals(privateDataDir)
                    || canonicalRaw.startsWith(privateDataDir + File.separator))) {
                android.util.Log.w("FileUtils",
                        "blocking raw: path that targets private data dir: " + canonicalRaw);
                return null;
            }
        } catch (IOException e) {
            // Cannot canonicalize — fail closed.
            return null;
        }

        // Gate 2: must resolve under external storage.
        return canonicalizeUnderExternalStorage(rawPath);
    }

    /**
     * Returns the canonical path of the app's private data directory,
     * covering both primary ({@code /data/data/<pkg>/}) and per-user
     * ({@code /data/user/<id>/<pkg>/}) locations.
     */
    @Nullable
    private static String getPrivateDataDirCanonical(final Context context) {
        try {
            final File dataDir;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                dataDir = context.getDataDir();
            } else {
                dataDir = new File(context.getApplicationInfo().dataDir);
            }
            return dataDir.getCanonicalPath();
        } catch (IOException e) {
            return null;
        }
    }

    private static String canonicalizeUnderExternalStorage(String filePath) {
        if (filePath == null) {
            return null;
        }

        try {
            final File baseDirectory = Environment.getExternalStorageDirectory();
            final String canonicalBase = baseDirectory.getCanonicalPath();
            final String canonicalPath = new File(filePath).getCanonicalPath();

            if (canonicalPath.equals(canonicalBase) || canonicalPath.startsWith(canonicalBase + File.separator)) {
                return canonicalPath;
            }
        } catch (IOException ignored) {
            return null;
        }

        return null;
    }

    private static boolean isDocumentUri(Context context, Uri uri) {
        return DocumentsContract.isDocumentUri(context, uri);
    }

    private static String getDataColumn2(Context context, Uri uri, String selection, String[] selectionArgs) {
        String column = "_data";
        String[] projection = {column};

        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(uri, projection, selection, selectionArgs, null);
            if (cursor != null && cursor.moveToFirst()) {
                int columnIndex = cursor.getColumnIndexOrThrow(column);
                return cursor.getString(columnIndex);
            }
        } catch (IllegalArgumentException e) {
            return null;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }

    /**
     * Get the value of the data column for this Uri.
     *
     * @param context       The context.
     * @param uri           The Uri to query.
     * @param selection     (Optional) Filter used in the query.
     * @param selectionArgs (Optional) Selection arguments used in the query.
     * @return The value of the _data column, which is typically a file path.
     */
    public static String getDataColumn(Context context, Uri uri, String selection,
                                       String[] selectionArgs) {
        Cursor cursor = null;
        final String column = "_data";
        final String[] projection = {column};

        try {
            cursor = context.getContentResolver().query(uri, projection, selection, selectionArgs, null);
            if (cursor != null && cursor.moveToFirst()) {
                final int column_index = cursor.getColumnIndexOrThrow(column);
                return cursor.getString(column_index);
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is ExternalStorageProvider.
     */
    public static boolean isExternalStorageDocument(Uri uri) {
        return "com.android.externalstorage.documents".equals(uri.getAuthority());
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is DownloadsProvider.
     */
    public static boolean isDownloadsDocument(Uri uri) {
        return "com.android.providers.downloads.documents".equals(uri.getAuthority());
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is MediaProvider.
     */
    public static boolean isMediaDocument(Uri uri) {
        return "com.android.providers.media.documents".equals(uri.getAuthority());
    }

    public static String getDisplayName(Context context, Uri uri) {
        Cursor cursor = null;
        if ("content".equalsIgnoreCase(uri.getScheme())) {
            try {
                cursor = context.getContentResolver().query(uri, null, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (index == -1) {
                        index = 0;
                    }
                    return cursor.getString(index);
                }
            } catch (Exception e) {
                return null;
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }
        return null;
    }

    public static boolean deleteDirectory(File directory) {
        if (directory.isDirectory()) {
            String[] children = directory.list();
            if (children == null) {
                return false;
            }
            for (int i = 0; i < children.length; i++) {
                boolean success = deleteDirectory(new File(directory, children[i]));
                if (!success) {
                    return false;
                }
            }
        }
        return directory.delete();
    }

    public static boolean mkdir(File directory) {
        return directory.exists() || directory.mkdir();
    }
}