package com.glaciersecurity.glaciermessenger.ui.util;

import static com.google.common.truth.Truth.assertThat;

import com.glaciersecurity.glaciermessenger.entities.Message;
import com.glaciersecurity.glaciermessenger.testutil.FakeConversation;
import com.glaciersecurity.glaciermessenger.xmpp.Jid;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

/**
 * {@link Attachment} is what the media viewer and the share sheet branch on, and the branch that
 * earns a test is the ambiguous-container one: .3gp and .ogg can hold audio, video or both, so the
 * declared mime type alone cannot say which player to open. The class resolves it from the file's
 * own dimensions/runtime instead, and getting it wrong opens a video surface on an audio-only file
 * -- a black rectangle, no error.
 *
 * <p>{@code renderThumbnail} is the other half, and its interesting case is SVG: it is an image
 * mime that is deliberately excluded, because rendering attacker-supplied SVG is not the same
 * proposition as decoding a bitmap.
 *
 * <p>Robolectric rather than plain JUnit: Attachment is built around {@code Uri}, whose
 * {@code fromFile} and {@code EMPTY} are both null against the mockable android.jar -- identity
 * and thumbnail decisions would collapse together and pass vacuously.
 */
@RunWith(RobolectricTestRunner.class)
public class AttachmentTest {

    private static final FakeConversation CONVERSATION =
            new FakeConversation(Jid.of("alice@example.com"));

    private static Message message(final String body, final String relativeFilePath) {
        final Message message = new Message(CONVERSATION, body, Message.ENCRYPTION_NONE);
        message.setType(Message.TYPE_FILE);
        message.setRelativeFilePath(relativeFilePath);
        return message;
    }

    // ---- shared locations -------------------------------------------------------------------

    @Test
    public void aGeoUriMessageIsALocationWithNoMimeType() {
        final Attachment attachment = Attachment.of(message("geo:51.5074,-0.1278", null));
        assertThat(attachment.getType()).isEqualTo(Attachment.Type.LOCATION);
        assertThat(attachment.getMime()).isNull();
        assertThat(attachment.renderThumbnail()).isFalse();
    }

    @Test
    public void onlyLocationsCanBeSentInBand() {
        final Attachment location = Attachment.of(message("geo:51.5074,-0.1278", null));
        final Attachment file = Attachment.of(message("1024|640|480", "photo.jpg"));

        assertThat(Attachment.canBeSendInBand(Collections.singletonList(location))).isTrue();
        assertThat(Attachment.canBeSendInBand(Arrays.asList(location, location))).isTrue();
        assertThat(Attachment.canBeSendInBand(Arrays.asList(location, file))).isFalse();
        assertThat(Attachment.canBeSendInBand(Collections.singletonList(file))).isFalse();
    }

    @Test
    public void anEmptySelectionIsVacuouslyInBand() {
        assertThat(Attachment.canBeSendInBand(Collections.emptyList())).isTrue();
    }

    // ---- ambiguous containers -----------------------------------------------------------------

    @Test
    public void aThreeGppWithDimensionsIsTreatedAsVideo() {
        final Attachment attachment = Attachment.of(message("1024|640|480", "clip.3gp"));
        assertThat(attachment.getMime()).isEqualTo("video/*");
        assertThat(attachment.renderThumbnail()).isTrue();
    }

    @Test
    public void aThreeGppWithOnlyARuntimeIsTreatedAsAudio() {
        final Attachment attachment =
                Attachment.of(message("https://example.com/clip.3gp|1024|0|0|5000", "clip.3gp"));
        assertThat(attachment.getMime()).isEqualTo("audio/*");
        assertThat(attachment.renderThumbnail()).isFalse();
    }

    /** Neither dimensions nor runtime: nothing is claimed about the contents. */
    @Test
    public void aThreeGppWithNeitherFallsBackToAnOpaqueType() {
        final Attachment attachment = Attachment.of(message("1024", "clip.3gp"));
        assertThat(attachment.getMime()).isEqualTo("application/octet-stream");
        assertThat(attachment.renderThumbnail()).isFalse();
    }

    @Test
    public void anUnambiguousMimeTypeIsPassedThroughUnchanged() {
        final Attachment attachment = Attachment.of(message("1024|640|480", "photo.jpg"));
        assertThat(attachment.getMime()).isEqualTo("image/jpeg");
        assertThat(attachment.getType()).isEqualTo(Attachment.Type.FILE);
        assertThat(attachment.renderThumbnail()).isTrue();
    }

    // ---- thumbnails -----------------------------------------------------------------------------

    private static Attachment fileAttachment(final String name, final String mime) {
        return Attachment.of(UUID.randomUUID(), new File("/tmp/" + name), mime);
    }

    @Test
    public void imagesAndVideosAreShownAsImages() {
        assertThat(fileAttachment("a.jpg", "image/jpeg").getType())
                .isEqualTo(Attachment.Type.IMAGE);
        assertThat(fileAttachment("a.mp4", "video/mp4").getType()).isEqualTo(Attachment.Type.IMAGE);
    }

    @Test
    public void aPdfIsAFileThatStillGetsAThumbnail() {
        final Attachment pdf = fileAttachment("a.pdf", "application/pdf");
        assertThat(pdf.getType()).isEqualTo(Attachment.Type.FILE);
        assertThat(pdf.renderThumbnail()).isTrue();
    }

    /** SVG is an image mime that is deliberately not decoded as one. */
    @Test
    public void anSvgIsNeitherShownAsAnImageNorThumbnailed() {
        final Attachment svg = fileAttachment("a.svg", "image/svg+xml");
        assertThat(svg.getType()).isEqualTo(Attachment.Type.FILE);
        assertThat(svg.renderThumbnail()).isFalse();
    }

    @Test
    public void anUnknownTypeGetsNoThumbnail() {
        final Attachment unknown = fileAttachment("a.bin", null);
        assertThat(unknown.getType()).isEqualTo(Attachment.Type.FILE);
        assertThat(unknown.renderThumbnail()).isFalse();
        assertThat(fileAttachment("a.zip", "application/zip").renderThumbnail()).isFalse();
    }

    // ---- identity --------------------------------------------------------------------------------

    /**
     * Identity is the Uri alone, so the same file picked twice is one attachment however it was
     * reached -- that is what stops a duplicate appearing in the send tray.
     */
    @Test
    public void attachmentsAreIdentifiedByUriRegardlessOfUuidOrMime() {
        final File file = new File("/tmp/same.jpg");
        final Attachment first = Attachment.of(UUID.randomUUID(), file, "image/jpeg");
        final Attachment second = Attachment.of(UUID.randomUUID(), file, "application/octet-stream");

        assertThat(first).isEqualTo(second);
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
        assertThat(first.getUuid()).isNotEqualTo(second.getUuid());
    }

    @Test
    public void differentFilesAreDifferentAttachments() {
        assertThat(fileAttachment("one.jpg", "image/jpeg"))
                .isNotEqualTo(fileAttachment("two.jpg", "image/jpeg"));
    }
}
