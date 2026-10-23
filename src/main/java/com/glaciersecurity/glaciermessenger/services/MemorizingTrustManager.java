/*
 * MemorizingTrustManager — Glacier Security hardened version
 *
 * Original copyright (c) 2010 Georg Lukas <georg@op-co.de>
 *
 * GLACIER SECURITY — SECURITY ARCHITECTURE NOTE
 * ==============================================
 * The upstream MTM lets users accept and permanently memorise arbitrary
 * certificates, including ones signed by attacker-controlled CAs.
 *
 * This version removes the memorisation and interactive-dialog behaviour
 * entirely. All XMPP TLS connections are now validated strictly against the
 * Android system trust store only. Any certificate that fails system
 * validation — including self-signed certs and certs from custom CAs — is
 * rejected immediately with no user override path.
 *
 * WHY NO CA PINS HERE
 * -------------------
 * CA pinning (Amazon ATS / GTS hashes) belongs only in:
 *   - OkHttpClientProvider.java           (REST API calls via OkHttp)
 *   - res/xml/network_security_config.xml  (OS-level enforcement)
 *
 * MTM handles XMPP server connections. The XMPP server may use any publicly-
 * trusted CA (Let's Encrypt, DigiCert, etc.) — those CAs are not in the
 * REST API pin set. Adding REST API pins here would break all XMPP connections
 * to servers not using Amazon ATS or GTS certificates.
 *
 * WHY NO MemorizingActivity / DIALOG
 * ------------------------------------
 * Since self-signed and custom-CA certificates are explicitly not trusted,
 * there is nothing for the user to decide. The dialog path has been removed.
 * If a legitimate XMPP server certificate fails system trust validation, the
 * correct fix is to ensure the server presents a valid public CA certificate —
 * not to add a user-approval bypass.
 *
 * WHAT IS PRESERVED
 * ------------------
 * - getNonInteractive() for call-site compatibility with XmppConnectionService
 * - checkClientTrusted() delegates to system trust (client certs unchanged)
 * - getCertificates() is kept as a stub so any UI that lists trusted certs
 *   compiles and shows an empty list
 */
package com.glaciersecurity.glaciermessenger.services;

import android.util.Log;

import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.Enumeration;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

public class MemorizingTrustManager implements X509TrustManager {

    private static final String TAG = "MemorizingTrustManager";

    private final X509TrustManager systemTrustManager;

    // =========================================================================
    // Construction
    // =========================================================================

    public MemorizingTrustManager() {
        // There is no app keystore or notification manager to initialise.
        systemTrustManager = buildSystemTrustManager();
    }

    private static X509TrustManager buildSystemTrustManager() {
        try {
            TrustManagerFactory tmf =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((java.security.KeyStore) null);
            for (TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof X509TrustManager) {
                    return (X509TrustManager) tm;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "buildSystemTrustManager: failed to initialise", e);
        }
        throw new IllegalStateException(
                "MemorizingTrustManager: no X509TrustManager available from system");
    }

    // =========================================================================
    // X509TrustManager — outer class
    // =========================================================================

    /**
     * Validates the server's certificate chain strictly against the Android
     * system trust store.
     *
     * <p>Self-signed certs, expired certs, and certs from any CA not in
     * Android's built-in trust store are rejected immediately with a
     * {@link CertificateException}. There is no fallback, no dialog, and no
     * user override path.</p>
     */
    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType)
            throws CertificateException {
        systemTrustManager.checkServerTrusted(chain, authType);
    }

    /**
     * Delegates client certificate validation to the system trust manager.
     * The strict server-trust policy above does not apply to client auth.
     */
    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType)
            throws CertificateException {
        systemTrustManager.checkClientTrusted(chain, authType);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return systemTrustManager.getAcceptedIssuers();
    }

    // =========================================================================
    // Public API — call-site compatible with XmppConnectionService
    //
    // Hostname verification is handled by the TLS layer (SSLParameters /
    // HostnameVerifier) at the socket level, independently of this manager.
    // =========================================================================

    /** Returns a strict system-trust-only manager with no hostname binding. */
    public X509TrustManager getNonInteractive() {
        return systemTrustManager;
    }

    // =========================================================================
    // HostnameVerifier API — required by HttpConnection.connect()
    //
    // The original MTM wrapped the hostname verifier to optionally show a
    // memorisation dialog for hostname mismatches. Since this version memorises
    // nothing, these methods return the verifier unchanged so that
    // HttpConnection gets back the strict system verifier it passed in
    // (HttpsURLConnection.getDefaultHostnameVerifier()) and standard hostname
    // validation proceeds normally.
    // =========================================================================

    /**
     * Returns {@code verifier} unchanged.
     *
     * Non-interactive variant called by background HTTP operations such as
     * file upload/download in {@code HttpConnection}.
     */
    public javax.net.ssl.HostnameVerifier wrapHostnameVerifierNonInteractive(
            final javax.net.ssl.HostnameVerifier verifier) {
        return verifier;
    }

    /**
     * No-op. The original MTM used this to bind the current thread to a
     * foreground Activity so dialog callbacks could be dispatched correctly.
     * Since there is no dialog, this method does nothing but must exist because
     * some call sites invoke it unconditionally before opening a TLS connection.
     */
    public void bindCurrentThread() {
        // intentionally empty
    }

    // =========================================================================
    // Stub keystore accessors
    //
    // Kept so that any UI listing previously-memorised certificates compiles
    // correctly and shows an empty list rather than crashing.
    // No certificates are stored or retrieved.
    // =========================================================================

    /** Always returns an empty enumeration — no certs are memorised. */
    public Enumeration<String> getCertificates() {
        return Collections.emptyEnumeration();
    }
}