package com.glaciersecurity.glaciermessenger.utils;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;

import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

/**
 * That the watch bookkeeping in {@link ConversationsFileObserver} produces watches that actually
 * deliver. The JVM tier drives {@code handleEvent} by hand, so it can only prove the list is right;
 * only a real inotify watch proves a directory the list claims is watched reports anything, and
 * that one it dropped was replaceable.
 *
 * <p>Device-only: there is no inotify behind {@code FileObserver} off a device, and neither the
 * mockable android.jar nor Robolectric grows one.
 */
@RunWith(AndroidJUnit4.class)
public class ConversationsFileObserverLiveTest {

    /** Generous: the walk, the kernel and the event executor are all between an act and its event. */
    private static final long TIMEOUT_MS = 10_000;

    @Rule public final TemporaryFolder folder = new TemporaryFolder();

    private File root;
    private File staging;
    private List<File> reported;
    private ConversationsFileObserver observer;

    @Before
    public void startObserver() throws IOException {
        root = folder.newFolder("watched");
        // Outside the watched tree, but on the same filesystem so a rename into it is a move
        // rather than a copy -- a copy would arrive as CREATE per entry and prove nothing.
        staging = folder.newFolder("staging");
        reported = new CopyOnWriteArrayList<>();
        observer = new ConversationsFileObserver(root.getAbsolutePath()) {
            @Override
            public void onEvent(final File path) {
                reported.add(path);
            }
        };
        observer.startWatching();
    }

    @After
    public void stopObserver() {
        observer.stopWatching();
    }

    @Test
    public void aDirectoryCreatedInTheTreeGetsAWatchThatDelivers() throws Exception {
        final File photos = new File(root, "photos");
        assertThat(photos.mkdir()).isTrue();

        awaitWatched(photos);
        assertDeletionInsideIsReported(photos);
    }

    @Test
    public void aDirectoryRecreatedAtTheSamePathIsWatchedAgain() throws Exception {
        final File photos = new File(root, "photos");
        assertThat(photos.mkdir()).isTrue();
        awaitWatched(photos);

        assertThat(photos.delete()).isTrue();
        await("the watch on " + photos + " to be dropped",
                () -> !observer.watchedDirectories().contains(photos));

        assertThat(photos.mkdir()).isTrue();
        awaitWatched(photos);
        // The stale entry used to survive the delete, so observing() refused this second watch and
        // everything below the recreated directory went unseen.
        assertDeletionInsideIsReported(photos);
    }

    @Test
    public void aDirectoryMovedInIsWatchedAllTheWayDown() throws Exception {
        final File incoming = new File(staging, "incoming");
        final File deep = new File(incoming, "trips/2026");
        assertThat(deep.mkdirs()).isTrue();

        final File moved = new File(root, "incoming");
        assertThat(incoming.renameTo(moved)).isTrue();

        final File movedDeep = new File(moved, "trips/2026");
        awaitWatched(movedDeep);
        // Watching only the top level of an arriving tree left the descendants it already had
        // unobserved; nothing below them ever reported.
        assertDeletionInsideIsReported(movedDeep);
    }

    @Test
    public void aDirectoryMovedOutTakesItsDescendantsOffTheWatchList() throws Exception {
        final File photos = new File(root, "photos");
        final File trips = new File(photos, "trips");
        assertThat(trips.mkdirs()).isTrue();
        awaitWatched(trips);

        assertThat(photos.renameTo(new File(staging, "photos"))).isTrue();

        await("the watches below " + photos + " to be dropped", () -> {
            final List<File> watched = observer.watchedDirectories();
            return !watched.contains(photos) && !watched.contains(trips);
        });
        assertThat(observer.watchedDirectories()).contains(root);
    }

    /** Deletes a file in {@code directory} and waits for it to reach {@code onEvent}. */
    private void assertDeletionInsideIsReported(final File directory) throws Exception {
        final File file = new File(directory, "image.jpg");
        assertThat(file.createNewFile()).isTrue();
        assertThat(file.delete()).isTrue();

        await(file + " to be reported deleted", () -> reported.contains(file));
    }

    private void awaitWatched(final File directory) throws InterruptedException {
        await(directory + " to be watched", () -> observer.watchedDirectories().contains(directory));
    }

    private void await(final String what, final BooleanSupplier condition) throws InterruptedException {
        final long deadline = SystemClock.uptimeMillis() + TIMEOUT_MS;
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(25);
        }
        fail("timed out after " + TIMEOUT_MS + "ms waiting for " + what
                + "; watching " + observer.watchedDirectories() + ", reported " + reported);
    }
}
