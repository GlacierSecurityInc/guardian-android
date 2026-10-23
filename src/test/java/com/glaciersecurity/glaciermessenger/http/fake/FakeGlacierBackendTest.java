package com.glaciersecurity.glaciermessenger.http.fake;

import static com.google.common.truth.Truth.assertThat;

import com.glaciersecurity.glaciermessenger.http.fake.FakeScenarios.Scenario;
import com.glaciersecurity.glaciermessenger.utils.SMSdbInfo;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * The fake backend answers what the app's own parsers expect.
 *
 * <p>These assertions are deliberately made through the production DTOs rather than against a
 * hand-written expected string. A fixture that only matches itself is worth nothing: the failure
 * mode this guards against is the fake and {@code SMSdbInfo}/{@code MobileApiResponses} drifting
 * apart, at which point a green manual pass against the fake would say nothing about the real
 * console.
 */
public class FakeGlacierBackendTest {

    private static final MediaType FORM = MediaType.get("application/x-www-form-urlencoded");
    private static final Gson GSON = new Gson();

    @Before
    public void reset() {
        FakeScenarios.reset();
        FakeTenant.seed();
    }

    @After
    public void tearDown() {
        FakeScenarios.reset();
        FakeTenant.seed();
    }

    // ── Routing ──────────────────────────────────────────────────────────────

    @Test
    public void onlyGlacierApiPathsAreIntercepted() {
        assertThat(FakeGlacierBackend.isGlacierApiPath("/api/mobile/is-live")).isTrue();
        assertThat(FakeGlacierBackend.isGlacierApiPath("/mobile/v1/input")).isTrue();
        // An HTTP-upload slot on the XMPP server, or an avatar. These must still reach the
        // network -- the fake stands in for the console, not for everything.
        assertThat(FakeGlacierBackend.isGlacierApiPath("/upload/abc/file.jpg")).isFalse();
        assertThat(FakeGlacierBackend.isGlacierApiPath(null)).isFalse();
    }

    @Test
    public void endpointKeyIsTheLastPathSegment() {
        assertThat(FakeGlacierBackend.lastSegment("/api/mobile/glacier-users"))
                .isEqualTo("glacier-users");
        // The security-info PUT hangs off glacier-users and must not collide with it.
        assertThat(FakeGlacierBackend.lastSegment("/api/mobile/glacier-users/security-info"))
                .isEqualTo("security-info");
        assertThat(FakeGlacierBackend.lastSegment("/api/mobile/is-live/")).isEqualTo("is-live");
    }

    @Test
    public void formFieldsSurviveUrlEncoding() {
        assertThat(FakeGlacierBackend.decodeFormField("purchaseNum=%2B12025550150", "purchaseNum"))
                .isEqualTo("+12025550150");
        assertThat(FakeGlacierBackend.decodeFormField("a=1&nickname=Front+Desk", "nickname"))
                .isEqualTo("Front Desk");
        assertThat(FakeGlacierBackend.decodeFormField("a=1", "missing")).isNull();
    }

    // ── The phone module, end to end ─────────────────────────────────────────

    @Test
    public void theSelectedNumberListParsesAsTheAppReadsIt() throws IOException {
        final SMSdbInfo.SmsResponse parsed = selectedNumbers();

        assertThat(parsed.message).isEqualTo("success");
        assertThat(parsed.data).isNotNull();
        assertThat(parsed.data.isSMSEnabled).isTrue();
        assertThat(parsed.data.allow_user_to_purchase_numbers).isTrue();
        assertThat(parsed.data.selected_twilionumber).hasLength(2);
        // "text" is the number and "id" the SID. Getting these the wrong way round produces a
        // list that renders as SIDs, which is exactly the bug this asserts against.
        assertThat(parsed.data.selected_twilionumber[0].text).isEqualTo("+12025550142");
        assertThat(parsed.data.selected_twilionumber[0].id).startsWith("PN");
        assertThat(parsed.data.selected_twilionumber[0].nickname).isEqualTo("Work");
    }

    @Test
    public void purchasingANumberAddsItToTheSelectedList() throws IOException {
        final String wanted = availableNumber();

        final JsonObject purchase = post("/api/mobile/purchaseTwilioNumber",
                "purchaseNum=" + urlEncode(wanted));
        assertThat(purchase.get("message").getAsString()).isEqualTo("success");
        assertThat(purchase.getAsJsonObject("data").get("phoneNumber").getAsString())
                .isEqualTo(wanted);
        assertThat(purchase.getAsJsonObject("data").get("sid").getAsString()).startsWith("PN");

        final SMSdbInfo.SmsResponse after = selectedNumbers();
        assertThat(after.data.selected_twilionumber).hasLength(3);
        assertThat(numbersIn(after)).contains(wanted);
    }

