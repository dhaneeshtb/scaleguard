package com.scaleguard.server.dns;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.dns.DefaultDnsRawRecord;
import io.netty.handler.codec.dns.DnsRecordType;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal DNS wire-format helpers.
 *
 * Netty's record decoder keeps rdata as raw bytes, which still contain compression pointers
 * into the original upstream packet for types such as MX, SOA and SRV. Re-sending those bytes in
 * a new packet produces corrupt answers. This parser decodes upstream responses and expands every
 * compressed name inside rdata, so records can be cached and re-encoded safely.
 */
final class DnsWire {

    private DnsWire() {}

    static final int TYPE_A = 1, TYPE_NS = 2, TYPE_CNAME = 5, TYPE_SOA = 6, TYPE_PTR = 12, TYPE_MX = 15,
            TYPE_TXT = 16, TYPE_RP = 17, TYPE_AFSDB = 18, TYPE_AAAA = 28, TYPE_SRV = 33, TYPE_NAPTR = 35,
            TYPE_DNAME = 39, TYPE_OPT = 41, TYPE_ANY = 255;
    static final int CLASS_IN = 1, CLASS_ANY = 255;

    static final int RCODE_NOERROR = 0, RCODE_FORMERR = 1, RCODE_SERVFAIL = 2, RCODE_NXDOMAIN = 3,
            RCODE_NOTIMP = 4, RCODE_REFUSED = 5;

    /** A resource record with uncompressed rdata. Immutable and safe to cache. */
    record Rr(String name, int type, int dnsClass, long ttl, byte[] rdata) {
        Rr withTtl(long newTtl) { return new Rr(name, type, dnsClass, newTtl, rdata); }
        Rr withName(String newName) { return new Rr(newName, type, dnsClass, ttl, rdata); }

        DefaultDnsRawRecord toNetty() {
            return new DefaultDnsRawRecord(name, DnsRecordType.valueOf(type), dnsClass, Math.max(0, ttl), Unpooled.wrappedBuffer(rdata));
        }
    }

    record Question(String name, int type, int dnsClass) {}

    static final class Message {
        int id;
        int flags;
        final List<Question> questions = new ArrayList<>(1);
        final List<Rr> answers = new ArrayList<>();
        final List<Rr> authority = new ArrayList<>();
        final List<Rr> additional = new ArrayList<>();
        int ednsPayload = -1;

        int rcode() { return flags & 0xF; }
        boolean truncated() { return (flags & 0x0200) != 0; }
        boolean isResponse() { return (flags & 0x8000) != 0; }
    }

    static final class FormatException extends Exception {
        FormatException(String m) { super(m); }
    }

    // ---------------------------------------------------------------- names

    static String normalize(String name) {
        if (name == null) return null;
        String n = name.trim().toLowerCase();
        if (n.isEmpty()) return ".";
        return n.endsWith(".") ? n : n + ".";
    }

