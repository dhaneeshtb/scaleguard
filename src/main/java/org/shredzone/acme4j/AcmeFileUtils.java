package org.shredzone.acme4j;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scaleguard.exceptions.GenericServerProcessingException;
import com.scaleguard.server.db.CertificateOrdersDB;
import com.scaleguard.server.db.DBModelSystem;
import io.netty.handler.ssl.CertificateStore;
import org.shredzone.acme4j.challenge.Dns01Challenge;
import org.shredzone.acme4j.challenge.Http01Challenge;
import org.shredzone.acme4j.exception.AcmeException;
import org.shredzone.acme4j.toolbox.JSON;
import org.shredzone.acme4j.util.KeyPairUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.bind.DatatypeConverter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
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

public class AcmeFileUtils {

    private static void validateHashKey(String hashKey) {
        if (hashKey.contains("..") || hashKey.contains("/") || hashKey.contains("\\")) {
            throw new IllegalArgumentException("Invalid hashKey");
        }
    }
    private static final Logger LOGGER = LoggerFactory.getLogger(AcmeFileUtils.class);

    private AcmeFileUtils(){

    }
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final File USER_KEY_FILE = new File("user.key");
    private static final File DOMAIN_KEY_FILE = new File("domain.key");
    private static final String CERTS_PATH = System.getProperty("certs.path","certs");
    private static final String DOMAIN_HASH_PATH = "domains";

    private static final int KEY_SIZE = 2048;
    private static final Logger LOG = LoggerFactory.getLogger(AcmeFileUtils.class);
    private static AcmeContext context =null;
    public static synchronized AcmeContext getContext() throws IOException, AcmeException {
        if(context==null) {
            AcmeContext context = new AcmeContext();
            context.setAccount(findOrRegisterAccount(new Session("acme://letsencrypt.org"), loadKeyPair()));
            context.setDomainKeyPair(loadOrCreateDomainKeyPair());
            AcmeFileUtils.context=context;
            //migrate();
        }
        return context;
    }

    private static void migrate() throws AcmeException, IOException {
        AcmeContext context = getContext();
        listCertificateIdsFromFile().forEach(s->{
            Order ord = readOrderFromFile(context,s);
            saveOrder(ord,s);
        });
    }






    public static JsonNode saveOrder(Order order,String hashKey){
        try {
            return saveOrderToDB(order,hashKey);
        } catch (Exception e) {
            e.printStackTrace();
            throw new GenericServerProcessingException(e);
        }
    }

    private static JsonNode saveOrderToDB(Order order,String hashKey) throws Exception {
        JsonNode on=AcmeUtils.toOrderInfoJson(order,hashKey);
        DBModelSystem dms = new DBModelSystem();
        dms.setId(hashKey);
        HashSet<String> domains = new HashSet<>();
        order.getIdentifiers().listIterator().forEachRemaining(id->domains.add(id.getDomain()));
        dms.setName(String.join(",",domains));
        dms.setPayload(on.toString());
        dms.setStatus(order.getStatus().name());
        CertificateOrdersDB.getInstance().save(dms);
        return on;
    }

    private static Order readOrderFromDB(AcmeContext context,String hashKey) throws Exception {
        List<DBModelSystem> dmsList=CertificateOrdersDB.getInstance().readItems("id",hashKey);
        if(!dmsList.isEmpty()){
            DBModelSystem dms = dmsList.get(0);
            final ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.readTree(dms.getPayload());
            Order order = new Order(context.getAccount().getLogin(), new URL(node.get("location").asText()));
            order.setJSON(JSON.parse(node.get("json").asText()));
            return order;
        }
        return null;
    }
    public static Order readOrderFromFile(AcmeContext context,String hashKey){
        try {
            if(new File(DOMAIN_HASH_PATH + "/" + hashKey).exists()) {
                String file = Files.readString(Path.of(DOMAIN_HASH_PATH + "/" + hashKey), Charset.forName("utf-8"));
                final ObjectMapper mapper = new ObjectMapper();
                JsonNode node = mapper.readTree(file);
                Order order = new Order(context.getAccount().getLogin(), new URL(node.get("location").asText()));
                order.setJSON(JSON.parse(node.get("json").asText()));
                return order;
            }
        }catch (Exception e){
            e.printStackTrace();
        }
        return null;
    }

