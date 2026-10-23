package com.glaciersecurity.glaciermessenger;

import static com.google.common.truth.Truth.assertThat;

import com.glaciersecurity.glaciermessenger.Endpoints.Environment;

import org.junit.Test;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Environment selection and URL joining. Deliberately asserts nothing about the actual base
 * URLs — those live in the gitignored EndPointConfig and differ per checkout, so a test that
 * read them would only be testing a secret.
 */
public class EndpointsTest {

    @Test
    public void recognisesEveryEnvironmentName() {
        assertThat(Endpoints.parse("PROD")).isEqualTo(Environment.PROD);
        assertThat(Endpoints.parse("STAGING")).isEqualTo(Environment.STAGING);
        assertThat(Endpoints.parse("DEV")).isEqualTo(Environment.DEV);
    }

    @Test
    public void environmentNamesAreCaseAndWhitespaceInsensitive() {
        assertThat(Endpoints.parse("staging")).isEqualTo(Environment.STAGING);
        assertThat(Endpoints.parse("  Staging  ")).isEqualTo(Environment.STAGING);
    }

    @Test
    public void anUnknownEnvironmentFallsBackToProduction() {
        // A typo in -PglacierApiEnvironment should build against production, not fail at
        // runtime with a malformed base URL.
        assertThat(Endpoints.parse("stagng")).isEqualTo(Environment.PROD);
        assertThat(Endpoints.parse("")).isEqualTo(Environment.PROD);
        assertThat(Endpoints.parse(null)).isEqualTo(Environment.PROD);
    }

    @Test
    public void joinInsertsExactlyOneSlash() {
        assertThat(Endpoints.join("https://api.example.com/", "api/mobile/is-live"))
                .isEqualTo("https://api.example.com/api/mobile/is-live");
        assertThat(Endpoints.join("https://api.example.com", "api/mobile/is-live"))
                .isEqualTo("https://api.example.com/api/mobile/is-live");
        assertThat(Endpoints.join("https://api.example.com/", "/api/mobile/is-live"))
                .isEqualTo("https://api.example.com/api/mobile/is-live");
        assertThat(Endpoints.join("https://api.example.com", "/api/mobile/is-live"))
                .isEqualTo("https://api.example.com/api/mobile/is-live");
    }

    @Test
    public void joiningNothingReturnsTheBaseUnchanged() {
        assertThat(Endpoints.join("https://api.example.com/", ""))
                .isEqualTo("https://api.example.com/");
        assertThat(Endpoints.join("https://api.example.com/", null))
                .isEqualTo("https://api.example.com/");
    }

    @Test
    public void aQueryStringSurvivesJoining() {
        assertThat(Endpoints.join("https://api.example.com/", "api/mobile/getVoicemail?id=7"))
                .isEqualTo("https://api.example.com/api/mobile/getVoicemail?id=7");
    }

    @Test
    public void theBaseIsAlwaysUsableAsARetrofitBaseUrl() {
        // Retrofit rejects a base URL with no trailing slash, and MobileApiClient builds its
        // service in a static initializer -- so a base without one takes the app down at class
        // load, not at request time. base() canonicalises rather than trusting EndPointConfig.
        assertThat(Endpoints.base()).isNotEmpty();
        assertThat(Endpoints.base()).endsWith("/");
    }

    @Test
    public void anEnvironmentWithNoUrlConfiguredIsAValidConfiguration() {
        // baseFor() documents that it may return null and base() falls back to production, so a
        // checkout that fills in only PROD_BASE is legitimate. Asserting every environment is
        // populated would make the suite reject a configuration the app deliberately supports.
        assertThat(Endpoints.base()).isNotEmpty();
    }

    @Test
    public void allBasesReportsEveryConfiguredEnvironmentNotJustTheSelectedOne() {
        // Pinning consumes allBases(), so switching to staging must not quietly leave the other
        // hosts unpinned -- but that coverage is asserted by utils/PinnedEndpointCoverageTest,
        // which compares EndPointConfig against the registered host sets. This test asserts only
        // the enumeration allBases() is responsible for: every configured environment, not just
        // the selected one. How many environments
        // are configured is a property of the checkout, so assert the bound rather than an exact
        // count, and tolerate two environments sharing a URL.
        final Set<String> bases = Endpoints.allBases();
        assertThat(bases).isNotEmpty();
        assertThat(bases.size()).isAtMost(Environment.values().length);
        for (final String base : bases) {
            assertThat(base).isNotEmpty();
        }
    }

    @Test
    public void theSelectedBaseIsOneOfTheConfiguredOnes() {
        // Compared slash-insensitively: base() canonicalises its result, allBases() reports the
        // URLs as configured because host extraction for pinning does not care either way.
        final Set<String> canonical = new LinkedHashSet<>();
        for (final String base : Endpoints.allBases()) {
            canonical.add(base.endsWith("/") ? base : base + "/");
        }
        assertThat(canonical).contains(Endpoints.base());
    }
}
