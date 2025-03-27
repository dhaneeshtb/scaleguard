package com.scaleguard.server.http.router;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.scaleguard.server.db.DBModelSystem;
import com.scaleguard.server.db.HostGroupsDB;
import com.scaleguard.server.db.SourceSystemDB;
import com.scaleguard.server.db.TargetSystemDB;
import com.scaleguard.server.ssh.tunnel.TunnelBook;
import com.scaleguard.server.system.SystemManager;
import org.apache.sshd.common.session.Session;

import java.net.MalformedURLException;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class QuickSystemMapper {

    private static String getDigest(String original) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("MD5");
        md.update(original.getBytes());
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

        public static void deleteSystem(String name){
            try {
                TunnelBook.remove(name);

                List<DBModelSystem> ss=  SourceSystemDB.getInstance().readItems("name",name);
                if(ss.size()>0){
                    SourceSystem s = SystemManager.getMapper().readValue(ss.get(0).getPayload(),SourceSystem.class);
                    if(s.getTarget()!=null){
                        ss= TargetSystemDB.getInstance().readItems("groupId",s.getTarget());
                        if(ss.size()>0){
                            TargetSystem t = SystemManager.getMapper().readValue(ss.get(0).getPayload(),TargetSystem.class);
                            String hg = t.getHostGroupId();
                            if(hg!=null){
                                ss = HostGroupsDB.getInstance().readItems("groupId",hg);
                                ss.forEach(h-> {
                                    try {
                                        HostGroupsDB.getInstance().delete(h.getId());
                                        RouteTable.getInstance().deleteHost(h.getId());

                                    } catch (Exception e) {
                                        throw new RuntimeException(e);
                                    }
                                });
                            }
                            TargetSystemDB.getInstance().delete(t.getId());
                            RouteTable.getInstance().deleteTarget(t.getId());

                        }

                        SourceSystemDB.getInstance().delete(s.getId());
                        RouteTable.getInstance().deleteSource(s.getId());
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        public static JsonNode mapSystem(JsonNode node) throws MalformedURLException, NoSuchAlgorithmException {
        String sourceURL = node.get("sourceURL").asText();
        String systemName = node.has("name") ? node.get("name").asText() : getDigest(sourceURL);
        boolean isTunnel = node.has("tunnel")? node.get("tunnel").asBoolean():false;
        ArrayNode targets = (ArrayNode) node.get("targets");
        String gid =systemName!=null?systemName: generateShortUUID();
        String hostGroupId = "h-" + gid;
        String targetGroupId = "t-" + gid;
        String sourceGroupId = "s-" + gid;

        List<HostGroup> hostGroups = new ArrayList<>();
        for (JsonNode target : targets) {
            hostGroups.add(createHost(target.asText(), hostGroupId));
        }

        TargetSystem targetSystem = createTarget(hostGroups.get(0), targets.get(0).asText(), targetGroupId);
        SourceSystem sourceSystem = createSource(sourceURL, targetSystem, sourceGroupId);
        sourceSystem.setTunnel(isTunnel);
        sourceSystem.setName(systemName);

        ObjectNode outNode = SystemManager.getMapper().createObjectNode();
        try {
            List<DBModelSystem> existingSystems = SourceSystemDB.getInstance().readItems("name", systemName);
            if (!existingSystems.isEmpty()) {
                throw new RuntimeException(systemName + " already exists");
            }
            boolean isMapped = true; // SystemManager.isSystemMapped(sourceSystem.getHost());
            if (isMapped) {
                hostGroups.forEach(ConfigManager::update);
                ConfigManager.update(targetSystem);
                ConfigManager.update(sourceSystem);
                outNode.set("hostGroups",SystemManager.getMapper().valueToTree(hostGroups));
                outNode.set("targetSystem",SystemManager.getMapper().valueToTree(targetSystem));
                outNode.set("sourceSystem",SystemManager.getMapper().valueToTree(sourceSystem));
            } else {
                throw new RuntimeException(sourceSystem.getHost() + " is not mapped to scaleguard");
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return outNode;
    }

    private static String generateShortUUID() {
        char[] chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890".toCharArray();
        Random random = new Random(System.currentTimeMillis());
        char[] id = new char[8];
        for (int i = 0; i < 8; i++) {
            id[i] = chars[random.nextInt(chars.length)];
        }
        return new String(id);
    }

    private static HostGroup createHost(String targetURL, String hostGroupId) throws MalformedURLException {
        URL url = new URL(targetURL);
        String protocol = url.getProtocol();
        int port = url.getPort() > 0 ? url.getPort() : protocol.equalsIgnoreCase("https") ? 443 : 80;
        HostGroup hostGroup = new HostGroup();
        hostGroup.setPort(String.valueOf(port));
        hostGroup.setHost(url.getHost());
        hostGroup.setPriority(10);
        hostGroup.setGroupId(hostGroupId);
        hostGroup.setScheme(protocol);
        hostGroup.setActive(true);
        hostGroup.setType("active");
        return hostGroup;
    }

    private static TargetSystem createTarget(HostGroup hostGroup, String targetURL, String groupId) throws MalformedURLException {
        URL url = new URL(targetURL);
        TargetSystem targetSystem = new TargetSystem();
        targetSystem.setPort(hostGroup.getPort());
        targetSystem.setHost("");
        targetSystem.setBasePath(url.getPath().isEmpty() ? "/" : url.getPath());
        targetSystem.setGroupId(groupId);
        targetSystem.setHostGroupId(hostGroup.getGroupId());
        targetSystem.setScheme(hostGroup.getScheme());
        return targetSystem;
    }

    private static SourceSystem createSource(String sourceURL, TargetSystem targetSystem, String sourceGroupId) throws MalformedURLException {
        URL url = new URL(sourceURL);
        String protocol = url.getProtocol();
        int port = url.getPort() > 0 ? url.getPort() : protocol.equalsIgnoreCase("https") ? 443 : 80;
        SourceSystem sourceSystem = new SourceSystem();
        sourceSystem.setPort(String.valueOf(port));
        sourceSystem.setHost(url.getHost());
        sourceSystem.setBasePath(url.getPath().isEmpty() ? "/" : url.getPath());
        sourceSystem.setTarget(targetSystem.getGroupId());
        sourceSystem.setGroupId(sourceGroupId);
        sourceSystem.setScheme(protocol);
        sourceSystem.setAutoProcure(protocol.equalsIgnoreCase("https"));
        return sourceSystem;
    }

    public static void main(String[] args) {
        ObjectNode request = SystemManager.getMapper().createObjectNode();
        request.put("sourceURL", "https://mycrm.app.unkloud.io");
        ArrayNode targets = SystemManager.getMapper().createArrayNode();
        targets.add("http://localhost:8099");
        request.put("targets", targets);
        try {
            mapSystem(request);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}