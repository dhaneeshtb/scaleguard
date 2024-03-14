package com.scaleguard.server.acmechallengeroute;

import com.fasterxml.jackson.databind.JsonNode;
import com.scaleguard.server.http.reverse.RequestRoute;
import com.scaleguard.server.http.reverse.RequestRoutingResponse;
import org.shredzone.acme4j.AcmeUtils;
import org.shredzone.acme4j.exception.AcmeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Objects;

public class AcmeChannelgeRoute implements RequestRoute {

    private static final Logger LOGGER = LoggerFactory.getLogger(AcmeChannelgeRoute.class);

    @Override
    public boolean isAuthNeeded() {
        return false;
    }

    @Override
    public RequestRoutingResponse handle(String method, String uri, String body) throws AcmeException, IOException {

        LOGGER.info("Acme challenge request !!!! for uri: {}",uri);

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



