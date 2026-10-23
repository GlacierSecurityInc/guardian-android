package com.glaciersecurity.glaciermessenger.crypto.sasl;

import com.glaciersecurity.glaciermessenger.utils.Log;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.Collections2;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.utils.SSLSockets;
import com.glaciersecurity.glaciermessenger.xml.Element;
import com.glaciersecurity.glaciermessenger.xml.Namespace;

import java.util.Collection;
import java.util.Collections;

import javax.net.ssl.SSLSocket;

public abstract class SaslMechanism {

    protected final Account account;

    protected SaslMechanism(final Account account) {
        this.account = account;
    }

    public static String namespace(final Version version) {
        if (version == Version.SASL) {
            return Namespace.SASL;
        } else {
            return Namespace.SASL_2;
        }
    }

    /**
     * The priority is used to pin the authentication mechanism. If authentication fails, it MAY be
     * retried with another mechanism of the same priority, but MUST NOT be tried with a mechanism
     * of lower priority (to prevent downgrade attacks).
     *
     * @return An arbitrary int representing the priority
     */
    public abstract int getPriority();

    public abstract String getMechanism();

    public String getClientFirstMessage(final SSLSocket sslSocket) {
        return "";
    }

    public String getResponse(final String challenge, final SSLSocket sslSocket)
            throws AuthenticationException {
        return "";
    }

    public static Collection<String> mechanisms(final Element authElement) {
        if (authElement == null) {
            return Collections.emptyList();
        }
        return Collections2.transform(
                Collections2.filter(
                        authElement.getChildren(),
                        c -> c != null && "mechanism".equals(c.getName())),
                c -> c == null ? null : c.getContent());
    }

    protected enum State {
        INITIAL,
        AUTH_TEXT_SENT,
        RESPONSE_SENT,
        VALID_SERVER_RESPONSE,
    }

    public enum Version {
        SASL,
        SASL_2;

        public static Version of(final Element element) {
            switch (Strings.nullToEmpty(element.getNamespace())) {
                case Namespace.SASL:
                    return SASL;
                case Namespace.SASL_2:
                    return SASL_2;
                default:
                    throw new IllegalArgumentException("Unrecognized SASL namespace");
            }
        }
    }

    public static class AuthenticationException extends Exception {
        public AuthenticationException(final String message) {
            super(message);
        }

        public AuthenticationException(final Exception inner) {
            super(inner);
        }

        public AuthenticationException(final String message, final Exception exception) {
            super(message, exception);
        }
    }

    public static class InvalidStateException extends AuthenticationException {
        public InvalidStateException(final String message) {
            super(message);
        }

        public InvalidStateException(final State state) {
            this("Invalid state: " + state.toString());
        }
    }

    public static final class Factory {

        private final Account account;

        public Factory(final Account account) {
            this.account = account;
        }

        private SaslMechanism of(
                final Collection<String> mechanisms, final ChannelBinding channelBinding) {
            Preconditions.checkNotNull(channelBinding, "Use ChannelBinding.NONE instead of null");
            if (account.hasToken()) {
                // JWT auth rides on SASL PLAIN (ejabberd_auth_jwt) -- there is no token-specific
                // mechanism. Force PLAIN so a JWT never ends up handed to SCRAM/DIGEST, which
                // cannot validate it (no shared salted secret).
                if (mechanisms.contains(Plain.MECHANISM)) {
                    return new Plain(account);
                }
                Log.w(
                        Config.LOGTAG,
                        "account has an XMPP token but server does not offer PLAIN; offered mechanisms: "
                                + mechanisms);
                return null;
            } else if (mechanisms.contains(External.MECHANISM) && account.getPrivateKeyAlias() != null) {
                return new External(account);
            } else if (mechanisms.contains(ScramSha512Plus.MECHANISM)
                    && channelBinding != ChannelBinding.NONE) {
                return new ScramSha512Plus(account, channelBinding);
            } else if (mechanisms.contains(ScramSha256Plus.MECHANISM)
                    && channelBinding != ChannelBinding.NONE) {
                return new ScramSha256Plus(account, channelBinding);
            } else if (mechanisms.contains(ScramSha1Plus.MECHANISM)
                    && channelBinding != ChannelBinding.NONE) {
                return new ScramSha1Plus(account, channelBinding);
            } else if (mechanisms.contains(ScramSha512.MECHANISM)) {
                return new ScramSha512(account);
            } else if (mechanisms.contains(ScramSha256.MECHANISM)) {
                return new ScramSha256(account);
            } else if (mechanisms.contains(ScramSha1.MECHANISM)) {
                return new ScramSha1(account);
            } else if (mechanisms.contains(Plain.MECHANISM)
                    && !account.getServer().equals("nimbuzz.com")) {
                return new Plain(account);
            } else if (mechanisms.contains(Anonymous.MECHANISM)) {
                return new Anonymous(account);
            } else {
                // Log which mechanisms were offered so operators can diagnose incompatible server configs.
                // Note: DIGEST-MD5 is intentionally not supported — MD5 is cryptographically broken.
                Log.w(Config.LOGTAG, "No supported SASL mechanism found in offered set: " + mechanisms + ". DIGEST-MD5 is not supported. Ensure the server offers SCRAM-SHA-* or PLAIN over TLS.");
                return null;
            }
        }

        public SaslMechanism of(
                final Collection<String> mechanisms,
                final Collection<ChannelBinding> bindings,
                final Version version,
                final SSLSockets.Version sslVersion) {
            final HashedToken fastMechanism = account.getFastMechanism();
            if (version == Version.SASL_2 && fastMechanism != null) {
                return fastMechanism;
            }
            final ChannelBinding channelBinding = ChannelBinding.best(bindings, sslVersion);
            return of(mechanisms, channelBinding);
        }

        public SaslMechanism of(final String mechanism, final ChannelBinding channelBinding) {
            return of(Collections.singleton(mechanism), channelBinding);
        }
    }

    public static SaslMechanism ensureAvailable(
            final SaslMechanism mechanism,
            final SSLSockets.Version sslVersion,
            final boolean requireChannelBinding) {
        if (mechanism instanceof ChannelBindingMechanism) {
            final ChannelBinding cb = ((ChannelBindingMechanism) mechanism).getChannelBinding();
            if (ChannelBinding.isAvailable(cb, sslVersion)) {
                return mechanism;
            } else {
                return null;
            }
        } else if (requireChannelBinding) {
            return null;
        } else {
            return mechanism;
        }
    }

    public static boolean hashedToken(final SaslMechanism saslMechanism) {
        return saslMechanism instanceof HashedToken;
    }

    public static boolean pin(final SaslMechanism saslMechanism) {
        return !hashedToken(saslMechanism);
    }
}