    @Test
    public void aNumberCanOnlyBePurchasedOnce() throws IOException {
        final String wanted = availableNumber();
        post("/api/mobile/purchaseTwilioNumber", "purchaseNum=" + urlEncode(wanted));

        // The real console answers a failure when somebody else took the number between the
        // list and the purchase. Answering success twice would hide that path entirely.
        final JsonObject second = post("/api/mobile/purchaseTwilioNumber",
                "purchaseNum=" + urlEncode(wanted));
        assertThat(second.get("message").getAsString()).isEqualTo("failure");
        assertThat(selectedNumbers().data.selected_twilionumber).hasLength(3);
    }

    @Test
    public void releasingANumberRemovesItAndPutsItBackOnOffer() throws IOException {
        final JsonObject release = post("/api/mobile/releaseTwilioNumber",
                "releaseNumber=" + urlEncode("+12025550143"));
        assertThat(release.get("message").getAsString()).isEqualTo("success");

        final SMSdbInfo.SmsResponse after = selectedNumbers();
        assertThat(after.data.selected_twilionumber).hasLength(1);
        assertThat(numbersIn(after)).doesNotContain("+12025550143");

        final JsonObject available = post("/api/mobile/availableNumList", "countryCode=US");
        assertThat(available.getAsJsonArray("available_phone_numbers").toString())
                .contains("+12025550143");
    }

    @Test
    public void releasingANumberTheTenantDoesNotOwnFails() throws IOException {
        final JsonObject release = post("/api/mobile/releaseTwilioNumber",
                "releaseNumber=" + urlEncode("+12025550199"));
        assertThat(release.get("message").getAsString()).isEqualTo("failure");
        assertThat(selectedNumbers().data.selected_twilionumber).hasLength(2);
    }

    @Test
    public void renamingANumberSticks() throws IOException {
        final JsonObject renamed = put("/api/mobile/nickname",
                "twilioNumber=" + urlEncode("+12025550143") + "&nickname=Front+Desk");
        assertThat(renamed.get("message").getAsString()).isEqualTo("success");

        final SMSdbInfo.SmsResponse after = selectedNumbers();
        assertThat(after.data.selected_twilionumber[1].nickname).isEqualTo("Front Desk");
    }

    @Test
    public void aNumberIsRecognisedInEverySpellingTheAppSendsIt() throws IOException {
        // getUnformattedNumber, the display form and the bare NANP form all reach the backend
        // somewhere in this app. A fake that only accepted E.164 would reject work the real
        // console accepts, and the difference would look like an app bug.
        assertThat(post("/api/mobile/releaseTwilioNumber", "releaseNumber=2025550143")
                .get("message").getAsString()).isEqualTo("success");
    }

    // ── Call history and voicemail ───────────────────────────────────────────

    @Test
    public void callHistorySplitsInboundFromOutbound() throws IOException {
        final SMSdbInfo.CallHistoryResponse history = GSON.fromJson(
                body(post("/api/mobile/call-history", "number=" + urlEncode("+12025550142"))),
                SMSdbInfo.CallHistoryResponse.class);

        assertThat(history.message).isEqualTo("success");
        assertThat(history.incomingCalls).hasLength(2);
        assertThat(history.outgoingCalls).hasLength(1);
    }

    @Test
    public void theSeededHistoryIncludesAMissedInboundCall() throws IOException {
        // HistoryItem.isMissedInbound keys the unread badge off inbound + no-answer. Without a
        // row of that shape in the seed, the badge is unreachable through the fake.
        final String json = body(post("/api/mobile/call-history",
                "number=" + urlEncode("+12025550142")));
        assertThat(json).contains("\"status\":\"no-answer\"");
        assertThat(json).contains("\"direction\":\"inbound\"");
    }

    @Test
    public void deletingOneCallRemovesOnlyThatCall() throws IOException {
        final JsonObject before = post("/api/mobile/call-history",
                "number=" + urlEncode("+12025550142"));
        final String sid = before.getAsJsonArray("incomingCalls").get(0)
                .getAsJsonObject().get("sid").getAsString();

        assertThat(post("/api/mobile/deleteCallLog", "callSid=" + sid)
                .get("message").getAsString()).isEqualTo("success");

        final SMSdbInfo.CallHistoryResponse after = GSON.fromJson(
                body(post("/api/mobile/call-history", "number=" + urlEncode("+12025550142"))),
                SMSdbInfo.CallHistoryResponse.class);
        assertThat(after.incomingCalls).hasLength(1);
        assertThat(after.outgoingCalls).hasLength(1);
    }

