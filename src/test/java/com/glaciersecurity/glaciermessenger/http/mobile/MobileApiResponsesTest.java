package com.glaciersecurity.glaciermessenger.http.mobile;

import static com.google.common.truth.Truth.assertThat;


import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import org.junit.Test;

/**
 * These are the shapes the Glacier mobile API is decoded into, so every field here arrives from
 * the backend and the decoding is the app's first contact with it.
 *
 * <p>Two fields are not plain values and are what this covers. {@code core_region} and
 * {@code securityInfo} are typed {@code JsonElement} because the backend sends either a single
 * value or an array, so each has a hand-written accessor that normalises the two. The comment on
 * {@code getSecurityInfoList} records that an earlier version accepted only primitives and
 * therefore returned an empty list for every object the server actually sent -- an empty list is
 * indistinguishable from "no devices", so it failed silently. The array-of-objects case below is
 * the regression guard for exactly that.
 */
public class MobileApiResponsesTest {

    private static final Gson GSON = new Gson();

    private static MobileApiResponses.GlacierUser userWith(final String field, final String json) {
        return GSON.fromJson("{\"" + field + "\":" + json + "}", MobileApiResponses.GlacierUser.class);
    }

    private static JsonElement parse(final String json) {
        return JsonParser.parseString(json);
    }

    // ---- core_region: one value or many -------------------------------------------------

    @Test
    public void anAbsentCoreRegionIsAnEmptyListRatherThanNull() {
        // Callers iterate this straight away; null here would be an NPE on first login.
        assertThat(new MobileApiResponses.GlacierUser().getCoreRegionList()).isEmpty();
    }

    @Test
    public void anExplicitJsonNullCoreRegionIsAnEmptyList() {
        assertThat(userWith("core_region", "null").getCoreRegionList()).isEmpty();
    }

    @Test
    public void aSingleCoreRegionIsAListOfOne() {
        // The backend sends a bare string when there is one region, not an array of one.
        assertThat(userWith("core_region", "\"us-east-1\"").getCoreRegionList())
                .containsExactly("us-east-1");
    }

    @Test
    public void severalCoreRegionsKeepTheirOrder() {
        assertThat(userWith("core_region", "[\"us-east-1\",\"eu-west-2\"]").getCoreRegionList())
                .containsExactly("us-east-1", "eu-west-2")
                .inOrder();
    }

    @Test
    public void anEmptyCoreRegionArrayIsAnEmptyList() {
        assertThat(userWith("core_region", "[]").getCoreRegionList()).isEmpty();
    }

    @Test
    public void nonPrimitiveCoreRegionEntriesAreSkippedRatherThanStringified() {
        // A region is a name. An object here is the backend sending something this build does
        // not understand, and guessing at its text would produce a region that matches nothing.
        assertThat(userWith("core_region", "[\"us-east-1\",{\"region\":\"eu-west-2\"},null]")
                        .getCoreRegionList())
                .containsExactly("us-east-1");
    }

    // ---- securityInfo: the case that used to be dropped ---------------------------------

    @Test
    public void anAbsentSecurityInfoIsAnEmptyList() {
        assertThat(new MobileApiResponses.GlacierUser().getSecurityInfoList()).isEmpty();
    }

    @Test
    public void anExplicitJsonNullSecurityInfoIsAnEmptyList() {
        assertThat(userWith("securityInfo", "null").getSecurityInfoList()).isEmpty();
    }

    @Test
    public void anArrayOfDeviceObjectsIsKeptAsRawJson() {
        // The regression guard. This is the shape the backend actually sends, and the version
        // that accepted only primitives returned an empty list for it -- Security Hub then showed
        // no devices at all, with nothing to distinguish that from an account having none.
        final java.util.List<String> entries =
                userWith(
                                "securityInfo",
                                "[{\"deviceid\":\"a\",\"compromised\":false},"
                                        + "{\"deviceid\":\"b\",\"compromised\":true}]")
                        .getSecurityInfoList();

        assertThat(entries).hasSize(2);
        assertThat(entries.get(0)).contains("\"deviceid\":\"a\"");
        assertThat(entries.get(1)).contains("\"compromised\":true");
    }

    @Test
    public void aSingleDeviceObjectIsAListOfOne() {
        assertThat(userWith("securityInfo", "{\"deviceid\":\"a\"}").getSecurityInfoList())
                .hasSize(1);
    }

