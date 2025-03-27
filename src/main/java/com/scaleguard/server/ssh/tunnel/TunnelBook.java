package com.scaleguard.server.ssh.tunnel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scaleguard.server.http.router.QuickSystemMapper;
import com.scaleguard.server.system.SystemManager;
import com.scaleguard.utils.UIDUtils;
import org.apache.sshd.common.session.Session;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TunnelBook {

    public static void removeSession(String name) {
         sessionMap.remove(name);

    }

    public static class TunnelRecord{
        private String name;
        private int port;
        private String localIp;
        private String username;
        private String password;

        private int systemPort;

        public int getSystemPort() {
            return systemPort;
        }

        public void setSystemPort(int systemPort) {
            this.systemPort = systemPort;
        }

        private int ttl;
        private String fqdn;

        private String edgefqdn;

        public String getEdgefqdn() {
            return edgefqdn;
        }

        public void setEdgefqdn(String edgefqdn) {
            this.edgefqdn = edgefqdn;
        }

        private String lfqdn;

        public String getLfqdn() {
            return lfqdn;
        }

        public void setLfqdn(String lfqdn) {
            this.lfqdn = lfqdn;
        }

        public String getFqdn() {
            return fqdn;
        }

        public void setFqdn(String fqdn) {
            this.fqdn = fqdn;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getLocalIp() {
            return localIp;
        }

        public void setLocalIp(String localIp) {
            this.localIp = localIp;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public int getTtl() {
            return ttl;
        }

        public void setTtl(int ttl) {
            this.ttl = ttl;
        }
    }
    private static Map<String, TunnelRecord> tunnelbookMap=new ConcurrentHashMap<>();

    private static Map<String, TunnelRecord> userpassMap=new ConcurrentHashMap<>();

    private static Map<String, Session> sessionMap=new ConcurrentHashMap<>();

    public static void putSession(String name,Session session){
        sessionMap.put(name,session);
    }

    public static void putSession(String user,String password,Session session){
        TunnelRecord tr = userpassMap.get(user+":"+password);
        if(tr!=null) {
            sessionMap.put(tr.getName(), session);
        }
    }

    public static Session getSession(String name){
        return sessionMap.get(name);
    }

    public static TunnelRecord get(String appName) {
        return tunnelbookMap.get(appName);
    }

    public static void remove(String appName) {
        TunnelRecord tr=tunnelbookMap.remove(appName);
        if(tr!=null) {
            userpassMap.remove(tr.getUsername() + ":" + tr.getPassword());
        }
        Session s= sessionMap.get(appName);
        if(s!=null){
            try {
                s.close();
            } catch (IOException e) {
               e.printStackTrace();
            }
        }
    }

    public static TunnelRecord getRecordByUserPassword(String user,String password) {
       return userpassMap.get(user+":"+password);
    }

    public static TunnelRecord getOrCreate(String appName,int port,String baseName,String host){
        String fqdn =null;
        if(baseName!=null){
            fqdn = "https://"+appName+"."+baseName;
        }else{
            try {
                URL hurl = new URL(host);
                fqdn = hurl.getProtocol()+"://"+appName+"."+hurl.getHost();
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }
        }
        String finalFqdn = fqdn;
        int sp = tunnelbookMap.values().stream().mapToInt(TunnelRecord::getSystemPort).max().orElse(50000)+1;
        String lfqdn = "http://localhost:"+sp;



        TunnelRecord tx= tunnelbookMap.computeIfAbsent(appName, a->{
            TunnelRecord tr = new TunnelRecord();
            tr.setName(appName);
            tr.setPort(port);
            tr.setSystemPort(sp);
            String username = UIDUtils.create(5);
            String password = UIDUtils.create(5);
            tr.setUsername(username);
            tr.setPassword(password);
            tr.setTtl(600);
            tr.setEdgefqdn("localhost:"+port);
            tr.setLfqdn(lfqdn);
            tr.setFqdn(finalFqdn);
            return tr;
        });

        userpassMap.put(tx.getUsername()+":"+tx.getPassword(),tx);

        return tx;


    }
    public static JsonNode configureSystem(TunnelRecord tr){
        ObjectNode request = SystemManager.getMapper().createObjectNode();
        request.put("sourceURL", tr.getFqdn());
        request.put("name", tr.getName());
        request.put("tunnel", true);


        ArrayNode targets = SystemManager.getMapper().createArrayNode();
        targets.add(tr.getLfqdn());
        request.put("targets", targets);
        try {
            return QuickSystemMapper.mapSystem(request);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}
