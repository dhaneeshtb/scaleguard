package com.scaleguard.server.cluster;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/**
 * Who this ScaleGuard instance is inside a cluster.
 *
 * <ul>
 *   <li>{@code scaleguard.nodeName}: stable node name (default {@code <hostname>:<http port>}, so two
 *       instances on one machine still differ).</li>
 *   <li>{@code scaleguard.cluster.advertise}: base URL other nodes use to reach this node's admin API
 *       (default {@code http://<first non-loopback IPv4>:<http port>}).</li>
 * </ul>
 */
public final class NodeIdentity {

    private NodeIdentity() {}

    private static final String NAME = computeName();
    private static final String ADDRESS = computeAddress();

    public static String name() {
        return NAME;
    }

    public static String adminUrl() {
        return ADDRESS;
    }

    private static String httpPort() {
        return System.getProperty("port", "80");
    }

    private static String computeName() {
        String n = System.getProperty("scaleguard.nodeName");
        if (n != null && !n.isBlank()) return n.trim();
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "node";
        }
        return host + ":" + httpPort();
    }

    private static String computeAddress() {
        String a = System.getProperty("scaleguard.cluster.advertise");
        if (a != null && !a.isBlank()) {
            a = a.trim();
            if (!a.startsWith("http://") && !a.startsWith("https://")) a = "http://" + a;
            return a.endsWith("/") ? a.substring(0, a.length() - 1) : a;
        }
        return "http://" + firstAddress() + ":" + httpPort();
    }

    private static String firstAddress() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (InetAddress ia : Collections.list(ni.getInetAddresses())) {
                    if (ia instanceof Inet4Address && !ia.isLoopbackAddress() && !ia.isLinkLocalAddress()) {
                        return ia.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return "127.0.0.1";
    }
}
