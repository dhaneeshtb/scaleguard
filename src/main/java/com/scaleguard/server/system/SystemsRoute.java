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
                return RequestRoutingResponse.succes(mapper.writeValueAsString(SystemAdapter.configure(node,false)));
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
