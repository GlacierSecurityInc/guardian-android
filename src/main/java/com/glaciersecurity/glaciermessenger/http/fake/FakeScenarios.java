package com.glaciersecurity.glaciermessenger.http.fake;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-endpoint response behaviour for {@link FakeGlacierBackend}.
 *
 * <p>Every endpoint answers {@link Scenario#OK} unless something overrides it. An override is
 * keyed by the endpoint's <em>last path segment</em> — {@code purchaseTwilioNumber},
 * {@code voicemail_list} — which is what the adb command and the tests use, so nobody has to
 * spell out a full path.
 *
 * <p>The point of the non-OK scenarios is that the app has distinct handling for each and none
 * of it was reachable before: {@link Scenario#MALFORMED} is the only way to reach
 * {@code handleWebhookJsonSyntaxException} and the {@code consoleError} banner it raises,
 * and {@link Scenario#NETWORK_ERROR} is the only way to reach the {@code IOException} catch
 * blocks without unplugging a device mid-call.
 */
public final class FakeScenarios {

    public enum Scenario {
        /** The endpoint's normal, successful answer. */
        OK,
        /** HTTP 200 carrying {@code {"message":"failure"}} — how this backend reports a refusal. */
        FAILURE,
        /** HTTP 500 with an HTML-ish body, the way a failing gateway actually answers. */
        HTTP_500,
        /** HTTP 200 with a zero-length body. */
        EMPTY,
        /** HTTP 200 with a body that is not JSON at all. */
        MALFORMED,
        /** The call throws {@link java.io.IOException} before a response exists. */
        NETWORK_ERROR;

        public static Scenario parse(final String value) {
            if (value == null) {
                return OK;
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (final IllegalArgumentException e) {
                return OK;
            }
        }
    }

    private static final Map<String, Scenario> OVERRIDES = new ConcurrentHashMap<>();

    private FakeScenarios() {
        throw new IllegalStateException("Do not instantiate");
    }

    /** The scenario for an endpoint, by last path segment. Never null. */
    public static Scenario forEndpoint(final String endpointKey) {
        if (endpointKey == null) {
            return Scenario.OK;
        }
        final Scenario scenario = OVERRIDES.get(endpointKey);
        return scenario == null ? Scenario.OK : scenario;
    }

    /**
     * Overrides one endpoint. The key {@code all} sets every endpoint at once, which is how you
     * ask "what does the whole app look like when the console is down?".
     */
    public static void set(final String endpointKey, final Scenario scenario) {
        if (endpointKey == null) {
            return;
        }
        if (scenario == null || scenario == Scenario.OK) {
            OVERRIDES.remove(endpointKey);
            return;
        }
        OVERRIDES.put(endpointKey, scenario);
    }

    /** Back to all-OK. */
    public static void reset() {
        OVERRIDES.clear();
    }

    /** A snapshot of the active overrides, for logging and for the debug receiver's reply. */
    public static Map<String, Scenario> active() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(OVERRIDES));
    }

    /** The key {@code all} applies to every endpoint; anything else matches one. */
    static final String WILDCARD_KEY = "all";

    static Scenario resolve(final String endpointKey) {
        final Scenario wildcard = OVERRIDES.get(WILDCARD_KEY);
        if (wildcard != null) {
            return wildcard;
        }
        return forEndpoint(endpointKey);
    }
}
