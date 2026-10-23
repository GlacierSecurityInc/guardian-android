package com.glaciersecurity.glaciermessenger.wireguard;

import com.wireguard.config.BadConfigException;
import com.wireguard.config.Config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ConfigParser {

    // WireGuard transmits nothing on an idle tunnel unless a keepalive is configured, so the
    // server keeps talking to whichever endpoint it last saw us on. That is harmless on wifi,
    // but a phone that moves onto cellular lands behind carrier NAT with a mapping that is only
    // held open while it is used, and the server cannot learn the new endpoint until we
    // transmit. Until then everything inbound -- incoming messages, incoming call signalling --
    // is dropped, and the chat connection has nothing of its own to send for up to five
    // minutes. 25s is the interval wg-quick itself recommends for a NATed peer and bounds that
    // window to roughly the same.
    private static final int DEFAULT_PERSISTENT_KEEPALIVE_SECONDS = 25;

    private static final String PEER_SECTION = "[peer]";
    private static final String PERSISTENT_KEEPALIVE_KEY = "persistentkeepalive";

    private ConfigParser() {
    }

    public static Config parseWithDefaults(String source) throws IOException, BadConfigException {
        BufferedReader reader = new BufferedReader(new StringReader(applyDefaults(source)));
        return Config.parse(reader);
    }

    static String applyDefaults(String source) throws IOException {
        return addMissingKeepalives(normalizeAddresses(source));
    }

    private static String normalizeAddresses(String source) throws IOException {
        BufferedReader reader = new BufferedReader(new StringReader(source));
        StringBuilder result = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            String trimmed = line.trim();
            if (trimmed.toLowerCase(Locale.US).startsWith("address")) {
                String[] parts = line.split("=", 2);
                if (parts.length == 2) {
                    String[] addresses = parts[1].split(",");
                    for (int i = 0; i < addresses.length; i++) {
                        String addr = addresses[i].trim();
                        if (!addr.contains("/")) {
                            if (addr.contains(":")) {
                                addr += "/128";
                            } else if (!addr.isEmpty()) {
                                addr += "/32";
                            }
                        }
                        addresses[i] = addr;
                    }
                    line = parts[0].trim() + " = " + String.join(",", addresses);
                }
            }
            result.append(line).append('\n');
        }
        return result.toString();
    }

    /**
     * Give every {@code [Peer]} section that does not already specify one a
     * {@code PersistentKeepalive}, so a tunnel that survives a wifi-to-cellular handoff also
     * tells the server where to reach it afterwards. A peer that sets its own value is left
     * alone.
     */
    private static String addMissingKeepalives(String source) throws IOException {
        final BufferedReader reader = new BufferedReader(new StringReader(source));
        final List<String> result = new ArrayList<>();
        boolean inPeer = false;
        boolean peerHasKeepalive = false;
        // Index in result of the last non-blank line of the peer section being read, so the
        // default lands with the rest of the section rather than after its trailing blank line.
        int lastPeerContentIndex = -1;
        String line;
        while ((line = reader.readLine()) != null) {
            final String trimmed = line.trim();
            if (trimmed.startsWith("[")) {
                if (inPeer && !peerHasKeepalive && lastPeerContentIndex >= 0) {
                    result.add(lastPeerContentIndex + 1, keepaliveLine());
                }
                inPeer = PEER_SECTION.equals(trimmed.toLowerCase(Locale.US));
                peerHasKeepalive = false;
                lastPeerContentIndex = -1;
            } else if (inPeer) {
                if (isKeepalive(trimmed)) {
                    peerHasKeepalive = true;
                }
                if (!trimmed.isEmpty()) {
                    lastPeerContentIndex = result.size();
                }
            }
            result.add(line);
        }
        if (inPeer && !peerHasKeepalive && lastPeerContentIndex >= 0) {
            result.add(lastPeerContentIndex + 1, keepaliveLine());
        }
        return String.join("\n", result) + "\n";
    }

    private static boolean isKeepalive(String trimmedLine) {
        final int equals = trimmedLine.indexOf('=');
        if (equals < 0) {
            return false;
        }
        return PERSISTENT_KEEPALIVE_KEY.equals(
                trimmedLine.substring(0, equals).trim().toLowerCase(Locale.US));
    }

    private static String keepaliveLine() {
        return "PersistentKeepalive = " + DEFAULT_PERSISTENT_KEEPALIVE_SECONDS;
    }
}
