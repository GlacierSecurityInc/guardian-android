package com.glaciersecurity.glaciermessenger.services;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.gson.internal.LinkedTreeMap;

import org.junit.Test;

/**
 * {@link CallInfo} maps a Twilio call payload onto the two questions the call UI actually asks:
 * which number is ours (the proxy) and which is the other party (the remote). The mapping inverts
 * with call direction, so getting it backwards shows the caller their own number as the person
 * they are talking to -- and dials the wrong one on a callback.
 *
 * <p>Plain JUnit: the class is a Gson map and String comparisons.
 */
public class CallInfoTest {

    /** Reserved test ranges only -- see the repo's no-real-data-in-tests rule. */
    private static final String OUR_NUMBER = "+15555550100";
    private static final String THEIR_NUMBER = "+15555550199";

    private static CallInfo callInfo(final String direction) {
        final LinkedTreeMap<Object, Object> data = new LinkedTreeMap<>();
        data.put("to", OUR_NUMBER);
        data.put("toFormatted", "(555) 555-0100");
        data.put("from", THEIR_NUMBER);
        data.put("fromFormatted", "(555) 555-0199");
        data.put("phoneNumberSid", "PN00000000000000000000000000000000");
        data.put("direction", direction);
        return new CallInfo(data);
    }

    @Test
    public void everyFieldInThePayloadIsCarriedOver() {
        final CallInfo info = callInfo("inbound");
        assertThat(info.getTo()).isEqualTo(OUR_NUMBER);
        assertThat(info.getFrom()).isEqualTo(THEIR_NUMBER);
        assertThat(info.getToFormatted()).isEqualTo("(555) 555-0100");
        assertThat(info.getFromFormatted()).isEqualTo("(555) 555-0199");
    }

    @Test
    public void onAnInboundCallWeAreTheCalledNumber() {
        final CallInfo info = callInfo("inbound");
        assertThat(info.getProxy()).isEqualTo(OUR_NUMBER);
        assertThat(info.getRemote()).isEqualTo(THEIR_NUMBER);
    }

    @Test
    public void onAnOutboundCallTheMappingInverts() {
        final CallInfo info = callInfo("outbound-api");
        assertThat(info.getProxy()).isEqualTo(THEIR_NUMBER);
        assertThat(info.getRemote()).isEqualTo(OUR_NUMBER);
    }

    /**
     * Only the exact string "inbound" is treated as inbound; anything else falls through to the
     * outbound mapping. Pinned because Twilio's outbound directions are several distinct strings
     * ("outbound-api", "outbound-dial"), and the default has to stay on that side.
     */
    @Test
    public void anUnrecognisedDirectionIsTreatedAsOutbound() {
        assertThat(callInfo("trunking-terminating").getProxy()).isEqualTo(THEIR_NUMBER);
    }

    @Test
    public void theCallerNameIsNotReadFromThePayloadAndStartsUnset() {
        final CallInfo info = callInfo("inbound");
        assertThat(info.getCallerName()).isNull();
        info.setCallerName("Someone");
        assertThat(info.getCallerName()).isEqualTo("Someone");
    }

    /**
     * A payload with no direction leaves the field null and both accessors throw. Pinned rather
     * than endorsed -- it is an unguarded {@code direction.equals(...)}, so a malformed payload
     * takes out the call screen rather than degrading.
     */
    @Test
    public void aMissingDirectionThrowsRatherThanDefaulting() {
        final LinkedTreeMap<Object, Object> data = new LinkedTreeMap<>();
        data.put("to", OUR_NUMBER);
        data.put("from", THEIR_NUMBER);
        final CallInfo info = new CallInfo(data);
        assertThrows(NullPointerException.class, info::getProxy);
        assertThrows(NullPointerException.class, info::getRemote);
    }
}
