package com.scaleguard.server.asyncengines;
import com.fasterxml.jackson.databind.JsonNode;
import com.scaleguard.server.application.AsyncEngines;
import com.scaleguard.server.dns.DNSAddressBook;
import com.scaleguard.server.http.reverse.RequestRoute;
import com.scaleguard.server.http.reverse.RequestRoutingResponse;
import com.scaleguard.server.system.SystemManager;
import com.scaleguard.utils.JSON;

public class AsyncEngineRoute implements RequestRoute {
    @Override
    public boolean isAuthNeeded() {
        return true;
    }

    @Override
    public RequestRoutingResponse handle(String method, String uri, String body) throws Exception {
        if(method.equalsIgnoreCase("get")){
           return RequestRoutingResponse.succes(JSON.toString(AsyncEngines.get()));
        }

        if(method.equalsIgnoreCase("delete")){

            String[] tuples = uri.split("/");
            String id =!tuples[tuples.length-1].equalsIgnoreCase("dns")?tuples[tuples.length-1]:null;
            if(id!=null){
                DNSAddressBook.remove(id);
            }
            return RequestRoutingResponse.succes("{}");
        }else if(method.equalsIgnoreCase("post")){
            JsonNode node = JSON.parse(body);
            String name =node.has("name")?node.get("name").asText():null;
            String type =node.has("type")? node.get("type").asText():null;
            String description = node.has("description")? node.get("description").asText():"";
            JsonNode payload = node.has("payload")? node.get("payload"):JSON.object();
            String id = node.has("id")? node.get("id").asText():null;
            AsyncEngines.WrappedAsyncEngineRecord ar = AsyncEngines.add(name,description,type,payload.toString(),id,true);
            return RequestRoutingResponse.succes(SystemManager.getMapper().writeValueAsString(ar));
        }else{
            return RequestRoutingResponse.response(405,"{}");
        }
    }
}
