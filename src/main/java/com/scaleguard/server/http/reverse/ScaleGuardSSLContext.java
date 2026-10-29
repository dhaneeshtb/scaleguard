package com.scaleguard.server.http.reverse;


import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
public class ScaleGuardSSLContext {
    private static final Logger logger = LoggerFactory.getLogger(ScaleGuardSSLContext.class);
    // The key manager looks certificates up per handshake (SNI), so one context serves every connection;
    // building a new SSLContext per connection was pure overhead.
    private static volatile SSLContext cached;

    public static SSLContext get(){
        SSLContext c = cached;
        if (c != null) return c;
        synchronized (ScaleGuardSSLContext.class) {
            if (cached == null) {
                try {
                    cached = create();
                } catch (Exception e) {
                    logger.error("Failed to create SSL context", e);
                    return null;
                }
            }
            return cached;
        }
    }
    public static SSLContext create() throws Exception {
        final SSLContext context;
//        KeyStore store = KeyStore.getInstance("JKS");
//        try (InputStream is = Files.newInputStream(Paths.get("keys.jks"))) {
//            store.load(is, "password".toCharArray());
//        }
//
//        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
//        factory.init(store, "password".toCharArray());
//
//        // Javadoc of SSLContext.init() states the first KeyManager implementing X509ExtendedKeyManager in the array is
//        // used. We duplicate this behaviour when picking the KeyManager to wrap around.
//        X509ExtendedKeyManager x509KeyManager = null;
//        for (KeyManager keyManager : factory.getKeyManagers()) {
//            if (keyManager instanceof X509ExtendedKeyManager) {
//                x509KeyManager = (X509ExtendedKeyManager) keyManager;
//            }
//        }
//
//        if (x509KeyManager == null)
//            throw new Exception("KeyManagerFactory did not create an X509ExtendedKeyManager");

        ScaleGuardKeyManager sniKeyManager = new ScaleGuardKeyManager(null);

        context = SSLContext.getInstance("TLS");
        context.init(new KeyManager[]{
                sniKeyManager
        }, null, null);

        return context;
    }
}
