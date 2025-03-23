package com.scaleguard.server.application;
import com.fasterxml.jackson.databind.JsonNode;
import com.scaleguard.server.dns.DNSAddressBook;
import com.scaleguard.server.http.reverse.RequestRoute;
import com.scaleguard.server.http.reverse.RequestRoutingResponse;
import com.scaleguard.server.system.SystemManager;
import com.scaleguard.utils.JSON;

public class AppRoute implements RequestRoute {
    @Override
    public boolean isAuthNeeded() {
        return true;
    }

    @Override
    public RequestRoutingResponse handle(String method, String uri, String body) throws Exception {
        if(method.equalsIgnoreCase("get")){
           return RequestRoutingResponse.succes(JSON.toString(Application.get().values()));
        }

        if(method.equalsIgnoreCase("delete")){
            String[] tuples = uri.split("/");
            String id =!tuples[tuples.length-1].equalsIgnoreCase("apps")?tuples[tuples.length-1]:null;
            Application.remove(id);
            return RequestRoutingResponse.succes("{}");
        }else if(method.equalsIgnoreCase("post")){
            JsonNode node = JSON.parse(body);
            String name =node.has("name")?node.get("name").asText():null;
            String description =node.has("description")? node.get("description").asText():null;

            Application.WrappeApplicationRecord ar= Application.add(name,description);
            if(ar!=null) {
                return RequestRoutingResponse.succes(SystemManager.getMapper().writeValueAsString(ar));
            }else{
                return RequestRoutingResponse.response(500,"{\"message\":\"failed to save app\"}");
            }
        }else{
            return RequestRoutingResponse.response(405,"{}");
        }
    }
}
