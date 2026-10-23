package com.glaciersecurity.glaciermessenger.utils;

import static com.google.common.truth.Truth.assertThat;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.pm.ProviderInfo;
import android.net.Uri;

import java.io.File;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * {@link ContentUriValidator}, which decides whether a Uri handed to us by another app may be
 * passed to a ContentResolver.
 *
 * <p>Robolectric rather than plain JUnit: {@code Uri.parse} and {@code resolveContentProvider}
 * both return null against the mockable android.jar, so every case would "pass" vacuously.
 */
@RunWith(RobolectricTestRunner.class)
public class ContentUriValidatorTest {

    private static final String OUR_AUTHORITY = "com.glaciersecurity.glaciermessenger.files";
    private static final String THEIR_AUTHORITY = "com.android.externalstorage.documents";

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        registerProvider(OUR_AUTHORITY, context.getPackageName());
        registerProvider(THEIR_AUTHORITY, "com.android.externalstorage");
    }

    private void registerProvider(final String authority, final String packageName) {
        final ProviderInfo providerInfo = new ProviderInfo();
        providerInfo.authority = authority;
        providerInfo.packageName = packageName;
        // Robolectric's package manager rejects a component without one.
        providerInfo.name = packageName + ".StubProvider";
        shadowOf(context.getPackageManager()).addOrUpdateProvider(providerInfo);
    }

    @Test
    public void aDocumentPickedOutOfAnotherAppIsAccepted() {
        assertThat(
                        ContentUriValidator.isSafeToResolve(
                                context,
                                Uri.parse(
                                        "content://"
                                                + THEIR_AUTHORITY
                                                + "/document/primary%3ADownload%2Fbackup.ceb")))
                .isTrue();
    }

    @Test
    public void aFileOnSharedStorageIsAccepted() {
        assertThat(
                        ContentUriValidator.isSafeToResolve(
                                context, Uri.parse("file:///storage/emulated/0/Download/b.ceb")))
                .isTrue();
    }

    @Test
    public void ourOwnPrivateStorageIsRefused() {
        assertThat(
                        ContentUriValidator.isSafeToResolve(
                                context,
                                Uri.parse(
                                        "file:///data/data/com.glaciersecurity.glaciermessenger"
                                                + "/databases/history.db")))
                .isFalse();
    }

    @Test
    public void traversalBackIntoPrivateStorageIsRefused() {
        assertThat(
                        ContentUriValidator.isSafeToResolve(
                                context,
                                Uri.parse("file:///storage/emulated/0/../../../data/data/x/y")))
                .isFalse();
    }

    @Test
    public void ourSliceOfExternalStorageIsRefused() {
        // Scoped storage keeps every other app out of Android/data/<package>, which is exactly
        // what makes a picker result naming it worth something to an attacker.
        final File external = context.getExternalFilesDir(null);
        assertThat(external).isNotNull();
        assertThat(
                        ContentUriValidator.isSafeToResolve(
                                context, Uri.parse("file://" + external + "/media/held.jpg")))
                .isFalse();
    }

    @Test
    public void aProviderRouteThatLooksLikeAPathIsStillAccepted() {
        // A content Uri's path is the provider's own route, not a filesystem path, so a route
        // beginning /data says nothing about where the bytes live.
        assertThat(
                        ContentUriValidator.isSafeToResolve(
                                context,
                                Uri.parse("content://" + THEIR_AUTHORITY + "/data/backup.ceb")))
                .isTrue();
    }

    @Test
    public void aUserQualifiedAuthorityDoesNotHideOurOwnProvider() {
        // ContentResolver strips the user id and routes to the provider underneath.
        assertThat(
                        ContentUriValidator.isSafeToResolve(
                                context, Uri.parse("content://0@" + OUR_AUTHORITY + "/shared/x")))
                .isFalse();
    }

    @Test
    public void ourOwnProviderIsRefused() {
        assertThat(
                        ContentUriValidator.isSafeToResolve(
                                context, Uri.parse("content://" + OUR_AUTHORITY + "/shared/x")))
                .isFalse();
    }

    @Test
    public void nullAndUnusableSchemesAreRefused() {
        assertThat(ContentUriValidator.isSafeToResolve(context, null)).isFalse();
        assertThat(ContentUriValidator.isSafeToResolve(context, Uri.parse("/no/scheme/at/all")))
                .isFalse();
        assertThat(ContentUriValidator.isSafeToResolve(context, Uri.parse("https://example.com/b")))
                .isFalse();
        assertThat(
                        ContentUriValidator.isSafeToResolve(
                                context, Uri.parse("android.resource://" + OUR_AUTHORITY + "/1")))
                .isFalse();
    }

    @Test
    public void theSchemeComparisonIgnoresCase() {
        assertThat(
                        ContentUriValidator.isSafeToResolve(
                                context, Uri.parse("FILE:///storage/emulated/0/Download/b.ceb")))
                .isTrue();
    }

    @Test
    public void aPrivateRootIsMatchedOnPathSegments() {
        // /database is not under /data, so a plain startsWith would refuse it wrongly.
        assertThat(ContentUriValidator.pointsIntoPrivateStorage("/database/backup")).isFalse();
        assertThat(ContentUriValidator.pointsIntoPrivateStorage("/data")).isTrue();
        assertThat(ContentUriValidator.pointsIntoPrivateStorage("/data/user/0/x")).isTrue();
        assertThat(ContentUriValidator.pointsIntoPrivateStorage("/proc/self/environ")).isTrue();
        assertThat(ContentUriValidator.pointsIntoPrivateStorage(null)).isFalse();
    }
}
