package com.glaciersecurity.glaciermessenger.utils;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * The voice token endpoint has returned both a bare JWT and several JSON envelopes over the
 * life of the Phone module. Getting this wrong means outgoing calls fail with no useful error,
 * which is the same failure mode as a backend rollback — so the parser has to be forgiving in
 * shape and strict about what counts as a token.
 */
public class TwilioAccessTokenParserTest {

    private static final String JWT = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJndWFyZGlhbiJ9.c2lnbmF0dXJl";
    private static final String JWE = "header.encryptedkey.iv.ciphertext.tag";

    @Test
    public void bareJwtIsReturnedAsIs() {
        assertThat(TwilioAccessTokenParser.parse(JWT)).isEqualTo(JWT);
    }

    @Test
    public void surroundingWhitespaceIsTrimmed() {
        assertThat(TwilioAccessTokenParser.parse("  " + JWT + "\n")).isEqualTo(JWT);
    }

    @Test
    public void fiveSegmentJweIsAlsoAToken() {
        assertThat(TwilioAccessTokenParser.parse(JWE)).isEqualTo(JWE);
    }

    @Test
    public void unwrapsTheLowerCaseTokenKey() {
        assertThat(TwilioAccessTokenParser.parse("{\"token\":\"" + JWT + "\"}")).isEqualTo(JWT);
    }

    @Test
    public void unwrapsTheCamelCaseAccessTokenKey() {
        assertThat(TwilioAccessTokenParser.parse("{\"accessToken\":\"" + JWT + "\"}"))
                .isEqualTo(JWT);
    }

    @Test
    public void unwrapsThePascalCaseAccessTokenKey() {
        assertThat(TwilioAccessTokenParser.parse("{\"AccessToken\":\"" + JWT + "\"}"))
                .isEqualTo(JWT);
    }

    @Test
    public void ignoresUnrelatedFieldsAlongsideTheToken() {
        final String body = "{\"identity\":\"testuser\",\"ttl\":3600,\"token\":\"" + JWT + "\"}";
        assertThat(TwilioAccessTokenParser.parse(body)).isEqualTo(JWT);
    }

    @Test
    public void nullResponseYieldsNoToken() {
        assertThat(TwilioAccessTokenParser.parse(null)).isNull();
    }

    @Test
    public void emptyResponseYieldsNoToken() {
        assertThat(TwilioAccessTokenParser.parse("")).isNull();
        assertThat(TwilioAccessTokenParser.parse("   ")).isNull();
    }

    @Test
    public void plainTextThatIsNotATokenYieldsNoToken() {
        assertThat(TwilioAccessTokenParser.parse("Internal Server Error")).isNull();
    }

    @Test
    public void jsonWithoutATokenFieldYieldsNoToken() {
        // A rollback or partial rollout can answer 200 with a body that carries no token.
        // Returning null here is what lets the caller clear the stale one instead of reusing it.
        assertThat(TwilioAccessTokenParser.parse("{\"identity\":\"testuser\"}")).isNull();
    }

    @Test
    public void jsonWhoseTokenIsNotATokenYieldsNoToken() {
        assertThat(TwilioAccessTokenParser.parse("{\"token\":\"expired\"}")).isNull();
    }

    @Test
    public void malformedJsonDoesNotThrow() {
        assertThat(TwilioAccessTokenParser.parse("{\"token\":")).isNull();
    }
}
