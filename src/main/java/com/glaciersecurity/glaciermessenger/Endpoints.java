package com.glaciersecurity.glaciermessenger;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Chooses which Glacier backend the app talks to.
 *
 * <p>{@link EndPointConfig} carries the three base URLs; call sites go through {@link #base()}
 * rather than naming one of them directly.
 *
 * <p>This lives here rather than in {@code EndPointConfig} on purpose. That file is gitignored
 * and supplied to CI as the {@code ENDPOINT_CONFIG_JAVA} secret, so anything added to it is
 * invisible to code review and lost on a fresh checkout. Selection logic belongs in version
 * control; only the URLs themselves stay in the secret.
 *
 * <h2>Choosing an environment</h2>
 * <pre>{@code
 * ./gradlew assembleDebug                                  # PROD (default)
 * ./gradlew assembleDebug -PglacierApiEnvironment=STAGING   # staging
 * ./gradlew assembleDebug -PglacierApiEnvironment=DEV       # dev
 * }</pre>
 *
 * <p>Release builds ignore the property entirely and always resolve to production — a mistyped
 * Gradle property cannot ship a store build pointed at staging.
 */
public final class Endpoints {

    public enum Environment {
        PROD,
        STAGING,
        DEV
    }

    private Endpoints() {
        throw new IllegalStateException("Do not instantiate");
    }

    /**
     * The environment this build talks to. Always {@link Environment#PROD} for release builds,
     * whatever {@code BuildConfig.API_ENVIRONMENT} says.
     */
    public static Environment environment() {
        if (!BuildConfig.DEBUG) {
            return Environment.PROD;
        }
        return parse(BuildConfig.API_ENVIRONMENT);
    }

    /**
     * Base URL for the Glacier REST API, always ending in a slash.
     *
     * <p>The trailing slash is not cosmetic. Retrofit rejects a base URL without one, so
     * {@code MobileApiClient}'s static initializer would throw the moment an environment whose
     * URL lacks it was selected; and the call sites that build a URL as {@code base() + path}
     * would otherwise run the host straight into the path. Canonicalising here means neither
     * depends on how the value happens to be written in EndPointConfig.
     *
     * <p>Falls back to production when the selected environment has no URL configured, so a
     * half-filled EndPointConfig degrades to a working build rather than a stream of malformed
     * requests.
     */
    public static String base() {
        final String selected = baseFor(environment());
        if (!isEmpty(selected)) {
            return withTrailingSlash(selected);
        }
        return withTrailingSlash(EndPointConfig.PROD_BASE);
    }

    /**
     * Absolute URL for one of the {@link EndPointConfig} paths, tolerating a base that does or
     * does not end in a slash.
     */
    public static String url(final String path) {
        return join(base(), path);
    }

    /** The base URL for a specific environment, or {@code null} when it is not configured. */
    public static String baseFor(final Environment environment) {
        switch (environment) {
            case STAGING:
                return EndPointConfig.STAGING_BASE;
            case DEV:
                return EndPointConfig.DEV_BASE;
            case PROD:
            default:
                return EndPointConfig.PROD_BASE;
        }
    }

    /**
     * Every configured base URL. Certificate pinning covers all of them regardless of which one
     * this build points at, so switching environments never silently drops pinning.
     */
    public static Set<String> allBases() {
        final Set<String> bases = new LinkedHashSet<>();
        for (final Environment environment : Environment.values()) {
            final String base = baseFor(environment);
            if (!isEmpty(base)) {
                bases.add(base);
            }
        }
        return Collections.unmodifiableSet(bases);
    }

    /** Joins a base and a path with exactly one slash between them. */
    static String join(final String base, final String path) {
        if (isEmpty(path)) {
            return base;
        }
        final boolean baseEndsWithSlash = base.endsWith("/");
        final boolean pathStartsWithSlash = path.startsWith("/");
        if (baseEndsWithSlash && pathStartsWithSlash) {
            return base + path.substring(1);
        }
        if (!baseEndsWithSlash && !pathStartsWithSlash) {
            return base + "/" + path;
        }
        return base + path;
    }

    /** Unknown or absent values resolve to production rather than failing the build. */
    static Environment parse(final String value) {
        if (isEmpty(value)) {
            return Environment.PROD;
        }
        try {
            return Environment.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException unknown) {
            return Environment.PROD;
        }
    }

    private static String withTrailingSlash(final String base) {
        if (isEmpty(base) || base.endsWith("/")) {
            return base;
        }
        return base + "/";
    }

    // Deliberately not android.text.TextUtils: keeping this class free of framework calls is
    // what lets its selection and URL-joining logic be covered by the JVM unit tier.
    private static boolean isEmpty(final String value) {
        return value == null || value.isEmpty();
    }
}
