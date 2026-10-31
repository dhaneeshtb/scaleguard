package com.scaleguard.server.functions;

import com.scaleguard.server.db.DBObject;

/** Row of the replicated cloud_functions table; the definition itself is JSON in {@code payload}. */
public class CloudFunction implements DBObject {
    private String id;
    private String name;
    private String payload;
    private long uts;
    private long mts;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public long getUts() { return uts; }
    public void setUts(long uts) { this.uts = uts; }
    public long getMts() { return mts; }
    public void setMts(long mts) { this.mts = mts; }
}
