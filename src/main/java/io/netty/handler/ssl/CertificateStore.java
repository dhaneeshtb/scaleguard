package io.netty.handler.ssl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.scaleguard.exceptions.GenericServerProcessingException;
import com.scaleguard.server.db.FileStorage;
import org.shredzone.acme4j.AcmeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringBufferInputStream;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.*;

public class CertificateStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(CertificateStore.class);

    private static final ObjectMapper mapper = new ObjectMapper();

    public static class CertificateInfo {
        X509Certificate[] keyCertChain;

        private String id;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

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
    private static Map<String, CertificateInfo> certificateMap = new ConcurrentHashMap<>();
    private static Map<String, CertificateInfo> wildcardCertsMap = new ConcurrentHashMap<>();

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
        CertificateInfo wCert = getWildcardCheck(alias);
        if(wCert!=null){
            return wCert;
        }
        load(alias);
        return certificateMap.get(alias);
    }

    private static CertificateInfo getWildcardCheck(String alias){
        String wTups=alias.substring(alias.indexOf("."));
        return wildcardCertsMap.get(wTups);
    }

    public static void loadAllCerts(){
        long startTime = System.currentTimeMillis();
        try {
            // Clear stale entries before reloading
            certificateMap.clear();
            wildcardCertsMap.clear();

            // Read all certificate metadata sequentially (single bulk DB call, fast)
            Set<String> certificateIds = AcmeUtils.listCertificateIds();
            LOGGER.info("Loading {} certificates...", certificateIds.size());

            List<JsonNode> certNodes = new ArrayList<>();
            for (String id : certificateIds) {
                try {
                    certNodes.add(AcmeUtils.readCachedCertificate(id));
                } catch (IOException e) {
                    LOGGER.warn("Failed to read certificate metadata for id={}", id, e);
                }
            }

            // Load cert files (private.key + server.crt) in parallel using parallelStream
            certNodes.parallelStream().forEach(s -> {
                String id = s.get("id").asText();
                CertificateInfo cinfo = loadFromDB(id);
                if (cinfo != null) {
                    cinfo.setId(id);
                    try {
                        JsonNode node = mapper.readTree(s.get("json").asText());
                        node.get("identifiers").forEach(ident -> {
                            String domainName = ident.get("value").asText();
                            certificateMap.put(domainName, cinfo);
                            if (domainName.startsWith("*.")) {
                                String wDomain = domainName.split("[*]")[1];
                                LOGGER.info("Wildcard certificate => {} ", wDomain);
                                wildcardCertsMap.put(wDomain, cinfo);
                            }
                            LOGGER.info("Loaded certificate => {} ", domainName);
                        });
                    } catch (IOException e) {
                        LOGGER.warn("Failed to parse cert json for id={}", id, e);
                    }
                } else {
                    LOGGER.info("Certificate not loaded yet for => {} ", id);
                }
            });

            long elapsed = System.currentTimeMillis() - startTime;
            LOGGER.info("Loaded {} certificates in {}ms", certificateMap.size(), elapsed);

        } catch (Exception e) {
            throw new GenericServerProcessingException(e);
        }
    }

    public static void main(String[] args) {

        loadAllCerts();

     //   loadFromPath("certs");
    }

    public static void load(String alias) {
		CertificateInfo info =loadFromDB(alias);
        certificateMap.put(alias,info );

	}



    public static CertificateInfo loadFromDB(String path) {
        String crtFileData = FileStorage.retrieveFileAsString(path,"server.crt");
        String keyFileData = FileStorage.retrieveFileAsString(path,"private.key");
        if(keyFileData!=null && crtFileData!=null) {
            X509Certificate[] keyCertChain = null;
            try {
                keyCertChain = SslContext.toX509Certificates(new StringBufferInputStream(crtFileData));
            } catch (Exception e) {
                e.printStackTrace();
            }
            PrivateKey key = null;
            try {
                key = SslContext.toPrivateKey(new StringBufferInputStream(keyFileData), null);
                System.out.println(key.getAlgorithm());
            } catch (Exception e) {
                e.printStackTrace();
            }
            return new CertificateInfo(keyCertChain, key);
        }else{
            return null;
        }

    }
}