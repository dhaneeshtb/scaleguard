package com.scaleguard.server.dns;
import com.fasterxml.jackson.databind.JsonNode;
import com.scaleguard.server.http.reverse.RequestRoute;
import com.scaleguard.server.http.reverse.RequestRoutingResponse;
import com.scaleguard.server.system.SystemManager;
import com.scaleguard.utils.JSON;

public class DNSRoute implements RequestRoute {
    @Override
    public boolean isAuthNeeded() {
        return true;
    }

    @Override
    public RequestRoutingResponse handle(String method, String uri, String body) throws Exception {
        if(method.equalsIgnoreCase("get")){
           return RequestRoutingResponse.succes(JSON.toString(DNSAddressBook.get()));
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
            String ip =node.has("ip")? node.get("ip").asText():null;
            String type = node.has("type")? node.get("type").asText():"record";
            if("base".equalsIgnoreCase(type) || (ip==null||ip.isEmpty())){
                ip ="base";
            }
            long ttl = node.has("ttl")? node.get("ttl").asLong():DNSAddressBook.DEFAULT_TTL;
            if(!name.endsWith(".")){
                name=name+".";
            }

            DNSAddressBook.add(name,ip,type,ttl);
            return RequestRoutingResponse.succes(node.toString());
        }else{
            return RequestRoutingResponse.response(405,"{}");
        }
    }
}
