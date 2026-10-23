package com.glaciersecurity.glaciermessenger.utils;

import com.google.common.io.ByteStreams;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;

public class SocksSocketFactory {

    public static void createSocksConnection(final Socket socket, final String destination, final int port) throws IOException {
        final InputStream proxyIs = socket.getInputStream();
        final OutputStream proxyOs = socket.getOutputStream();
        proxyOs.write(new byte[]{0x05, 0x01, 0x00});
        proxyOs.flush();
        final byte[] handshake = new byte[2];
        ByteStreams.readFully(proxyIs, handshake);
        if (handshake[0] != 0x05 || handshake[1] != 0x00) {
            throw new SocksConnectionException("Socks 5 handshake failed");
        }
        final byte[] dest = destination.getBytes();
        final ByteBuffer request = ByteBuffer.allocate(7 + dest.length);
        request.put(new byte[]{0x05, 0x01, 0x00, 0x03});
        request.put((byte) dest.length);
        request.put(dest);
        request.putShort((short) port);
        proxyOs.write(request.array());
        proxyOs.flush();
        final byte[] response = new byte[4];
        ByteStreams.readFully(proxyIs, response);
        final byte ver = response[0];
        if (ver != 0x05) {
            throw new IOException(String.format("Unknown Socks version %02X ", ver));
        }
        final byte status = response[1];
        final byte bndAddrType = response[3];
        final byte[] bndDestination = readDestination(bndAddrType, proxyIs);
        final byte[] bndPort = new byte[2];
        if (bndAddrType == 0x03) {
            final String receivedDestination = new String(bndDestination);
            if (!receivedDestination.equalsIgnoreCase(destination)) {
                throw new IOException(String.format("Destination mismatch. Received %s Expected %s", receivedDestination, destination));
            }
        }
        ByteStreams.readFully(proxyIs, bndPort);
        if (status != 0x00) {
            if (status == 0x04) {
                throw new HostNotFoundException("Host unreachable");
            }
            if (status == 0x05) {
                throw new HostNotFoundException("Connection refused");
            }
            throw new IOException(String.format("Unknown status code %02X ", status));
        }
    }

    private static byte[] readDestination(final byte type, final InputStream inputStream) throws IOException {
        final byte[] bndDestination;
        if (type == 0x01) {
            bndDestination = new byte[4];
        } else if (type == 0x03) {
            final int length = inputStream.read();
            bndDestination = new byte[length];
        } else if (type == 0x04) {
            bndDestination = new byte[16];
        } else {
            throw new IOException(String.format("Unknown Socks address type %02X ", type));
        }
        ByteStreams.readFully(inputStream, bndDestination);
        return bndDestination;
    }

    public static boolean contains(byte needle, byte[] haystack) {
        for (byte hay : haystack) {
            if (hay == needle) {
                return true;
            }
        }
        return false;
    }

    private static class SocksConnectionException extends IOException {
        SocksConnectionException(String message) {
            super(message);
        }
    }

    public static class HostNotFoundException extends SocksConnectionException {
        HostNotFoundException(String message) {
            super(message);
        }
    }
}
