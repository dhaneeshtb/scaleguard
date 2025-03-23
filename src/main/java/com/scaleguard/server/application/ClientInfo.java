package com.scaleguard.server.application;

import com.scaleguard.exceptions.GenericServerProcessingException;
import com.scaleguard.server.db.ApplicationEntriesDB;
import com.scaleguard.server.db.ClientInfoEntriesDB;
import com.scaleguard.server.db.ClientInfoEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class ClientInfo {

    private ClientInfo() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientInfo.class);
    private static Map<String, WrappeClientInfoRecord> clientInfoMap = new ConcurrentHashMap<>();

    static {
        load();
    }

    public static class WrappeClientInfoRecord {
        private String id;
        private String name;
        private String description;
        private String appid;
        private String clientid;

        private String clientsecret;

        public String getClientsecret() {
            return clientsecret;
        }

        public void setClientsecret(String clientsecret) {
            this.clientsecret = clientsecret;
        }

        public long getExpiry() {
            return expiry;
        }

        public void setExpiry(long expiry) {
            this.expiry = expiry;
        }

        private long expiry;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getAppid() {
            return appid;
        }

        public void setAppid(String appid) {
            this.appid = appid;
        }

        public String getClientid() {
            return clientid;
        }

        public void setClientid(String clientid) {
            this.clientid = clientid;
        }

        WrappeClientInfoRecord(String name, String description, String appid, String clientid, String clientsecret, long expiry, String id) {
            this.name = name;
            this.description = description;
            this.appid = appid;
            this.clientid = clientid;
            this.clientsecret = clientsecret;
            this.expiry = expiry;
            this.id = id!=null?id: UUID.randomUUID().toString();
        }
    }


    public static WrappeClientInfoRecord add(String name, String description, String appid, String clientid, String clientsecret, long expiry, String id, boolean save) {
        LOGGER.info("{} {} {} {} {} {}", name, clientid, id, save);
        WrappeClientInfoRecord appRecord = clientInfoMap.computeIfAbsent(id, k -> new WrappeClientInfoRecord(name, description, appid, clientid, clientsecret, expiry, id));
        if (save) {
            save(appRecord);
            Application.load();
        }
        return appRecord;
    }

    public static Map<String, WrappeClientInfoRecord> get() {
        return clientInfoMap;
    }

    private static void load() {
        try {
            LOGGER.info("loading entries from db");
            ClientInfoEntriesDB.getInstance().readAll().forEach(r -> add(r.getName(), r.getDescription(), r.getAppid(), r.getClientid(), r.getClientsecret(), r.getExpiry(), r.getId(), false));
        } catch (Exception e) {
            e.printStackTrace();
            LOGGER.info("error loading entries from db", e);
        }
    }

    private static ClientInfoEntry save(WrappeClientInfoRecord dnsRecord) {
        ClientInfoEntry entry =null;
        try {
            List<ClientInfoEntry> entries = ClientInfoEntriesDB.getInstance().readItems("id", dnsRecord.getId());
            if (!entries.isEmpty()) {
                 entry = entries.get(0);
                entry.setDescription(dnsRecord.getDescription());
                entry.setUts(System.currentTimeMillis());
                ClientInfoEntriesDB.getInstance().save(entry);
            } else {
                 entry = new ClientInfoEntry();
                entry.setId(dnsRecord.getId());
                entry.setName(dnsRecord.getName());
                entry.setDescription(dnsRecord.getDescription());
                entry.setAppid(dnsRecord.getAppid());
                entry.setClientid(dnsRecord.getClientid());
                entry.setClientsecret(dnsRecord.getClientsecret());
                entry.setExpiry(dnsRecord.getExpiry());
                entry.setMts(System.currentTimeMillis());
                entry.setUts(System.currentTimeMillis());
                ClientInfoEntriesDB.getInstance().create(entry);
            }

        } catch (Exception e) {
            throw new GenericServerProcessingException(e);
        }
        return entry;
    }

    public static void remove(String id) {
        List<WrappeClientInfoRecord> wrappeApplicationRecords = clientInfoMap.values().stream().filter(v -> v.id.equalsIgnoreCase(id)).collect(Collectors.toList());
        if (!wrappeApplicationRecords.isEmpty()) {
            WrappeClientInfoRecord rec = wrappeApplicationRecords.get(0);
            if (rec != null) {
                try {
                    ClientInfoEntriesDB.getInstance().delete(rec.getId());
                    clientInfoMap.remove(rec.getId());
                    Application.load();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }

    }


}
