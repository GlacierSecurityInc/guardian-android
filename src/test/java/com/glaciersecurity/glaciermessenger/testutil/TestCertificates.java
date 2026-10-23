package com.glaciersecurity.glaciermessenger.testutil;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

/**
 * Certificate fixtures for the TLS tests.
 *
 * <p>Both are self-signed and name only reserved documentation domains, so they chain to nothing
 * in any trust store -- which is exactly what the tests want: a certificate the platform refuses
 * is what makes "everything rejected it" reachable without minting one per test.
 *
 * <p>They are dated a century out deliberately. An expired fixture still fails the checks, but
 * for the wrong reason, and it starts doing so on a date nobody chose.
 *
 * <p>Generated with:
 *
 * <pre>
 * openssl req -x509 -newkey rsa:2048 -keyout /dev/null -out cert.pem -days 36500 -nodes \
 *   -subj "/CN=example.com/O=Example" \
 *   [-addext "subjectAltName=DNS:example.com,DNS:*.example.com,DNS:im.example.org"]
 * </pre>
 */
public final class TestCertificates {

    private TestCertificates() {}

    /** CN=example.com, with no subjectAltName at all -- exercises the CN fallback. */
    public static final String NO_SAN_PEM =
            ""
                    + "-----BEGIN CERTIFICATE-----\n"
                    + "MIIDMzCCAhugAwIBAgIUEAOapk5cSId8cq4vbNQlb6v6ByAwDQYJKoZIhvcNAQEL\n"
                    + "BQAwKDEUMBIGA1UEAwwLZXhhbXBsZS5jb20xEDAOBgNVBAoMB0V4YW1wbGUwIBcN\n"
                    + "MjYwOTIyMTgxODQ0WhgPMjEyNjA4MjkxODE4NDRaMCgxFDASBgNVBAMMC2V4YW1w\n"
                    + "bGUuY29tMRAwDgYDVQQKDAdFeGFtcGxlMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8A\n"
                    + "MIIBCgKCAQEArHdLyBbzt/1pwlUludxT//5MGMmhG3LnglEJXF1B/M4k3x7ERyTw\n"
                    + "70nqH1Tc4NGwVDuoIFPgokkV/MmUPf7+uDB8nv6VHV26KGDrxOK0M75zB9BkFJnh\n"
                    + "vk66mRzQR+f4GMxuuhp0mGNRjEWYmcHc8wVYlvr/kJ+8zpovSqdzd5xuGr0jOVhx\n"
                    + "WczgG2sr5hbo8qkd9Dn3AVdsuw7UqCjWkdhJAQWMQp1zpWL1xps9kgKV3kW1bxen\n"
                    + "PMYkpV8eVMDTmnzdx9tn12H6sdG/1b/7sUpV+9ObRmekWTMGE8OX9awbsbUrW3gL\n"
                    + "acWmUWQGxJsZXOItbjI21fbxd5Sfl2pK9QIDAQABo1MwUTAdBgNVHQ4EFgQU8cje\n"
                    + "U6tLh3K/7vLcAPizWWDkRoIwHwYDVR0jBBgwFoAU8cjeU6tLh3K/7vLcAPizWWDk\n"
                    + "RoIwDwYDVR0TAQH/BAUwAwEB/zANBgkqhkiG9w0BAQsFAAOCAQEATvS49LsBcewd\n"
                    + "nUFAIsp1ULgtMC2RQ8tgA37D0OqIJzE8+3eUqaeB6mgtEnlgtcpHID0mgnYekdr0\n"
                    + "ksq3YbnHodW0RA1WQrbNIacnlF9rmQ1NAs/pz1KdrOAiY9HAwZ49UpqsqB2w0gCn\n"
                    + "JpWoMShoPzZrG0/3SPANvYeUe4VrvLbM33ZknZSYv3I5D4r1C7X8Fn24qvS+tklM\n"
                    + "THWsBDsKCswexpGXBAcHulWTCgTqAxRODJU8pFil+hfnnwabLLF3byUgQQ1YJwKX\n"
                    + "NyAa8j65ubKfOdBWwPaxetOMM0sYIDr/knm7zkxzZcdal/xteamEwNVVtXJ742de\n"
                    + "neQTY/v9wQ==\n"
                    + "-----END CERTIFICATE-----\n";

