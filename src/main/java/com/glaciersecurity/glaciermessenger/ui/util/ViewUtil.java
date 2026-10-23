package com.glaciersecurity.glaciermessenger.ui.util;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.entities.DownloadableFile;
import com.glaciersecurity.glaciermessenger.persistance.FileBackend;
import com.glaciersecurity.glaciermessenger.ui.MediaViewerActivity;
import com.glaciersecurity.glaciermessenger.ui.PdfViewerActivity;
import com.glaciersecurity.glaciermessenger.ui.TextViewerActivity;
import com.glaciersecurity.glaciermessenger.utils.Log;

public class ViewUtil {

    public static void view(Context context, Attachment attachment) {
        File file = new File(attachment.getUri().getPath());
        final String mime = attachment.getMime() == null ? "*/*" : attachment.getMime();
        view(context, file, mime);
    }

    /**
     * Returns true when the given attachment is an image or video that opens in the swipeable
     * {@link MediaViewerActivity} gallery.
     */
    public static boolean isViewableInGallery(final Attachment attachment) {
        return isGalleryMime(attachment.getMime());
    }

    /**
     * Returns true when {@code mime} denotes an image or video that opens in the swipeable
     * {@link MediaViewerActivity} gallery.
     */
    public static boolean isGalleryMime(final String mime) {
        final String normalized = normalizeMime(mime);
        return normalized != null && (normalized.startsWith("image/") || normalized.startsWith("video/"));
    }

    /**
     * Upper bound on how many items the gallery is seeded with in a single intent. Every entry
     * costs a content:// URI plus a mime type and a file name, and the whole intent has to travel
     * through one binder transaction, so a conversation with thousands of attachments would
     * otherwise throw TransactionTooLargeException on startActivity. The window is centred on the
     * item the user tapped, which keeps swiping in both directions useful.
     */
    private static final int MAX_GALLERY_ITEMS = 200;

    /**
     * Opens the {@link MediaViewerActivity} as a gallery seeded with every image/video in
     * {@code attachments}, starting on {@code clicked}, so the user can swipe between them.
     */
    public static void viewInGallery(Context context, List<Attachment> attachments, Attachment clicked) {
        final List<File> files = new ArrayList<>();
        final List<String> mimes = new ArrayList<>();
        int clickedIndex = -1;
        for (final Attachment attachment : attachments) {
            if (!isViewableInGallery(attachment)) {
                continue;
            }
            final File file = new File(attachment.getUri().getPath());
            if (!file.exists()) {
                continue;
            }
            if (attachment.equals(clicked)) {
                clickedIndex = files.size();
            }
            files.add(file);
            mimes.add(attachment.getMime());
        }
        if (clickedIndex < 0) {
            // The attachment the user tapped is not one the gallery can show: its file is gone, or
            // it is not an image/video. Seeding the gallery with the remaining media would open on
            // an unrelated item; hand it to view() so the real reason is reported instead.
            view(context, clicked);
            return;
        }
        launchGallery(context, files, mimes, clickedIndex);
    }

    /**
     * Opens the {@link MediaViewerActivity} as a gallery seeded with every image/video file in
     * {@code files} (paired with {@code mimes}), starting on {@code clicked}, so the user can swipe
     * between them. Falls back to opening {@code clicked} on its own if it cannot be shown in the
     * gallery.
     */
    public static void viewInGallery(Context context, List<File> files, List<String> mimes, File clicked) {
        final List<File> galleryFiles = new ArrayList<>();
        final List<String> galleryMimes = new ArrayList<>();
        int clickedIndex = -1;
        for (int i = 0; i < files.size(); ++i) {
            final File file = files.get(i);
            final String mime = i < mimes.size() ? mimes.get(i) : null;
            if (file == null || !isGalleryMime(mime) || !file.exists()) {
                continue;
            }
            if (file.equals(clicked)) {
                clickedIndex = galleryFiles.size();
            }
            galleryFiles.add(file);
            galleryMimes.add(mime);
        }
        if (clickedIndex < 0) {
            if (clicked != null) {
                final int idx = files.indexOf(clicked);
                view(context, clicked, idx >= 0 && idx < mimes.size() ? mimes.get(idx) : null);
            }
            return;
        }
        launchGallery(context, galleryFiles, galleryMimes, clickedIndex);
    }

