package com.scaleguard.server.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.scaleguard.server.certificates.CertificatesRoute;
import com.scaleguard.server.db.SystemProperty;
import com.scaleguard.server.db.SystemPropertyDB;
import io.netty.handler.ssl.CertificateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class SystemAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(SystemAdapter.class);
    public static SystemProperty configure(JsonNode node,boolean failOnError) throws Exception {
        String d = node.get("hostName").asText();
        String id = node.has("id")?node.get("id").asText():null;
        return configure(d,id,failOnError);
    }



    private static SystemProperty getHostNameProperty(){
       return SystemPropertyDB.getInstance().readAll().stream().filter(s->s.getName().equalsIgnoreCase("hostName")).findFirst().orElse(null);
    }
    public static boolean checkConfiguration(String hostName) throws Exception {
       CertificateStore.CertificateInfo certInfo =  CertificateStore.get(hostName);
       if(certInfo==null){
           LOG.warn("Certificate not found for: {}", hostName);
           return false;
       }
       LOG.debug("Certificate found for: {}", certInfo.getId());
       SystemProperty sp = getHostNameProperty();
       if(certInfo!=null && sp!=null){
           LOG.debug("System Property Configuration found for hostName: {}", sp.getValue());
           if(!sp.getValue().equalsIgnoreCase(hostName)){
               LOG.info("Reconfiguration needed for: {}", hostName);
               return false;
           }else{
               return true;
           }
       }
       return false;
    }
    public static SystemProperty configure(String d) throws Exception {
        boolean isMapped =  SystemManager.isSystemMapped(d);
        if(!isMapped){
           throw new RuntimeException("Scalegurad server not mapped to "+d);
        }

        if(!checkConfiguration(d)) {
            return configure(d, Optional.ofNullable(getHostNameProperty()).map(s -> s.getId()).orElse(null), true);
        }else {
            return getHostNameProperty();
        }
    }
    public static SystemProperty configure(String d,String id,boolean failOnError) throws Exception {
        InetAddress address = InetAddress.getByName(d);
        String hostName = address.getHostName();
        String ip = address.getHostAddress();
        if (!hostName.equalsIgnoreCase(ip) && SystemManager.readNetworks().contains(ip)) {
            LOG.info("System matched with the host ip for: {}", d);
            if (!"localhost".equalsIgnoreCase(hostName)) {
                String certificateId = UUID.randomUUID().toString();
                CertificatesRoute.getCm().orderCertificate(List.of(hostName), certificateId);
                CertificatesRoute.getCm().verifyOrder(certificateId, "http");
                long hostCertificateCount = SystemPropertyDB.getInstance().readAll().stream().filter(r -> r.getId().equalsIgnoreCase("hostCertificate")).count();
                SystemProperty sp = new SystemProperty();
                sp.setName("hostCertificate");
                sp.setValue(certificateId);
                sp.setGroupname("system");
                sp.setId("hostCertificate");
                sp.setMts(System.currentTimeMillis());
                sp.setUts(System.currentTimeMillis());
                if (hostCertificateCount > 0) {
                    SystemPropertyDB.getInstance().edit(sp);
                } else
                    SystemPropertyDB.getInstance().create(sp);
            }
        } else {
            LOG.warn("Could not identify system configuration for: {}", d);
            if(failOnError){
                throw new RuntimeException("hostName "+hostName+ "  is not mapped to scaleguard");
            }
        }


        SystemProperty sp = new SystemProperty();
        sp.setName("hostName");
        sp.setValue(d);
        sp.setGroupname("system");
        sp.setId("hostName");
        sp.setMts(System.currentTimeMillis());
        sp.setUts(System.currentTimeMillis());
        if (id != null) {
            SystemPropertyDB.getInstance().edit(sp);
        } else
            SystemPropertyDB.getInstance().create(sp);
        return sp;
    }
}
