package com.scaleguard.server.http.h3;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.quic.QuicTokenHandler;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.security.SecureRandom;

/**
 * QUIC address validation (Retry) with stateless HMAC tokens: {@code timestamp | hmac(timestamp, client address, dcid) | dcid}.
 * A client must echo the token from its own address within the validity window before the server spends any state on
 * it — protection against spoofed-source floods, at the cost of one extra round trip on new connections.
 * Enabled with {@code -Dscaleguard.http3.retry=true}.
 */
final class RetryTokenHandler implements QuicTokenHandler {

    private static final int MAC_LEN = 16;
    private static final long VALID_MS = 30_000;
    private final SecretKeySpec key;

    RetryTokenHandler() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        key = new SecretKeySpec(k, "HmacSHA256");
    }

    @Override
    public boolean writeToken(ByteBuf out, ByteBuf dcid, InetSocketAddress address) {
        long now = System.currentTimeMillis();
        out.writeLong(now);
        out.writeBytes(mac(now, address, dcid, dcid.readerIndex(), dcid.readableBytes()));
        out.writeBytes(dcid, dcid.readerIndex(), dcid.readableBytes());
        return true;
    }

    @Override
    public int validateToken(ByteBuf token, InetSocketAddress address) {
        int start = token.readerIndex();
        if (token.readableBytes() < 8 + MAC_LEN) return -1;
        long ts = token.getLong(start);
        long age = System.currentTimeMillis() - ts;
        if (age < 0 || age > VALID_MS) return -1;
        int dcidOffset = 8 + MAC_LEN;
        byte[] expected = mac(ts, address, token, start + dcidOffset, token.readableBytes() - dcidOffset);
        byte[] got = new byte[MAC_LEN];
        token.getBytes(start + 8, got);
        return java.security.MessageDigest.isEqual(expected, got) ? dcidOffset : -1;
    }

    @Override
    public int maxTokenLength() {
        return 8 + MAC_LEN + 20;   // QUIC connection ids are at most 20 bytes
    }

    private byte[] mac(long ts, InetSocketAddress address, ByteBuf buf, int off, int len) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(key);
            for (int i = 7; i >= 0; i--) m.update((byte) (ts >>> (i * 8)));
            m.update(address.getAddress().getAddress());
            m.update((byte) (address.getPort() >>> 8));
            m.update((byte) address.getPort());
            byte[] d = new byte[len];
            buf.getBytes(off, d);
            m.update(d);
            byte[] full = m.doFinal();
            byte[] out = new byte[MAC_LEN];
            System.arraycopy(full, 0, out, 0, MAC_LEN);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