    /**
     * Resolves a window of {@code files} around {@code clickedIndex} into content:// URIs and starts
     * {@link MediaViewerActivity} on the clicked item. If the clicked file itself cannot be resolved
     * the gallery is not opened at all - showing a different picture than the one that was tapped is
     * worse than saying why it cannot be shown.
     */
    private static void launchGallery(
            final Context context,
            final List<File> files,
            final List<String> mimes,
            final int clickedIndex) {
        int to = Math.min(files.size(), Math.max(0, clickedIndex - MAX_GALLERY_ITEMS / 2) + MAX_GALLERY_ITEMS);
        int from = Math.max(0, to - MAX_GALLERY_ITEMS);

        final ArrayList<Uri> uris = new ArrayList<>();
        final ArrayList<String> outMimes = new ArrayList<>();
        final ArrayList<String> titles = new ArrayList<>();
        int startPosition = 0;
        for (int i = from; i < to; ++i) {
            final File file = files.get(i);
            final Uri uri;
            try {
                uri = FileBackend.getUriForFile(context, file);
            } catch (final SecurityException e) {
                if (i == clickedIndex) {
                    view(context, file, mimes.get(i));
                    return;
                }
                continue;
            }
            if (i == clickedIndex) {
                startPosition = uris.size();
            }
            uris.add(uri);
            outMimes.add(normalizeMime(mimes.get(i)));
            titles.add(file.getName());
        }
        final Intent intent = new Intent(context, MediaViewerActivity.class);
        intent.putParcelableArrayListExtra(MediaViewerActivity.EXTRA_URIS, uris);
        intent.putStringArrayListExtra(MediaViewerActivity.EXTRA_MIME_TYPES, outMimes);
        intent.putStringArrayListExtra(MediaViewerActivity.EXTRA_TITLES, titles);
        intent.putExtra(MediaViewerActivity.EXTRA_POSITION, startPosition);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(intent);
    }

    /**
     * Mime types that route a file to the Android package installer.
     */
    private static final List<String> PACKAGE_ARCHIVE_MIMES =
            Arrays.asList(
                    "application/vnd.android.package-archive",
                    "application/vnd.android.package-archive-v2",
                    "application/x-apk",
                    "application/vnd.apkm");

    /**
     * File extensions that route a file to the Android package installer.
     */
    private static final List<String> PACKAGE_ARCHIVE_EXTENSIONS =
            Arrays.asList("apk", "apks", "apkm", "xapk", "apex");

    /**
     * True when handing {@code file} to a generic {@link Intent#ACTION_VIEW} intent could reach
     * the system package installer.
     *
     * <p>A received attachment is attacker-controlled in both its bytes and its file name, and
     * the mime type is only ever guessed from that name (see
     * {@code DownloadableFile#getMimeType()}), so neither the declared mime nor the extension is
     * trustworthy on its own — both are checked.</p>
     *
     * <p>Package-private so {@code ViewUtilPackageArchiveTest} can exercise it directly; the
     * rest of {@link ViewUtil} needs a real {@code Context} to call.</p>
     */
    static boolean isPackageArchive(final File file, final String lowerCaseMime) {
        if (lowerCaseMime != null && PACKAGE_ARCHIVE_MIMES.contains(lowerCaseMime)) {
            return true;
        }
        final String name = file.getName().toLowerCase(Locale.US);
        final int separatorIndex = name.lastIndexOf('.');
        if (separatorIndex < 0 || separatorIndex + 1 >= name.length()) {
            return false;
        }
        return PACKAGE_ARCHIVE_EXTENSIONS.contains(name.substring(separatorIndex + 1));
    }

    /**
     * The concrete type a file of unknown kind is offered to other apps as.
     *
     * <p>Chosen so that generic handlers still match — an app whose filter declares a wildcard
     * type matches an {@code application/octet-stream} intent — while the package installer,
     * whose filter names {@code application/vnd.android.package-archive} exactly, does not.</p>
     *
     * <p>Package-private so {@code ViewUtilPackageArchiveTest} can assert those two properties of
     * the value itself.</p>
     */
    static final String UNKNOWN_TYPE_FALLBACK = "application/octet-stream";

