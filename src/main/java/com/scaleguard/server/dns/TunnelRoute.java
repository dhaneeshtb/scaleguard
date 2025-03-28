package com.scaleguard.server.dns;
import com.fasterxml.jackson.databind.JsonNode;
import com.scaleguard.server.http.reverse.RequestRoute;
import com.scaleguard.server.http.reverse.RequestRoutingResponse;
import com.scaleguard.server.http.router.QuickSystemMapper;
import com.scaleguard.server.ssh.tunnel.TunnelBook;
import com.scaleguard.server.system.SystemManager;
import com.scaleguard.utils.JSON;

import java.util.List;
import java.util.stream.Collectors;

public class TunnelRoute implements RequestRoute {
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
            String id =!tuples[tuples.length-1].equalsIgnoreCase("tunnel")?tuples[tuples.length-1]:null;
            QuickSystemMapper.deleteSystem(id);
            return RequestRoutingResponse.succes("{}");
        }else if(method.equalsIgnoreCase("post")){
            JsonNode node = JSON.parse(body);
            String name =node.has("name")?node.get("name").asText():null;
            int port =node.has("port")?node.get("port").asInt():0;
            String host =node.has("host")?node.get("host").asText():null;
            String baseDNSInput =node.has("baseDNS")?node.get("baseDNS").asText():null;
            List<DNSAddressBook.WrappedDNSRecord> dnsrecords = DNSAddressBook.get().entrySet().stream().flatMap(s->s.getValue().stream()).filter(y->y.getType().equalsIgnoreCase("base")).collect(Collectors.toList());
            String baseName=null;
            if(dnsrecords.size()>0){
                baseName=dnsrecords.get(0).getName();
                if(baseName.endsWith(".")){
                    baseName=baseName.substring(0,baseName.length()-1);
                }
            }
            TunnelBook.TunnelRecord tr = TunnelBook.getOrCreate(name,port,baseName,host);
            if(TunnelBook.configureSystem(tr)!=null) {
                return RequestRoutingResponse.succes(SystemManager.getMapper().writeValueAsString(tr));
            }else{
                return RequestRoutingResponse.response(500,"{}");
            }
        }else{
            return RequestRoutingResponse.response(405,"{}");
        }
    }
}
