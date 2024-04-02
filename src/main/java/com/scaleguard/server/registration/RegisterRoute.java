package com.scaleguard.server.registration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scaleguard.server.http.reverse.RequestRoute;
import com.scaleguard.server.http.reverse.RequestRoutingResponse;
import com.scaleguard.server.http.router.HostGroup;

public class RegisterRoute implements RequestRoute {
    private static final ObjectMapper mapper = new ObjectMapper();


    @Override
    public boolean isAuthNeeded() {
        return true;
    }

    @Override
    public RequestRoutingResponse handle(String method, String uri, String body) throws Exception {
        String[] tuples = uri.split("/");
        String action = tuples[tuples.length - 1];
        String tg = tuples[tuples.length - 2];
        if (method.equalsIgnoreCase("post")) {
            boolean isBind = "bind".equalsIgnoreCase(action);
            boolean unbind = "unbind".equalsIgnoreCase(action);
            HostGroup node = mapper.readValue(body, HostGroup.class);
            if(isBind) {
                DynamicRegistrar.register(tg, node, false);
                return RequestRoutingResponse.succes(body);
            }else if(unbind){
                DynamicRegistrar.unRegister(tg, node);
                return RequestRoutingResponse.succes(body);
            }else{
                return RequestRoutingResponse.response(405,"{\"message\":\"unsupported operation\"}");
            }
        } else {
            return RequestRoutingResponse.succes("{\"properties\":\"\"}");
        }
    }
}
