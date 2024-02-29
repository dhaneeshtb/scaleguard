package com.scaleguard.server.acmechallengeroute;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.scaleguard.server.http.reverse.RequestRoute;
import com.scaleguard.server.http.reverse.RequestRoutingResponse;
import org.shredzone.acme4j.AcmeUtils;
import org.shredzone.acme4j.exception.AcmeException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class AcmeChannelgeRoute implements RequestRoute {
    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public boolean isAuthNeeded() {
        return false;
    }

    @Override
    public RequestRoutingResponse handle(String method, String uri, String body) throws AcmeException, IOException {

        System.out.println("Acme challenge request !!!! for uri:"+uri);

        if (method.equalsIgnoreCase("get")) {
            String[] tuples = uri.split("/");
            String token = tuples[tuples.length - 1];
            String content = AcmeUtils.listCertificateIds().stream().map(s -> {
                try {
                    JsonNode jn = AcmeUtils.readCachedCertificate(s);
                    if (jn.has("httpChallenge")) {
                        JsonNode jnn = jn.get("httpChallenge");
                        String tokeni = jnn.get("token").asText();
                        if (tokeni.equalsIgnoreCase(token)) {
                            return jnn.get("content").asText();
                        }
                    } else {
                        return null;
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
                return null;
            }).filter(Objects::nonNull).findFirst().orElse(null);
            if (content != null) {
                return RequestRoutingResponse.succes(content);
            } else {
                return RequestRoutingResponse.response(404, "not found " + token);
            }
        }
        return RequestRoutingResponse.response(404, "not found");
    }
}



