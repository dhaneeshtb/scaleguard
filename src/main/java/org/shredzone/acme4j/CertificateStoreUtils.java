package org.shredzone.acme4j;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scaleguard.exceptions.GenericServerProcessingException;
import com.scaleguard.server.db.CertificateOrdersDB;
import com.scaleguard.server.db.DBModelSystem;
import com.scaleguard.server.db.FileStorage;
import io.netty.handler.ssl.CertificateStore;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.shredzone.acme4j.exception.AcmeException;
import org.shredzone.acme4j.toolbox.JSON;
import org.shredzone.acme4j.util.KeyPairUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.bind.DatatypeConverter;
import java.io.*;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class CertificateStoreUtils {

    private static final Logger LOGGER = LoggerFactory.getLogger(CertificateStoreUtils.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int KEY_SIZE = 2048;

    public static String getDomainHashKey(Collection<String> domains){
        try {
            String domainsHash = String.join(",", domains);
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(domainsHash.getBytes());
            byte[] digest = md.digest();
            return DatatypeConverter
                    .printHexBinary(digest).toUpperCase();
        }catch (Exception e){
            LOGGER.error("Error while generating domain hash ",e);
            return null;
        }
    }

    public static KeyPair loadOrCreateDomainKeyPair() throws IOException {
        String domainKeyContent=FileStorage.retrieveFileAsString("domain_key","domain.key");
        if (domainKeyContent!=null) {
            try (StringReader fr = new StringReader(domainKeyContent)) {
                return KeyPairUtils.readKeyPair(fr);
            }
        } else {
            KeyPair domainKeyPair = KeyPairUtils.createKeyPair(KEY_SIZE);
            try (StringWriter fw = new StringWriter()) {
                KeyPairUtils.writeKeyPair(domainKeyPair, fw);
                FileStorage.storeFileAsString("domain_key","domain.key",fw.toString());
            }
            return domainKeyPair;
        }
    }
    public static KeyPair loadOrCreateUserKeyPair() throws IOException {
        String userKeyContent=FileStorage.retrieveFileAsString("user_key","user.key");
        if (userKeyContent!=null) {
            // If there is a key file, read it
            try (StringReader fr = new StringReader(userKeyContent)) {
                return KeyPairUtils.readKeyPair(fr);
            }

        } else {
            // If there is none, create a new key pair and save it
            KeyPair userKeyPair = KeyPairUtils.createKeyPair(KEY_SIZE);
            try (StringWriter fw = new StringWriter()) {
                KeyPairUtils.writeKeyPair(userKeyPair, fw);
                FileStorage.storeFileAsString("user_key","user.key",fw.toString());
            }
            return userKeyPair;
        }
    }

    private static JsonNode saveOrderToDB(Order order, String hashKey) throws Exception {
        JsonNode on = toOrderInfoJson(order, hashKey);
        DBModelSystem dms = new DBModelSystem();
        dms.setId(hashKey);
        HashSet<String> domains = new HashSet<>();
        order.getIdentifiers().listIterator().forEachRemaining(id -> domains.add(id.getDomain()));
        dms.setName(String.join(",", domains));
        dms.setPayload(on.toString());
        dms.setStatus(order.getStatus().name());
        CertificateOrdersDB.getInstance().save(dms);
        return on;
    }

    private static Order readOrderFromDB(AcmeContext context, String hashKey) throws Exception {
        List<DBModelSystem> dmsList = CertificateOrdersDB.getInstance().readItems("id", hashKey);
        if (!dmsList.isEmpty()) {
            DBModelSystem dms = dmsList.get(0);
            final ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.readTree(dms.getPayload());
            Order order = new Order(context.getAccount().getLogin(), new URL(node.get("location").asText()));
            order.setJSON(JSON.parse(node.get("json").asText()));
            return order;
        }
        return null;
    }

    /***
     *
     * @param hashKey
     * @param order
     * @param context
     * @throws Exception
     */
    public static void saveCertificate(String hashKey, Order order, AcmeContext context) {
        validateHashKey(hashKey);
        String serverCrt = null;
        String privateKey = null;
        try (StringWriter fw = new StringWriter()) {
            KeyPairUtils.writeKeyPair(context.domainKeyPair, fw);
            privateKey = fw.toString();
        } catch (IOException e) {
            throw new GenericServerProcessingException(e);
        }

        try (StringWriter fw = new StringWriter()) {
            order.getCertificate().writeCertificate(fw);
            serverCrt = fw.toString();
        } catch (IOException e) {
            throw new GenericServerProcessingException(e);
        }

        FileStorage.storeFileAsString(hashKey, "private.key", privateKey);
        FileStorage.storeFileAsString(hashKey, "server.crt", serverCrt);
        try{
            saveOrderToDB(order, hashKey);
        }catch (Exception e){
            e.printStackTrace();
        }
        CertificateStore.loadAllCerts();
    }

    public static JsonNode readCertificate(String hashKey) {
        validateHashKey(hashKey);
        ObjectNode on = mapper.createObjectNode();
        on.put("id", hashKey);
        on.put("privateKey", FileStorage.retrieveFileAsString(hashKey, "private.key"));
        on.put("certificate", FileStorage.retrieveFileAsString(hashKey, "server.crt"));
        return on;
    }


    public static JsonNode toOrderInfoJson(Order order,String hashKey){
        ObjectNode on =  mapper.createObjectNode();
        on.put("id",hashKey);
        on.put("creationTime",System.currentTimeMillis());
        on.put("location",order.getLocation().toString());
        on.put("json",order.getJSON().toString());
        if(order.getStatus().equals(Status.VALID) && order.getCertificate()!=null && order.getCertificate().getCertificate()!=null ){
            on.put("expiryTime",order.getCertificate().getCertificate().getNotAfter().getTime());
        }
        if(!order.getStatus().equals(Status.VALID)) {
            order.getAuthorizations().forEach(auth -> {
                try {
                    on.set("httpChallenge", AcmeUtils.httpChallenge(auth));
                    on.set("dnsChallenge", AcmeUtils.dnsChallenge(auth));
                } catch (AcmeException e) {
                    throw new GenericServerProcessingException(e);
                }
            });
        }
        return on;
    }

    private static void validateHashKey(String hashKey) {
        if (hashKey.contains("..") || hashKey.contains("/") || hashKey.contains("\\")) {
            throw new IllegalArgumentException("Invalid hashKey");
        }
    }



    public static void main(String[] args) {
        try {
            System.out.println(loadOrCreateDomainKeyPair());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