    public static Order readOrder(AcmeContext context,String hashKey){
        try {
            return readOrderFromDB(context,hashKey);
        } catch (Exception e) {
            throw new GenericServerProcessingException(e);
        }
    }

    private static KeyPair loadKeyPair() throws IOException {
        return loadOrCreateUserKeyPair();
    }

    private static Account findOrRegisterAccount(Session session, KeyPair accountKey) throws AcmeException {
        Account account = new AccountBuilder()
                .agreeToTermsOfService()
                .useKeyPair(accountKey)
                .create(session);
        LOG.info("Registered a new user, URL: {}", account.getLocation());
        return account;
    }

    private static KeyPair loadOrCreateDomainKeyPair() throws IOException {
        if (DOMAIN_KEY_FILE.exists()) {
            try (FileReader fr = new FileReader(DOMAIN_KEY_FILE)) {
                return KeyPairUtils.readKeyPair(fr);
            }
        } else {
            KeyPair domainKeyPair = KeyPairUtils.createKeyPair(KEY_SIZE);
            try (FileWriter fw = new FileWriter(DOMAIN_KEY_FILE)) {
                KeyPairUtils.writeKeyPair(domainKeyPair, fw);
            }
            return domainKeyPair;
        }
    }
    private static KeyPair loadOrCreateUserKeyPair() throws IOException {
        if (USER_KEY_FILE.exists()) {
            // If there is a key file, read it
            try (FileReader fr = new FileReader(USER_KEY_FILE)) {
                return KeyPairUtils.readKeyPair(fr);
            }

        } else {
            // If there is none, create a new key pair and save it
            KeyPair userKeyPair = KeyPairUtils.createKeyPair(KEY_SIZE);
            try (FileWriter fw = new FileWriter(USER_KEY_FILE)) {
                KeyPairUtils.writeKeyPair(userKeyPair, fw);
            }
            return userKeyPair;
        }
    }

    public static Set<String> listCertificateIds()  {
        return CertificateOrdersDB.getInstance().readAll().stream().map(s->s.getId()).collect(Collectors.toSet());

    }
    public static Set<String> listCertificateIdsFromFile() throws IOException {
        try (Stream<Path> stream = Files.list(Paths.get(DOMAIN_HASH_PATH))) {
            return stream
                    .filter(file -> !Files.isDirectory(file))
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .collect(Collectors.toSet());
        }
    }
    public  static JsonNode readCachedCertificate(String hashKey) throws IOException {
        final ObjectMapper mapper = new ObjectMapper();
        try {
            return mapper.readTree(CertificateOrdersDB.getInstance().readItems("id",hashKey).get(0).getPayload());
        } catch (Exception e) {
            throw new GenericServerProcessingException(e);
        }
    }

    public  static void deleteCertificate(String hashKey) throws IOException {
        try {
            CertificateOrdersDB.getInstance().delete(hashKey);
        } catch (Exception e) {
            throw new GenericServerProcessingException(e);
        }
    }

    public static JsonNode readCertificate(String hashKey) throws IOException {
        validateHashKey(hashKey);
        ObjectNode on =  mapper.createObjectNode();
        String certificatePath = CERTS_PATH+File.separator+hashKey;
        on.put("id",hashKey);
        on.put("privateKey", Files.readString(Path.of(certificatePath+"/private.key")));
        on.put("certificate", Files.readString(Path.of(certificatePath+"/server.crt")));
        return on;

    }
    public static void saveCertificate(String hashKey,Order order,AcmeContext context){
        validateHashKey(hashKey);
        String certificatePath = CERTS_PATH+File.separator+hashKey;
        File f = new File(certificatePath);
        if(!f.exists())
            f.mkdirs();

        try (FileWriter fw = new FileWriter(certificatePath+"/private.key")) {
            KeyPairUtils.writeKeyPair(context.domainKeyPair, fw);
        } catch (IOException e) {
            throw new GenericServerProcessingException(e);
        }
        try (FileWriter fw = new FileWriter(certificatePath+"/server.crt")) {
            order.getCertificate().writeCertificate(fw);
        } catch (IOException e) {
            throw new GenericServerProcessingException(e);
        }
        saveOrder(order,hashKey);
        CertificateStore.loadAllCerts();
    }

    public static void main(String[] args) throws IOException, AcmeException {

        listCertificateIds().forEach(s->LOGGER.info("certificate id from db->{}",s));

    }

}
