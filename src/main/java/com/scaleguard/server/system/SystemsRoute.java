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
                // Accept a list of properties or a single one (previously read one and cast it to a List → ClassCastException).
                JsonNode tree = mapper.readTree(body);
                List<SystemProperty> nodes = tree != null && tree.isArray()
                        ? mapper.convertValue(tree, new TypeReference<List<SystemProperty>>() {})
                        : List.of(mapper.convertValue(tree, SystemProperty.class));
                return RequestRoutingResponse.succes(mapper.valueToTree(cm.createProperty(nodes)).toString());
            }
        } else {
            return RequestRoutingResponse.succes("{\"properties\":\"\"}");
        }
    }
}
