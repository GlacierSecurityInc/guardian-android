package com.glaciersecurity.glaciermessenger.utils;


import android.os.FileObserver;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Stack;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import com.glaciersecurity.glaciermessenger.Config;

/**
 * Copyright (C) 2012 Bartek Przybylski
 * Copyright (C) 2015 ownCloud Inc.
 * Copyright (C) 2016 Daniel Gultsch
 */

public abstract class ConversationsFileObserver {
    private static final Executor EVENT_EXECUTOR = Executors.newSingleThreadExecutor();
    // DELETE_SELF and MOVE_SELF are not reported to the parent's watch, and the root has no parent
    // watch at all, so without them a vanished directory keeps a dead observer on the list.
    private static final int MASK = FileObserver.DELETE | FileObserver.MOVED_FROM | FileObserver.MOVED_TO | FileObserver.CREATE
            | FileObserver.DELETE_SELF | FileObserver.MOVE_SELF;
    // Levels below the watched root, not from the filesystem root. 5 is what the old absolute
    // bound of 8 worked out to for /storage/emulated/0, which sits 3 down from /.
    private static final int MAX_DEPTH = 5;


    private final String path;
    private final File root;
    private final List<SingleFileObserver> mObservers = new ArrayList<>();
    private final AtomicBoolean shouldStop = new AtomicBoolean(true);

    protected ConversationsFileObserver(String path) {
        this.path = path;
        this.root = new File(path).getAbsoluteFile();
    }

    public synchronized void startWatching() {
        if (!shouldStop.get()) {
            return;
        }
        shouldStop.set(false);
        startWatchingInternal();
    }

    private synchronized void startWatchingInternal() {
        watchTree(new File(path));
    }

    /**
     * Walks {@code start} and everything watchable below it, starting an observer per directory.
     *
     * <p>Each watch starts the moment it is created rather than after the walk, so a tree that
     * takes a while to walk is not unwatched while it is being walked -- and because the stack
     * hands back the directory it was given first, that directory is covered before any of its
     * children are visited. Nothing observes a half-built list as a result: every event runs on
     * the single-threaded {@link #EVENT_EXECUTOR} and every method that touches
     * {@link #mObservers} is synchronized, so an event raised mid-walk queues behind the walk
     * instead of racing it.
     */
    private synchronized void watchTree(final File start) {
        final Stack<File> stack = new Stack<>();
        stack.push(start);

        while (!stack.empty()) {
            if (shouldStop.get()) {
                return;
            }
            final File directory = stack.pop();
            if (observing(directory)) {
                continue;
            }
            final SingleFileObserver observer = new SingleFileObserver(directory, MASK);
            mObservers.add(observer);
            observer.startWatching();
            final File[] files = directory.listFiles();
            for (final File file : (files == null ? new File[0] : files)) {
                if (shouldStop.get()) {
                    return;
                }
                if (file.isDirectory() && isWatchable(file) && !stack.contains(file) && !observing(file)) {
                    stack.push(file);
                }
            }
        }
    }

    /**
     * The bounds the walk keeps to: nothing hidden, nothing more than {@link #MAX_DEPTH} levels
     * below {@link #root}.
     */
    private boolean isWatchable(final File directory) {
        final String name = directory.getName();
        if (name.isEmpty() || name.charAt(0) == '.') {
            return false;
        }
        final int depth = depthBelowRoot(directory);
        return depth >= 0 && depth <= MAX_DEPTH;
    }

    /**
     * Levels between {@code file} and {@link #root}, or -1 when it is not below it at all.
     *
     * <p>Compared lexically rather than canonically: every candidate arrives here either from
     * {@code listFiles()} on a watched directory or from an inotify event on one, so it is already
     * spelled as a descendant of the root and resolving symlinks would only cost a stat per
     * directory of the walk.
     */
    private int depthBelowRoot(File file) {
        int depth = 0;
        for (File f = file.getAbsoluteFile(); f != null; f = f.getParentFile()) {
            if (f.equals(root)) {
                return depth;
            }
            depth++;
        }
        return -1;
    }

