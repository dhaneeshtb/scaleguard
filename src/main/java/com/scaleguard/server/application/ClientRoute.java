

package com.scaleguard.server.application;
import com.fasterxml.jackson.databind.JsonNode;
import com.scaleguard.server.db.ClientInfoEntry;
import com.scaleguard.server.http.reverse.RequestRoute;
import com.scaleguard.server.http.reverse.RequestRoutingResponse;
import com.scaleguard.server.system.SystemManager;
import com.scaleguard.utils.JSON;

import java.util.UUID;

public class ClientRoute implements RequestRoute {
    @Override
    public boolean isAuthNeeded() {
        return true;
    }

    @Override
    public RequestRoutingResponse handle(String method, String uri, String body) throws Exception {
        if(method.equalsIgnoreCase("get")){
           return RequestRoutingResponse.succes(JSON.toString(ClientInfo.get().values()));
        }

        if(method.equalsIgnoreCase("delete")){
            String[] tuples = uri.split("/");
            String id =!tuples[tuples.length-1].equalsIgnoreCase("clients")?tuples[tuples.length-1]:null;
            ClientInfo.remove(id);
            return RequestRoutingResponse.succes("{}");
        }else if(method.equalsIgnoreCase("post")){
            JsonNode node = JSON.parse(body);
            String name =node.has("name")?node.get("name").asText():null;
            String description =node.has("description")? node.get("description").asText():null;
            String clientid =node.has("clientid")?node.get("clientid").asText():null;
            String appid =node.has("appid")?node.get("appid").asText():null;
            String clientsecret =node.has("clientsecret")?node.get("clientsecret").asText():null;
            String id =node.has("id")?node.get("id").asText():null;
            long expiry =node.has("expiry")?node.get("expiry").asLong():null;

            Application.WrappeApplicationRecord ar=Application.getAppId(appid);
            if(ar!=null) {
               ClientInfo.WrappeClientInfoRecord cle= ClientInfo.add(name, description, appid, clientid, clientsecret, expiry, id != null ? id : UUID.randomUUID().toString(), true);
               return RequestRoutingResponse.succes(SystemManager.getMapper().writeValueAsString(cle));
            }else {
                return RequestRoutingResponse.response(404,"{\"message\":\"app not found\"}");
            }
        }else{
            return RequestRoutingResponse.response(405,"{}");
        }
    }
}