    @Test
    public void clearingAnEmptyHistoryStillReportsSuccess() throws IOException {
        // +12025550143 has no calls. The real endpoint does not treat "nothing to delete" as an
        // error, and a fake that did would make the UI show a failure toast that never happens.
        assertThat(post("/api/mobile/remove-call-history", "number=" + urlEncode("+12025550143"))
                .get("message").getAsString()).isEqualTo("success");
    }

    @Test
    public void voicemailListUsesTheLowercaseSidKeysVoicemailRecordReads() throws IOException {
        final String json = body(post("/api/mobile/voicemail_list",
                "number=" + urlEncode("+12025550142")));
        final JsonObject first = JsonParser.parseString(json).getAsJsonObject()
                .getAsJsonArray("recordings").get(0).getAsJsonObject();

        // VoicemailRecord reads "recordingsid" and "callsid", not the camelCase spellings.
        assertThat(first.has("recordingsid")).isTrue();
        assertThat(first.has("callsid")).isTrue();
        assertThat(first.get("status").getAsString()).isEqualTo("new");
    }

    @Test
    public void markingAVoicemailReadChangesTheListedStatus() throws IOException {
        final String json = body(post("/api/mobile/voicemail_list",
                "number=" + urlEncode("+12025550142")));
        final String recordingSid = JsonParser.parseString(json).getAsJsonObject()
                .getAsJsonArray("recordings").get(0).getAsJsonObject()
                .get("recordingsid").getAsString();

        // Not {"message":"success"}: VoicemailPhoneFragment compares against this exact sentence
        // and only then applies the change to the UI and the local database.
        assertThat(post("/api/mobile/updateVoicemailStatus",
                "recordingSid=" + recordingSid + "&status=read")
                .get("message").getAsString())
                .isEqualTo("Voicemail status updated successfully");

        final String after = body(post("/api/mobile/voicemail_list",
                "number=" + urlEncode("+12025550142")));
        assertThat(after).doesNotContain("\"status\":\"new\"");
    }

    @Test
    public void deletingAVoicemailKeepsItDeletedAcrossARefresh() throws IOException {
        // Delete All sends status=deleted through updateAllVoicemailStatus. Hard-coding the read
        // transition, or listing a deleted record anyway, made the deletion come back as an
        // ordinary readable message on the next refresh.
        assertThat(post("/api/mobile/updateAllVoicemailStatus",
                "number=" + urlEncode("+12025550142") + "&status=deleted")
                .get("message").getAsString())
                .isEqualTo("All voicemail statuses updated successfully");

        final JsonObject after = post("/api/mobile/voicemail_list",
                "number=" + urlEncode("+12025550142"));
        assertThat(after.getAsJsonArray("recordings")).isEmpty();
    }

    @Test
    public void aBulkStatusOtherThanDeletedIsAppliedAsSent() throws IOException {
        post("/api/mobile/updateAllVoicemailStatus",
                "number=" + urlEncode("+12025550142") + "&status=read");

        final String after = body(post("/api/mobile/voicemail_list",
                "number=" + urlEncode("+12025550142")));
        assertThat(after).doesNotContain("\"status\":\"new\"");
        // Still listed -- only "deleted" removes a record.
        assertThat(JsonParser.parseString(after).getAsJsonObject()
                .getAsJsonArray("recordings")).hasSize(2);
    }

    @Test
    public void callDetailsAreUnderTheCallKeyNotData() throws IOException {
        // ExternalCallManager.CallDetailsResponse and the IncomingCallNotificationService copy
        // both deserialise this as `call`. Under `data` the field stays null and the
        // external-call path dereferences a null CallInfo.
        final JsonObject body = post("/api/mobile/getCallDetail", "callSid=CA123");
        assertThat(body.has("call")).isTrue();
        assertThat(body.has("data")).isFalse();
        assertThat(body.getAsJsonObject("call").get("sid").getAsString()).isEqualTo("CA123");
    }

