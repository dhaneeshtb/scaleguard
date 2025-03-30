package com.scaleguard.server.application;
import com.fasterxml.jackson.databind.JsonNode;
import com.scaleguard.exceptions.GenericServerProcessingException;
import com.scaleguard.server.db.AsyncEngineEntriesDB;
import com.scaleguard.server.db.AsyncEngineEntry;
import com.scaleguard.server.system.SystemManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class AsyncEngines {
    private AsyncEngines() {
    }
    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncEngines.class);
    private static final Map<String, WrappedAsyncEngineRecord> asyncEnginesMap = new ConcurrentHashMap<>();

    static {
        load();
    }

    public static class WrappedAsyncEngineRecord {
        private String id;
        private String name;
        private String description;
        private JsonNode payload;
        private String type;

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

        public JsonNode getPayload() {
            return payload;
        }

        public void setPayload(JsonNode payload) {
            this.payload = payload;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        WrappedAsyncEngineRecord(String name, String description, String type, String payload, String id) {
            this.name = name;
            this.description = description;
            this.type = type;
            try {
                this.payload=payload!=null? SystemManager.getMapper().readTree(payload):null;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            this.id = id!=null?id: UUID.randomUUID().toString();
        }
    }


    public static WrappedAsyncEngineRecord add(String name, String description, String type,String payload,  String id, boolean save) {
        LOGGER.info("{} {} {} {} {} {}", name,description, type,payload, id, save);
        WrappedAsyncEngineRecord appRecord = asyncEnginesMap.computeIfAbsent(id, k -> new WrappedAsyncEngineRecord(name, description, type, payload, id));
        if (save) {
            save(appRecord);
            load();
        }
        return appRecord;
    }

    public static Map<String, WrappedAsyncEngineRecord> get() {
        return asyncEnginesMap;
    }

    private static void load() {
        try {
            LOGGER.info("loading entries from db");
            AsyncEngineEntriesDB.getInstance().readAll().forEach(r -> add(r.getName(), r.getDescription(), r.getType(), r.getPayload(),  r.getId(), false));
        } catch (Exception e) {
            e.printStackTrace();
            LOGGER.info("error loading entries from db", e);
        }
    }

    private static AsyncEngineEntry save(WrappedAsyncEngineRecord engineRecord) {
        AsyncEngineEntry entry =null;
        try {
            List<AsyncEngineEntry> entries = AsyncEngineEntriesDB.getInstance().readItems("id", engineRecord.getId());
            if (!entries.isEmpty()) {
                 entry = entries.get(0);
                entry.setDescription(engineRecord.getDescription());
                if(engineRecord.getPayload()!=null) {
                    entry.setPayload(engineRecord.getPayload().toString());
                }
                entry.setType(engineRecord.getType());
                entry.setUts(System.currentTimeMillis());
                AsyncEngineEntriesDB.getInstance().save(entry);
            } else {
                entry = new AsyncEngineEntry();
                if(engineRecord.getPayload()!=null) {
                    entry.setPayload(engineRecord.getPayload().toString());
                }
                entry.setType(engineRecord.getType());

                entry.setId(engineRecord.getId());
                entry.setName(engineRecord.getName());
                entry.setDescription(engineRecord.getDescription());
                entry.setMts(System.currentTimeMillis());
                entry.setUts(System.currentTimeMillis());
                AsyncEngineEntriesDB.getInstance().create(entry);
            }

        } catch (Exception e) {
            throw new GenericServerProcessingException(e);
        }
        return entry;
    }

    public static void remove(String id) {
        List<WrappedAsyncEngineRecord> wrappeApplicationRecords = asyncEnginesMap.values().stream().filter(v -> v.id.equalsIgnoreCase(id)).collect(Collectors.toList());
        if (!wrappeApplicationRecords.isEmpty()) {
            WrappedAsyncEngineRecord rec = wrappeApplicationRecords.get(0);
            if (rec != null) {
                try {
                    AsyncEngineEntriesDB.getInstance().delete(rec.getId());
                    asyncEnginesMap.remove(rec.getId());
                    load();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }

    }


}
