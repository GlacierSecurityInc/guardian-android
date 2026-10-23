package com.glaciersecurity.glaciermessenger.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.FileObserver;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Drives {@link ConversationsFileObserver#handleEvent(File, int, String)} directly against a real
 * directory tree: a unit test gets no inotify watch, so the events an observer would receive are
 * fed in by hand while the filesystem is put in the state the kernel would have reported.
 */
public class ConversationsFileObserverTest {

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private File root;
    private List<File> reported;
    private ConversationsFileObserver observer;

    @Before
    public void setUp() {
        root = temporaryFolder.getRoot();
        reported = new ArrayList<>();
        observer = new ConversationsFileObserver(root.getAbsolutePath()) {
            @Override
            public void onEvent(final File path) {
                reported.add(path);
            }
        };
    }

    @After
    public void tearDown() {
        observer.stopWatching();
    }

    @Test
    public void initialWalkWatchesEveryDirectoryBelowTheRoot() throws IOException {
        final File nested = newFolder("photos", "trips");
        observer.startWatching();

        assertWatched(root);
        assertWatched(nested.getParentFile());
        assertWatched(nested);
    }

    @Test
    public void deletedDirectoryIsUnwatchedAndCanBeWatchedAgain() throws IOException {
        final File photos = newFolder("photos");
        final File trips = newFolder("photos", "trips");
        observer.startWatching();

        assertTrue(trips.delete());
        assertTrue(photos.delete());
        observer.handleEvent(root, FileObserver.DELETE, "photos");

        assertNotWatched(photos);
        assertNotWatched(trips);
        assertEquals("the deletion still reaches subclasses", List.of(photos), reported);

        // The stale observer used to make observing() refuse a watch on the recreated directory.
        assertTrue(photos.mkdir());
        observer.handleEvent(root, FileObserver.CREATE, "photos");
        assertWatched(photos);
    }

    @Test
    public void directoryMovedOutTakesItsDescendantsOffTheWatchList() throws IOException {
        final File photos = newFolder("photos");
        final File trips = newFolder("photos", "trips");
        observer.startWatching();

        observer.handleEvent(root, FileObserver.MOVED_FROM, "photos");

        assertNotWatched(photos);
        assertNotWatched(trips);
        assertWatched(root);
    }

    @Test
    public void selfEventOnAVanishedDirectoryDropsItsSubtree() throws IOException {
        final File photos = newFolder("photos");
        final File trips = newFolder("photos", "trips");
        observer.startWatching();

        observer.handleEvent(photos, FileObserver.MOVE_SELF, null);

        assertNotWatched(photos);
        assertNotWatched(trips);
        assertWatched(root);
        assertTrue("a self event carries no filename to report", reported.isEmpty());
    }

    @Test
    public void directoryMovedInIsWatchedAlongWithWhatItAlreadyContains() throws IOException {
        observer.startWatching();
        final File photos = newFolder("photos");
        final File trips = newFolder("photos", "trips");

        observer.handleEvent(root, FileObserver.MOVED_TO, "photos");

        assertWatched(photos);
        assertWatched(trips);
        assertTrue("an arrival is not a deletion", reported.isEmpty());
    }

    @Test
    public void aMovedInTreeKeepsToTheDepthAndHiddenBounds() throws IOException {
        observer.startWatching();
        final File deep = newFolder("d1", "d2", "d3", "d4", "d5", "d6");
        final File hidden = newFolder("d1", ".thumbnails");

        observer.handleEvent(root, FileObserver.MOVED_TO, "d1");

        assertWatched(deep.getParentFile());
        assertNotWatched(deep);
        assertNotWatched(hidden);
    }

    @Test
    public void fileMovedInIsNotReportedAsADeletion() throws IOException {
        observer.startWatching();
        temporaryFolder.newFile("image.jpg");

        observer.handleEvent(root, FileObserver.MOVED_TO, "image.jpg");

        assertTrue(reported.isEmpty());
    }

    @Test
    public void deletedFileIsReportedAndLeavesTheWatchListAlone() throws IOException {
        final File photos = newFolder("photos");
        observer.startWatching();
        final int watchCount = observer.watchedDirectories().size();

        observer.handleEvent(photos, FileObserver.DELETE, "image.jpg");

        assertEquals(List.of(new File(photos, "image.jpg")), reported);
        assertEquals(watchCount, observer.watchedDirectories().size());
    }

    private File newFolder(final String... path) throws IOException {
        final File folder = new File(root, String.join(File.separator, path));
        assertTrue("could not create " + folder, folder.isDirectory() || folder.mkdirs());
        return folder;
    }

    private void assertWatched(final File directory) {
        assertTrue(directory + " should be watched, watching " + observer.watchedDirectories(),
                observer.watchedDirectories().contains(directory));
    }

    private void assertNotWatched(final File directory) {
        assertFalse(directory + " should not be watched, watching " + observer.watchedDirectories(),
                observer.watchedDirectories().contains(directory));
    }
}
