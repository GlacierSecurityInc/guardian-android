package com.glaciersecurity.glaciermessenger.utils;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.amplifyframework.auth.AuthChannelEventName;
import com.amplifyframework.auth.cognito.AWSCognitoAuthSession;
import com.amplifyframework.auth.cognito.result.AWSCognitoAuthSignOutResult;
import com.amplifyframework.core.Amplify;
import com.amplifyframework.hub.HubChannel;
import com.glaciersecurity.glaciermessenger.BuildConfig;
import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.EndPointConfig;
import com.glaciersecurity.glaciermessenger.GlacierGuardian;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.entities.CognitoAccount;
import com.glaciersecurity.glaciermessenger.crypto.GlacierCertificatePinner;
import com.glaciersecurity.glaciermessenger.http.fake.DestructiveWriteGuard;
import com.glaciersecurity.glaciermessenger.http.fake.FakeGlacierBackend;
import com.glaciersecurity.glaciermessenger.persistance.DatabaseBackend;
import com.glaciersecurity.glaciermessenger.services.ConnectivityReceiver;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

public class OkHttpClientProvider {
    private static Context context;
    public static final String ACTION_REAUTHENTICATION_REQUIRED =
            "com.glaciersecurity.glaciermessenger.action.REAUTHENTICATION_REQUIRED";

    private static final String API_VERSION_HEADER          = "X-Glacier-API-Version";
    private static final String AUTHORIZATION_HEADER        = "Authorization";
    private static final String ALLOW_OFFLINE_ATTEMPT_HEADER = "X-Allow-Offline-Attempt";
    private static final String MOBILE_API_PREFIX           = "/api/mobile/";
    private static final String MOBILE_TRACKING_API_PREFIX  = "/mobile/v1/input";
    private static final MediaType FORM_MEDIA_TYPE =
            MediaType.get("application/x-www-form-urlencoded");
    private static final MediaType JSON_MEDIA_TYPE =
            MediaType.get("application/json; charset=utf-8");
    private static final AtomicReference<String> CACHED_ACCESS_TOKEN = new AtomicReference<>();
    private static final AtomicLong CACHED_TOKEN_EXPIRY_MS = new AtomicLong(0L);
    private static final long TOKEN_EXPIRY_SKEW_MS = TimeUnit.SECONDS.toMillis(10);
    private static final long OKHTTP_CONNECT_TIMEOUT_SECONDS = 30L;
    private static final long COGNITO_TOKEN_FETCH_TIMEOUT_SECONDS = OKHTTP_CONNECT_TIMEOUT_SECONDS;
    private static final ReentrantLock REAUTH_WAIT_LOCK = new ReentrantLock();
    private static final AtomicReference<CountDownLatch> REAUTH_SIGNAL_LATCH =
            new AtomicReference<>(new CountDownLatch(1));
    private static final AtomicBoolean REAUTH_WAIT_CANCELLED = new AtomicBoolean(false);
    // elapsedRealtime, not wall clock: a backward clock correction would otherwise make the
    // elapsed comparison below negative and hold the guard closed until wall time caught up,
    // which is the very lockout this TTL exists to prevent. 0 means "no broadcast yet".
    private static final AtomicLong REAUTH_PROMPT_BROADCAST_AT_ELAPSED_MS = new AtomicLong(0L);
    // This guard collapses a burst of concurrent token failures into a single prompt; it is not
    // meant to suppress the prompt permanently. It has to expire, because a delivered broadcast
    // does not mean a dialog was shown - the receiving activity may not be one that can show it.
    // Without a TTL, a prompt that never appeared could never be retried for the life of the
    // process.
    private static final long REAUTH_PROMPT_GUARD_TTL_MS = TimeUnit.SECONDS.toMillis(10);
    private static final ScheduledExecutorService REAUTH_SNOOZE_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor();
    private static final AtomicReference<ScheduledFuture<?>> REAUTH_SNOOZE_RESET_FUTURE =
            new AtomicReference<>(null);
    private static final AtomicLong REAUTH_SNOOZED_UNTIL_MS = new AtomicLong(0L);
    private static final AtomicBoolean AUTH_HUB_LISTENER_REGISTERED = new AtomicBoolean(false);
    private static final AtomicBoolean SILENT_SIGN_IN_IN_PROGRESS = new AtomicBoolean(false);
    private static final List<String> MOBILE_API_ENDPOINTS = buildMobileApiEndpoints();
    private static final Set<String> OFFLINE_FAST_FAIL_ENDPOINTS = buildOfflineFastFailEndpoints();

    private static final Set<String> GLACIER_API_HOSTS = resolveGlacierApiHosts();

    private static Set<String> resolveGlacierApiHosts() {
        final Set<String> hosts = new HashSet<>();
        addResolvedHost(hosts, EndPointConfig.PROD_BASE, "PROD_BASE");
        addResolvedHost(hosts, EndPointConfig.DEV_BASE, "DEV_BASE");
        addResolvedHost(hosts, EndPointConfig.STAGING_BASE, "STAGING_BASE");

        if (hosts.isEmpty()) {
            Log.e(Config.LOGTAG,
                    "OkHttpClientProvider: no valid EndPointConfig base URLs found — "
                            + "JWT headers will NOT be attached to any request (fail-closed).");
            return null;
        }
        return hosts;
    }

    /**
     * Cloudflare-fronted hosts, pinned with their own CA set rather than the Amazon one.
     *
     * <p>Today that is the tracking host alone. It lives on a different domain from
     * PROD/DEV/STAGING_BASE, so it is not in {@link #GLACIER_API_HOSTS}, and it has to be pinned
     * here because that request carries GPS coordinates, username, organization, a device id and
     * an {@code Authorization: Bearer} Cognito token.
     *
     * <p>Kept separate from {@link #GLACIER_API_HOSTS} for two independent reasons. First, that
     * set also gates JWT attachment in {@link #shouldApplyMobileApiHeaders}, and
     * widening a security gate as a side effect of pinning would be two changes wearing one
     * name. Second, the two need <em>different</em> pin sets: Cloudflare issues from Google
     * Trust Services and Let's Encrypt, Glacier's own ALBs from Amazon Trust Services, and
     * neither list belongs on the other's hosts.
     *
     * <p>Being a separate argument also keeps the fail-closed guard honest: {@link
     * GlacierCertificatePinner#build} enforces it on the <em>API</em> host set specifically,
     * so a resolvable tracking host cannot satisfy that guard while
     * every Glacier API host goes unpinned. This set is an addition to API-host pinning and
     * cannot substitute for it.
     */
    private static final Set<String> CLOUDFLARE_PINNED_HOSTS = resolveCloudflarePinnedHosts();

