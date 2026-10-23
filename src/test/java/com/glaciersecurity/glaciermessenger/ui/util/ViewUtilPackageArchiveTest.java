package com.glaciersecurity.glaciermessenger.ui.util;

import static com.google.common.truth.Truth.assertThat;

import java.io.File;

import org.junit.Test;

/**
 * The guard that keeps a received attachment from reaching the Android package installer.
 *
 * <p>{@code ViewUtil.view} falls through to a generic {@code ACTION_VIEW} intent for any mime it
 * has no in-app viewer for. An .apk routed that way opens the system installer, so a peer could
 * turn a file transfer into an install prompt (CodeQL {@code java/android/arbitrary-apk-installation}).
 * Both the declared mime and the file name are attacker-controlled, so the guard has to reject on
 * either one.</p>
 *
 * <p>The second half of the guard covers the route that names no package type at all: intent-filter
 * matching is symmetric, so a wildcard intent type matches a filter naming any specific type — the
 * installer's included. Unknown types are narrowed to a concrete one instead.</p>
 */
public class ViewUtilPackageArchiveTest {

    private static final String PACKAGE_INSTALLER_MIME = "application/vnd.android.package-archive";

    /** The deny-list must still name the installer's own type, or the fallback is the only guard. */
    private static final boolean PACKAGE_ARCHIVE_MIME_IS_DENIED =
            ViewUtil.isPackageArchive(new File("x"), PACKAGE_INSTALLER_MIME);

    private static File named(final String name) {
        return new File("/data/data/com.example/files/" + name);
    }

    @Test
    public void packageArchiveMimeIsRejected() {
        assertThat(
                        ViewUtil.isPackageArchive(
                                named("invoice.pdf"), "application/vnd.android.package-archive"))
                .isTrue();
    }

    @Test
    public void apkExtensionIsRejectedEvenWhenTheMimeLooksHarmless() {
        // The mime is only ever guessed from the name, so a peer can send any pairing it likes.
        assertThat(ViewUtil.isPackageArchive(named("photo.apk"), "image/png")).isTrue();
        assertThat(ViewUtil.isPackageArchive(named("photo.apk"), "application/octet-stream")).isTrue();
        assertThat(ViewUtil.isPackageArchive(named("photo.apk"), null)).isTrue();
    }

    @Test
    public void otherPackageExtensionsAreRejected() {
        assertThat(ViewUtil.isPackageArchive(named("bundle.apks"), null)).isTrue();
        assertThat(ViewUtil.isPackageArchive(named("bundle.apkm"), null)).isTrue();
        assertThat(ViewUtil.isPackageArchive(named("bundle.xapk"), null)).isTrue();
        assertThat(ViewUtil.isPackageArchive(named("module.apex"), null)).isTrue();
    }

    @Test
    public void extensionMatchIsCaseInsensitive() {
        assertThat(ViewUtil.isPackageArchive(named("Payload.APK"), null)).isTrue();
        assertThat(ViewUtil.isPackageArchive(named("Payload.ApK"), null)).isTrue();
    }

    @Test
    public void onlyTheFinalExtensionCounts() {
        // "report.apk.pdf" is a PDF; the installer is reached through the last segment only.
        assertThat(ViewUtil.isPackageArchive(named("report.apk.pdf"), "application/pdf")).isFalse();
        assertThat(ViewUtil.isPackageArchive(named("report.pdf.apk"), "application/pdf")).isTrue();
    }

    @Test
    public void ordinaryAttachmentsStillOpen() {
        assertThat(ViewUtil.isPackageArchive(named("holiday.jpg"), "image/jpeg")).isFalse();
        assertThat(ViewUtil.isPackageArchive(named("notes.txt"), "text/plain")).isFalse();
        assertThat(ViewUtil.isPackageArchive(named("archive.zip"), "application/zip")).isFalse();
        assertThat(ViewUtil.isPackageArchive(named("clip.mp4"), "video/mp4")).isFalse();
    }

    @Test
    public void namesWithoutAUsableExtensionAreNotRejected() {
        assertThat(ViewUtil.isPackageArchive(named("README"), null)).isFalse();
        assertThat(ViewUtil.isPackageArchive(named("trailing."), null)).isFalse();
    }

    // ---- the wildcard route ------------------------------------------------------------------
    //
    // A file served from an extensionless URL with no usable Content-Type is stored under the
    // extensionless message UUID, Attachment.getMime() is null, and view(Context, Attachment)
    // substitutes "*/*". Nothing in that flow ever names a package archive, so the deny-list
    // above cannot catch it -- the type has to stop being a wildcard.

    @Test
    public void absentAndWildcardTypesCountAsUnknown() {
        assertThat(ViewUtil.isUnknownType(null)).isTrue();
        assertThat(ViewUtil.isUnknownType("")).isTrue();
        assertThat(ViewUtil.isUnknownType("*/*")).isTrue();
        assertThat(ViewUtil.isUnknownType("*/png")).isTrue();
        assertThat(ViewUtil.isUnknownType("image/*")).isTrue();
    }

    @Test
    public void malformedTypesCountAsUnknown() {
        assertThat(ViewUtil.isUnknownType("application")).isTrue();
        assertThat(ViewUtil.isUnknownType("application/")).isTrue();
        assertThat(ViewUtil.isUnknownType("/png")).isTrue();
        assertThat(ViewUtil.isUnknownType("/")).isTrue();
    }

    @Test
    public void concreteTypesAreLeftAlone() {
        assertThat(ViewUtil.isUnknownType("text/plain")).isFalse();
        assertThat(ViewUtil.isUnknownType("image/jpeg")).isFalse();
        assertThat(ViewUtil.isUnknownType("application/zip")).isFalse();
        assertThat(ViewUtil.isUnknownType("application/octet-stream")).isFalse();
    }

    @Test
    public void theFallbackTypeCannotMatchTheInstallersFilter() {
        // Asserted against the production constant, not a copy of it: whatever unknown types get
        // narrowed to has to be a concrete type (a wildcard would match every filter, which is
        // the whole bug) and must not be the one type the installer's filter names.
        assertThat(ViewUtil.isUnknownType(ViewUtil.UNKNOWN_TYPE_FALLBACK)).isFalse();
        assertThat(ViewUtil.UNKNOWN_TYPE_FALLBACK).isNotEqualTo(PACKAGE_INSTALLER_MIME);
        assertThat(PACKAGE_ARCHIVE_MIME_IS_DENIED).isTrue();
    }
}
