package com.scaleguard.server.dns;
import com.fasterxml.jackson.databind.JsonNode;
import com.scaleguard.server.http.reverse.RequestRoute;
import com.scaleguard.server.http.reverse.RequestRoutingResponse;
import com.scaleguard.utils.JSON;

public class DNSRoute implements RequestRoute {
    @Override
    public boolean isAuthNeeded() {
        return true;
    }

    @Override
    public RequestRoutingResponse handle(String method, String uri, String body) throws Exception {
        JsonNode node = JSON.parse(body);
        String name = node.get("name").asText();
        String ip = node.get("ip").asText();
        if(method.equalsIgnoreCase("delete")){
            DNSAddressBook.remove(name,ip);
            return RequestRoutingResponse.succes("{}");
        }else if(method.equalsIgnoreCase("post")){
            DNSAddressBook.add(name,ip);
            return RequestRoutingResponse.succes(node.toString());
        }else{
            return RequestRoutingResponse.response(405,"{}");
        }
    }
}
