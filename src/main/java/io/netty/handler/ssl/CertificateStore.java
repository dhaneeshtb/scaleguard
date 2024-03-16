package io.netty.handler.ssl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.scaleguard.exceptions.GenericServerProcessingException;
import org.shredzone.acme4j.AcmeUtils;

import java.io.File;
import java.io.IOException;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class CertificateStore {
    private static final ObjectMapper mapper = new ObjectMapper();

    public static class CertificateInfo {
        X509Certificate[] keyCertChain;
        public X509Certificate[] getKeyCertChain() {
            return keyCertChain;
        }
        public void setKeyCertChain(X509Certificate[] keyCertChain) {
            this.keyCertChain = keyCertChain;
        }
        public PrivateKey getKey() {
            return key;
        }

        public void setKey(PrivateKey key) {
            this.key = key;
        }
        PrivateKey key;
        CertificateInfo(X509Certificate[] keyCertChain, PrivateKey key) {
            this.keyCertChain = keyCertChain;
            this.key = key;
        }
    }
    private static Map<String, CertificateInfo> certificateMap = new HashMap<>();

    static{
        java.security.Security.addProvider(
                new org.bouncycastle.jce.provider.BouncyCastleProvider()
        );
        loadAllCerts();
    }
    public static CertificateInfo get(String alias) {
        if (certificateMap.containsKey(alias)) {
            return certificateMap.get(alias);
        }
        //load(alias);
        return certificateMap.get(alias);
    }

    private static void loadAllCerts(){
        try {
            Set<String> ceritifcateIds= AcmeUtils.listCertificateIds();
            ArrayNode an =mapper.createArrayNode();
            ceritifcateIds.forEach(s-> {
                try {
                    an.add(AcmeUtils.readCachedCertificate(s));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            an.forEach(s->{
                String id = s.get("id").asText();
                CertificateInfo cinfo=loadFromPath("certs/"+id);
                try {
                    JsonNode node  = mapper.readTree(s.get("json").asText());
                    node.get("identifiers").forEach(ident->{
                       String domainName =  ident.get("value").asText();
                       certificateMap.put(domainName,cinfo);
                    });
                } catch (IOException e) {
                    throw new GenericServerProcessingException(e);
                }
            });

            System.out.println(an);
        } catch (Exception e) {
            throw new GenericServerProcessingException(e);
        }

    }

    public static void main(String[] args) {

        loadAllCerts();

     //   loadFromPath("certs");
    }

    public static void load(String alias) {
		CertificateInfo info = loadFromPath("certs/"+alias);
        certificateMap.put(alias,info );

	}

    public static CertificateInfo loadFromPath(String path) {
        File crtFile = new File(path+"/server.crt");
        File keyFile = new File(path+"/private.key");
        if(!crtFile.exists()) {
            System.out.println("Fallback to default path...."+path);
            crtFile = new File("certs/server.crt");
            keyFile = new File("certs/private.key");
        }
        X509Certificate[] keyCertChain = null;
        try {
            keyCertChain = SslContext.toX509Certificates(crtFile);
        } catch (Exception e) {
            e.printStackTrace();
        }
        PrivateKey key = null;
        try {
            key = SslContext.toPrivateKey(keyFile, null);
            System.out.println(key.getAlgorithm());
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new CertificateInfo(keyCertChain, key);

    }
}