package com.scaleguard.server.certificates;

import org.shredzone.acme4j.AcmeUtils;
import org.shredzone.acme4j.Order;
import org.shredzone.acme4j.Status;
import org.shredzone.acme4j.challenge.Dns01Challenge;
import org.shredzone.acme4j.challenge.Http01Challenge;
import org.shredzone.acme4j.exception.AcmeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class CertificateManagerTest {
    private static final Logger LOG = LoggerFactory.getLogger(CertificateManager.class);

    public static void main(String[] args) {
        CertificateManager cm = new CertificateManager();
        String id ="76fde90a-f667-473e-ac86-d34050ee72f4";// UUID.randomUUID().toString();
        try {
            //System.out.println(cm.orderCertificate(Arrays.asList("crm.noootherday.com"),id));
            LOG.info("Checking status ............");
            LOG.info("Current Status=>{}",cm.checkStatus(id));

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


            LOG.info("order.getStatus() :=> {}",order.getStatus());


        } catch (AcmeException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

}