    /**
     * Watches a directory that arrived after the initial walk, along with anything already inside
     * it -- a directory moved in arrives whole, and its existing descendants produce no events of
     * their own.
     *
     * <p>The observers have to be held in {@link #mObservers}, not just started: a
     * {@link FileObserver} stops delivering events once it is collected, and {@link #stopWatching()}
     * only knows about the observers on that list. Synchronized because this runs on the event
     * executor while {@link #startWatchingInternal()} may be iterating.
     *
     * <p>Held observers accumulate, so this applies the same {@link #isWatchable} bounds the initial
     * walk does -- otherwise a hidden or deep directory tree could grow the watch set without limit
     * and exhaust the inotify instance.
     */
    private synchronized void watchDirectory(final File directory) {
        if (shouldStop.get() || !isWatchable(directory) || observing(directory)) {
            return;
        }
        watchTree(directory);
    }

    /**
     * Drops the observers for a directory that is gone, including the ones below it: a subtree
     * moved out of the watched root reports only on the directory that moved, and its descendants
     * would otherwise keep watching a tree we no longer care about.
     *
     * <p>Dropping them is what lets the path be watched again. The inotify watch dies with the
     * directory, so an observer left on the list only makes {@link #observing(File)} refuse a new
     * watch if something is later created at the same path.
     */
    private synchronized void unwatchDirectory(final File directory) {
        final String absolute = directory.getAbsolutePath();
        final String prefix = absolute + File.separator;
        for (final Iterator<SingleFileObserver> iterator = mObservers.iterator(); iterator.hasNext(); ) {
            final SingleFileObserver observer = iterator.next();
            final String observed = observer.path.getAbsolutePath();
            if (observed.equals(absolute) || observed.startsWith(prefix)) {
                observer.stopWatching();
                iterator.remove();
            }
        }
    }

    private boolean observing(final File path) {
        for (final SingleFileObserver observer : mObservers) {
            if (path.equals(observer.path)) {
                return true;
            }
        }
        return false;
    }

    public void stopWatching() {
        shouldStop.set(true);
        stopWatchingInternal();
    }

    private synchronized void stopWatchingInternal() {
        for (FileObserver observer : mObservers) {
            observer.stopWatching();
        }
        mObservers.clear();
    }

    abstract public void onEvent(File path);

    public void restartWatching() {
        stopWatching();
        startWatching();
    }

    /**
     * The dispatch {@link SingleFileObserver} hands to the event executor. Visible for testing so
     * the watch bookkeeping can be driven without a real inotify watch.
     *
     * @param directory the watched directory the event was reported on
     */
    void handleEvent(final File directory, final int event, final String filename) {
        if ((event & (FileObserver.DELETE_SELF | FileObserver.MOVE_SELF)) != 0) {
            // Reported on the watched directory itself, so it carries no filename. The parent's
            // watch reports the same departure with a name and is what reaches onEvent(File).
            unwatchDirectory(directory);
            return;
        }
        if (filename == null) {
            return;
        }
        final File file = new File(directory, filename);
        // Arrivals, not departures: a directory moved in needs its own watch, and a file
        // moved in must not reach onEvent, which every implementation reads as a deletion.
        if ((event & (FileObserver.CREATE | FileObserver.MOVED_TO)) != 0) {
            if (file.isDirectory()) {
                watchDirectory(file);
            }
            return;
        }
        if ((event & (FileObserver.DELETE | FileObserver.MOVED_FROM)) != 0) {
            // Unconditionally: the entry is already gone, so isDirectory() can no longer tell us
            // it was a directory. unwatchDirectory matches by path and ignores everything else.
            unwatchDirectory(file);
        }
        onEvent(file);
    }

    /** Visible for testing: the directories currently watched, in the order they were added. */
    synchronized List<File> watchedDirectories() {
        final List<File> directories = new ArrayList<>(mObservers.size());
        for (final SingleFileObserver observer : mObservers) {
            directories.add(observer.path);
        }
        return directories;
    }

    private class SingleFileObserver extends FileObserver {
        private final File path;

        SingleFileObserver(final File path, final int mask) {
            super(path.getAbsolutePath(), mask);
            this.path = path;
        }

        @Override
        public void onEvent(final int event, final String filename) {
            EVENT_EXECUTOR.execute(() -> handleEvent(this.path, event, filename));
        }
    }
}
