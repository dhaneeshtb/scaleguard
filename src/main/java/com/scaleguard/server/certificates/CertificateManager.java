package com.scaleguard.server.certificates;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.shredzone.acme4j.*;
import org.shredzone.acme4j.challenge.Challenge;
import org.shredzone.acme4j.challenge.Dns01Challenge;
import org.shredzone.acme4j.challenge.Http01Challenge;
import org.shredzone.acme4j.challenge.TokenChallenge;
import org.shredzone.acme4j.exception.AcmeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class CertificateManager {
    private static final Logger LOG = LoggerFactory.getLogger(CertificateManager.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    AcmeContext context;
    public CertificateManager(){
        try {
            context = AcmeUtils.getContext();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    public JsonNode loadAll() throws AcmeException, IOException {
       Set<String> ceritifcateIds= AcmeUtils.listCertificateIds();
        ArrayNode an =mapper.createArrayNode();
        ceritifcateIds.forEach(s-> {
            try {
                an.add(AcmeUtils.readCachedCertificate(s));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        return an;
    }
    public JsonNode orderCertificate(List<String> domains,String id) throws AcmeException, IOException {
        LoadOrder loader= new LoadOrder(context);
        return loader.createOrder(domains,id==null? UUID.randomUUID().toString():id);
    }

    public JsonNode checkStatus(String orderId) throws AcmeException, IOException {
        LoadOrder loader= new LoadOrder(context);
        return loader.loadOrderStatus(orderId);
    }

    public JsonNode readCertificates(String orderId) throws AcmeException, IOException {
        return AcmeUtils.readCertificate(orderId);
    }

    public void delete(String orderId) throws AcmeException, IOException {
        AcmeUtils.deleteCertificate(orderId);
    }

    public Order getOrder(String orderId) throws AcmeException, IOException {
        LoadOrder loader= new LoadOrder(context);
        return loader.loadOrder(orderId);
    }

    public String verifyOrder(String id,String challengeType) throws AcmeException, IOException {
        Order order = getOrder(id);
        order.update();
        List<Challenge> challenges = new ArrayList<>();
        System.out.println("Location "+order.getLocation());
        //String challengeType="http";//http or dns


        if(order.getStatus().equals(Status.PENDING)) {
            order.getAuthorizations().forEach(auth -> {
                Http01Challenge challengeHTTP = auth.findChallenge(Http01Challenge.class).orElse(null);
                Dns01Challenge challengeDNS = auth.findChallenge(Dns01Challenge.class).orElse(null);
                TokenChallenge challenge= ("http".equalsIgnoreCase(challengeType))?challengeHTTP:challengeDNS;
                if(challenge!=null) {
                    try {
                        challenge.trigger();
                        challenges.add(challenge);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            });
        }

        if (order.getStatus().equals(Status.READY)){

            System.out.println("Executing Domain Order ###");
            order.execute(context.getDomainKeyPair());
            try {
                Thread.sleep(4000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        int attempts = 10;
        while (order.getStatus() != Status.VALID && attempts-- > 0) {
            System.out.println("Checking status" +order.getStatus());

            if (order.getStatus() == Status.INVALID) {
                throw new AcmeException("Challenge failed... Giving up.");
            } else if (order.getStatus().equals(Status.READY)) {
                System.out.println("Executing inner ready" +order.getStatus());

                order.execute(context.getDomainKeyPair());
            }
            try {
                Thread.sleep(3000L);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            order.update();
        }

        if(order.getStatus().equals(Status.VALID)){
            System.out.println("Final status" +order.getStatus());

            AcmeUtils.saveCertificate(id,order,context);
        }else{
            AcmeUtils.saveOrder(order,id);
        }
        return order.getStatus().toString();

    }

    public static void main(String[] args) {
        CertificateManager cm = new CertificateManager();
        String id ="76fde90a-f667-473e-ac86-d34050ee72f4";// UUID.randomUUID().toString();
        try {
            //System.out.println(cm.orderCertificate(Arrays.asList("crm.noootherday.com"),id));
            System.out.println("Checking status ............");
            System.out.println(cm.checkStatus(id));

            Order order = cm.getOrder(id);
            order.update();

            if(order.getStatus().equals(Status.PENDING)) {
                order.getAuthorizations().forEach(auth -> {
                    Http01Challenge challenge = auth.findChallenge(Http01Challenge.class).orElse(null);
                    try {
                        if(challenge!=null) {
                            challenge.trigger();
                        }
                    } catch (AcmeException e) {
                        throw new RuntimeException(e);
                    }
                });

                order.getAuthorizations().forEach(auth -> {
                    Dns01Challenge challenge = auth.findChallenge(Dns01Challenge.class).orElse(null);
                    try {
                      if(challenge!=null)  challenge.trigger();
                    } catch (AcmeException e) {
                        throw new RuntimeException(e);
                    }
                });
            }

            if(order.getStatus().equals(Status.READY)) {
                order.execute(cm.context.getDomainKeyPair());
            }

            if(order.getStatus().equals(Status.VALID)){
                AcmeUtils.saveCertificate(id,order,cm.context);
            }


            System.out.println(order.getStatus());


        } catch (AcmeException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

}
