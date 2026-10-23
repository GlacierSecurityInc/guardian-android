package com.glaciersecurity.glaciermessenger.http.mobile;

import androidx.annotation.NonNull;

import com.glaciersecurity.glaciermessenger.EndPointConfig;
import com.glaciersecurity.glaciermessenger.Endpoints;
import com.glaciersecurity.glaciermessenger.utils.OkHttpClientProvider;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class MobileUploadClient {

    private static final String DEFAULT_UPLOAD_FAILURE_MESSAGE = "Upload failed";
    private static final MediaType OCTET_STREAM = MediaType.parse("application/octet-stream");
    private static final long DEFAULT_MAX_UPLOAD_BYTES = 100L * 1024L * 1024L;
    private static final Map<String, Long> ENDPOINT_MAX_UPLOAD_BYTES =
            createEndpointMaxUploadBytes();

    public static final class UploadHttpException extends IOException {
        private final int statusCode;
        @NonNull private final String responseBody;
        @NonNull private final String displayMessage;

        public UploadHttpException(
                final int statusCode,
                @NonNull final String responseBody,
                @NonNull final String displayMessage) {
            super(displayMessage);
            this.statusCode = statusCode;
            this.responseBody = responseBody;
            this.displayMessage = displayMessage;
        }

        public int getStatusCode() {
            return statusCode;
        }

        @NonNull
        public String getResponseBody() {
            return responseBody;
        }

        @NonNull
        public String getDisplayMessage() {
            return displayMessage;
        }
    }

    public interface UploadCallback {
        void onSuccess();

        void onFailure(@NonNull Throwable throwable);
    }

    public static final class UploadTooLargeException extends IOException {
        private final long fileSizeBytes;
        private final long maxUploadBytes;
        @NonNull private final String endpoint;

        private UploadTooLargeException(
                @NonNull final String endpoint,
                @NonNull final File file,
                final long fileSizeBytes,
                final long maxUploadBytes) {
            super(
                    "File too large to upload: "
                            + file.getAbsolutePath()
                            + " is "
                            + formatBytes(fileSizeBytes)
                            + " ("
                            + fileSizeBytes
                            + " bytes), limit is "
                            + formatBytes(maxUploadBytes)
                            + " ("
                            + maxUploadBytes
                            + " bytes) for endpoint "
                            + endpoint);
            this.endpoint = endpoint;
            this.fileSizeBytes = fileSizeBytes;
            this.maxUploadBytes = maxUploadBytes;
        }

        public long getFileSizeBytes() {
            return fileSizeBytes;
        }

        public long getMaxUploadBytes() {
            return maxUploadBytes;
        }

        @NonNull
        public String getEndpoint() {
            return endpoint;
        }
    }

    private MobileUploadClient() {
    }

    public static void uploadFile(
            @NonNull final String endpoint,
            @NonNull final File file,
            @NonNull final String path,
            @NonNull final UploadCallback callback) {
        if (!file.exists() || !file.isFile()) {
            callback.onFailure(new IOException("File not found: " + file.getAbsolutePath()));
            return;
        }

        final long maxUploadBytes = getMaxUploadBytes(endpoint);
        final long fileSizeBytes = file.length();
        if (maxUploadBytes > 0 && fileSizeBytes > maxUploadBytes) {
            callback.onFailure(
                    new UploadTooLargeException(endpoint, file, fileSizeBytes, maxUploadBytes));
            return;
        }

        final RequestBody multipartBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                        "file",
                        file.getName(),
                        RequestBody.create(file, OCTET_STREAM))
                .addFormDataPart("path", path)
                .build();

        final Request request = new Request.Builder()
                .url(buildUrl(endpoint))
                .post(multipartBody)
                .build();

        OkHttpClientProvider.getOkHttpClient()
                .newCall(request)
                .enqueue(new okhttp3.Callback() {
                    @Override
                    public void onFailure(@NonNull okhttp3.Call call, @NonNull IOException e) {
                        callback.onFailure(e);
                    }

                    @Override
                    public void onResponse(@NonNull okhttp3.Call call, @NonNull Response response)
                            throws IOException {
                        try (Response ignored = response) {
                            if (response.isSuccessful()) {
                                callback.onSuccess();
                                return;
                            }

                            final String responseBody = response.body() == null
                                    ? ""
                                    : response.body().string();
                            callback.onFailure(
                                    new UploadHttpException(
                                            response.code(),
                                            responseBody,
                                            buildHttpFailureMessage(response.code(), responseBody)));
                        }
                    }
                });
    }

    @NonNull
    private static String buildHttpFailureMessage(final int statusCode, @NonNull final String responseBody) {
        final String trimmedBody = responseBody.trim();
        if (trimmedBody.isEmpty()) {
            return DEFAULT_UPLOAD_FAILURE_MESSAGE + " (HTTP " + statusCode + ")";
        }
        return DEFAULT_UPLOAD_FAILURE_MESSAGE + " (HTTP " + statusCode + "): " + trimmedBody;
    }

    @NonNull
    public static boolean isUploadTooLarge(@NonNull final Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof UploadTooLargeException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    public static long getMaxUploadBytes(@NonNull final String endpoint) {
        final Long endpointLimit = ENDPOINT_MAX_UPLOAD_BYTES.get(endpoint);
        return endpointLimit == null ? DEFAULT_MAX_UPLOAD_BYTES : endpointLimit;
    }

    private static String buildUrl(@NonNull final String endpoint) {
        return Endpoints.url(endpoint);
    }

    @NonNull
    private static Map<String, Long> createEndpointMaxUploadBytes() {
        final Map<String, Long> endpointLimits = new HashMap<>();
        endpointLimits.put(EndPointConfig.FILESAFE_UPLOAD_URL, DEFAULT_MAX_UPLOAD_BYTES);
        endpointLimits.put(EndPointConfig.BUG_REPORT_UPLOAD_URL, DEFAULT_MAX_UPLOAD_BYTES);
        endpointLimits.put(EndPointConfig.MEDIA_UPLOAD_URL, DEFAULT_MAX_UPLOAD_BYTES);
        return Collections.unmodifiableMap(endpointLimits);
    }

    @NonNull
    private static String formatBytes(final long bytes) {
        if (bytes >= 1024L * 1024L) {
            return String.format(Locale.US, "%.1f MiB", bytes / (1024d * 1024d));
        }
        if (bytes >= 1024L) {
            return String.format(Locale.US, "%.1f KiB", bytes / 1024d);
        }
        return bytes + " B";
    }
}
