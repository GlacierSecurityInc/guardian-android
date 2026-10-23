package com.glaciersecurity.glaciermessenger.http.fake;

import com.glaciersecurity.glaciermessenger.BuildConfig;
import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.http.fake.FakeScenarios.Scenario;
import com.glaciersecurity.glaciermessenger.utils.Log;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

/**
 * Answers the Glacier mobile API from memory, so a debug build can exercise the phone module,
 * the org sync and the Security Hub without a console, a login, or a Twilio account.
 *
 * <p>It installs as the <em>first</em> OkHttp interceptor in
 * {@code OkHttpClientProvider.createClient()}, which matters: returning before the JWT
 * interceptor runs means no Cognito session is fetched, so the app answers these calls while
 * signed out, and the certificate pinner is never consulted because no socket is opened.
 * Everything in the app reaches the backend through that one client -- raw OkHttp calls, the
 * Retrofit {@code MobileApiService}, and the multipart uploads -- so this one interceptor covers
 * the whole surface.
 *
 * <h2>Turning it on</h2>
 * <pre>{@code
 * ./gradlew installPlaystoreDebug -PglacierFakeBackend=true
 * }</pre>
 *
 * <p>Two independent conditions gate it, the same arrangement
 * {@code GlacierCertificatePinner} uses and for the same reason: {@code BuildConfig.DEBUG}
 * cannot be true in a store build, and {@code FAKE_BACKEND} is hardcoded false for the release
 * build type. Neither a mistyped Gradle property nor an edit to this file alone can ship an app
 * that serves itself fake data. Both are compile-time constants, so R8 removes this class from
 * release builds entirely.
 *
 * <h2>Driving it</h2>
 * See {@code FakeBackendReceiver} in {@code src/debug} for the adb commands that switch an
 * endpoint to a failure scenario or reseed the tenant mid-session.
 */
public final class FakeGlacierBackend {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final MediaType HTML = MediaType.get("text/html; charset=utf-8");

    private FakeGlacierBackend() {
        throw new IllegalStateException("Do not instantiate");
    }

    /**
     * The interceptor, or null when this build is not a fake-backend build.
     *
     * <p>Returning null rather than a pass-through interceptor keeps the production client's
     * interceptor chain byte-for-byte what it was before this class existed.
     */
    public static Interceptor interceptorOrNull() {
        if (!isEnabled()) {
            return null;
        }
        Log.w(Config.LOGTAG,
                "FakeGlacierBackend: ACTIVE. Every Glacier API call is answered from memory; "
                        + "no request reaches the console. Never use this build to verify a "
                        + "backend change.");
        return FakeGlacierBackend::intercept;
    }

    public static boolean isEnabled() {
        return BuildConfig.DEBUG && BuildConfig.FAKE_BACKEND;
    }

    private static Response intercept(final Interceptor.Chain chain) throws IOException {
        final Request request = chain.request();
        if (!isGlacierApiPath(request.url().encodedPath())) {
            // Not ours -- media downloads, avatars, HTTP upload slots on the XMPP server. Those
            // still go out to the network, which is what you want: the fake stands in for the
            // Glacier console, not for the internet.
            return chain.proceed(request);
        }
        return respondTo(request);
    }

    /**
     * Answers one Glacier API request. Split out of {@link #intercept} and deliberately free of
     * both the {@code BuildConfig} gate and the OkHttp chain, so {@code FakeGlacierBackendTest}
     * can drive every endpoint on the JVM -- including in a build where the fake is switched
     * off, which is every build the test tier normally runs against.
     */
    static Response respondTo(final Request request) throws IOException {
        final HttpUrl url = request.url();
        final String path = url.encodedPath();
        final String endpoint = lastSegment(path);
        final Scenario scenario = FakeScenarios.resolve(endpoint);

        switch (scenario) {
            case NETWORK_ERROR:
                throw new IOException("FakeGlacierBackend: injected network failure for " + endpoint);
            case HTTP_500:
                return response(request, 500, "Internal Server Error",
                        "<html><head><title>502 Bad Gateway</title></head><body>"
                                + "<h1>502 Bad Gateway</h1></body></html>", HTML);
            case EMPTY:
                return response(request, 200, "OK", "", JSON);
            case MALFORMED:
                // Not merely invalid JSON -- an HTML error page served with a JSON content type,
                // which is the shape that actually reaches handleWebhookJsonSyntaxException in
                // production when a gateway answers instead of the API.
                return response(request, 200, "OK",
                        "<html><body>Service Temporarily Unavailable</body></html>", JSON);
            case FAILURE:
                return json(request, 200, failure());
            case OK:
            default:
                return ok(request, url, endpoint);
        }
    }