    private static Set<String> resolveCloudflarePinnedHosts() {
        final Set<String> hosts = new HashSet<>();
        addResolvedHost(hosts, EndPointConfig.SEND_TRACKING_URL, "SEND_TRACKING_URL");
        return hosts.isEmpty() ? null : hosts;
    }

    private static void addResolvedHost(final Set<String> hosts,
                                        final String base,
                                        final String baseName) {
        if (TextUtils.isEmpty(base)) {
            return;
        }
        try {
            final String host = new URL(base).getHost();
            if (TextUtils.isEmpty(host)) {
                Log.w(Config.LOGTAG,
                        "OkHttpClientProvider: could not extract host from " + baseName + ".");
                return;
            }
            hosts.add(host.toLowerCase(Locale.ROOT));
        } catch (MalformedURLException e) {
            Log.w(Config.LOGTAG,
                    "OkHttpClientProvider: " + baseName + " is malformed.", e);
        }
    }

    public static void init(Context ctx) {
        context = ctx.getApplicationContext();
        ensureAuthHubListenerRegistered();
    }

    private OkHttpClientProvider() {
    }

    public static OkHttpClient getOkHttpClient() {
        return OkHttpClientHolder.INSTANCE;
    }

    private static OkHttpClient createClient() {
        final Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(64);
        dispatcher.setMaxRequestsPerHost(20);

        final ConnectionPool pool = new ConnectionPool(20, 5, TimeUnit.MINUTES);
        final okhttp3.CertificatePinner certificatePinner =
                GlacierCertificatePinner.build(GLACIER_API_HOSTS, CLOUDFLARE_PINNED_HOSTS);
        if (GLACIER_API_HOSTS != null && !GLACIER_API_HOSTS.isEmpty()) {
        }

        final OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder()
                .connectTimeout(OKHTTP_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .writeTimeout(45, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS)
                .certificatePinner(certificatePinner)
                .dispatcher(dispatcher)
                .connectionPool(pool);

        // First in the chain, and only in a -PglacierFakeBackend=true debug build. Returning
        // here short-circuits both interceptors below, so a faked call fetches no Cognito token
        // and opens no socket -- which is what lets the phone module be driven while signed out.
        // Null in every other build, leaving the chain exactly as it was.
        final okhttp3.Interceptor fakeBackend = FakeGlacierBackend.interceptorOrNull();
        if (fakeBackend != null) {
            clientBuilder.addInterceptor(fakeBackend);
        }

        // After the fake (which would have answered already) and before everything else: when
        // this build talks to a real tenant, the calls that spend money or destroy data are
        // refused outright. Null unless -PglacierProdWriteGuard=true.
        final okhttp3.Interceptor writeGuard = DestructiveWriteGuard.interceptorOrNull();
        if (writeGuard != null) {
            clientBuilder.addInterceptor(writeGuard);
        }

        return clientBuilder
                .addInterceptor(chain -> {
                    if (context == null) {
                        throw new IOException(
                                "OkHttpClientProvider not initialized: context is null (fail-closed)");
                    }
                    final Request request = chain.request();
                    if (shouldFastFailWhenOffline(request) && !ConnectivityReceiver.isConnected(context)) {
                        throw new IOException(
                                "Offline fast-fail for non-interactive request class");
                    }
                    return chain.proceed(stripOfflineControlHeaders(request));
                })
                .addInterceptor(chain -> {
                    final Request request = chain.request();
                    if (!shouldApplyMobileApiHeaders(request)) {
                        return chain.proceed(request);
                    }
                    final Request.Builder builder = request.newBuilder()
                            .header(API_VERSION_HEADER, BuildConfig.VERSION_NAME);
                    addJwtHeaders(builder);
                    return chain.proceed(builder.build());
                })
                .build();
    }

    private static final class OkHttpClientHolder {
        private static final OkHttpClient INSTANCE = createClient();
    }

    // ── Header routing ────────────────────────────────────────────────────────

    private static boolean shouldApplyMobileApiHeaders(final Request request) {
        // ── Gate 1: Host validation ────────────────────────────────────────
        if (GLACIER_API_HOSTS == null) {
            return false; // trusted host unknown — fail closed
        }
        final HttpUrl url = request.url();
        final String requestHost = url.host().toLowerCase(Locale.ROOT);
        if (!GLACIER_API_HOSTS.contains(requestHost)) {
            Log.w(Config.LOGTAG,
                    "OkHttpClientProvider: suppressing JWT for non-Glacier host");
            return false;
        }

        // ── Gate 2: Path validation (unchanged) ─────────────────────────────
        final String path = url.encodedPath();
        if (path == null) {
            return false;
        }
        if (path.startsWith(MOBILE_API_PREFIX)) {
            return true;
        }
        if (path.startsWith(MOBILE_TRACKING_API_PREFIX)) {
            return true;
        }
        for (final String endpoint : MOBILE_API_ENDPOINTS) {
            if (path.equals(endpoint)) {
                return true;
            }
        }
        return false;
    }

    private static boolean shouldFastFailWhenOffline(final Request request) {
        final HttpUrl url = request.url();
        final String path = url.encodedPath();
        if (path == null) {
            return false;
        }
        return OFFLINE_FAST_FAIL_ENDPOINTS.contains(path);
    }

    private static Request stripOfflineControlHeaders(final Request request) {
        if (request.header(ALLOW_OFFLINE_ATTEMPT_HEADER) == null) {
            return request;
        }
        return request.newBuilder()
                .removeHeader(ALLOW_OFFLINE_ATTEMPT_HEADER)
                .build();
    }

    // ── Endpoint list ─────────────────────────────────────────────────────────

    private static List<String> buildMobileApiEndpoints() {
        final List<String> endpoints = new ArrayList<>();
        addMobileEndpoint(endpoints, EndPointConfig.CHAT_ACCESS_TOKEN_URL);
        addMobileEndpoint(endpoints, EndPointConfig.GET_AVAILABLE_NUM_LIST_URL);
        addMobileEndpoint(endpoints, EndPointConfig.RELEASE_NUMBER_URL);
        addMobileEndpoint(endpoints, EndPointConfig.PURCHASE_NUMBER_URL);
        addMobileEndpoint(endpoints, EndPointConfig.SELECTED_TWILIO_NUMBERS_URL);
        addMobileEndpoint(endpoints, EndPointConfig.NICKNAME_NUMBER_URL);
        addMobileEndpoint(endpoints, EndPointConfig.VOICE_TOKEN_URL);
        addMobileEndpoint(endpoints, EndPointConfig.CALL_HISTORY_URL);
        addMobileEndpoint(endpoints, EndPointConfig.GET_DNS_PROFILE_URL);
        addMobileEndpoint(endpoints, EndPointConfig.DELETE_CALL_HISTORY_URL);
        addMobileEndpoint(endpoints, EndPointConfig.LIST_VOICEMAILS_URL);
        addMobileEndpoint(endpoints, EndPointConfig.GET_VOICEMAILS_URL);
        addMobileEndpoint(endpoints, EndPointConfig.UPDATE_VOICEMAIL_STATUS_URL);
        addMobileEndpoint(endpoints, EndPointConfig.UPDATE_ALL_VOICEMAIL_STATUS_URL);
        addMobileEndpoint(endpoints, EndPointConfig.GET_CALL_DETAILS_URL);
        addMobileEndpoint(endpoints, EndPointConfig.DELETE_ALL_CALL_HISTORY_URL);
        addMobileEndpoint(endpoints, EndPointConfig.IS_LIVE_URL);
        addMobileEndpoint(endpoints, EndPointConfig.SEND_TRACKING_URL);
        addMobileEndpoint(endpoints, EndPointConfig.GET_GLACIER_USER);
        addMobileEndpoint(endpoints, EndPointConfig.GET_GLACIER_ORG);
        addMobileEndpoint(endpoints, EndPointConfig.UPDATE_SECHUB_INFO);
        addMobileEndpoint(endpoints, EndPointConfig.GET_CORE_PROFILE);
        addMobileEndpoint(endpoints, EndPointConfig.FILESAFE_UPLOAD_URL);
        addMobileEndpoint(endpoints, EndPointConfig.BUG_REPORT_UPLOAD_URL);
        addMobileEndpoint(endpoints, EndPointConfig.MEDIA_UPLOAD_URL);
        return endpoints;
    }

    private static Set<String> buildOfflineFastFailEndpoints() {
        // UPDATE_SECHUB_INFO is deliberately absent. Fast-failing it locally meant the
        // Security Hub report was discarded before it reached the network whenever
        // ConnectivityReceiver could not recognise the active transport, and its caller is a
        // WorkManager job that already runs under a CONNECTED constraint and retries.
        final Set<String> endpoints = new HashSet<>();
        addMobileEndpoint(endpoints, EndPointConfig.SEND_TRACKING_URL);
        addMobileEndpoint(endpoints, EndPointConfig.GET_DNS_PROFILE_URL);
        addMobileEndpoint(endpoints, EndPointConfig.CHAT_ACCESS_TOKEN_URL);
        addMobileEndpoint(endpoints, EndPointConfig.GET_AVAILABLE_NUM_LIST_URL);
        addMobileEndpoint(endpoints, EndPointConfig.RELEASE_NUMBER_URL);
        addMobileEndpoint(endpoints, EndPointConfig.PURCHASE_NUMBER_URL);
        addMobileEndpoint(endpoints, EndPointConfig.SELECTED_TWILIO_NUMBERS_URL);
        addMobileEndpoint(endpoints, EndPointConfig.NICKNAME_NUMBER_URL);
        addMobileEndpoint(endpoints, EndPointConfig.VOICE_TOKEN_URL);
        addMobileEndpoint(endpoints, EndPointConfig.CALL_HISTORY_URL);
        addMobileEndpoint(endpoints, EndPointConfig.GET_DNS_PROFILE_URL);
        addMobileEndpoint(endpoints, EndPointConfig.DELETE_CALL_HISTORY_URL);
        addMobileEndpoint(endpoints, EndPointConfig.LIST_VOICEMAILS_URL);
        addMobileEndpoint(endpoints, EndPointConfig.GET_VOICEMAILS_URL);
        addMobileEndpoint(endpoints, EndPointConfig.UPDATE_VOICEMAIL_STATUS_URL);
        addMobileEndpoint(endpoints, EndPointConfig.UPDATE_ALL_VOICEMAIL_STATUS_URL);
        addMobileEndpoint(endpoints, EndPointConfig.GET_CALL_DETAILS_URL);
        addMobileEndpoint(endpoints, EndPointConfig.DELETE_ALL_CALL_HISTORY_URL);
        addMobileEndpoint(endpoints, EndPointConfig.IS_LIVE_URL);
        addMobileEndpoint(endpoints, EndPointConfig.SEND_TRACKING_URL);
        addMobileEndpoint(endpoints, EndPointConfig.GET_GLACIER_USER);
        addMobileEndpoint(endpoints, EndPointConfig.GET_GLACIER_ORG);
        addMobileEndpoint(endpoints, EndPointConfig.GET_CORE_PROFILE);
        addMobileEndpoint(endpoints, EndPointConfig.FILESAFE_UPLOAD_URL);
        addMobileEndpoint(endpoints, EndPointConfig.BUG_REPORT_UPLOAD_URL);
        addMobileEndpoint(endpoints, EndPointConfig.MEDIA_UPLOAD_URL);
        return endpoints;
    }

    private static void addMobileEndpoint(final List<String> endpoints, final String endpoint) {
        final String normalizedPath = normalizeEndpointPath(endpoint);
        if (normalizedPath == null) return;
        endpoints.add(normalizedPath);
    }

    private static void addMobileEndpoint(final Set<String> endpoints, final String endpoint) {
        final String normalizedPath = normalizeEndpointPath(endpoint);
        if (normalizedPath == null) return;
        endpoints.add(normalizedPath);
    }

    private static String normalizeEndpointPath(final String endpoint) {
        if (TextUtils.isEmpty(endpoint)) {
            return null;
        }
        if (endpoint.startsWith("http://") || endpoint.startsWith("https://")) {
            try {
                final HttpUrl parsedUrl = HttpUrl.parse(endpoint);
                final String path = parsedUrl == null ? null : parsedUrl.encodedPath();
                if (TextUtils.isEmpty(path)) {
                    return null;
                }
                return path;
            } catch (final Exception ignored) {
                return null;
            }
        }
        return endpoint.startsWith("/") ? endpoint : "/" + endpoint;
    }

    public static String getCognitoTokens() {
        return getCognitoTokens(true, true);
    }

    private static String getCognitoTokens(final boolean allowReauthenticationWait) {
        return getCognitoTokens(allowReauthenticationWait, true);
    }

    private static String getCognitoTokens(final boolean allowReauthenticationWait,
                                           final boolean allowSilentRecoveryAttempt) {
        ensureAuthHubListenerRegistered();

        final String cachedToken = CACHED_ACCESS_TOKEN.get();
        final long cachedExpiryMs = CACHED_TOKEN_EXPIRY_MS.get();
        if (!TextUtils.isEmpty(cachedToken)
                && cachedExpiryMs > 0L
                && (System.currentTimeMillis() + TOKEN_EXPIRY_SKEW_MS) < cachedExpiryMs) {
            return cachedToken;
        }
        clearTokenCache();

        final CountDownLatch latch    = new CountDownLatch(1);
        final AtomicReference<String> tokenRef = new AtomicReference<>();
        final AtomicBoolean shouldAttemptSilentRecovery = new AtomicBoolean(false);
        final AtomicReference<Throwable> authSessionErrorRef = new AtomicReference<>(null);
        final AtomicBoolean shouldNotifyReauthentication = new AtomicBoolean(false);

        Amplify.Auth.fetchAuthSession(authSession -> {
            if (authSession instanceof AWSCognitoAuthSession) {
                final AWSCognitoAuthSession cs = (AWSCognitoAuthSession) authSession;
                final boolean signedIn = cs.isSignedIn();
                if (signedIn) {
                    final String token = cs.getAccessToken();
                    if (!TextUtils.isEmpty(token)) {
                        tokenRef.set(token);
                        cacheToken(token);
                    } else {
                        Log.w(Config.LOGTAG,
                                "fetchAuthSession returned signed-in state but empty access token");
                        logUserPoolTokensFailure(cs);
                        // Feed the underlying token error into the recovery path. Without this the
                        // error arrives as null and a transient refresh failure is indistinguishable
                        // from a credential failure, so recovery signs out a still-valid session.
                        authSessionErrorRef.set(getUserPoolTokensError(cs));
                        shouldAttemptSilentRecovery.set(true);
                    }
                } else {
                    Log.i(Config.LOGTAG,
                            "fetchAuthSession returned not-signed-in state while fetching access token");
                    clearTokenCache();
                    shouldAttemptSilentRecovery.set(true);
                    final Throwable userPoolTokenError = getUserPoolTokensError(cs);
                    authSessionErrorRef.set(userPoolTokenError);
                    if (classifyAuthError(userPoolTokenError) == AuthErrorClass.CREDENTIAL_AUTH_STATE) {
                        shouldNotifyReauthentication.set(true);
                    }
                }
            } else {
                Log.w(Config.LOGTAG,
                        "Auth session is not Cognito; skipping access token header");
                shouldAttemptSilentRecovery.set(true);
            }
            latch.countDown();
        }, error -> {
            Log.w(Config.LOGTAG,
                    "Failed to fetch auth session for access token", error);
            authSessionErrorRef.set(error);
            shouldAttemptSilentRecovery.set(true);
            latch.countDown();
        });

        try {
            if (!latch.await(COGNITO_TOKEN_FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.w(Config.LOGTAG, "Timed out waiting for Cognito tokens");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.w(Config.LOGTAG, "Interrupted while waiting for Cognito tokens", e);
    }
        final String fetchedToken = tokenRef.get();
        if (allowSilentRecoveryAttempt
                && TextUtils.isEmpty(fetchedToken)
                && shouldAttemptSilentRecovery.get()) {
            final SilentRecoveryOutcome recoveryOutcome = attemptSilentRecoveryFromStoredCredentials(
                    authSessionErrorRef.get(),
                    true);
            final boolean recoverySucceeded = recoveryOutcome.status == SilentRecoveryStatus.SUCCEEDED;
            if (recoveryOutcome.status == SilentRecoveryStatus.FAILED_REAUTH_REQUIRED
                    && recoveryOutcome.errorClass == AuthErrorClass.CREDENTIAL_AUTH_STATE) {
                shouldNotifyReauthentication.set(true);
            }
            if (recoverySucceeded) {
                refreshCacheFromSession();
                final String recoveredToken = getCognitoTokens(false, false);
                if (!TextUtils.isEmpty(recoveredToken)) {
                    return recoveredToken;
                }
            }
        }
        if (allowReauthenticationWait
                && TextUtils.isEmpty(fetchedToken)
                && context != null
                && shouldNotifyReauthentication.get()) {
            notifyReauthenticationRequired();
            final String reauthenticatedToken = waitForValidSessionAfterReauthenticationSignal();
            return reauthenticatedToken;
        }
        final String returnToken = TextUtils.isEmpty(fetchedToken) ? null : fetchedToken;
        return returnToken;
    }

    private static void ensureAuthHubListenerRegistered() {
        if (!AUTH_HUB_LISTENER_REGISTERED.compareAndSet(false, true)) {
            return;
        }
        Amplify.Hub.subscribe(HubChannel.AUTH, hubEvent -> {
            final String eventName = hubEvent.getName();
            if (AuthChannelEventName.SIGNED_OUT.toString().equals(eventName)) {
                clearTokenCache();
                return;
            }
            if (AuthChannelEventName.SESSION_EXPIRED.toString().equals(eventName)) {
                refreshCacheFromSession();
            }
        });
    }

    private static void refreshCacheFromSession() {
        Amplify.Auth.fetchAuthSession(authSession -> {
            if (authSession instanceof AWSCognitoAuthSession) {
                final AWSCognitoAuthSession cs = (AWSCognitoAuthSession) authSession;
                if (cs.isSignedIn() && !TextUtils.isEmpty(cs.getAccessToken())) {
                    cacheToken(cs.getAccessToken());
                } else {
                    clearTokenCache();
                    Log.w(Config.LOGTAG,
                            "refreshCacheFromSession saw signed-in session without access token");
                    attemptSilentRecoveryFromStoredCredentials(getUserPoolTokensError(cs), false);
                    signalReauthenticationLatch();
                }
            } else {
                clearTokenCache();
                Log.w(Config.LOGTAG, "Not congito session");
                attemptSilentRecoveryFromStoredCredentials(null, false);
                signalReauthenticationLatch();
            }
            signalReauthenticationLatch();
        }, error -> {
            clearTokenCache();
            Log.w(Config.LOGTAG, "Failed to refresh Cognito token cache from auth session", error);
            attemptSilentRecoveryFromStoredCredentials(error, false);
            signalReauthenticationLatch();
        });
    }

    private enum AuthErrorClass {
        TRANSIENT_NETWORK,
        CREDENTIAL_AUTH_STATE,
        UNKNOWN
    }

    private enum SilentRecoveryStatus {
        SUCCEEDED,
        FAILED_RETRYABLE,
        FAILED_REAUTH_REQUIRED
    }

    private static final class SilentRecoveryOutcome {
        private final SilentRecoveryStatus status;
        private final AuthErrorClass errorClass;

        private SilentRecoveryOutcome(final SilentRecoveryStatus status,
                                      final AuthErrorClass errorClass) {
            this.status = status;
            this.errorClass = errorClass;
        }

        private static SilentRecoveryOutcome succeeded() {
            return new SilentRecoveryOutcome(SilentRecoveryStatus.SUCCEEDED, AuthErrorClass.UNKNOWN);
        }

        private static SilentRecoveryOutcome retryable(final AuthErrorClass errorClass) {
            return new SilentRecoveryOutcome(SilentRecoveryStatus.FAILED_RETRYABLE, errorClass);
        }

        private static SilentRecoveryOutcome reauthRequired(final AuthErrorClass errorClass) {
            return new SilentRecoveryOutcome(SilentRecoveryStatus.FAILED_REAUTH_REQUIRED, errorClass);
        }
    }

    private static SilentRecoveryOutcome attemptSilentRecoveryFromStoredCredentials(final Throwable error,
                                                                                    final boolean waitForCompletion) {


        if (context == null) {
            return SilentRecoveryOutcome.retryable(AuthErrorClass.UNKNOWN);
        }

        // Recovery re-signs-in from stored credentials, which can only succeed with a
        // network. Attempting it offline burns the stored session for nothing.
        if (!ConnectivityReceiver.isConnected(context)) {
            return SilentRecoveryOutcome.retryable(AuthErrorClass.TRANSIENT_NETWORK);
        }

        if (error != null) {
            final AuthErrorClass errorClass = classifyAuthError(error);
            if (errorClass == AuthErrorClass.TRANSIENT_NETWORK) {
                return SilentRecoveryOutcome.retryable(errorClass);
            }
            if (!SILENT_SIGN_IN_IN_PROGRESS.compareAndSet(false, true)) {
                return SilentRecoveryOutcome.retryable(errorClass);
            }
        } else if (!SILENT_SIGN_IN_IN_PROGRESS.compareAndSet(false, true)) {
            return SilentRecoveryOutcome.retryable(AuthErrorClass.UNKNOWN);
        }

        final Context appContext = context.getApplicationContext();
        final DatabaseBackend databaseBackend = DatabaseBackend.getInstance(appContext);
        final List<Account> accounts = databaseBackend.getAccounts();
        if (accounts.isEmpty()) {
            SILENT_SIGN_IN_IN_PROGRESS.set(false);
            Log.w(Config.LOGTAG, "No account available for session-expired credential recovery");
            return SilentRecoveryOutcome.reauthRequired(AuthErrorClass.CREDENTIAL_AUTH_STATE);
        }

        final Account account = accounts.get(0);
        final CognitoAccount cognitoAccount = databaseBackend.getCognitoAccount(account);
        if (cognitoAccount == null) {
            SILENT_SIGN_IN_IN_PROGRESS.set(false);
            Log.w(Config.LOGTAG, "No Cognito account available for session-expired credential recovery");
            return SilentRecoveryOutcome.reauthRequired(AuthErrorClass.CREDENTIAL_AUTH_STATE);
        }

        final String username = cognitoAccount.getUserName();
        final String password = cognitoAccount.getPassword();
        if (TextUtils.isEmpty(username) || TextUtils.isEmpty(password)) {
            SILENT_SIGN_IN_IN_PROGRESS.set(false);
            return SilentRecoveryOutcome.reauthRequired(AuthErrorClass.CREDENTIAL_AUTH_STATE);
        }

        final CountDownLatch completionLatch = waitForCompletion ? new CountDownLatch(1) : null;
        final AtomicReference<SilentRecoveryOutcome> recoveryOutcome =
                new AtomicReference<>(SilentRecoveryOutcome.retryable(AuthErrorClass.UNKNOWN));

        // This path frequently runs in the background (e.g. before device unlock), where Amplify
        // may have failed to configure. Touching Amplify.Auth then throws an uncaught
        // IllegalStateException, so make sure it is configured and never let it crash the process.
        if (!GlacierGuardian.ensureAmplifyConfigured(appContext)) {
            SILENT_SIGN_IN_IN_PROGRESS.set(false);
            Log.w(Config.LOGTAG, "Amplify is not configured; deferring silent credential recovery");
            return SilentRecoveryOutcome.retryable(AuthErrorClass.UNKNOWN);
        }

        Log.w(Config.LOGTAG, "Attempting silent Cognito sign-in after session expiration");
        try {
            // Sign in first. Signing out up front discarded a still-valid refresh session
            // whenever the sign-in then failed for a transient reason, turning a recoverable
            // blip into a mandatory reauthentication prompt. The only reason a sign-out is
            // needed at all is that Amplify rejects signIn while a session is present, so
            // fall back to sign-out-and-retry only when it reports exactly that.
            runSilentSignIn(username, password, true, recoveryOutcome, completionLatch);
        } catch (final Exception e) {
            SILENT_SIGN_IN_IN_PROGRESS.set(false);
            Log.w(Config.LOGTAG, "Amplify auth threw during silent credential recovery", e);
            if (completionLatch != null) {
                completionLatch.countDown();
            }
            return SilentRecoveryOutcome.retryable(AuthErrorClass.UNKNOWN);
        }
        if (completionLatch != null) {
            try {
                if (!completionLatch.await(COGNITO_TOKEN_FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    Log.w(Config.LOGTAG, "Timed out waiting for silent credential recovery sign-in");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.w(Config.LOGTAG, "Interrupted while waiting for silent credential recovery", e);
            }
        }
        return recoveryOutcome.get();
    }

    private static void runSilentSignIn(final String username,
                                       final String password,
                                       final boolean allowSignOutRetry,
                                       final AtomicReference<SilentRecoveryOutcome> recoveryOutcome,
                                       final CountDownLatch completionLatch) {
        Amplify.Auth.signIn(username, password, result -> {
            SILENT_SIGN_IN_IN_PROGRESS.set(false);
            if (result.isSignedIn()) {
                Log.i(Config.LOGTAG, "Silent Cognito sign-in succeeded after session expiration");
                recoveryOutcome.set(SilentRecoveryOutcome.succeeded());
                refreshCacheFromSession();
            } else {
                Log.w(Config.LOGTAG,
                        "Silent Cognito sign-in returned next step " + result.getNextStep().getSignInStep());
                recoveryOutcome.set(
                        SilentRecoveryOutcome.reauthRequired(AuthErrorClass.CREDENTIAL_AUTH_STATE));
            }
            countDown(completionLatch);
        }, signInError -> {
            final AuthErrorClass signInErrorClass = classifyAuthError(signInError);
            // Clearing a stale local session and retrying is what the previous unconditional
            // sign-out achieved, and it is still needed when Amplify refuses to sign in while a
            // session is present. Skip it in the two cases where it only does harm:
            //   TRANSIENT_NETWORK    — the session is fine, the network is not.
            //   CREDENTIAL_AUTH_STATE — the stored password is rejected, so a retry fails the
            //                           same way while discarding a refresh session that may
            //                           still be valid.
            if (allowSignOutRetry && signInErrorClass == AuthErrorClass.UNKNOWN) {
                try {
                    Amplify.Auth.signOut(signOutResult -> {
                        logSignOutResult(signOutResult);
                        runSilentSignIn(username, password, false, recoveryOutcome, completionLatch);
                    });
                } catch (final Exception signOutFailure) {
                    SILENT_SIGN_IN_IN_PROGRESS.set(false);
                    Log.w(Config.LOGTAG,
                            "Amplify threw while signing out for silent sign-in retry", signOutFailure);
                    recoveryOutcome.set(SilentRecoveryOutcome.retryable(AuthErrorClass.UNKNOWN));
                    countDown(completionLatch);
                }
                return;
            }
            SILENT_SIGN_IN_IN_PROGRESS.set(false);
            Log.e(Config.LOGTAG, "Silent Cognito sign-in failed after session expiration", signInError);
            if (signInErrorClass == AuthErrorClass.CREDENTIAL_AUTH_STATE) {
                recoveryOutcome.set(SilentRecoveryOutcome.reauthRequired(signInErrorClass));
            } else {
                recoveryOutcome.set(SilentRecoveryOutcome.retryable(signInErrorClass));
            }
            countDown(completionLatch);
        });
    }

    private static void logSignOutResult(final Object signOutResult) {
        if (signOutResult instanceof AWSCognitoAuthSignOutResult.CompleteSignOut) {
        } else if (signOutResult instanceof AWSCognitoAuthSignOutResult.PartialSignOut) {
            // Partial sign-out still clears enough state for a fresh sign-in
        } else if (signOutResult instanceof AWSCognitoAuthSignOutResult.FailedSignOut) {
            // Proceed anyway — a fresh signIn will overwrite the existing session
        }
    }

    private static void countDown(final CountDownLatch latch) {
        if (latch != null) {
            latch.countDown();
        }
    }

    private static AuthErrorClass classifyAuthError(final Throwable error) {
        if (error == null) {
            return AuthErrorClass.UNKNOWN;
        }
        Throwable current = error;
        while (current != null) {
            if (current instanceof SocketTimeoutException
                    || current instanceof UnknownHostException
                    || current instanceof IOException) {
                return AuthErrorClass.TRANSIENT_NETWORK;
            }
            final String className = current.getClass().getSimpleName();
            final String message = String.valueOf(current.getMessage());
            if (className.contains("NotAuthorizedException")
                    || className.contains("UserNotFoundException")
                    || className.contains("SessionExpiredException")
                    || message.contains("Refresh Token has been revoked")
                    || message.contains("refresh token has been revoked")
                    || message.contains("session has expired")
                    || message.contains("Incorrect username or password")) {
                return AuthErrorClass.CREDENTIAL_AUTH_STATE;
            }
            current = current.getCause();
        }
        return AuthErrorClass.UNKNOWN;
    }

    private static Throwable getUserPoolTokensError(final AWSCognitoAuthSession session) {
        if (session == null) {
            return null;
        }
        try {
            final Method userPoolTokensResultMethod =
                    session.getClass().getMethod("getUserPoolTokensResult");
            final Object tokensResult = userPoolTokensResultMethod.invoke(session);
            if (tokensResult == null) {
                return null;
            }

            final Method errorMethod = tokensResult.getClass().getMethod("getError");
            final Object error = errorMethod.invoke(tokensResult);
            if (error instanceof Throwable) {
                return (Throwable) error;
            }
        } catch (Exception reflectionFailure) {
            Log.w(Config.LOGTAG, "Could not introspect user pool token error", reflectionFailure);
        }
        return null;
    }

    private static void logUserPoolTokensFailure(final AWSCognitoAuthSession session) {
        try {
            final Method userPoolTokensResultMethod =
                    session.getClass().getMethod("getUserPoolTokensResult");
            final Object tokensResult = userPoolTokensResultMethod.invoke(session);
            if (tokensResult == null) {
                Log.w(Config.LOGTAG, "userPoolTokensResult was null");
                return;
            }

            final Method errorMethod = tokensResult.getClass().getMethod("getError");
            final Object error = errorMethod.invoke(tokensResult);
            if (error == null) {
                Log.w(Config.LOGTAG,
                        "userPoolTokensResult.getError() returned null while access token was empty");
            } else if (error instanceof Throwable) {
                Log.w(Config.LOGTAG,
                        "userPoolTokensResult.getError() indicated token retrieval failure",
                        (Throwable) error);
            } else {
                Log.w(Config.LOGTAG,
                        "userPoolTokensResult.getError() returned: " + error);
            }
        } catch (Exception reflectionFailure) {
            Log.w(Config.LOGTAG,
                    "Unable to inspect userPoolTokensResult.error from auth session",
                    reflectionFailure);
        }
    }

    private static void signalReauthenticationLatch() {
        final CountDownLatch latch = REAUTH_SIGNAL_LATCH.get();
        if (latch != null) {
            latch.countDown();
        }
    }

    private static void notifyReauthenticationRequired() {
        if (context == null) {
            Log.w(Config.LOGTAG, "Unable to notify reauthentication UI; context is null");
            return;
        }
        final long snoozedUntilMs = REAUTH_SNOOZED_UNTIL_MS.get();
        if (snoozedUntilMs > 0L && System.currentTimeMillis() < snoozedUntilMs) {
            return;
        }
        final long lastBroadcastElapsedMs = REAUTH_PROMPT_BROADCAST_AT_ELAPSED_MS.get();
        final long nowElapsedMs = SystemClock.elapsedRealtime();
        if (lastBroadcastElapsedMs > 0L
                && (nowElapsedMs - lastBroadcastElapsedMs) < REAUTH_PROMPT_GUARD_TTL_MS) {
            return;
        }
        if (!REAUTH_PROMPT_BROADCAST_AT_ELAPSED_MS.compareAndSet(
                lastBroadcastElapsedMs, nowElapsedMs)) {
            return;
        }
        final Intent intent = new Intent(ACTION_REAUTHENTICATION_REQUIRED);
        final boolean delivered = LocalBroadcastManager.getInstance(context.getApplicationContext())
                .sendBroadcast(intent);
        if (!delivered) {
            Log.w(Config.LOGTAG,
                    "Reauthentication prompt broadcast had no active receivers; clearing prompt guard");
            REAUTH_PROMPT_BROADCAST_AT_ELAPSED_MS.set(0L);
        }
    }


    public static void notifyReauthenticationCancelled() {
        clearPendingSnoozeReset();
        REAUTH_SNOOZED_UNTIL_MS.set(0L);
        REAUTH_PROMPT_BROADCAST_AT_ELAPSED_MS.set(0L);
        REAUTH_WAIT_CANCELLED.set(true);
        signalReauthenticationLatch();
    }

    /**
     * Suppresses the reauthentication prompt for {@code duration}, then allows a
     * fresh attempt. Without this back-off the next JWT-bearing request that finds
     * a bad session re-broadcasts immediately and the dialog reappears at once.
     */
    public static void notifyReauthenticationSnoozed(final long duration, final TimeUnit unit) {
        clearPendingSnoozeReset();
        // The deadline is what actually holds the dialog back. Releasing the broadcast guard
        // without it just lets the next request re-broadcast immediately.
        REAUTH_SNOOZED_UNTIL_MS.set(System.currentTimeMillis() + unit.toMillis(duration));
        REAUTH_PROMPT_BROADCAST_AT_ELAPSED_MS.set(0L);
        REAUTH_WAIT_CANCELLED.set(true);
        signalReauthenticationLatch();
        final ScheduledFuture<?> resetFuture = REAUTH_SNOOZE_EXECUTOR.schedule(() -> {
            REAUTH_SNOOZED_UNTIL_MS.set(0L);
            REAUTH_WAIT_CANCELLED.set(false);
            signalReauthenticationLatch();
        }, duration, unit);
        REAUTH_SNOOZE_RESET_FUTURE.set(resetFuture);
    }

    public static void notifyReauthenticationSucceeded() {
        clearPendingSnoozeReset();
        REAUTH_SNOOZED_UNTIL_MS.set(0L);
        REAUTH_PROMPT_BROADCAST_AT_ELAPSED_MS.set(0L);
        REAUTH_WAIT_CANCELLED.set(false);
        refreshCacheFromSession();
    }

    private static void clearPendingSnoozeReset() {
        final ScheduledFuture<?> resetFuture = REAUTH_SNOOZE_RESET_FUTURE.getAndSet(null);
        if (resetFuture != null) {
            resetFuture.cancel(false);
        }
    }

    private static void clearTokenCache() {
        CACHED_ACCESS_TOKEN.set(null);
        CACHED_TOKEN_EXPIRY_MS.set(0L);
    }

    private static void cacheToken(final String token) {
        final long expiryMs = extractJwtExpiryMs(token);
        if (expiryMs <= 0L) {
            // extractJwtExpiryMs returned sentinel — token is unusable.
            Log.w(Config.LOGTAG,
                    "OkHttpClientProvider: rejecting token with unparseable expiry — not caching");
            clearTokenCache();
            return;
        }
        if ((System.currentTimeMillis() + TOKEN_EXPIRY_SKEW_MS) >= expiryMs) {
            // Token is already expired (or within the skew window) — do not cache.
            Log.w(Config.LOGTAG,
                    "OkHttpClientProvider: rejecting already-expired token (exp="
                            + expiryMs + ") — not caching");
            clearTokenCache();
            return;
        }
        CACHED_ACCESS_TOKEN.set(token);
        CACHED_TOKEN_EXPIRY_MS.set(expiryMs);
    }

    private static long extractJwtExpiryMs(final String jwtToken) {
        try {
            final String[] parts = jwtToken.split("\\.");
            if (parts.length < 2) {
                return -1L;
            }
            final String payloadJson = new String(
                    android.util.Base64.decode(
                            parts[1],
                            android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP),
                    StandardCharsets.UTF_8);

            final int expIndex = payloadJson.indexOf("\"exp\"");
            if (expIndex < 0) {
                // No exp claim — token must not be cached.
                Log.w(Config.LOGTAG,
                        "OkHttpClientProvider: JWT has no 'exp' claim — will not cache");
                return -1L;
            }
            final int colonIndex = payloadJson.indexOf(':', expIndex);
            if (colonIndex < 0) {
                return -1L;
            }
            int startIndex = colonIndex + 1;
            while (startIndex < payloadJson.length()
                    && Character.isWhitespace(payloadJson.charAt(startIndex))) {
                startIndex++;
            }
            int endIndex = startIndex;
            while (endIndex < payloadJson.length()
                    && Character.isDigit(payloadJson.charAt(endIndex))) {
                endIndex++;
            }
            if (startIndex == endIndex) {
                // Colon found but no digits follow — malformed.
                return -1L;
            }
            final long expSeconds = Long.parseLong(
                    payloadJson.substring(startIndex, endIndex));
            if (expSeconds <= 0) {
                // Nonsensical expiry (epoch zero or negative).
                Log.w(Config.LOGTAG,
                        "OkHttpClientProvider: JWT 'exp' is non-positive (" + expSeconds + ") — will not cache");
                return -1L;
            }
            return expSeconds * 1_000L;
        } catch (final Exception e) {
            Log.w(Config.LOGTAG, "OkHttpClientProvider: unable to parse JWT expiry", e);
            return -1L;
        }
    }

    public static Request.Builder buildJwtFormRequestBuilder(
            final String url, final Map<String, String> fields) throws IOException {
        final RequestBody body    = buildRawFormBody(fields);
        final Request.Builder builder = new Request.Builder().url(url).post(body);
        addJwtHeaders(builder);
        return builder;
    }

    public static Request.Builder buildJwtJsonRequestBuilder(
            final String url, final String jsonPayload) throws IOException {
        final RequestBody body    = RequestBody.create(jsonPayload, JSON_MEDIA_TYPE);
        final Request.Builder builder = new Request.Builder().url(url).post(body);
        addJwtHeaders(builder);
        return builder;
    }

    public static RequestBody buildRawFormBody(final Map<String, String> fields) {
        final StringBuilder encoded = new StringBuilder();
        for (final Map.Entry<String, String> entry : fields.entrySet()) {
            if (encoded.length() > 0) encoded.append('&');
            final String key   = entry.getKey();
            final String value = entry.getValue() == null ? "" : entry.getValue();
            final String encKey, encVal;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                encKey = URLEncoder.encode(key,   StandardCharsets.UTF_8);
                encVal = URLEncoder.encode(value, StandardCharsets.UTF_8);
            } else {
                try {
                    encKey = URLEncoder.encode(key,   "UTF-8");
                    encVal = URLEncoder.encode(value, "UTF-8");
                } catch (java.io.UnsupportedEncodingException e) {
                    throw new RuntimeException(
                            "UTF-8 must be supported per the Java specification", e);
                }
            }
            encoded.append(encKey).append('=').append(encVal);
        }
        return RequestBody.create(encoded.toString(), FORM_MEDIA_TYPE);
    }




    private static String waitForValidSessionAfterReauthenticationSignal() {
        REAUTH_WAIT_LOCK.lock();
        try {
            while (true) {
                if (REAUTH_WAIT_CANCELLED.get()) {
                    Log.w(Config.LOGTAG,
                            "OkHttpClientProvider: reauthentication wait cancelled by user");
                    return null;
                }

                final String refreshedToken = getCognitoTokens(false, false);
                if (!TextUtils.isEmpty(refreshedToken)) {
                    return refreshedToken;
                }

                final CountDownLatch waitLatch = new CountDownLatch(1);
                REAUTH_SIGNAL_LATCH.set(waitLatch);

                if (REAUTH_WAIT_CANCELLED.get()) {
                    Log.w(Config.LOGTAG,
                            "OkHttpClientProvider: reauthentication wait cancelled by user");
                    return null;
                }

                final String updatedToken = getCognitoTokens(false, false);
                if (!TextUtils.isEmpty(updatedToken)) {
                    return updatedToken;
                }

                waitLatch.await();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.w(Config.LOGTAG,
                    "OkHttpClientProvider: interrupted while waiting for reauthentication", e);
            return null;
        } finally {
            REAUTH_WAIT_LOCK.unlock();
        }
    }


    private static void addJwtHeaders(Request.Builder builder) {
        // buildJwtFormRequestBuilder and buildJwtJsonRequestBuilder call this while *building*
        // the request, before newCall() ever reaches the interceptor chain -- so for a
        // fake-backend build the Cognito lookup below would run anyway, and on a signed-out
        // device it can attempt a silent sign-in or block on the reauthentication latch until
        // it times out. The request would then never reach FakeGlacierBackend at all, which is
        // the opposite of the point: most of the phone module builds its requests this way.
        // The header is meaningless when the fake answers, so skip fetching it.
        if (FakeGlacierBackend.isEnabled()) {
            return;
        }
        String accessToken = getCognitoTokens();
        if (accessToken == null ) {

            return;
        }
        if (!accessToken.isEmpty()) {

            builder.header(AUTHORIZATION_HEADER, "Bearer " + accessToken);
        }
    }

}
