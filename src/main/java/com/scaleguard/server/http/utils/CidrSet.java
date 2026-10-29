package com.scaleguard.server.http.utils;

import io.netty.util.NetUtil;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** A set of IPv4/IPv6 CIDR blocks, e.g. "127.0.0.0/8,10.0.0.0/8,::1/128". Immutable and thread-safe. */
public final class CidrSet {

    private final List<byte[]> nets = new ArrayList<>();
    private final List<Integer> bits = new ArrayList<>();

    private CidrSet() {}

    public static CidrSet parse(String csv) {
        CidrSet set = new CidrSet();
        if (csv == null || csv.isBlank()) return set;
        for (String raw : Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList()) {
            String ip = raw;
            int prefix = -1;
            int slash = raw.indexOf('/');
            try {
                if (slash > 0) {
                    ip = raw.substring(0, slash);
                    prefix = Integer.parseInt(raw.substring(slash + 1));
                }
            } catch (NumberFormatException e) {
                continue;
            }
            byte[] addr = NetUtil.createByteArrayFromIpAddressString(ip);
            if (addr == null) continue;
            int max = addr.length * 8;
            set.nets.add(addr);
            set.bits.add(prefix < 0 || prefix > max ? max : prefix);
        }
        return set;
    }

    public boolean isEmpty() { return nets.isEmpty(); }

    public boolean contains(InetAddress a) {
        return a != null && contains(a.getAddress());
    }

    /** Accepts an IP literal; returns false for anything that isn't one. */
    public boolean contains(String ip) {
        if (ip == null) return false;
        String s = ip.trim();
        int pct = s.indexOf('%');
        if (pct > 0) s = s.substring(0, pct);
        if (s.startsWith("[") && s.endsWith("]")) s = s.substring(1, s.length() - 1);
        byte[] b = NetUtil.createByteArrayFromIpAddressString(s);
        return b != null && contains(b);
    }

    private boolean contains(byte[] x) {
        for (int i = 0; i < nets.size(); i++) {
            byte[] n = nets.get(i);
            if (n.length == x.length && matches(n, x, bits.get(i))) return true;
        }
        return false;
    }

    private static boolean matches(byte[] net, byte[] addr, int prefix) {
        int full = prefix / 8, rem = prefix % 8;
        for (int i = 0; i < full; i++) if (net[i] != addr[i]) return false;
        if (rem == 0) return true;
        int mask = (0xFF << (8 - rem)) & 0xFF;
        return (net[full] & mask) == (addr[full] & mask);
    }

    @Override
    public String toString() {
        List<String> s = new ArrayList<>();
        for (int i = 0; i < nets.size(); i++) s.add(NetUtil.bytesToIpAddress(nets.get(i)) + "/" + bits.get(i));
        return String.join(",", s).toLowerCase(Locale.ROOT);
    }
}
