/**
 * Copyright (C) 2010-2012 Regis Montoya (aka r3gis - www.r3gis.fr)
 * This file is part of CSipSimple.
 *
 *  CSipSimple is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *  If you own a pjsip commercial license you can also redistribute it
 *  and/or modify it under the terms of the GNU Lesser General Public License
 *  as an android library.
 *
 *  CSipSimple is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with CSipSimple.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.glaciersecurity.glaciermessenger.utils;

import com.glaciersecurity.glaciermessenger.Config;

/**
 The logLevel variable controls what is seen when using logcat while using the app.
 Available values are 0 through 5, with each level providing more detail than the previous level.
 Available logging levels are as follows:

 logLevel = 0    No logging (Use this for a production release)
 logLevel = 1    Error logging (Only shows errors)
 logLevel = 2    Warning logging (Shows warnings and errors)
 logLevel = 3    Info logging (Shows info, warnings, and errors)
 logLevel = 4    Debug logging (Shows debug logging, as well as info, warnings, and errors)
 logLevel = 5    Verbose logging (Essentially shows all available logging)
 */

public class Log {
    private static int logLevel = Config.logLevel;  // SET THIS TO ZERO FOR PRODUCTION RELEASES

    /**
     * Log verbose
     * @param tag Tag for this log
     * @param msg Msg for this log
     */
    public static void v(String tag, String msg) {
        if(logLevel >= 5) {
            android.util.Log.v(tag, sanitize(msg));
        }
    }

    /**
     * Log verbose
     * @param tag Tag for this log
     * @param msg Msg for this log
     * @param tr Error to serialize in log
     */
    public static void v(String tag, String msg, Throwable tr) {
        if(logLevel >= 5) {
            android.util.Log.v(tag, sanitize(msg), tr);
        }
    }

    /**
     * Log debug
     * @param tag Tag for this log
     * @param msg Msg for this log
     */
    public static void d(String tag, String msg) {
        if(logLevel >= 4) {
            android.util.Log.d(tag, sanitize(msg));
        }
    }

    /**
     * Log debug
     * @param tag Tag for this log
     * @param msg Msg for this log
     * @param tr Error to serialize in log
     */
    public static void d(String tag, String msg, Throwable tr) {
        if(logLevel >= 4) {
            android.util.Log.d(tag, sanitize(msg), tr);
        }
    }

    /**
     * Log info
     * @param tag Tag for this log
     * @param msg Msg for this log
     */
    public static void i(String tag, String msg) {
        if(logLevel >= 3) {
            android.util.Log.i(tag, sanitize(msg));
        }
    }

    /**
     * Log info
     * @param tag Tag for this log
     * @param msg Msg for this log
     * @param tr Error to serialize in log
     */
    public static void i(String tag, String msg, Throwable tr) {
        if(logLevel >= 3) {
            android.util.Log.i(tag, sanitize(msg), tr);
        }
    }

    /**
     * Log warning
     * @param tag Tag for this log
     * @param msg Msg for this log
     */
    public static void w(String tag, String msg) {
        if(logLevel >= 2) {
            android.util.Log.w(tag, sanitize(msg));
        }
    }

    /**
     * Log warning
     * @param tag Tag for this log
     * @param msg Msg for this log
     * @param tr Error to serialize in log
     */
    public static void w(String tag, String msg, Throwable tr) {
        if(logLevel >= 2) {
            android.util.Log.w(tag, sanitize(msg), tr);
        }
    }

    /**
     * Log error
     * @param tag Tag for this log
     * @param msg Msg for this log
     */
    public static void e(String tag, String msg) {
        if(logLevel >= 1) {
            android.util.Log.e(tag, sanitize(msg));
        }
    }

    /**
     * Log error
     * @param tag Tag for this log
     * @param msg Msg for this log
     * @param tr Error to serialize in log
     */
    public static void e(String tag, String msg, Throwable tr) {
        if(logLevel >= 1) {
            android.util.Log.e(tag, sanitize(msg), tr);
        }
    }

    /**
     * Strips line terminators and other control characters from a log message.
     *
     * <p>Logcat is line-oriented, so a message carrying a CR or LF can forge extra
     * log lines and fabricate or bury events (CWE-117). Plenty of messages in this
     * app interpolate strings the app did not author — XMPP element and namespace
     * names straight off {@code XmlReader}, and {@code Intent} extras handed to
     * exported receivers — so the scrubbing lives here, at the one point every call
     * site funnels through, instead of at each of them.
     *
     * <p>The common case is a message with nothing to strip; that returns the
     * original instance without copying.
     *
     * <p>The {@code Throwable} overloads deliberately do not scrub {@code tr}. Its
     * stack trace is legitimately multi-line, so flattening it would destroy the
     * one thing it is logged for, and a {@code Throwable}'s message cannot be
     * rewritten in place. The residue is that a peer who controls an exception's
     * message text can still forge a line inside the trace block; callers who
     * interpolate remote text should put it in {@code msg}, which is scrubbed,
     * rather than rely on the exception carrying it.
     *
     * @param msg the caller's message, possibly {@code null}
     * @return {@code msg} with CR, LF, the other C0 controls and DEL replaced by
     *         spaces, or {@code msg} unchanged if it holds none of them
     */
    private static String sanitize(final String msg) {
        if (msg == null || msg.isEmpty()) {
            return msg;
        }
        for (int i = 0; i < msg.length(); i++) {
            final char c = msg.charAt(i);
            if (c < ' ' || c == '\u007f') {
                return msg.replaceAll("[\\r\\n\\u0000-\\u001f\\u007f]", " ");
            }
        }
        return msg;
    }
}