    @Test
    public void theVoicemailAudioIsAPlayableWavHeader() throws IOException {
        final Response response = FakeGlacierBackend.respondTo(
                new Request.Builder()
                        .url("https://api.example.com/api/mobile/getVoicemail?id=1")
                        .build());
        final byte[] bytes = response.body().bytes();
        assertThat(bytes).hasLength(44);
        assertThat(new String(bytes, 0, 4, java.nio.charset.StandardCharsets.US_ASCII))
                .isEqualTo("RIFF");
        assertThat(new String(bytes, 8, 4, java.nio.charset.StandardCharsets.US_ASCII))
                .isEqualTo("WAVE");
    }

    // ── Tokens, org, user ────────────────────────────────────────────────────

    @Test
    public void theVoiceTokenIsAParseableJwt() throws IOException {
        final String json = body(post("/api/mobile/getAccessTokenForDial", "identity=testuser"));
        assertThat(com.glaciersecurity.glaciermessenger.utils.TwilioAccessTokenParser.parse(json))
                .isEqualTo(FakeTenant.FAKE_JWT);
    }

    @Test
    public void theTokenIsConstructedNotCopied() {
        // Decodes to {"sub":"guardian"} with the literal signature "signature" -- the same
        // fixture TwilioAccessTokenParserTest uses. Nothing here may be swapped for a real token.
        final String payload = new String(
                java.util.Base64.getUrlDecoder().decode(FakeTenant.FAKE_JWT.split("\\.")[1]),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(payload).isEqualTo("{\"sub\":\"guardian\"}");
    }

    @Test
    public void theUserRecordCarriesNoCredentialsSoItCannotClobberARealAccount() throws IOException {
        // XmppConnectionService.queryGlacierUsers writes a non-empty xmpp_token onto the account
        // with setToken and persists it. Serving one replaces the real credential of whatever
        // account is signed in -- persistently, since it outlives this build. Absent means the
        // stored credential is left alone. This is a regression guard, not a nicety: an earlier
        // version of the fake served a token and broke XMPP login on a test device.
        final JsonObject user = JsonParser.parseString(
                        body(get("/api/mobile/glacier-users?organization=acme&username=testuser")))
                .getAsJsonObject().getAsJsonObject("user");

        assertThat(user.has("xmpp_token")).isFalse();
        // The rest of the record still has to be there, or the org/user sync has nothing to read.
        assertThat(user.get("organization").getAsString()).isEqualTo("acme");
        assertThat(user.get("username").getAsString()).isEqualTo("testuser");
    }

    @Test
    public void isLiveAnswersTheHyphenatedKeyTheAppReads() throws IOException {
        final String json = body(get("/api/mobile/is-live"));
        assertThat(JsonParser.parseString(json).getAsJsonObject().get("is-live").getAsBoolean())
                .isTrue();
    }

    @Test
    public void securityInfoIsAnEmptyTwoHundredBecauseTheAppDeclaresItAsVoid() throws IOException {
        final Response response = FakeGlacierBackend.respondTo(
                new Request.Builder()
                        .url("https://api.example.com/api/mobile/glacier-users/security-info")
                        .put(RequestBody.create("{}", MediaType.get("application/json")))
                        .build());
        assertThat(response.code()).isEqualTo(200);
        assertThat(response.body().string()).isEmpty();
    }

    @Test
    public void theCoreProfileIsAParseableWireguardConfig() throws IOException {
        final String config = body(get("/api/mobile/core-profile?organization=acme"));
        assertThat(config).contains("[Interface]");
        assertThat(config).contains("[Peer]");
        // Documentation ranges only: TEST-NET-1 for addresses, example.com for the endpoint.
        assertThat(config).contains("192.0.2.");
        assertThat(config).contains("vpn.example.com");
    }

    @Test
    public void theDnsProfileIdPassesTheAppsOwnValidator() throws IOException {
        final String json = body(get("/api/mobile/dns/getOrganizationProfileId?organization=acme"));
        final String id = JsonParser.parseString(json).getAsJsonObject().get("data").getAsString();
        assertThat(com.glaciersecurity.glaciermessenger.entities.GlacierOrgEntity.isValidDnsId(id))
                .isTrue();
    }

    @Test
    public void anUnknownGlacierEndpointAnswersFiveOhOneRatherThanPretendingToSucceed()
            throws IOException {
        final Response response = FakeGlacierBackend.respondTo(
                new Request.Builder()
                        .url("https://api.example.com/api/mobile/somethingNobodyImplemented")
                        .build());
        // A default of {"message":"success"} would make an unimplemented endpoint look working.
        assertThat(response.code()).isEqualTo(501);
    }

    // ── Failure injection ────────────────────────────────────────────────────

    @Test
    public void anEndpointCanBeMadeToFailWithoutAffectingTheOthers() throws IOException {
        FakeScenarios.set("purchaseTwilioNumber", Scenario.FAILURE);

        assertThat(post("/api/mobile/purchaseTwilioNumber",
                "purchaseNum=" + urlEncode(availableNumber()))
                .get("message").getAsString()).isEqualTo("failure");
        assertThat(selectedNumbers().message).isEqualTo("success");
    }

    @Test
    public void theWildcardKeyTakesDownEverything() throws IOException {
        FakeScenarios.set("all", Scenario.HTTP_500);
        assertThat(get("/api/mobile/is-live").code()).isEqualTo(500);
        assertThat(get("/api/mobile/glacier-organization").code()).isEqualTo(500);
    }

    @Test
    public void theMalformedScenarioProducesTheShapeThatReachesTheJsonSyntaxHandler()
            throws IOException {
        FakeScenarios.set("getUserSelectedTwilioNumbers", Scenario.MALFORMED);
        final Response response = FakeGlacierBackend.respondTo(
                postRequest("/api/mobile/getUserSelectedTwilioNumbers", ""));

        // HTML served with a JSON content type: a 200 the app cannot parse, which is what
        // actually reaches handleWebhookJsonSyntaxException when a gateway answers instead of
        // the API. A plain 500 would be caught a layer earlier and never reach it.
        assertThat(response.code()).isEqualTo(200);
        assertThat(response.body().contentType().subtype()).isEqualTo("json");
        assertThat(response.body().string()).startsWith("<html>");
    }

    @Test
    public void theNetworkErrorScenarioThrowsRatherThanAnswering() {
        FakeScenarios.set("call-history", Scenario.NETWORK_ERROR);
        assertThrows(() -> FakeGlacierBackend.respondTo(
                postRequest("/api/mobile/call-history", "number=%2B12025550142")));
    }

    @Test
    public void anUnknownScenarioNameClearsTheOverrideInsteadOfStickingAtTheOldOne() {
        FakeScenarios.set("is-live", Scenario.HTTP_500);
        FakeScenarios.set("is-live", Scenario.parse("htp_500"));
        assertThat(FakeScenarios.forEndpoint("is-live")).isEqualTo(Scenario.OK);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static SMSdbInfo.SmsResponse selectedNumbers() throws IOException {
        return GSON.fromJson(
                body(post("/api/mobile/getUserSelectedTwilioNumbers", "organization=acme")),
                SMSdbInfo.SmsResponse.class);
    }

    private static java.util.List<String> numbersIn(final SMSdbInfo.SmsResponse response) {
        final java.util.List<String> numbers = new java.util.ArrayList<>();
        for (final SMSdbInfo.TwilioNumberInfo info : response.data.selected_twilionumber) {
            numbers.add(info.text);
        }
        return numbers;
    }

    private static String availableNumber() throws IOException {
        return post("/api/mobile/availableNumList", "countryCode=US")
                .getAsJsonArray("available_phone_numbers").get(0)
                .getAsJsonObject().get("phoneNumber").getAsString();
    }

    private static JsonObject post(final String path, final String form) throws IOException {
        return JsonParser.parseString(body(FakeGlacierBackend.respondTo(postRequest(path, form))))
                .getAsJsonObject();
    }

    private static JsonObject put(final String path, final String form) throws IOException {
        final Request request = new Request.Builder()
                .url("https://api.example.com" + path)
                .put(RequestBody.create(form, FORM))
                .build();
        return JsonParser.parseString(body(FakeGlacierBackend.respondTo(request)))
                .getAsJsonObject();
    }

    private static Request postRequest(final String path, final String form) {
        return new Request.Builder()
                .url("https://api.example.com" + path)
                .post(RequestBody.create(form, FORM))
                .build();
    }

    private static Response get(final String path) throws IOException {
        return FakeGlacierBackend.respondTo(
                new Request.Builder().url("https://api.example.com" + path).build());
    }

    private static String body(final Response response) throws IOException {
        return response.body().string();
    }

    private static String body(final JsonObject object) {
        return object.toString();
    }

    private static String urlEncode(final String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (final java.io.UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }

    private static void assertThrows(final ThrowingRunnable runnable) {
        try {
            runnable.run();
        } catch (final IOException expected) {
            return;
        }
        throw new AssertionError("expected an IOException");
    }

    private interface ThrowingRunnable {
        void run() throws IOException;
    }
}
