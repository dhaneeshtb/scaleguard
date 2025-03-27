package com.scaleguard.server.ssh;
import com.scaleguard.server.ssh.tunnel.TunnelBook;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.command.CommandFactory;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.common.forward.PortForwardingEventListener;
import org.apache.sshd.server.shell.ProcessShellFactory;
import java.io.IOException;
import java.nio.file.Paths;


public class SshServerWithTunnelListener {
    private static int sshPort = 2222;
    public static void main(String[] args) throws IOException {
        startServer();
    }
    public static void startServer() throws IOException {
        new Thread(() -> {
            SshServer sshd = SshServer.setUpDefaultServer();
            sshd.setPort(sshPort);
            sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(Paths.get("hostkey.ser")));
            sshd.setPasswordAuthenticator((username, password, session) ->{
//                "user".equals(username) && "password".equals(password)
               TunnelBook.TunnelRecord tr =TunnelBook.getRecordByUserPassword(username,password);
               if(tr!=null){
                   TunnelBook.putSession(username,password,session);
               }
               return tr!=null;
            });
            sshd.setShellFactory(new ProcessShellFactory("/bin/sh", new String[]{"ls", "-i"}));
            sshd.setCommandFactory(new CommandFactory() {
                @Override
                public Command createCommand(org.apache.sshd.server.channel.ChannelSession channelSession, String s) throws IOException {
                    return null;
                }
            });
            sshd.setForwardingFilter(new MyForwardingFilter()); // Set your filter implementation
            // Listen for tunnel creation events
            sshd.addPortForwardingEventListener(new PortForwardingEventListener() {
                @Override
                public void establishedExplicitTunnel(Session session, SshdSocketAddress local,
                                                      SshdSocketAddress remote, boolean localForwarding,
                                                      SshdSocketAddress boundAddress, Throwable reason) {
                    if (reason == null) {
                        System.out.println("✅ Tunnel successfully created from " + local + " to " + remote);
                    } else {
                        System.err.println("❌ Tunnel creation failed: " + reason.getMessage());
                    }
                }
            });

            try {
                sshd.start();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            System.out.println("🚀 SSH Server started on port 2222");

            // Keep the server running
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }

}
