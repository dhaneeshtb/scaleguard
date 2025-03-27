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
    private static final ObjectMapper mapper = new ObjectMapper();

    private static List<SystemProperty> properties = null;

    private static final Logger LOGGER
            = LoggerFactory.getLogger(SystemManager.class);


    public static ObjectMapper getMapper(){
        return mapper;
    }

    public static boolean isSystemMapped(String hostName) throws UnknownHostException, SocketException {
        try {
            InetAddress addr = InetAddress.getByName(hostName);
            String host = addr.getHostAddress();
            LOGGER.info("hostname {} resolved address {}", hostName, host);
            return readNetworks().contains(host);
        }catch (Exception e){
            e.printStackTrace();
            LOGGER.info("hostname failed to resolve {}", hostName);
            return false;
        }
    }

    public static String getAddressIfMapped(String hostName) throws UnknownHostException, SocketException {
        InetAddress addr = InetAddress.getByName(hostName);
        String host = addr.getHostAddress();
        LOGGER.info("hostname {} resolved address {}",hostName,host);
        if(readNetworks().contains(host)){
            return host;
        }else{
            return null;
        }
    }

    private static List<String> networks;
    public static List<String> readNetworksCached() {
        if(networks==null||networks.isEmpty()){
            synchronized (SystemManager.class) {
                if(networks==null||networks.isEmpty()) {
                    networks = readNetworks();
                }
            }
        }
        return networks;
    }

    public static List<String> readNetworks() {
        try {
            List<NetworkInterface> interfaces = NetworkInterface.networkInterfaces().collect(Collectors.toList());
            List<String> publicIps = new ArrayList<>();

            for (NetworkInterface ni : interfaces) {
                ni.getInetAddresses().asIterator().forEachRemaining(inetAddress -> {
                    String ip = inetAddress.getHostAddress();
                    if (isPublicIp(inetAddress)) {
                        publicIps.add(ip);
                    }
                });
            }
            return publicIps;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return List.of();
    }

    private static boolean isPublicIp(InetAddress address) {
        if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()) {
            return false;  // Exclude local, private, and link-local IPs
        }
        return true;  // Accept only public addresses
    }
    public ArrayNode systemProperties() throws Exception {

                properties =   SystemPropertyDB.getInstance().readAll();

      return   mapper.valueToTree(properties);
    }

    public static Map<String,SystemProperty> readPropertyMap() throws Exception {
       return SystemPropertyDB.getInstance().readAll().stream().collect(Collectors.toMap(SystemProperty::getName, Function.identity()));
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