    private static Response ok(final Request request, final HttpUrl url, final String endpoint)
            throws IOException {
        switch (endpoint) {
            case "is-live":
                return json(request, 200, isLive());

            // ── Phone module: numbers ────────────────────────────────────────
            case "availableNumList":
                return json(request, 200, availableNumbers(request));
            case "purchaseTwilioNumber":
                return json(request, 200, purchase(request));
            case "releaseTwilioNumber":
                return json(request, 200, release(request));
            case "nickname":
                return json(request, 200, nickname(request));
            case "getUserSelectedTwilioNumbers":
                return json(request, 200, selectedNumbers());

            // ── Phone module: history and voicemail ──────────────────────────
            case "call-history":
                return json(request, 200, callHistory(request));
            case "deleteCallLog":
                return json(request, 200, deleteCallLog(request));
            case "remove-call-history":
                return json(request, 200, removeCallHistory(request));
            case "getCallDetail":
                return json(request, 200, callDetail(request));
            case "voicemail_list":
                return json(request, 200, voicemailList(request));
            case "getVoicemail":
                return voicemailAudio(request);
            case "updateVoicemailStatus":
                return json(request, 200, updateVoicemailStatus(request));
            case "updateAllVoicemailStatus":
                return json(request, 200, updateAllVoicemailStatus(request));

            // ── Tokens ───────────────────────────────────────────────────────
            case "getAccessToken":
                return json(request, 200, chatAccessToken());
            case "getAccessTokenForDial":
                return json(request, 200, voiceToken());

            // ── Org, user, profiles ──────────────────────────────────────────
            case "glacier-users":
                return json(request, 200, glacierUser(url));
            case "security-info":
                // The app declares this as Call<Void>; anything but an empty 200 is noise.
                return response(request, 200, "OK", "", JSON);
            case "glacier-organization":
                return json(request, 200, glacierOrganization(url));
            case "latest-version":
                return json(request, 200, latestVersion());
            case "core-profile":
                return coreProfile(request);
            case "getOrganizationProfileId":
                return json(request, 200, dnsProfile());

            // ── Uploads and other writes ─────────────────────────────────────
            case "filesafe":
            case "media":
            case "bugreport":
                return json(request, 200, uploadAccepted(endpoint));
            case "clearEjabberdChatHistory":
                return json(request, 200, success());
            case "input":
                // Tracking ingest. The app ignores the body and only reads the status code.
                return response(request, 200, "OK", "", JSON);

            default:
                // A Glacier path this class has not learned yet. Fail loudly rather than
                // quietly answering {"message":"success"} -- a silent default here would make a
                // new endpoint look like it worked when nothing implemented it.
                Log.w(Config.LOGTAG,
                        "FakeGlacierBackend: no canned response for '" + endpoint
                                + "'; answering 501. Add it to FakeGlacierBackend.ok().");
                return json(request, 501, error("no fake response for endpoint " + endpoint));
        }
    }

    // ── Response builders ────────────────────────────────────────────────────

    private static JsonObject success() {
        final JsonObject body = new JsonObject();
        body.addProperty("message", "success");
        return body;
    }

    /** A success envelope whose message is the exact sentence one endpoint's caller compares. */
    private static JsonObject message(final String text) {
        final JsonObject body = new JsonObject();
        body.addProperty("message", text);
        return body;
    }

    private static JsonObject failure() {
        final JsonObject body = new JsonObject();
        body.addProperty("message", "failure");
        return body;
    }

    private static JsonObject error(final String detail) {
        final JsonObject body = new JsonObject();
        body.addProperty("message", "failure");
        body.addProperty("detail", detail);
        return body;
    }

    private static JsonObject isLive() {
        final JsonObject body = new JsonObject();
        body.addProperty("is-live", true);
        return body;
    }

