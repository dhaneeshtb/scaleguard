package com.scaleguard.server.certificates;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.scaleguard.server.http.reverse.RequestRoute;
import com.scaleguard.server.http.reverse.RequestRoutingResponse;
import org.shredzone.acme4j.exception.AcmeException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class CertificatesRoute implements RequestRoute {
    private static final ObjectMapper mapper = new ObjectMapper();

    static CertificateManager cm=new CertificateManager();

    public static CertificateManager getCm() {
        return cm;
    }


    @Override
    public boolean isAuthNeeded() {
        return true;
    }

    @Override
    public RequestRoutingResponse handle(String method, String uri, String body) throws AcmeException, IOException {
        if(method.equalsIgnoreCase("delete")){
            String[] tuples = uri.split("/");
            cm.delete(tuples[tuples.length-1]);
            return RequestRoutingResponse.succes("{}");
        }else if(method.equalsIgnoreCase("post")){
            JsonNode node = mapper.readTree(body);
            ArrayNode an = (ArrayNode) node.get("domainNames");
            List<String> domainNames = new ArrayList<>();
            an.forEach(a->domainNames.add(a.asText()));
            return RequestRoutingResponse.succes(cm.orderCertificate(domainNames,null).toString());
        }else{

            String[] tuples = uri.split("/");
            String action = (tuples[tuples.length-1]);

            JsonNode node =body!=null && !body.isEmpty()? mapper.readTree(body):null;
            String challengeType = node!=null && node.has("challengeType")?
                    node.get("challengeType").asText():"http";

            if("verify".equalsIgnoreCase(action)){
                String id = tuples[tuples.length-2];
                return RequestRoutingResponse.succes("{\"status\":\""+cm.verifyOrder(id,challengeType)+"\"}");
            }else
            if("download".equalsIgnoreCase(action)){
                String id = tuples[tuples.length-2];
                return RequestRoutingResponse.succes(cm.readCertificates(id).toString());
            }else {
                return RequestRoutingResponse.succes(cm.loadAll().toString());
            }

        }
    }
}
