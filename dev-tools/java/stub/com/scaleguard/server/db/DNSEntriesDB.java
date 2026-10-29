package com.scaleguard.server.db;

import java.util.*;

/** Test stand-in: in-memory dns_entries table that counts calls. */
public class DNSEntriesDB {
    private static final DNSEntriesDB I = new DNSEntriesDB();
    public static DNSEntriesDB getInstance() { return I; }
    public final Map<String, DNSEntry> rows = new LinkedHashMap<>();
    public int creates, edits, deletes, reads;
    public synchronized List<DNSEntry> readAll() { reads++; return new ArrayList<>(rows.values()); }
    public synchronized void create(DBObject o) { DNSEntry e=(DNSEntry)o; creates++; rows.put(e.getId(), e); }
    public synchronized void edit(DBObject o) { DNSEntry e=(DNSEntry)o; edits++; rows.put(e.getId(), e); }
    public synchronized void delete(String id) { deletes++; rows.remove(id); }
}
