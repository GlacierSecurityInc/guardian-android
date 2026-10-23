package com.glaciersecurity.glaciermessenger.xmpp.jingle;

import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.content.ContextCompat;

import com.glaciersecurity.glaciermessenger.GlacierGuardian;
import com.google.common.collect.ImmutableList;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Enumeration;
import java.util.List;

public class DirectConnectionUtils {

    // Android 17 (API 37) requires this permission for raw socket traffic to local network
    // addresses (direct/Jingle candidates open server sockets and connect peer-to-peer). The
    // permission does not exist on older platforms, so it is only checked from API 37 onward.
    public static final int ACCESS_LOCAL_NETWORK_SDK_INT = 37;
    public static final String ACCESS_LOCAL_NETWORK_PERMISSION =
            "android.permission.ACCESS_LOCAL_NETWORK";

    public static boolean hasLocalNetworkAccess() {
        if (Build.VERSION.SDK_INT < ACCESS_LOCAL_NETWORK_SDK_INT) {
            return true;
        }
        final var context = GlacierGuardian.getContext();
        if (context == null) {
            return false;
        }
        return ContextCompat.checkSelfPermission(context, ACCESS_LOCAL_NETWORK_PERMISSION)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static List<InetAddress> getLocalAddresses() {
        final ImmutableList.Builder<InetAddress> inetAddresses = new ImmutableList.Builder<>();
        final Enumeration<NetworkInterface> interfaces;
        try {
            interfaces = NetworkInterface.getNetworkInterfaces();
        } catch (final SocketException e) {
            return inetAddresses.build();
        }
        while (interfaces.hasMoreElements()) {
            NetworkInterface networkInterface = interfaces.nextElement();
            final Enumeration<InetAddress> inetAddressEnumeration = networkInterface.getInetAddresses();
            while (inetAddressEnumeration.hasMoreElements()) {
                final InetAddress inetAddress = inetAddressEnumeration.nextElement();
                if (inetAddress.isLoopbackAddress() || inetAddress.isLinkLocalAddress()) {
                    continue;
                }
                if (inetAddress instanceof Inet6Address) {
                    //let's get rid of scope
                    try {
                        inetAddresses.add(Inet6Address.getByAddress(inetAddress.getAddress()));
                    } catch (UnknownHostException e) {
                        //ignored
                    }
                } else {
                    inetAddresses.add(inetAddress);
                }
            }
        }
        return inetAddresses.build();
    }
}