    static byte[] encodeName(String name) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(name.length() + 2);
        writeName(out, name);
        return out.toByteArray();
    }

    static void writeName(ByteArrayOutputStream out, String name) {
        if (name != null && !name.isEmpty() && !".".equals(name)) {
            for (String label : name.split("\\.")) {
                if (label.isEmpty()) continue;
                byte[] b = label.getBytes(StandardCharsets.ISO_8859_1);
                int len = Math.min(63, b.length);
                out.write(len);
                out.write(b, 0, len);
            }
        }
        out.write(0);
    }

    /** Reads a possibly-compressed name starting at {@code off}; returns the offset just past it in the stream. */
    static int readName(byte[] b, int off, StringBuilder sb) throws FormatException {
        int pos = off, end = -1, jumps = 0, total = 0;
        while (true) {
            if (pos >= b.length) throw new FormatException("name past end");
            int len = b[pos] & 0xFF;
            if ((len & 0xC0) == 0xC0) {
                if (pos + 1 >= b.length) throw new FormatException("truncated pointer");
                int ptr = ((len & 0x3F) << 8) | (b[pos + 1] & 0xFF);
                if (end < 0) end = pos + 2;
                if (++jumps > 64 || ptr >= b.length) throw new FormatException("bad pointer");
                pos = ptr;
                continue;
            }
            if ((len & 0xC0) != 0) throw new FormatException("unsupported label type");
            pos++;
            if (len == 0) break;
            if (pos + len > b.length) throw new FormatException("label past end");
            total += len + 1;
            if (total > 255) throw new FormatException("name too long");
            for (int i = 0; i < len; i++) sb.append((char) (b[pos + i] & 0xFF));
            sb.append('.');
            pos += len;
        }
        if (sb.length() == 0) sb.append('.');
        return end < 0 ? pos : end;
    }

    // ---------------------------------------------------------------- parsing

    static Message parse(byte[] b) throws FormatException {
        if (b.length < 12) throw new FormatException("short header");
        Message m = new Message();
        m.id = u16(b, 0);
        m.flags = u16(b, 2);
        int qd = u16(b, 4), an = u16(b, 6), ns = u16(b, 8), ar = u16(b, 10);
        int pos = 12;
        for (int i = 0; i < qd; i++) {
            StringBuilder sb = new StringBuilder();
            pos = readName(b, pos, sb);
            need(b, pos, 4);
            m.questions.add(new Question(sb.toString(), u16(b, pos), u16(b, pos + 2)));
            pos += 4;
        }
        pos = readSection(b, pos, an, m.answers, m);
        pos = readSection(b, pos, ns, m.authority, m);
        readSection(b, pos, ar, m.additional, m);
        return m;
    }

    private static int readSection(byte[] b, int pos, int count, List<Rr> out, Message m) throws FormatException {
        for (int i = 0; i < count; i++) {
            StringBuilder sb = new StringBuilder();
            pos = readName(b, pos, sb);
            need(b, pos, 10);
            int type = u16(b, pos), cls = u16(b, pos + 2);
            long ttl = ((long) u16(b, pos + 4) << 16) | u16(b, pos + 6);
            int rdlen = u16(b, pos + 8);
            pos += 10;
            need(b, pos, rdlen);
            if (type == TYPE_OPT) {
                m.ednsPayload = cls;
            } else {
                if (ttl > Integer.MAX_VALUE) ttl = 0; // RFC 2181: treat as zero
                out.add(new Rr(sb.toString(), type, cls, ttl, expandRdata(b, pos, rdlen, type)));
            }
            pos += rdlen;
        }
        return pos;
    }

    /** Returns rdata with any embedded compressed names expanded. Falls back to a raw copy if malformed. */
    private static byte[] expandRdata(byte[] b, int off, int len, int type) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(len + 16);
            int p = off, end = off + len;
            switch (type) {
                case TYPE_NS, TYPE_CNAME, TYPE_PTR, TYPE_DNAME -> p = copyName(b, p, out);
                case TYPE_MX, TYPE_AFSDB -> { p = copy(b, p, 2, out); p = copyName(b, p, out); }
                case TYPE_SOA -> { p = copyName(b, p, out); p = copyName(b, p, out); p = copy(b, p, 20, out); }
                case TYPE_RP -> { p = copyName(b, p, out); p = copyName(b, p, out); }
                case TYPE_SRV -> { p = copy(b, p, 6, out); p = copyName(b, p, out); }
                case TYPE_NAPTR -> {
                    p = copy(b, p, 4, out);
                    for (int i = 0; i < 3; i++) { need(b, p, 1); p = copy(b, p, 1 + (b[p] & 0xFF), out); }
                    p = copyName(b, p, out);
                }
                default -> { return java.util.Arrays.copyOfRange(b, off, end); }
            }
            if (p != end) return java.util.Arrays.copyOfRange(b, off, end);
            return out.toByteArray();
        } catch (FormatException e) {
            return java.util.Arrays.copyOfRange(b, off, Math.min(b.length, off + len));
        }
    }

    private static int copyName(byte[] b, int p, ByteArrayOutputStream out) throws FormatException {
        StringBuilder sb = new StringBuilder();
        int next = readName(b, p, sb);
        writeName(out, sb.toString());
        return next;
    }

    private static int copy(byte[] b, int p, int n, ByteArrayOutputStream out) throws FormatException {
        need(b, p, n);
        out.write(b, p, n);
        return p + n;
    }

    private static void need(byte[] b, int pos, int n) throws FormatException {
        if (pos < 0 || pos + n > b.length) throw new FormatException("record past end");
    }

    static int u16(byte[] b, int o) { return ((b[o] & 0xFF) << 8) | (b[o + 1] & 0xFF); }

    // ---------------------------------------------------------------- building

    static byte[] buildQuery(int id, String name, int type, int cls, boolean recursionDesired, int ednsPayload) {
        byte[] qn = encodeName(name);
        ByteBuffer bb = ByteBuffer.allocate(12 + qn.length + 4 + (ednsPayload > 0 ? 11 : 0));
        bb.putShort((short) id);
        bb.putShort((short) (recursionDesired ? 0x0100 : 0));
        bb.putShort((short) 1).putShort((short) 0).putShort((short) 0).putShort((short) (ednsPayload > 0 ? 1 : 0));
        bb.put(qn).putShort((short) type).putShort((short) cls);
        if (ednsPayload > 0) {
            bb.put((byte) 0).putShort((short) TYPE_OPT).putShort((short) ednsPayload).putInt(0).putShort((short) 0);
        }
        return bb.array();
    }

    static byte[] txtRdata(String text) {
        byte[] all = text.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(all.length + all.length / 255 + 1);
        if (all.length == 0) { out.write(0); return out.toByteArray(); }
        for (int i = 0; i < all.length; i += 255) {
            int n = Math.min(255, all.length - i);
            out.write(n);
            out.write(all, i, n);
        }
        return out.toByteArray();
    }

    static byte[] soaRdata(String mname, String rname, long serial, int refresh, int retry, int expire, int minimum) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64);
        writeName(out, mname);
        writeName(out, rname);
        ByteBuffer bb = ByteBuffer.allocate(20).putInt((int) serial).putInt(refresh).putInt(retry).putInt(expire).putInt(minimum);
        out.write(bb.array(), 0, 20);
        return out.toByteArray();
    }

    static byte[] mxRdata(int preference, String exchange) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(32);
        out.write((preference >> 8) & 0xFF);
        out.write(preference & 0xFF);
        writeName(out, exchange);
        return out.toByteArray();
    }

    /** SOA MINIMUM field (last 4 bytes of uncompressed SOA rdata), or -1. */
    static long soaMinimum(byte[] rdata) {
        if (rdata.length < 20) return -1;
        int o = rdata.length - 4;
        return ((long) (rdata[o] & 0xFF) << 24) | ((rdata[o + 1] & 0xFF) << 16) | ((rdata[o + 2] & 0xFF) << 8) | (rdata[o + 3] & 0xFF);
    }

    /** Target name of a CNAME rdata (uncompressed). */
    static String nameFromRdata(byte[] rdata) {
        try {
            StringBuilder sb = new StringBuilder();
            readName(rdata, 0, sb);
            return sb.toString();
        } catch (FormatException e) {
            return null;
        }
    }
}
