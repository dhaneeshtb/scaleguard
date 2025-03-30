package com.scaleguard.server.db;

public class AsyncEngineEntry implements DBObject{

    private String id;
    private String name;
    private String description;

    private String payload;

    private String type;

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

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

    public long getMts() {
        return mts;
    }

    public void setMts(long mts) {
        this.mts = mts;
    }

    public long getUts() {
        return uts;
    }

    public void setUts(long uts) {
        this.uts = uts;
    }


    //Creation or Management Timestamp
    private long mts;

    //Updation TImestamp
    private long uts;

}
