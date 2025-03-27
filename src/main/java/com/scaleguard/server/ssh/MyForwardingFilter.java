package com.scaleguard.server.ssh;

import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.apache.sshd.server.forward.ForwardingFilter;

class MyForwardingFilter implements ForwardingFilter {

    @Override
    public boolean canForwardAgent(Session session, String s) {
        return true;
    }

    @Override
    public boolean canListen(SshdSocketAddress sshdSocketAddress, Session session) {
        return true;
    }

    @Override
    public boolean canConnect(Type type, SshdSocketAddress sshdSocketAddress, Session session) {
        return true;
    }

    @Override
    public boolean canForwardX11(Session session, String s) {
        return true;
    }
}