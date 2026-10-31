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

    static String defaultDomain(List<DNSAddressBook.WrappedDNSRecord> records) {
        for (String type : new String[]{"zone", "base"}) {
            String best = null;
            for (DNSAddressBook.WrappedDNSRecord r : records) {
                if (r.getType() != null && r.getType().equalsIgnoreCase(type) && r.getName() != null && (best == null || r.getName().compareTo(best) < 0)) best = r.getName();
            }
            if (best != null) return best;
        }
        return null;
    }

    private static final java.util.regex.Pattern DOMAIN = java.util.regex.Pattern.compile("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+");

    /**
     * The domain tunnels are published under (app.&lt;domain&gt;): what the client asked for ({@code baseDNS}), else the
     * {@code scaleguard.tunnel.domain} setting, else a served domain ("zone" record), else a "base" record; null = the server's own host name.
     */
    static String chooseBase(String requested, String configured, String baseRecordName) {
        for (String candidate : new String[]{requested, configured}) {
            if (candidate == null || candidate.isBlank()) continue;
            String d = candidate.trim().toLowerCase();
            if (d.endsWith(".")) d = d.substring(0, d.length() - 1);
            if (!DOMAIN.matcher(d).matches()) throw new IllegalArgumentException("invalid tunnel domain: " + candidate);
            return d;
        }
        if (baseRecordName == null) return null;
        return baseRecordName.endsWith(".") ? baseRecordName.substring(0, baseRecordName.length() - 1) : baseRecordName;
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
            // default domain: the first domain this server is the authority for ("Domains served" in the DNS page), else a base record
            String recordName = defaultDomain(DNSAddressBook.get().entrySet().stream().flatMap(s->s.getValue().stream()).collect(Collectors.toList()));
            String baseName;
            try {
                baseName = chooseBase(baseDNSInput, com.scaleguard.server.http.utils.AppProperties.get("scaleguard.tunnel.domain"), recordName);
            } catch (IllegalArgumentException e) {
                return RequestRoutingResponse.response(400, "{\"message\":\"" + e.getMessage() + "\"}");
            }
            TunnelBook.TunnelRecord tr = TunnelBook.getOrCreate(name,port,baseName,host);
            if(TunnelBook.configureSystem(tr)!=null) {
                // tell the client where to open the SSH tunnel: this server's host (as the client reached it) and the real SSH port
                com.fasterxml.jackson.databind.node.ObjectNode out = SystemManager.getMapper().valueToTree(tr);
                try {
                    if (host != null) out.put("sshHost", new java.net.URL(host).getHost());
                } catch (java.net.MalformedURLException ignored) {
                    // client falls back to the server address it used
                }
                out.put("sshPort", com.scaleguard.server.ssh.SshServerWithTunnelListener.port());
                return RequestRoutingResponse.succes(out.toString());
            }else{
                return RequestRoutingResponse.response(500,"{}");
            }
        }else{
            return RequestRoutingResponse.response(405,"{}");
        }
    }
}