    /**
     * True when {@code lowerCaseMime} does not name a concrete type.
     *
     * <p>Wildcards matter here because intent-filter matching is symmetric: a wildcard intent
     * type matches a filter naming <em>any</em> specific type, so it is not a neutral "let the
     * chooser decide" — it opts into every handler installed on the device.</p>
     */
    static boolean isUnknownType(final String lowerCaseMime) {
        if (lowerCaseMime == null || lowerCaseMime.isEmpty() || lowerCaseMime.indexOf('*') >= 0) {
            return true;
        }
        final int slashIndex = lowerCaseMime.indexOf('/');
        return slashIndex < 1 || slashIndex + 1 >= lowerCaseMime.length();
    }

    private static String normalizeMime(final String mime) {
        if (mime == null) {
            return null;
        }
        final int separatorIndex = mime.indexOf(';');
        final String stripped = separatorIndex > 0 ? mime.substring(0, separatorIndex) : mime;
        return stripped.trim().toLowerCase(Locale.US);
    }

    public static void view (Context context, DownloadableFile file) {
        if (!file.exists()) {
            Toast.makeText(context, R.string.file_deleted, Toast.LENGTH_SHORT).show();
            return;
        }
        String mime = file.getMimeType();
        if (mime == null) {
            mime = "*/*";
        }
        view(context, file, mime);
    }

    public static void view(Context context, File file, String mime) {
        if (!file.exists()) {
            Toast.makeText(context, R.string.file_deleted, Toast.LENGTH_SHORT).show();
            return;
        }
        final Uri uri;
        try {
            uri = FileBackend.getUriForFile(context, file);
        } catch (SecurityException e) {
            Toast.makeText(context, context.getString(R.string.no_permission_to_access_x, file.getAbsolutePath()), Toast.LENGTH_SHORT).show();
            return;
        }
        final String normalizedMime;
        if (mime == null) {
            normalizedMime = null;
        } else {
            final int separatorIndex = mime.indexOf(';');
            if (separatorIndex > 0) {
                normalizedMime = mime.substring(0, separatorIndex).trim();
            } else {
                normalizedMime = mime.trim();
            }
        }

        final String lowerCaseMime = normalizedMime == null ? null : normalizedMime.toLowerCase(Locale.US);

        if (lowerCaseMime != null && (lowerCaseMime.startsWith("image/")
                || lowerCaseMime.startsWith("video/") )) {
            final Intent mediaIntent = new Intent(context, MediaViewerActivity.class);
            mediaIntent.setData(uri);
            mediaIntent.putExtra(MediaViewerActivity.EXTRA_MIME_TYPE, lowerCaseMime);
            mediaIntent.putExtra(Intent.EXTRA_TITLE, file.getName());
            mediaIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(mediaIntent);
            return;
        }

        if ("application/pdf".equals(lowerCaseMime)) {
            final Intent pdfIntent = new Intent(context, PdfViewerActivity.class);
            pdfIntent.setData(uri);
            pdfIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(pdfIntent);
            return;
        }
        if (lowerCaseMime != null && (lowerCaseMime.startsWith("text/")
                || "application/json".equals(lowerCaseMime)
                || "application/xml".equals(lowerCaseMime))) {
            final Intent textIntent = new Intent(context, TextViewerActivity.class);
            textIntent.setData(uri);
            textIntent.putExtra(Intent.EXTRA_TITLE, file.getName());
            textIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(textIntent);
            return;
        }
        if (isPackageArchive(file, lowerCaseMime)) {
            // Passing an .apk to ACTION_VIEW opens the system package installer, which turns any
            // attachment a peer sends into an install prompt. Nothing in a messenger needs to
            // install a package, so this is refused rather than handed to another app.
            Log.w(Config.LOGTAG, "refusing to open package archive " + file.getName());
            Toast.makeText(context, R.string.cannot_open_app_package, Toast.LENGTH_LONG).show();
            return;
        }
        // A wildcard or absent type is not neutral: it matches every intent filter on the device,
        // the package installer's included. A file downloaded from an extensionless URL with no
        // usable Content-Type arrives here as "*/*" (Attachment.getMime() is null, and
        // view(Context, Attachment) substitutes the wildcard), so without this the installer is
        // still reachable through the chooser even though the mime never names a package archive.
        final String openMime = isUnknownType(lowerCaseMime) ? UNKNOWN_TYPE_FALLBACK : mime;
        final Intent openIntent = new Intent(Intent.ACTION_VIEW);
        openIntent.setDataAndType(uri, openMime);
        openIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            context.startActivity(openIntent);
        } catch (final ActivityNotFoundException e) {
            Toast.makeText(context, R.string.no_application_found_to_open_file, Toast.LENGTH_SHORT).show();
        }
    }

}
