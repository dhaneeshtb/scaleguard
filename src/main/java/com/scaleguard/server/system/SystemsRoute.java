package com.scaleguard.server.system;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scaleguard.server.certificates.CertificatesRoute;
import com.scaleguard.server.db.SystemProperty;
import com.scaleguard.server.db.SystemPropertyDB;
import com.scaleguard.server.http.reverse.RequestRoute;
import com.scaleguard.server.http.reverse.RequestRoutingResponse;

import java.net.InetAddress;
import java.util.List;
import java.util.UUID;

public class SystemsRoute implements RequestRoute {
    private static final ObjectMapper mapper = new ObjectMapper();

    SystemManager cm = new SystemManager();

    @Override
    public boolean isAuthNeeded() {
        return true;
    }

    @Override
    public RequestRoutingResponse handle(String method, String uri, String body) throws Exception {

        String[] tuples = uri.split("/");
        String action = tuples[tuples.length - 1];
        if ("systems".equalsIgnoreCase(action) && method.equalsIgnoreCase("get")) {
            return RequestRoutingResponse.succes(cm.systemProperties().toString());
        } else if (method.equalsIgnoreCase("post")) {
            if ("configure".equalsIgnoreCase(action)) {
                JsonNode node = mapper.readTree(body);

                String d = node.get("hostName").asText();

                InetAddress address = InetAddress.getByName(d);

                String hostName = address.getHostName();
                String ip = address.getHostAddress();


                if (!hostName.equalsIgnoreCase(ip) && SystemManager.readNetworks().contains(ip)) {

                    System.out.println("System matched with the host ip");
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
                    System.out.println("couldnt identify the system configuration for " + d);

                }


                SystemProperty sp = new SystemProperty();
                sp.setName("hostName");
                sp.setValue(d);

                sp.setGroupname("system");
                sp.setId("hostName");
                sp.setMts(System.currentTimeMillis());
                sp.setUts(System.currentTimeMillis());
                if (node.get("id") != null) {
                    SystemPropertyDB.getInstance().edit(sp);
                } else
                    SystemPropertyDB.getInstance().create(sp);

                return RequestRoutingResponse.succes(body);
            } else {
                List<SystemProperty> nodes = mapper.readValue(body, new TypeReference<SystemProperty>() {
                });
                return RequestRoutingResponse.succes(mapper.valueToTree(cm.createProperty(nodes)).toString());
            }
        } else {
            return RequestRoutingResponse.succes("{\"properties\":\"\"}");
        }
    }
}
