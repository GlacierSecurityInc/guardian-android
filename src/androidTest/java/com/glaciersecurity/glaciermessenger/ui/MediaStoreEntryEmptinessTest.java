package com.glaciersecurity.glaciermessenger.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.provider.MediaStore;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * That {@link SmsConvActivity#mediaStoreEntryIsEmpty} can tell a camera capture that landed from
 * one that never did.
 *
 * <p>The bug it guards: {@code pickFromCamera} inserts a pending MediaStore row before launching
 * the camera, and some camera apps return {@code RESULT_OK} without ever writing to
 * {@code EXTRA_OUTPUT}. Publishing unconditionally on OK therefore drops an empty picture into the
 * user's gallery -- the same visible symptom as the cancel path, which is otherwise handled.
 *
 * <p>Device-only on purpose. The whole question is what a real MediaProvider does with a row it
 * owns but has no bytes for; a stubbed resolver would only re-assert the stub.
 *
 * <p>Not covered here: the branch that keeps a row when {@code openInputStream} fails for some
 * reason other than the file being absent. Every URI shape reachable from a test -- unknown
 * scheme, dead authority, directory URI -- comes back as {@code FileNotFoundException}, so there
 * is no honest way to provoke the other exception from outside. It is asserted by inspection at
 * the call site instead.
 */
@RunWith(AndroidJUnit4.class)
public class MediaStoreEntryEmptinessTest {

    private static final byte[] ONE_PIXEL_PNG = {
        (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A
    };

    private ContentResolver resolver;
    private final List<Uri> inserted = new ArrayList<>();

    @Before
    public void setUp() {
        final Context context = ApplicationProvider.getApplicationContext();
        resolver = context.getContentResolver();
    }

    /** MediaStore rows outlive the app's data, so pm clear between methods does not undo these. */
    @After
    public void tearDown() {
        for (final Uri uri : inserted) {
            try {
                resolver.delete(uri, null, null);
            } catch (final Exception ignored) {
                // Already gone -- one test deletes its own row.
            }
        }
        inserted.clear();
    }

    /** The defect itself: the camera app returned OK but wrote nothing. */
    @Test
    public void aPendingRowThatWasNeverWrittenIsEmpty() {
        final Uri uri = insertPendingRow();

        assertWithMessage("a row the camera never wrote to must read as empty")
                .that(SmsConvActivity.mediaStoreEntryIsEmpty(resolver, uri))
                .isTrue();
    }

    /** The case that must not regress: a real capture is never discarded. */
    @Test
    public void aRowWithBytesIsNotEmpty() throws Exception {
        final Uri uri = insertPendingRow();
        try (final OutputStream out = resolver.openOutputStream(uri)) {
            assertThat(out).isNotNull();
            out.write(ONE_PIXEL_PNG);
        }

        assertWithMessage("a capture that landed must survive")
                .that(SmsConvActivity.mediaStoreEntryIsEmpty(resolver, uri))
                .isFalse();
    }

    /**
     * A row deleted out from under us -- by the user, or by MediaStore expiring the pending
     * entry -- reads as empty rather than throwing out of onActivityResult.
     */
    @Test
    public void aDeletedRowIsEmpty() {
        final Uri uri = insertPendingRow();
        assertThat(resolver.delete(uri, null, null)).isEqualTo(1);

        assertThat(SmsConvActivity.mediaStoreEntryIsEmpty(resolver, uri)).isTrue();
    }

    /** Mirrors what pickFromCamera inserts, so the test subject is the shape that ships. */
    private Uri insertPendingRow() {
        final ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.TITLE, "Temp_pic");
        values.put(MediaStore.Images.Media.DESCRIPTION, "Temp Description");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        final Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        assertWithMessage("MediaStore refused the insert; the test cannot run")
                .that(uri)
                .isNotNull();
        inserted.add(uri);
        return uri;
    }
}