    @Test
    public void aPrimitiveSecurityInfoIsTakenAsItsStringValue() {
        // Kept working because older backends sent a pre-serialised string rather than an object.
        assertThat(userWith("securityInfo", "\"already-a-string\"").getSecurityInfoList())
                .containsExactly("already-a-string");
    }

    @Test
    public void nullEntriesInASecurityInfoArrayAreSkipped() {
        assertThat(userWith("securityInfo", "[{\"deviceid\":\"a\"},null]").getSecurityInfoList())
                .hasSize(1);
    }

    // ---- the rest of the response shapes -------------------------------------------------

    @Test
    public void theOrganisationFeatureFlagsAreDecodedFromTheirSnakeCaseNames() {
        final MobileApiResponses.GlacierOrganization organization =
                GSON.fromJson(
                        "{\"sms_enabled\":true,\"voicemail_enabled\":false,"
                                + "\"tracking_enabled\":true,\"force_tracking\":false,"
                                + "\"tracking_frequency\":\"15m\",\"dns_enabled\":true}",
                        MobileApiResponses.GlacierOrganization.class);

        assertThat(organization.smsEnabled).isTrue();
        assertThat(organization.voicemailEnabled).isFalse();
        assertThat(organization.trackingEnabled).isTrue();
        assertThat(organization.forceTracking).isFalse();
        assertThat(organization.trackingFrequency).isEqualTo("15m");
        assertThat(organization.dnsEnabled).isTrue();
    }

    @Test
    public void aGlacierUserIsDecodedFromItsSnakeCaseNames() {
        // The field names differ from the JSON names for most of these, so an accidental rename
        // would decode to null rather than fail.
        final MobileApiResponses.GlacierUser user =
                GSON.fromJson(
                        "{\"organization\":\"acme\",\"username\":\"testuser\","
                                + "\"messenger_id\":\"mid\",\"xmpp_token\":\"tok\","
                                + "\"first_name\":\"Test\",\"last_name\":\"User\","
                                + "\"isSMSEnabled\":true,\"add_to_core_vpn\":false}",
                        MobileApiResponses.GlacierUser.class);

        assertThat(user.organization).isEqualTo("acme");
        assertThat(user.messengerId).isEqualTo("mid");
        assertThat(user.xmppToken).isEqualTo("tok");
        assertThat(user.firstName).isEqualTo("Test");
        assertThat(user.lastName).isEqualTo("User");
        assertThat(user.isSMSEnabled).isTrue();
        assertThat(user.add_to_core_vpn).isFalse();
    }

    @Test
    public void theLatestVersionsResponseIsDecoded() {
        final MobileApiResponses.LatestVersionsResponse response =
                GSON.fromJson(
                        "{\"message\":\"ok\",\"versions\":{\"glacier_android\":\"7.3.0\","
                                + "\"android_os\":\"15\"}}",
                        MobileApiResponses.LatestVersionsResponse.class);

        assertThat(response.message).isEqualTo("ok");
        assertThat(response.versions.glacierAndroid).isEqualTo("7.3.0");
        assertThat(response.versions.androidOs).isEqualTo("15");
    }

    @Test
    public void aSecurityInfoUpdateRequestSerialisesUnderTheNamesTheBackendExpects() {
        // These @SerializedName annotations exist so the keys survive R8 member obfuscation --
        // without them the request went out as an empty object. Asserting the serialised form
        // rather than the fields is what would catch that.
        final MobileApiResponses.SecurityInfoUpdateRequest request =
                new MobileApiResponses.SecurityInfoUpdateRequest(
                        "acme", "testuser", parse("[{\"deviceid\":\"a\"}]"));

        final String json = GSON.toJson(request);

        assertThat(json).contains("\"organization\":\"acme\"");
        assertThat(json).contains("\"username\":\"testuser\"");
        assertThat(json).contains("\"securityInfo\":[{\"deviceid\":\"a\"}]");
    }

    @Test
    public void aDnsProfileResponseCarriesItsPayloadAsText() {
        final MobileApiResponses.DnsProfileResponse response =
                GSON.fromJson(
                        "{\"message\":\"ok\",\"data\":\"<profile/>\"}",
                        MobileApiResponses.DnsProfileResponse.class);

        assertThat(response.message).isEqualTo("ok");
        assertThat(response.data).isEqualTo("<profile/>");
    }
}
