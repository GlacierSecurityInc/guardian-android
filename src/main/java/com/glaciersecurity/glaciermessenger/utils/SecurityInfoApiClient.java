package com.glaciersecurity.glaciermessenger.utils;

import androidx.annotation.NonNull;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.http.mobile.MobileApiClient;
import com.glaciersecurity.glaciermessenger.http.mobile.MobileApiResponses;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import org.json.JSONObject;

import java.io.IOException;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public final class SecurityInfoApiClient {

    private SecurityInfoApiClient() {
    }

    public interface UpdateCallback {
        void onSuccess();

        void onFailure(@NonNull String message, Throwable t);
    }

    public static void updateSecurityInfo(
            @NonNull String organization,
            @NonNull String username,
            @NonNull String securityInfo,
            @NonNull UpdateCallback callback) {
        try {
            final MobileApiResponses.SecurityInfoUpdateRequest request =
                    buildRequest(organization, username, securityInfo);
            MobileApiClient.service().updateSecurityInfo(request).enqueue(
                    new Callback<Void>() {
                        @Override
                        public void onResponse(Call<Void> call, Response<Void> response) {
                            if (response.isSuccessful()) {
                                callback.onSuccess();
                                return;
                            }
                            callback.onFailure(
                                    "Security info upload failed. code="
                                            + response.code()
                                            + ", backendMessage="
                                            + parseBackendMessage(readErrorBody(response)),
                                    null);
                        }

                        @Override
                        public void onFailure(Call<Void> call, Throwable t) {
                            callback.onFailure("Security info upload request failed", t);
                        }
                    });
        } catch (Exception e) {
            callback.onFailure("Security info upload request build failed", e);
        }
    }

    /**
     * Synchronous variant for callers already running on a background thread (e.g. a
     * WorkManager {@code Worker}) that need to block until the upload completes rather than
     * juggle a callback plus a latch of their own.
     */
    public static void updateSecurityInfoSync(
            @NonNull String organization,
            @NonNull String username,
            @NonNull String securityInfo) throws IOException {
        final MobileApiResponses.SecurityInfoUpdateRequest request =
                buildRequest(organization, username, securityInfo);
        final Response<Void> response =
                MobileApiClient.service().updateSecurityInfo(request).execute();
        if (!response.isSuccessful()) {
            throw new IOException(
                    "Security info upload failed. code="
                            + response.code()
                            + ", backendMessage="
                            + parseBackendMessage(readErrorBody(response)));
        }
    }

    private static MobileApiResponses.SecurityInfoUpdateRequest buildRequest(
            @NonNull String organization,
            @NonNull String username,
            @NonNull String securityInfo) {
        final JsonElement securityElement = JsonParser.parseString(securityInfo);
        return new MobileApiResponses.SecurityInfoUpdateRequest(organization, username, securityElement);
    }

    private static String readErrorBody(Response<?> response) {
        try {
            return response.errorBody() != null ? response.errorBody().string() : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static String parseBackendMessage(String responseBody) {
        if (responseBody == null || responseBody.trim().isEmpty()) {
            return "empty response body";
        }
        try {
            final JSONObject json = new JSONObject(responseBody);
            final String message = json.optString("message", "");
            if (!message.isEmpty()) {
                return message;
            }
            final String error = json.optString("error", "");
            if (!error.isEmpty()) {
                return error;
            }
        } catch (Exception ignored) {
            Log.w(Config.LOGTAG, "Security info upload error response is not JSON");
        }
        return responseBody;
    }
}
