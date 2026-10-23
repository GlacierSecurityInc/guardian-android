package com.glaciersecurity.glaciermessenger.utils;

import android.database.AbstractWindowedCursor;
import android.database.Cursor;
import android.database.CursorWindow;
import android.database.sqlite.SQLiteCursor;

import com.glaciersecurity.glaciermessenger.Config;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class CursorUtils {

    /**
     * The default cursor window is 2 MB, which a conversation carrying large message bodies can
     * overflow -- the symptom is "Row too big to fit into CursorWindow" and a failed load.
     */
    private static final int WINDOW_SIZE_BYTES = 4 * 1024 * 1024;

    /** Cursor classes already reported as unhandled, so the warning does not repeat per query. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    /**
     * Grows the cursor window so large rows load.
     *
     * <p>SQLCipher does not reuse the framework cursor classes. Its cursors are
     * {@code net.zetetic.database.sqlcipher.SQLiteCursor} extending
     * {@code net.zetetic.database.AbstractWindowedCursor}, and its windows are
     * {@code net.zetetic.database.CursorWindow} -- none of which is an {@code android.database}
     * type. So once a query comes from an encrypted database, <b>both</b> of the framework
     * {@code instanceof} checks below stop matching, and the only visible effect is that large
     * conversations start failing to load again. Nothing throws, and no log line appears, which is
     * why this method now reports a cursor it does not recognise instead of silently doing nothing.
     *
     * <p>{@code setFillWindowForwardOnly} has <b>no SQLCipher equivalent</b> -- its
     * {@code SQLiteCursor} does not expose it -- so encrypted reads keep the larger window but lose
     * the forward-only fill. That is a throughput hint rather than a correctness one, and there is
     * nothing to call in its place.
     *
     * <p>SQLCipher also offers a process-wide {@code SQLiteCursor.setCursorWindowSize(int)}, which
     * is a better lever than sizing each cursor here and is worth adopting when {@code
     * DatabaseBackend} moves onto SQLCipher -- at that point every cursor gets the larger window,
     * including any that never reach this method.
     */
    public static void upgradeCursorWindowSize(final Cursor cursor) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) {
            return;
        }
        boolean handled = false;

        if (cursor instanceof AbstractWindowedCursor) {
            final AbstractWindowedCursor windowedCursor = (AbstractWindowedCursor) cursor;
            windowedCursor.setWindow(new CursorWindow("4M", WINDOW_SIZE_BYTES));
            handled = true;
        }
        if (cursor instanceof SQLiteCursor) {
            ((SQLiteCursor) cursor).setFillWindowForwardOnly(true);
            handled = true;
        }

        if (cursor instanceof net.zetetic.database.AbstractWindowedCursor) {
            ((net.zetetic.database.AbstractWindowedCursor) cursor)
                    .setWindow(new net.zetetic.database.CursorWindow("4M", WINDOW_SIZE_BYTES));
            handled = true;
        }

        if (!handled && cursor != null) {
            warnOnce(cursor);
        }
    }

    /**
     * Reports a windowed cursor this method does not know how to enlarge, once per class.
     *
     * <p>The failure this guards against is silence: a cursor implementation that matches none of
     * the branches above keeps the 2 MB default and fails only on unusually large rows, in the
     * field, with no clue pointing here.
     */
    private static void warnOnce(final Cursor cursor) {
        final String name = cursor.getClass().getName();
        if (WARNED.add(name)) {
            Log.w(
                    Config.LOGTAG,
                    "cursor window left at the default size: "
                            + name
                            + " matches no known windowed-cursor type, so large rows may fail to"
                            + " load. Add a branch for it in CursorUtils.");
        }
    }
}