    private static JsonObject availableNumbers(final Request request) {
        final String areaCode = formField(request, "areaCode");
        final String contains = formField(request, "contains");
        final JsonArray numbers = new JsonArray();
        for (final FakeTenant.Number number : FakeTenant.purchasable()) {
            if (areaCode != null && !areaCode.isEmpty()
                    && !FakeTenant.normalize(number.e164).startsWith("1" + areaCode)) {
                continue;
            }
            if (contains != null && !contains.isEmpty()
                    && !FakeTenant.normalize(number.e164).contains(FakeTenant.normalize(contains))) {
                continue;
            }
            final JsonObject entry = new JsonObject();
            entry.addProperty("phoneNumber", number.e164);
            numbers.add(entry);
        }
        final JsonObject body = success();
        body.add("available_phone_numbers", numbers);
        return body;
    }

    private static JsonObject purchase(final Request request) {
        final String wanted = formField(request, "purchaseNum");
        final FakeTenant.Number bought = FakeTenant.purchase(wanted);
        if (bought == null) {
            return failure();
        }
        final JsonObject data = new JsonObject();
        data.addProperty("sid", bought.sid);
        data.addProperty("phoneNumber", bought.e164);
        final JsonObject body = success();
        body.add("data", data);
        return body;
    }

    private static JsonObject release(final Request request) {
        return FakeTenant.release(formField(request, "releaseNumber")) ? success() : failure();
    }

    private static JsonObject nickname(final Request request) {
        final boolean renamed = FakeTenant.rename(
                formField(request, "twilioNumber"), formField(request, "nickname"));
        return renamed ? success() : failure();
    }

    private static JsonObject selectedNumbers() {
        final JsonArray numbers = new JsonArray();
        for (final FakeTenant.Number number : FakeTenant.owned()) {
            final JsonObject entry = new JsonObject();
            // "text" is the number and "id" the SID -- SMSdbInfo.TwilioNumberInfo names them
            // that way round, which is easy to get backwards.
            entry.addProperty("text", number.e164);
            entry.addProperty("id", number.sid);
            entry.addProperty("nickname", number.nickname);
            numbers.add(entry);
        }
        final JsonObject data = new JsonObject();
        data.add("selected_twilionumber", numbers);
        data.addProperty("isSMSEnabled", FakeTenant.isSmsEnabled());
        data.addProperty("allow_user_to_purchase_numbers", FakeTenant.isPurchaseNumbersAllowed());
        final JsonObject body = success();
        body.add("data", data);
        return body;
    }

    private static JsonObject callHistory(final Request request) {
        final String number = formField(request, "number");
        final JsonArray incoming = new JsonArray();
        final JsonArray outgoing = new JsonArray();
        for (final FakeTenant.Call call : FakeTenant.callsFor(number)) {
            final JsonObject entry = new JsonObject();
            entry.addProperty("sid", call.sid);
            entry.addProperty("from", call.from);
            entry.addProperty("to", call.to);
            entry.addProperty("dateCreated", call.dateCreated);
            entry.addProperty("status", call.status);
            entry.addProperty("direction", call.direction);
            entry.addProperty("duration", call.duration);
            if ("inbound".equals(call.direction)) {
                incoming.add(entry);
            } else {
                outgoing.add(entry);
            }
        }
        final JsonObject body = success();
        body.add("incomingCalls", incoming);
        body.add("outgoingCalls", outgoing);
        return body;
    }

    private static JsonObject deleteCallLog(final Request request) {
        final String sid = firstNonEmpty(
                formField(request, "callSid"), formField(request, "sid"),
                request.url().queryParameter("callSid"));
        return FakeTenant.deleteCall(sid) ? success() : failure();
    }

    private static JsonObject removeCallHistory(final Request request) {
        final String number = firstNonEmpty(
                formField(request, "number"), request.url().queryParameter("number"));
        FakeTenant.deleteAllCalls(number);
        // The real endpoint reports success for a number with nothing to delete, so clearing an
        // already-empty history must not look like a failure.
        return success();
    }

    private static JsonObject callDetail(final Request request) {
        final String sid = firstNonEmpty(
                formField(request, "callSid"), formField(request, "sid"),
                request.url().queryParameter("callSid"));
        final JsonObject data = new JsonObject();
        data.addProperty("sid", sid == null ? "" : sid);
        data.addProperty("from", "+12025550188");
        data.addProperty("to", "+12025550142");
        data.addProperty("direction", "inbound");
        data.addProperty("status", "completed");
        data.addProperty("duration", "31");
        final JsonObject body = success();
        // "call", not "data": ExternalCallManager.CallDetailsResponse and the
        // IncomingCallNotificationService copy both deserialise this field as `call`. Under
        // "data" the field stays null, incoming-call lookup reports no details, and the
        // external-call path dereferences a null CallInfo before its catch block.
        body.add("call", data);
        return body;
    }

