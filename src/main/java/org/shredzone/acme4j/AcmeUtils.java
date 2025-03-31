package org.shredzone.acme4j;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scaleguard.exceptions.GenericServerProcessingException;
import com.scaleguard.server.db.ConnectionUtil;
import com.scaleguard.utils.JSON;
import org.shredzone.acme4j.challenge.Dns01Challenge;
import org.shredzone.acme4j.challenge.Http01Challenge;
import org.shredzone.acme4j.exception.AcmeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collection;
import java.util.Set;

public class AcmeUtils {
    private static final Logger LOG = LoggerFactory.getLogger(AcmeFileUtils.class);

    private static boolean isFileSupport = !ConnectionUtil.isPostgres();

    private AcmeUtils() {
    }

    public static boolean isIsFileSupport() {
        return isFileSupport;
    }


    public static synchronized AcmeContext getContext() throws IOException, AcmeException {
        return isFileSupport ? AcmeFileUtils.getContext() : AcmeDBUtils.getContext();
    }

    public static Set<String> listCertificateIds() {
        return isFileSupport ? AcmeFileUtils.listCertificateIds() : AcmeDBUtils.listCertificateIds();

    }

    public static JsonNode readCachedCertificate(String key) throws IOException {
        return isFileSupport ? AcmeFileUtils.readCachedCertificate(key) : AcmeDBUtils.readCachedCertificate(key);
    }

    public static String getDomainHashKey(Collection<String> domains) {
        return CertificateStoreUtils.getDomainHashKey(domains);
    }

    public static JsonNode saveOrder(Order order, String hashKey) {
        return isFileSupport ? AcmeFileUtils.saveOrder(order, hashKey) : AcmeDBUtils.saveOrder(order, hashKey);
    }

    public static Order readOrder(AcmeContext context, String hashKey) {
        return isFileSupport ? AcmeFileUtils.readOrder(context, hashKey) : AcmeDBUtils.readOrder(context, hashKey);

    }


    public static void saveCertificate(String hashKey, Order order, AcmeContext context) {
        if (isFileSupport)
            AcmeFileUtils.saveCertificate(hashKey, order, context);
        else
            CertificateStoreUtils.saveCertificate(hashKey, order, context);
    }

    public static JsonNode readCertificate(String hashKey) throws IOException {
        return isFileSupport ? AcmeFileUtils.readCertificate(hashKey) : CertificateStoreUtils.readCertificate(hashKey);
    }

    public static void deleteCertificate(String hashKey) throws IOException {
        if (isFileSupport)
            AcmeFileUtils.deleteCertificate(hashKey);
        else
            AcmeDBUtils.deleteCertificate(hashKey);
    }

    public static JsonNode httpChallenge(Authorization auth) throws AcmeException {
        // Find a single http-01 challenge
        ObjectNode on = JSON.object();

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

    public static JsonNode toOrderInfoJson(Order order,String hashKey){
        ObjectNode on = JSON.object();
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


    public static JsonNode dnsChallenge(Authorization auth) throws AcmeException {
        // Find a single dns-01 challenge
        ObjectNode on = JSON.object();
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
}
