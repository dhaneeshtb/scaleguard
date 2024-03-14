package com.scaleguard.server.system;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.scaleguard.server.db.SystemProperty;
import com.scaleguard.server.db.SystemPropertyDB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

public class SystemManager {
    private static final Logger LOG = LoggerFactory.getLogger(SystemManager.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private static List<SystemProperty> properties = null;

    public static boolean isSystemMapped(String hostName) throws UnknownHostException, SocketException {

        InetAddress addr = InetAddress.getByName(hostName);
        String host = addr.getHostAddress();
        return  readNetworks().contains(host);
    }

    public static List<String> readNetworks() throws SocketException {
        List<NetworkInterface> nil = NetworkInterface.networkInterfaces().collect(Collectors.toList());
        ArrayList<String> node =new ArrayList();
        for(NetworkInterface ni:nil) {
            ni.getInetAddresses().asIterator().forEachRemaining(i->{
                String ha =i.getHostAddress();
                if(!ha.contains(":") && !i.isMCGlobal()) {
                    node.add(i.getHostAddress());
                }
            });
        }
        return node;
    }
    public ArrayNode systemProperties() throws Exception {

                properties =   SystemPropertyDB.getInstance().readAll();

      return   mapper.valueToTree(properties);
    }

    public static Map<String,SystemProperty> readPropertyMap() throws Exception {
       return SystemPropertyDB.getInstance().readAll().stream().collect(Collectors.toMap(SystemProperty::getName, Function.identity()));
    }

    public static void main(String[] args) {
        try {
            System.out.println(isSystemMapped("google.com"));
            //System.out.println(new SystemManager().readNetworks());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public List<SystemProperty> createProperty(List<SystemProperty> property) throws Exception {
        List<SystemProperty> properties =  property.stream().map(this::sanitize).collect(Collectors.toList());
        List<SystemProperty> uproperties= properties.stream().filter(s->s.getId()!=null).collect(Collectors.toList());
        List<SystemProperty> cproperties= properties.stream().filter(s->s.getId()==null).collect(Collectors.toList());
        SystemPropertyDB.getInstance().edit(uproperties.stream().map(this::sanitize).collect(Collectors.toList()));
        SystemPropertyDB.getInstance().create(cproperties.stream().map(this::sanitize).collect(Collectors.toList()));
        return property;
    }
    public SystemProperty sanitize(SystemProperty property)  {
        if(property.getId()!=null){
            property.setMts(property.getMts());
            property.setValue(property.getValue());
            property.setGroupname(property.getGroupname());
            return property;
        }else{
            property.setId(UUID.randomUUID().toString());
            return property;
        }

    }
}
