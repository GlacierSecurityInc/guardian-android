package com.glaciersecurity.glaciermessenger.utils;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;

import com.glaciersecurity.glaciermessenger.BuildConfig;
import com.glaciersecurity.glaciermessenger.services.NotificationService;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.Thread.UncaughtExceptionHandler;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public class ExceptionHandler implements UncaughtExceptionHandler {

    private static final Pattern JID_PATTERN =
            Pattern.compile("\\b[\\w.+%-]+@[\\w.-]+(?:/[\\w.+%-]+)?\\b");
    private static final Pattern UUID_PATTERN =
            Pattern.compile(
                    "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}\\b");
    private static final Pattern FILE_PATH_PATTERN =
            Pattern.compile("(?<![\\w.])(?>/[\\w.\\- ]+)+|[A-Za-z]:\\\\(?:[^\\\\\\r\\n]+\\\\)*[^\\\\\\r\\n]*");

    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ENGLISH);

    private final UncaughtExceptionHandler defaultHandler;
    private final Context context;

    ExceptionHandler(final Context context) {
        this.context = context;
        this.defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
    }

    @Override
    public void uncaughtException(@NonNull Thread thread, final Throwable throwable) {
        NotificationService.cancelIncomingCallNotification(context);
        final String stacktrace;
        try (final StringWriter stringWriter = new StringWriter();
                final PrintWriter printWriter = new PrintWriter(stringWriter)) {
            throwable.printStackTrace(printWriter);
            stacktrace = stringWriter.toString();
        } catch (final IOException e) {
            return;
        }
        final List<String> report =
                ImmutableList.of(
                        String.format(
                                "Version: %s %s", BuildConfig.APP_NAME, BuildConfig.VERSION_NAME),
                        String.format("Manufacturer: %s", Strings.nullToEmpty(Build.MANUFACTURER)),
                        String.format("Device: %s", Strings.nullToEmpty(Build.DEVICE)),
                        String.format("Timestamp: %s", DATE_FORMAT.format(new Date())),
                        sanitizeSensitiveValues(stacktrace));
        ExceptionHelper.writeToStacktraceFile(context, sanitizeSensitiveValues(Joiner.on("\n").join(report)));
        this.defaultHandler.uncaughtException(thread, throwable);
    }

    private static String sanitizeSensitiveValues(final String text) {
        String sanitized = JID_PATTERN.matcher(text).replaceAll("[REDACTED_JID]");
        sanitized = UUID_PATTERN.matcher(sanitized).replaceAll("[REDACTED_UUID]");
        sanitized = FILE_PATH_PATTERN.matcher(sanitized).replaceAll("[REDACTED_PATH]");
        return sanitized;
    }
}
