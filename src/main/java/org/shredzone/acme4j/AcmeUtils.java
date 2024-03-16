package org.shredzone.acme4j;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scaleguard.exceptions.GenericServerProcessingException;
import com.scaleguard.server.db.CertificateOrdersDB;
import com.scaleguard.server.db.DBModelSystem;
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

public class AcmeUtils {

    private static final Logger LOGGER = LoggerFactory.getLogger(AcmeUtils.class);

    private AcmeUtils(){

    }
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final File USER_KEY_FILE = new File("user.key");
    private static final File DOMAIN_KEY_FILE = new File("domain.key");
    private static final String CERTS_PATH = System.getProperty("certs.path","certs");
    private static final String DOMAIN_HASH_PATH = "domains";

    private static final int KEY_SIZE = 2048;
    private static final Logger LOG = LoggerFactory.getLogger(AcmeUtils.class);
    private static AcmeContext context =null;
    public static synchronized AcmeContext getContext() throws IOException, AcmeException {
        if(context==null) {
            AcmeContext context = new AcmeContext();
            context.setAccount(findOrRegisterAccount(new Session("acme://letsencrypt.org"), loadKeyPair()));
            context.setDomainKeyPair(loadOrCreateDomainKeyPair());
            AcmeUtils.context=context;
            migrate();
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
                    on.set("httpChallenge", httpChallenge(auth));
                    on.set("dnsChallenge", dnsChallenge(auth));
                } catch (AcmeException e) {
                    throw new GenericServerProcessingException(e);
                }
            });
        }
        return on;
    }

    public static JsonNode httpChallenge(Authorization auth) throws AcmeException {
        // Find a single http-01 challenge
        ObjectNode on = mapper.createObjectNode();

        try {
            Http01Challenge challenge = auth.findChallenge(Http01Challenge.class)
                    .orElseThrow(() -> new AcmeException("Found no " + Http01Challenge.TYPE
                            + " challenge, don't know what to do..."));

            // Output the challenge, wait for acknowledge...
            LOG.info("Please create a file in your web server's base directory.");
            LOG.info("It must be reachable at: http://{}/.well-known/acme-challenge/{}",
                    auth.getIdentifier().getDomain(), challenge.getToken());
            LOG.info("File name: {}", challenge.getToken());
            LOG.info("Content: {}", challenge.getAuthorization());
            LOG.info("The file must not contain any leading or trailing whitespaces or line breaks!");
            LOG.info("If you're ready, dismiss the dialog...");

            on.put("domain", auth.getIdentifier().getDomain());
            on.put("path", "/.well-known/acme-challenge/");
            on.put("token", challenge.getToken());
            on.put("content", challenge.getAuthorization());


            StringBuilder message = new StringBuilder();
            message.append("Please create a file in your web server base directory.\n\n");
            message.append("http://")
                    .append(auth.getIdentifier().getDomain())
                    .append("/.well-known/acme-challenge/")
                    .append(challenge.getToken())
                    .append("\n\n");
            message.append("Content:\n\n");
            message.append(challenge.getAuthorization());
            on.put("message", message.toString());
        }catch (Exception e){
            LOG.error("Http01Challenge.TYPE not saved=>{}",e.getMessage());
        }

        return on;
    }


    public static JsonNode dnsChallenge(Authorization auth) throws AcmeException {
        // Find a single dns-01 challenge
        ObjectNode on =  mapper.createObjectNode();
        try {
            Dns01Challenge challenge = auth.findChallenge(Dns01Challenge.TYPE)
                    .map(Dns01Challenge.class::cast)
                    .orElseThrow(() -> new AcmeException("Found no " + Dns01Challenge.TYPE
                            + " challenge, don't know what to do..."));

            // Output the challenge, wait for acknowledge...
            LOG.info("Please create a TXT record:");
            if(LOG.isInfoEnabled()) {
                LOG.info("{} IN TXT {}",
                        Dns01Challenge.toRRName(auth.getIdentifier()), challenge.getDigest());
            }
            LOG.info("If you're ready, dismiss the dialog...");
            on.put("domainRecordName", Dns01Challenge.toRRName(auth.getIdentifier()));
            on.put("dnsRecordType", "TXT");
            on.put("dnsRecordValue", challenge.getDigest());

            StringBuilder message = new StringBuilder();
            message.append("Please create a TXT record:\n\n");
            message.append(Dns01Challenge.toRRName(auth.getIdentifier()))
                    .append(" IN TXT ")
                    .append(challenge.getDigest());
            on.put("message", message.toString());
        }catch (Exception e){
            LOG.error("Dns01Challenge.TYPE not saved=> {}",e.getMessage());
        }

        return on;
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
        JsonNode on=toOrderInfoJson(order,hashKey);
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
        return CertificateOrdersDB.getInstance().readAll().stream().filter(s->Status.VALID.name().equals(s.getStatus())).map(s->s.getId()).collect(Collectors.toSet());

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

        Files.delete(Path.of(DOMAIN_HASH_PATH + File.separator + hashKey));
    }

    public static JsonNode readCertificate(String hashKey) throws IOException {
        ObjectNode on =  mapper.createObjectNode();
        String certificatePath = CERTS_PATH+File.separator+hashKey;
        on.put("id",hashKey);
        on.put("privateKey", Files.readString(Path.of(certificatePath+"/private.key")));
        on.put("certificate", Files.readString(Path.of(certificatePath+"/server.crt")));
        return on;


    }
    public static void saveCertificate(String hashKey,Order order,AcmeContext context){
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
    }

    public static void main(String[] args) throws IOException, AcmeException {

        listCertificateIds().forEach(s->LOGGER.info("certificate id from db->{}",s));

    }

}
