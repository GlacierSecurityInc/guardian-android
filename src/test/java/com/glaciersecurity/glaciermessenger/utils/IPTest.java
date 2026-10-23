package com.glaciersecurity.glaciermessenger.utils;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Host handling on the connection path: an address literal has to be recognised as one so it
 * is bracketed for IPv6 and never handed to the resolver as a hostname.
 */
public class IPTest {

    @Test
    public void recognisesIpv4() {
        assertThat(IP.matches("192.168.1.1")).isTrue();
        assertThat(IP.matches("0.0.0.0")).isTrue();
        assertThat(IP.matches("255.255.255.255")).isTrue();
    }

    @Test
    public void rejectsOutOfRangeIpv4() {
        assertThat(IP.matches("256.1.1.1")).isFalse();
        assertThat(IP.matches("192.168.1")).isFalse();
        assertThat(IP.matches("192.168.1.1.1")).isFalse();
    }

    @Test
    public void recognisesFullIpv6() {
        assertThat(IP.matches("2001:0db8:85a3:0000:0000:8a2e:0370:7334")).isTrue();
    }

    @Test
    public void recognisesCompressedIpv6() {
        assertThat(IP.matches("2001:db8::1")).isTrue();
        assertThat(IP.matches("::1")).isTrue();
    }

    @Test
    public void rejectsHostnames() {
        assertThat(IP.matches("xmpp.example.com")).isFalse();
        assertThat(IP.matches("localhost")).isFalse();
    }

    @Test
    public void rejectsNull() {
        assertThat(IP.matches(null)).isFalse();
    }

    @Test
    public void bracketsIpv6ForUseInAUri() {
        assertThat(IP.wrapIPv6("2001:db8::1")).isEqualTo("[2001:db8::1]");
    }

    @Test
    public void leavesHostnamesUnbracketed() {
        assertThat(IP.wrapIPv6("xmpp.example.com")).isEqualTo("xmpp.example.com");
    }

    @Test
    public void unwrapIsTheInverseOfWrap() {
        final String address = "2001:db8::1";
        assertThat(IP.unwrapIPv6(IP.wrapIPv6(address))).isEqualTo(address);
    }

    @Test
    public void unwrapLeavesBracketedNonAddressesAlone() {
        assertThat(IP.unwrapIPv6("[not-an-address]")).isEqualTo("[not-an-address]");
    }
}
