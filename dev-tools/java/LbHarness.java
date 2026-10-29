import com.scaleguard.server.http.reverse.AppServer;
import com.scaleguard.server.http.router.*;
import java.util.*;

/**
 * Starts the real ScaleGuard AppServer with in-memory routes: localhost:8080 (s1) and, for TLS, localhost:8443 (s2)
 * -> target t1 -> host group h1 (backends).
 * Options: -Dharness.backendScheme=https (TLS to backends), -Dharness.tlsCert=cert.pem -Dharness.tlsKey=key.pem
 * (PKCS#8, served for "localhost" on the TLS port without ACME).
 */
public class LbHarness {
    static final String SCHEME = System.getProperty("harness.backendScheme", "http");

    /** Registers a PEM certificate + PKCS#8 key for "localhost" directly in CertificateStore (no ACME, no DB). */
    static void injectCertificate(String certPem, String keyPem) throws Exception {
        var cf = java.security.cert.CertificateFactory.getInstance("X.509");
        java.security.cert.X509Certificate[] chain;
        try (var in = new java.io.FileInputStream(certPem)) {
            chain = cf.generateCertificates(in).toArray(new java.security.cert.X509Certificate[0]);
        }
        String k = new String(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(keyPem)))
                .replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        var spec = new java.security.spec.PKCS8EncodedKeySpec(Base64.getDecoder().decode(k));
        java.security.PrivateKey key;
        try { key = java.security.KeyFactory.getInstance("EC").generatePrivate(spec); }
        catch (Exception e) { key = java.security.KeyFactory.getInstance("RSA").generatePrivate(spec); }
        var ctor = io.netty.handler.ssl.CertificateStore.CertificateInfo.class
                .getDeclaredConstructor(java.security.cert.X509Certificate[].class, java.security.PrivateKey.class);
        ctor.setAccessible(true);
        var info = ctor.newInstance(chain, key);
        info.setId("localhost");
        var f = io.netty.handler.ssl.CertificateStore.class.getDeclaredField("certificateMap");
        f.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) f.get(null);
        m.put("localhost", info);
    }

    public static void main(String[] a) throws Exception {
        if (System.getProperty("harness.tlsCert") != null) {
            injectCertificate(System.getProperty("harness.tlsCert"), System.getProperty("harness.tlsKey"));
        }
        String[] backends = a.length > 0 ? a[0].split(",") : new String[]{"127.0.0.1:9000"};
        RouteTable.setLoader(new SystemLoader() {
            public List<HostGroup> loadHostGroups() {
                List<HostGroup> l = new ArrayList<>();
                for (String b : backends) {
                    HostGroup h = new HostGroup();
                    h.setId(UUID.randomUUID().toString()); h.setScheme(SCHEME);
                    h.setHost(b.split(":")[0]); h.setPort(b.split(":")[1]);
                    h.setGroupId("h1"); h.setType("active"); h.setActive(true); h.setPriority(1); h.setLoadFactor(1f);
                    l.add(h);
                }
                return l;
            }
            public List<TargetSystem> loadTargets(List<HostGroup> hgs) {
                TargetSystem t = new TargetSystem();
                t.setId("t1"); t.setName("bench"); t.setGroupId("t1"); t.setScheme(SCHEME); t.setBasePath("/");
                t.setHostGroupId("h1"); t.setHostGroups(new ArrayList<>(hgs));
                return List.of(t);
            }
            public List<SourceSystem> loadSources() {
                SourceSystem s = new SourceSystem();
                s.setId("s1"); s.setName("bench"); s.setScheme("http"); s.setHost("localhost"); s.setPort("8080");
                s.setBasePath("/"); s.setTarget("t1");
                SourceSystem s2 = new SourceSystem();
                s2.setId("s2"); s2.setName("bench-tls"); s2.setScheme("https"); s2.setHost("localhost");
                s2.setPort(System.getProperty("sport", "8443")); s2.setBasePath("/"); s2.setTarget("t1");
                return new ArrayList<>(List.of(s, s2));
            }
        });
        AppServer server = new AppServer();
        server.start();
        System.out.println("LB READY");
        server.listen();
    }
}