    /** CN=example.com, with SANs example.com, *.example.com and im.example.org. */
    public static final String WITH_SANS_PEM =
            ""
                    + "-----BEGIN CERTIFICATE-----\n"
                    + "MIIDbDCCAlSgAwIBAgIUdzwngmLupxb+RLHQs5TSl9zCmtAwDQYJKoZIhvcNAQEL\n"
                    + "BQAwKDEUMBIGA1UEAwwLZXhhbXBsZS5jb20xEDAOBgNVBAoMB0V4YW1wbGUwIBcN\n"
                    + "MjYwOTIyMTk0MDE5WhgPMjEyNjA4MjkxOTQwMTlaMCgxFDASBgNVBAMMC2V4YW1w\n"
                    + "bGUuY29tMRAwDgYDVQQKDAdFeGFtcGxlMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8A\n"
                    + "MIIBCgKCAQEAx+KryCy4HqN/4fdtwFjdvRfLmjIPxxqE3RH1sybOiJUHhTgF92/o\n"
                    + "LdJcWM8CaQ0yipKs2aogJeRitozdZXN1tSQ7dSf7Qlp+UcP5iVyaSpFs5Iz/TJPV\n"
                    + "erIDQRHe8uSINgBRS4lKjleM0WCXTSTXQSpsE8FhoOJ9IjeWekYeAP7FFs9qSFJB\n"
                    + "e0j8o/YqzHD6CoJ2I78skwU9ZJs2xTtWQTC0n/dQqxee9P+WKH2PQvEcvqkRlkvs\n"
                    + "YAbXxJ6y14YkXuM40wGp9rB46oC3w5ItR7YCARGsVYxd+Q1S7wjqeNKlgdkT9nmS\n"
                    + "Z+K+6eXFjP8qq4bnaQI4KuHfuGaWaZ7IoQIDAQABo4GLMIGIMB0GA1UdDgQWBBS+\n"
                    + "zYetJJ3Ynb9ChOJhD1AP+1z+SzAfBgNVHSMEGDAWgBS+zYetJJ3Ynb9ChOJhD1AP\n"
                    + "+1z+SzAPBgNVHRMBAf8EBTADAQH/MDUGA1UdEQQuMCyCC2V4YW1wbGUuY29tgg0q\n"
                    + "LmV4YW1wbGUuY29tgg5pbS5leGFtcGxlLm9yZzANBgkqhkiG9w0BAQsFAAOCAQEA\n"
                    + "MAqcQCEf/rX69cMgZIsBKyL5IWlK1MBENIggh0dcXnfIlYOmOBmIMQ2UIg/s8fut\n"
                    + "LBnkRKPGYMOqfwZjoj4VvZL6enNodVHMcjQknZ3oz5Ft4moJ2uxdrPnsYCf9u4VT\n"
                    + "/+Q5FMgF+9WLOB33Kl3paNDjrhvKfloYAilcKTnhYNmL/jKdZL2RlObANoSFVhsr\n"
                    + "9guh2LRDlZFjoWaXCKxZmgcm4bQELd5ndfsCXyOBEgFA1qV1q1Ap2iryFXxXpckv\n"
                    + "SepIyeb6fodRRnHrPxUDbMzW0ePrQDMWfZpYOwdJngd97mgwlzRtFXwxeoEo8Nz+\n"
                    + "68N0ImvBqAZkduyeHxORxg==\n"
                    + "-----END CERTIFICATE-----\n";

    public static X509Certificate parse(final String pem) throws CertificateException {
        return (X509Certificate)
                CertificateFactory.getInstance("X.509")
                        .generateCertificate(
                                new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
    }
}
