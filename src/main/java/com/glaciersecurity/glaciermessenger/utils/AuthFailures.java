package com.glaciersecurity.glaciermessenger.utils;

import androidx.annotation.Nullable;

import com.amplifyframework.auth.AuthException;

import java.io.IOException;
import java.util.Locale;

/**
 * Classifies a failed Cognito sign-in so callers can tell the user what actually went wrong.
 *
 * Amplify reports a rejected password, a dead network and a broken backend through the same
 * {@link AuthException}, so a caller that treats every failure as "wrong password" sends users
 * off retyping a correct one. Only the signals we positively recognize as a rejected credential
 * are reported as such; anything else stays UNKNOWN.
 */
public final class AuthFailures {

    private AuthFailures() {}

    public enum Kind {
        /** The credential itself was rejected: bad password, unknown user, bad MFA code. */
        CREDENTIALS,
        /** The request never got a verdict: no connectivity, DNS failure, timeout. */
        NETWORK,
        /** Anything else -- a backend error, a misconfigured client, an unrecognized failure. */
        UNKNOWN
    }

    public static Kind classify(@Nullable final AuthException error) {
        if (error == null) {
            return Kind.UNKNOWN;
        }

        final String className = error.getClass().getSimpleName();
        if ("NotAuthorizedException".equals(className)
                || "UserNotFoundException".equals(className)
                || "InvalidParameterException".equals(className)
                || "CodeMismatchException".equals(className)
                || "ExpiredCodeException".equals(className)) {
            return Kind.CREDENTIALS;
        }

        final StringBuilder details = new StringBuilder();
        if (error.getMessage() != null) {
            details.append(error.getMessage()).append(' ');
        }
        if (error.getRecoverySuggestion() != null) {
            details.append(error.getRecoverySuggestion()).append(' ');
        }
        if (error.getCause() != null && error.getCause().getMessage() != null) {
            details.append(error.getCause().getMessage());
        }

        final String lowerDetails = details.toString().toLowerCase(Locale.US);

        if (lowerDetails.contains("incorrect username")
                || lowerDetails.contains("incorrect password")
                || lowerDetails.contains("not authorized")
                || lowerDetails.contains("user does not exist")
                || lowerDetails.contains("invalid password")) {
            return Kind.CREDENTIALS;
        }

        if (lowerDetails.contains("connect_timeout")
                || lowerDetails.contains("sockettimeoutexception")
                || lowerDetails.contains("timed out")
                || lowerDetails.contains("network")
                || lowerDetails.contains("unable to resolve host")
                || lowerDetails.contains("failed to connect")) {
            return Kind.NETWORK;
        }

        if (hasNetworkCause(error)) {
            return Kind.NETWORK;
        }

        // Unknown failures are not evidence of a bad password. Reporting them as one made
        // users retype a correct password, so they stay UNKNOWN.
        return Kind.UNKNOWN;
    }

    /**
     * True only for failures we can positively attribute to the credential the user typed.
     */
    public static boolean isCredentialFailure(@Nullable final AuthException error) {
        return classify(error) == Kind.CREDENTIALS;
    }

    /**
     * Classifies a non-Amplify failure -- a Retrofit callback, say -- where an {@link IOException}
     * anywhere in the cause chain means the call never reached the backend.
     */
    public static Kind classifyThrowable(@Nullable final Throwable throwable) {
        if (throwable instanceof AuthException authException) {
            return classify(authException);
        }
        return isNetworkThrowable(throwable) ? Kind.NETWORK : Kind.UNKNOWN;
    }

    private static boolean hasNetworkCause(final Throwable throwable) {
        return isNetworkThrowable(throwable.getCause());
    }

    private static boolean isNetworkThrowable(@Nullable Throwable throwable) {
        // Guard against a self-referencing or cyclic cause chain rather than trusting it.
        for (int depth = 0; throwable != null && depth < 8; ++depth) {
            if (throwable instanceof IOException) {
                return true;
            }
            final Throwable cause = throwable.getCause();
            if (cause == throwable) {
                return false;
            }
            throwable = cause;
        }
        return false;
    }
}
