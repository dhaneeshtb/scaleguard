package com.scaleguard.server.application;

import com.scaleguard.exceptions.GenericServerProcessingException;
import com.scaleguard.server.db.ApplicationEntriesDB;
import com.scaleguard.server.db.ApplicationEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class Application {
    private Application(){}
    private static final Logger LOGGER
            = LoggerFactory.getLogger(Application.class);
    private static Map<String, WrappeApplicationRecord> applicationsMap =new ConcurrentHashMap<>();
    static {
        load();
    }
    public static class WrappeApplicationRecord{
        private String name;
        private String description;

        private List<ClientInfo.WrappeClientInfoRecord> clients = new ArrayList<>();

        public List<ClientInfo.WrappeClientInfoRecord> getClients() {
            return clients;
        }

        public void setClients(List<ClientInfo.WrappeClientInfoRecord> clients) {
            this.clients = clients;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        private String id;

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

        WrappeApplicationRecord(String name, String description, String id){
            this.description=description;
            this.name=name;
            this.id=id;
        }
    }

    public static WrappeApplicationRecord add(String name, String description){
       return add(name,description,UUID.randomUUID().toString(),true);
    }
    private static WrappeApplicationRecord add(String name, String description, String id, boolean save){
        LOGGER.info("{} {} {} {} {} {}",name,description,id,save);
        WrappeApplicationRecord appRecord = applicationsMap.computeIfAbsent(name, k->new WrappeApplicationRecord(name,description,id));
        if(save) {
            save(appRecord);
        }
        return appRecord;
    }

    public static Map<String, WrappeApplicationRecord> get(){
        return applicationsMap;
    }

    public static WrappeApplicationRecord get(String appname){
        return applicationsMap.get(appname);
    }

    public static WrappeApplicationRecord getAppId(String appid){
        return applicationsMap.values().stream().filter(r->r.id.equalsIgnoreCase(appid)).findFirst().orElse(null);
    }

    public static void load(){
        try {
            LOGGER.info("loading entries from db");
            applicationsMap.clear();
            ApplicationEntriesDB.getInstance().readAll().forEach(r -> add(r.getName(), r.getDescription(), r.getId(), false));
            loadClients();
        }catch (Exception e){
            e.printStackTrace();
            LOGGER.info("error loading entries from db",e);
        }
    }

    public static void loadClients(){
        try {
            Map<String,WrappeApplicationRecord> appIdMap = new HashMap<>();
            applicationsMap.forEach((k,v)->appIdMap.put(v.id,v));
            LOGGER.info("loading entries from db");
            ClientInfo.get().values().forEach(c->{
                WrappeApplicationRecord ar=  appIdMap.get(c.getAppid());
                ar.getClients().add(c);
            });
        }catch (Exception e){
            e.printStackTrace();
            LOGGER.info("error loading entries from db",e);
        }
    }

    private static void save(WrappeApplicationRecord dnsRecord){
        try {
            List<ApplicationEntry> entries = ApplicationEntriesDB.getInstance().readItems("name", dnsRecord.getName());
            entries = entries.stream().filter(e ->e.getName().equalsIgnoreCase(dnsRecord.getName())).collect(Collectors.toList());
            if (!entries.isEmpty()) {
                ApplicationEntry entry = entries.get(0);
                entry.setDescription(dnsRecord.getDescription());
                entry.setUts(       System.currentTimeMillis());
                ApplicationEntriesDB.getInstance().save(entry);
            }else{
                ApplicationEntry entry = new ApplicationEntry();
                entry.setId(dnsRecord.getId());
                entry.setName(dnsRecord.getName());
                entry.setMts(System.currentTimeMillis());
                entry.setUts(System.currentTimeMillis());
                entry.setDescription(dnsRecord.getDescription());
                ApplicationEntriesDB.getInstance().create(entry);
            }

        }catch (Exception e){
            throw new GenericServerProcessingException(e);
        }
    }

    public static void remove(String id){
       List<WrappeApplicationRecord> wrappeApplicationRecords=  applicationsMap.values().stream().filter(v->v.id.equalsIgnoreCase(id)).collect(Collectors.toList());
        if(!wrappeApplicationRecords.isEmpty()){
            WrappeApplicationRecord rec =  wrappeApplicationRecords.get(0);
            if(rec!=null) {
                try {
                    ApplicationEntriesDB.getInstance().delete(rec.getId());
                    applicationsMap.remove(rec.getName());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }

    }
}
