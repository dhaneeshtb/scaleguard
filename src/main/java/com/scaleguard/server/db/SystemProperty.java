package com.scaleguard.server.db;

public class SystemProperty implements DBObject{

    private String id;
    private String name;
    private String groupname;

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

    public String getGroupname() {
        return groupname;
    }

    public void setGroupname(String groupname) {
        this.groupname = groupname;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
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

    private String value;

    private long mts;
    private long uts;

}
