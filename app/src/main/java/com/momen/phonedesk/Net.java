package com.momen.phonedesk;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class Net {
    private Net() { }

    /** {ip, label} for every active IPv4 interface. */
    static List<String[]> addresses() {
        List<String[]> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address)) continue;
                    String n = ni.getName();
                    String label = n.startsWith("wlan") ? "Wi-Fi"
                            : (n.startsWith("tun") || n.contains("tailscale")) ? "VPN / Tailscale"
                            : n.startsWith("rmnet") || n.startsWith("ccmni") ? "mobile data – usually not reachable"
                            : n.startsWith("ap") || n.startsWith("swlan") ? "hotspot"
                            : n;
                    out.add(new String[]{a.getHostAddress(), label});
                }
            }
        } catch (Exception ignored) { }
        return out;
    }
}
