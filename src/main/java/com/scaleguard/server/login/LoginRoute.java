package com.scaleguard.server.login;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scaleguard.server.http.auth.AuthUtils;
import com.scaleguard.server.http.reverse.RequestRoute;
import com.scaleguard.server.http.reverse.RequestRoutingResponse;

public class LoginRoute implements RequestRoute {
    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public boolean isAuthNeeded() {
        return false;
    }

    @Override
    public RequestRoutingResponse handle(String method, String uri, String body) throws Exception {
            String[] tuples = uri.split("/");
            if(method.equalsIgnoreCase("post")){
                JsonNode node = mapper.readTree(body);
                String token  = AuthUtils.login(node.get("username").asText(),node.get("password").asText());
                return RequestRoutingResponse.succes("{\"token\":\""+token+"\"}");
            }else{
                throw new RuntimeException("not implemented");
            }
    }
}