    private static JsonObject voicemailList(final Request request) {
        final String number = formField(request, "number");
        final JsonArray recordings = new JsonArray();
        for (final FakeTenant.Voicemail voicemail : FakeTenant.voicemailsFor(number)) {
            if ("deleted".equals(voicemail.status)) {
                // Setting the status is not enough on its own: the fragment reloads the list
                // after a delete, so a "deleted" record that kept being listed would reappear.
                continue;
            }
            final JsonObject entry = new JsonObject();
            // Lower-case "callsid" and "recordingsid": VoicemailRecord reads exactly these keys.
            entry.addProperty("recordingsid", voicemail.recordingSid);
            entry.addProperty("callsid", voicemail.callSid);
            entry.addProperty("from", voicemail.from);
            entry.addProperty("to", voicemail.to);
            entry.addProperty("time", voicemail.time);
            entry.addProperty("duration", voicemail.duration);
            entry.addProperty("status", voicemail.status);
            entry.addProperty("url", "https://example.com/voicemail/" + voicemail.recordingSid + ".wav");
            recordings.add(entry);
        }
        final JsonObject body = success();
        body.add("recordings", recordings);
        return body;
    }

    /**
     * The voicemail audio itself. A 44-byte WAV header describing a zero-sample file: real
     * enough that MediaPlayer opens it and reports a duration of zero, rather than throwing the
     * way it would on a JSON body with an audio content type.
     */
    private static Response voicemailAudio(final Request request) {
        final byte[] wav = silentWav();
        return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(wav, MediaType.get("audio/wav")))
                .build();
    }

    // These two endpoints do not report success with {"message":"success"}.
    // VoicemailPhoneFragment compares the message against these exact sentences and applies the
    // change to the UI and the local database only on a match, so the generic envelope would
    // mutate the fake's state while the screen sat there unchanged.
    private static final String VOICEMAIL_STATUS_UPDATED = "Voicemail status updated successfully";
    private static final String ALL_VOICEMAIL_STATUSES_UPDATED =
            "All voicemail statuses updated successfully";

    private static JsonObject updateVoicemailStatus(final Request request) {
        final String sid = firstNonEmpty(
                formField(request, "recordingSid"), formField(request, "recordingsid"),
                request.url().queryParameter("recordingSid"));
        final String status = firstNonEmpty(formField(request, "status"), "read");
        if (!FakeTenant.setVoicemailStatus(sid, status)) {
            return failure();
        }
        return message(VOICEMAIL_STATUS_UPDATED);
    }

    private static JsonObject updateAllVoicemailStatus(final Request request) {
        // Honour the submitted status rather than assuming "read". Delete All sends
        // status=deleted (VoicemailRecord.VM_Status.DELETED.label), and hard-coding the read
        // transition made a deletion come back as an ordinary readable message on the next
        // refresh.
        final String status = firstNonEmpty(formField(request, "status"), "read");
        FakeTenant.setAllVoicemailStatuses(
                firstNonEmpty(formField(request, "number"),
                        request.url().queryParameter("number")),
                status);
        return message(ALL_VOICEMAIL_STATUSES_UPDATED);
    }

    private static JsonObject chatAccessToken() {
        final JsonObject body = success();
        body.addProperty("token", FakeTenant.FAKE_JWT);
        body.addProperty("add_user_to_purchase_numbers", FakeTenant.isPurchaseNumbersAllowed());
        return body;
    }

    private static JsonObject voiceToken() {
        final JsonObject body = success();
        body.addProperty("token", FakeTenant.FAKE_JWT);
        return body;
    }

    private static JsonObject glacierUser(final HttpUrl url) {
        final String organization = firstNonEmpty(
                url.queryParameter("organization"), FakeTenant.ORGANIZATION);
        final String username = firstNonEmpty(
                url.queryParameter("username"), FakeTenant.USERNAME);

        final JsonArray coreRegion = new JsonArray();
        coreRegion.add("us-east-1");

        final JsonObject user = new JsonObject();
        user.addProperty("organization", organization);
        user.addProperty("username", username);
        user.addProperty("messenger_id", FakeTenant.MESSENGER_ID);
        // xmpp_token is deliberately ABSENT, and must stay absent.
        //
        // XmppConnectionService.queryGlacierUsers writes a non-empty xmpp_token straight onto the
        // account with setToken and calls databaseBackend.updateAccount. Serving one replaces the
        // real credential of whatever account is signed in on the device, *persistently* -- the
        // fake JWT outlives the fake build, because it is stored with the account, and the next
        // real build then fails SASL with "account has an XMPP token but server does not offer
        // PLAIN". With no token the stored credentials are left alone (the client takes no
        // password from this record; that field is retired).
        //
        // Do not "complete" this object.
        user.addProperty("first_name", "Test");
        user.addProperty("last_name", "User");
        user.addProperty("isSMSEnabled", FakeTenant.isSmsEnabled());
        user.addProperty("add_to_core_vpn", true);
        // No add_user_to_SMS here, and no field for it anywhere any more: the console never
        // sent it and nothing ever branched on it. SMS access is gated by isSMSEnabled.
        user.addProperty("add_user_to_purchase_numbers", FakeTenant.isPurchaseNumbersAllowed());
        user.add("core_region", coreRegion);
        user.add("securityInfo", new JsonArray());

        final JsonObject body = success();
        body.add("user", user);
        return body;
    }

    private static JsonObject glacierOrganization(final HttpUrl url) {
        final JsonObject organization = new JsonObject();
        organization.addProperty("organization",
                firstNonEmpty(url.queryParameter("organization"), FakeTenant.ORGANIZATION));
        organization.addProperty("securityhub_data_enabled", true);
        organization.addProperty("sms_enabled", FakeTenant.isSmsEnabled());
        organization.addProperty("upload_enabled", true);
        organization.addProperty("voicemail_enabled", true);
        organization.addProperty("tracking_enabled", false);
        organization.addProperty("force_tracking", false);
        // A number upstream, not a string. The DTO declares String and Gson coerces, so
        // the app works either way -- but the fake exists to look like the console.
        organization.addProperty("tracking_frequency", 15);
        organization.addProperty("dns_enabled", true);
        // Sent upstream and carried by GlacierOrgEntity. Must satisfy isValidDnsId.
        organization.addProperty("dns_id", "7c549e");

        final JsonObject body = success();
        body.add("organization", organization);
        return body;
    }

    private static JsonObject latestVersion() {
        final JsonObject versions = new JsonObject();
        versions.addProperty("alt_os", "1.0.0");
        versions.addProperty("alt_os_new", "1.0.0");
        versions.addProperty("android_os", "16");
        versions.addProperty("glacier", BuildConfig.VERSION_NAME);
        versions.addProperty("glacier_android", BuildConfig.VERSION_NAME);
        final JsonObject body = success();
        body.add("versions", versions);
        return body;
    }

    private static JsonObject dnsProfile() {
        final JsonObject body = success();
        // GlacierOrgEntity.isValidDnsId accepts [A-Za-z0-9_-]+ only.
        body.addProperty("data", "fake-dns-profile");
        return body;
    }

    /**
     * The Core (WireGuard) profile, which the app downloads as a file rather than as JSON. A
     * syntactically valid config whose endpoint and keys are placeholders: it parses, so the
     * import path can be exercised, and it cannot connect to anything.
     */
    private static Response coreProfile(final Request request) {
        final String config = "[Interface]\n"
                + "PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\n"
                + "Address = 192.0.2.2/32\n"
                + "DNS = 192.0.2.53\n"
                + "\n"
                + "[Peer]\n"
                + "PublicKey = BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=\n"
                + "AllowedIPs = 192.0.2.0/24\n"
                + "Endpoint = vpn.example.com:51820\n"
                + "PersistentKeepalive = 25\n";
        return response(request, 200, "OK", config, MediaType.get("text/plain; charset=utf-8"));
    }

    private static JsonObject uploadAccepted(final String kind) {
        final JsonObject body = success();
        body.addProperty("url", "https://example.com/uploads/" + kind + "/fake-object");
        return body;
    }

    // ── Plumbing ─────────────────────────────────────────────────────────────

    /**
     * Whether a path belongs to the Glacier mobile API. Matches the same two prefixes
     * {@code OkHttpClientProvider.shouldApplyMobileApiHeaders} uses, so the fake covers exactly
     * the set of requests that would otherwise carry a JWT to the console.
     */
    static boolean isGlacierApiPath(final String path) {
        if (path == null) {
            return false;
        }
        return path.startsWith("/api/mobile/") || path.startsWith("/mobile/v1/input");
    }

    static String lastSegment(final String path) {
        if (path == null) {
            return "";
        }
        final String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        final int slash = trimmed.lastIndexOf('/');
        return slash < 0 ? trimmed : trimmed.substring(slash + 1);
    }

    /**
     * Reads one field out of a form-encoded request body without consuming it. The body is
     * buffered and re-read, which is safe here because every Glacier request carries a small
     * in-memory body -- but it is why uploads are answered on the path alone and never have
     * their body inspected.
     */
    static String formField(final Request request, final String name) {
        final RequestBody body = request.body();
        if (body == null || name == null) {
            return null;
        }
        try {
            final MediaType type = body.contentType();
            if (type == null || !"x-www-form-urlencoded".equals(type.subtype())) {
                return null;
            }
            final Buffer buffer = new Buffer();
            body.writeTo(buffer);
            final String encoded = buffer.readString(StandardCharsets.UTF_8);
            return decodeFormField(encoded, name);
        } catch (final IOException e) {
            Log.w(Config.LOGTAG, "FakeGlacierBackend: could not read form body", e);
            return null;
        }
    }

    static String decodeFormField(final String encodedBody, final String name) {
        if (encodedBody == null || name == null) {
            return null;
        }
        for (final String pair : encodedBody.split("&")) {
            final int equals = pair.indexOf('=');
            if (equals < 0) {
                continue;
            }
            if (!name.equals(urlDecode(pair.substring(0, equals)))) {
                continue;
            }
            return urlDecode(pair.substring(equals + 1));
        }
        return null;
    }

    private static String urlDecode(final String value) {
        try {
            return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (final Exception e) {
            return value;
        }
    }

    static String firstNonEmpty(final String... values) {
        for (final String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return null;
    }

    private static Response json(final Request request, final int code, final JsonObject body) {
        final String message = code >= 500 ? "Internal Server Error" : code >= 400 ? "Error" : "OK";
        return response(request, code, message, body.toString(), JSON);
    }

    private static Response response(final Request request, final int code, final String message,
                                     final String body, final MediaType type) {
        return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(message)
                .body(ResponseBody.create(body, type))
                .build();
    }

    /** A 44-byte RIFF/WAVE header, 8 kHz mono 16-bit, describing zero samples of audio. */
    private static byte[] silentWav() {
        final byte[] header = new byte[44];
        writeAscii(header, 0, "RIFF");
        writeIntLe(header, 4, 36);            // chunk size = 36 + data size (0)
        writeAscii(header, 8, "WAVE");
        writeAscii(header, 12, "fmt ");
        writeIntLe(header, 16, 16);           // PCM fmt chunk size
        writeShortLe(header, 20, 1);          // PCM
        writeShortLe(header, 22, 1);          // mono
        writeIntLe(header, 24, 8000);         // sample rate
        writeIntLe(header, 28, 16000);        // byte rate = rate * channels * bits/8
        writeShortLe(header, 32, 2);          // block align
        writeShortLe(header, 34, 16);         // bits per sample
        writeAscii(header, 36, "data");
        writeIntLe(header, 40, 0);            // data size
        return header;
    }

    private static void writeAscii(final byte[] target, final int offset, final String value) {
        final byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, target, offset, bytes.length);
    }

    private static void writeIntLe(final byte[] target, final int offset, final int value) {
        target[offset] = (byte) (value & 0xff);
        target[offset + 1] = (byte) ((value >> 8) & 0xff);
        target[offset + 2] = (byte) ((value >> 16) & 0xff);
        target[offset + 3] = (byte) ((value >> 24) & 0xff);
    }

    private static void writeShortLe(final byte[] target, final int offset, final int value) {
        target[offset] = (byte) (value & 0xff);
        target[offset + 1] = (byte) ((value >> 8) & 0xff);
    }

    /** Unused today; kept so a future endpoint can answer a list without rebuilding the helper. */
    static JsonArray arrayOf(final List<String> values) {
        final JsonArray array = new JsonArray();
        for (final String value : values) {
            array.add(value);
        }
        return array;
    }

    static String describe() {
        return String.format(Locale.ROOT,
                "FakeGlacierBackend[enabled=%s, owned=%d, purchasable=%d, overrides=%s]",
                isEnabled(), FakeTenant.owned().size(), FakeTenant.purchasable().size(),
                FakeScenarios.active());
    }
}
