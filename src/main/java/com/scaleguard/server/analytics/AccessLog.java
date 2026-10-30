package com.scaleguard.server.analytics;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

import java.util.ArrayDeque;

/**
 * Pairs requests with responses on a backend connection. HTTP/1.1 answers requests in order, so each
 * proxied request's record is queued on the outbound channel and the next response completes the oldest one.
 * All calls happen on the connection's event loop (the outbound channel shares the inbound channel's loop).
 */
public final class AccessLog {

    private AccessLog() {}

    private static final AttributeKey<ArrayDeque<AccessRecord>> PENDING = AttributeKey.valueOf("sg.access.pending");

    public static void expectResponse(Channel outbound, AccessRecord r) {
        if (r == null || outbound == null) return;
        ArrayDeque<AccessRecord> q = outbound.attr(PENDING).get();
        if (q == null) {
            q = new ArrayDeque<>(2);
            outbound.attr(PENDING).set(q);
        }
        q.add(r);
    }

    /** The record the next backend response belongs to, or null. */
    public static AccessRecord nextResponse(Channel outbound) {
        ArrayDeque<AccessRecord> q = outbound.attr(PENDING).get();
        return q == null ? null : q.poll();
    }

    /** Takes back a record that was never sent (e.g. the connect failed and the request is retried elsewhere). */
    public static void withdraw(Channel outbound, AccessRecord r) {
        ArrayDeque<AccessRecord> q = outbound == null ? null : outbound.attr(PENDING).get();
        if (q != null) q.remove(r);
    }

    /** Backend connection closed with requests still unanswered. */
    public static void failPending(Channel outbound, int status, String outcome) {
        ArrayDeque<AccessRecord> q = outbound.attr(PENDING).get();
        if (q == null) return;
        AccessRecord r;
        while ((r = q.poll()) != null) r.finish(status, outcome);
    }
}
