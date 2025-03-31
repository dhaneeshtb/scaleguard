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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.xml.bind.DatatypeConverter;
import java.io.IOException;
import java.net.URL;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class AcmeDBUtils {

    private static void validateHashKey(String hashKey) {
        if (hashKey.contains("..") || hashKey.contains("/") || hashKey.contains("\\")) {
            throw new IllegalArgumentException("Invalid hashKey");
        }
    }
    private static final Logger LOGGER = LoggerFactory.getLogger(AcmeDBUtils.class);

    private AcmeDBUtils(){

    }
    private static final ObjectMapper mapper = new ObjectMapper();


    private static final int KEY_SIZE = 2048;
    private static final Logger LOG = LoggerFactory.getLogger(AcmeDBUtils.class);
    private static AcmeContext context =null;
    public static synchronized AcmeContext getContext() throws IOException, AcmeException {
        if(context==null) {
            AcmeContext context = new AcmeContext();
            context.setAccount(findOrRegisterAccount(new Session("acme://letsencrypt.org"), CertificateStoreUtils.loadOrCreateUserKeyPair()));
            context.setDomainKeyPair(CertificateStoreUtils.loadOrCreateDomainKeyPair());
            AcmeDBUtils.context=context;
        }
        return context;
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


    public static Order readOrder(AcmeContext context,String hashKey){
        try {
            return readOrderFromDB(context,hashKey);
        } catch (Exception e) {
            throw new GenericServerProcessingException(e);
        }
    }


    private static Account findOrRegisterAccount(Session session, KeyPair accountKey) throws AcmeException {
        Account account = new AccountBuilder()
                .agreeToTermsOfService()
                .useKeyPair(accountKey)
                .create(session);
        LOG.info("Registered a new user, URL: {}", account.getLocation());
        return account;
    }



    public static Set<String> listCertificateIds()  {
        return CertificateOrdersDB.getInstance().readAll().stream().map(s->s.getId()).collect(Collectors.toSet());

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




    public static void main(String[] args) throws IOException, AcmeException {

        listCertificateIds().forEach(s->LOGGER.info("certificate id from db->{}",s));

    }

}
