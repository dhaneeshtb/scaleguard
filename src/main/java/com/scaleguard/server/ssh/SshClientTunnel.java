package com.scaleguard.server.ssh;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.client.session.forward.PortForwardingTracker;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class SshClientTunnel {

    private static int sshPort = 2222;

    public static class PortForard{
        private String username;
        private String password;
        private int remotePort;
        private String remoteHost;
        private int localPort;

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public int getRemotePort() {
            return remotePort;
        }

        public void setRemotePort(int remotePort) {
            this.remotePort = remotePort;
        }

        public String getRemoteHost() {
            return remoteHost;
        }

        public void setRemoteHost(String remoteHost) {
            this.remoteHost = remoteHost;
        }

        public int getLocalPort() {
            return localPort;
        }

        public void setLocalPort(int localPort) {
            this.localPort = localPort;
        }

        PortForard(String remoteHost,String username,String password,int remotePort,int localPort){

            this.remoteHost=remoteHost;
            this.remotePort=remotePort;
            this.localPort=localPort;

            this.username=username;
            this.password=password;

        }
    }
    public static void main(String[] args) {

        SshClientTunnel sshClientTunnel = new SshClientTunnel();
        PortForard pf = new PortForard("router.unkloud.io","user","password",8099,8081);
        Thread t= sshClientTunnel.forwardPort(pf);

        try {
            t.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

    }

    public Thread forwardPort(PortForard portForard){
        Thread t= new Thread(()->{
            try (SshClient client = SshClient.setUpDefaultClient()) {
                client.start();
                client.setForwardingFilter(new MyForwardingFilter()); // Set your filter implementation
                try (ClientSession session = client.connect(portForard.getUsername(), portForard.getRemoteHost(), sshPort)
                        .verify(10, TimeUnit.SECONDS).getSession()) {
                    session.addPasswordIdentity(portForard.getPassword());
                    session.auth().verify(5, TimeUnit.SECONDS);

                    // Enable remote port forwarding
                    SshdSocketAddress remoteAddress = new SshdSocketAddress("0.0.0.0", portForard.getRemotePort());
                    SshdSocketAddress localAddress = new SshdSocketAddress("localhost", portForard.getLocalPort());
                    PortForwardingTracker tracker = session.createRemotePortForwardingTracker(remoteAddress, localAddress);

                    tracker.getSession();
                    System.out.println("✅ Tunnel established: localhost:" + portForard.getLocalPort() + " -> remote:" + portForard.getRemotePort());

                    // Keep the session open
                    Thread.sleep(Long.MAX_VALUE);
                    // Keep the session open
                    Thread.sleep(Long.MAX_VALUE);
                }
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
                System.out.println("✅ Tunnel removed: localhost:" + portForard.getLocalPort() + " -> remote:" + portForard.getRemotePort());

            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }
}
