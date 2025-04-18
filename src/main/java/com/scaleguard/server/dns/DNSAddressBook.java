package com.scaleguard.server.dns;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.scaleguard.exceptions.GenericServerProcessingException;
import com.scaleguard.server.db.DNSEntry;
import com.scaleguard.server.db.DNSEntriesDB;
import com.scaleguard.server.system.SystemManager;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.dns.*;
import io.netty.util.NetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class DNSAddressBook {

    private DNSAddressBook(){}
    private static final Logger LOGGER
            = LoggerFactory.getLogger(DNSAddressBook.class);
    private static Map<String, List<WrappedDNSRecord>> dnsAddressMap=new ConcurrentHashMap<>();

    static {
        load();
    }
    public static class WrappedDNSRecord{
        private String name;
        private String ip;

        private boolean base;

        public boolean isBase() {
            return base;
        }

        public void setBase(boolean base) {
            this.base = base;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        private String id;

        private String type;

        public long getTtl() {
            return ttl;
        }

        public void setTtl(long ttl) {
            this.ttl = ttl;
        }

        private long ttl;

        public String getType() {
            return type;
        }

        public DefaultDnsRawRecord getRecord(String inName) {
            return  new DefaultDnsRawRecord(
                    inName!=null?inName:name,
                    DnsRecordType.A, ttl, Unpooled.wrappedBuffer(NetUtil.createByteArrayFromIpAddressString(ip)));
        }
        public DefaultDnsRawRecord getRecord(String inName,String type) {
            if(type.equalsIgnoreCase("txt")){
                ByteBuf content = createTxtRecord(ip);

                return new DefaultDnsRawRecord(
                        inName != null ? inName : name,
                        DnsRecordType.TXT, ttl, content );
            }else {
                return new DefaultDnsRawRecord(
                        inName != null ? inName : name,
                        DnsRecordType.A, ttl, Unpooled.wrappedBuffer(NetUtil.createByteArrayFromIpAddressString(ip)));
            }
        }
        private ByteBuf createTxtRecord(String text) {
            ByteBuf buf = Unpooled.buffer();
            byte[] txtBytes = text.getBytes(StandardCharsets.UTF_8);
            buf.writeByte(txtBytes.length);  // TXT record length prefix
            buf.writeBytes(txtBytes);
            return buf;
        }
        public String getName() {
            return name;
        }
        public String getIp() {
            return ip;
        }
        WrappedDNSRecord(String name, String ip,String type,long ttl){
            this.ip=ip;
            this.name=name;
            this.type=type;
            this.ttl=ttl;
        }
    }
    public static final long DEFAULT_TTL=600l;

    public static void add(String name,String ip,String type,long ttl){
        add(name,ip,type,ttl,UUID.randomUUID().toString(),true);
    }
    private static void add(String name,String ip,String type,long ttl,String id,boolean save){
        LOGGER.info("{} {} {} {} {} {}",name,ip,type,ttl,id,save);
        List<WrappedDNSRecord> dnsList = dnsAddressMap.computeIfAbsent(name,k->new ArrayList<>());
        List<WrappedDNSRecord> filtered="base".equalsIgnoreCase(ip)?dnsList: dnsList.stream().filter(s->s.getIp().equalsIgnoreCase(ip)).collect(Collectors.toList());
        WrappedDNSRecord dnsRecord;
        if(filtered.isEmpty()){
            dnsRecord= new WrappedDNSRecord(name,ip,type,ttl);
            dnsRecord.setId(id);
            dnsList.add(dnsRecord);
        }else {
            dnsRecord= filtered.get(0);
            dnsRecord.setTtl(ttl);
            dnsRecord.type=type;
            dnsRecord.ip=ip;
        }
        if(save) {
            save(dnsRecord);
        }
    }

    public static Map<String, List<WrappedDNSRecord>> get(){
        return dnsAddressMap;
    }

    private static void load(){
        try {
            LOGGER.info("loading entries from db");
            DNSEntriesDB.getInstance().readAll().forEach(r -> add(r.getName(), r.getValue(), r.getType(), r.getTtl(), r.getId(), false));
        }catch (Exception e){
            e.printStackTrace();
            LOGGER.info("error loading entries from db",e);
        }
    }

    private static void save(WrappedDNSRecord dnsRecord){
        try {
            List<DNSEntry> entries = DNSEntriesDB.getInstance().readItems("name", dnsRecord.getName());
            entries = entries.stream().filter(e ->e.getValue().equalsIgnoreCase("base")|| e.getValue().equalsIgnoreCase(dnsRecord.getIp())).collect(Collectors.toList());
            if (!entries.isEmpty()) {
                DNSEntry entry = entries.get(0);
                entry.setTtl(dnsRecord.getTtl());
                entry.setType(dnsRecord.getType());
                entry.setValue(dnsRecord.getIp());
                DNSEntriesDB.getInstance().save(entry);
            }else{
                DNSEntry entry = new DNSEntry();
                entry.setId(dnsRecord.getId());
                entry.setName(dnsRecord.getName());
                entry.setTtl(dnsRecord.getTtl());
                entry.setValue(dnsRecord.getIp());
                entry.setType(dnsRecord.getType());
                DNSEntriesDB.getInstance().create(entry);
            }

        }catch (Exception e){
            throw new GenericServerProcessingException(e);
        }
    }

    public static void remove(String name,String ip){
        List<WrappedDNSRecord> dnsList = dnsAddressMap.computeIfAbsent(name,k->new ArrayList<>());
        Iterator<WrappedDNSRecord> iterator = dnsList.iterator();
        iterator.forEachRemaining(s->{
            if("base".equalsIgnoreCase(ip) || s.getIp().equalsIgnoreCase(ip)){
                iterator.remove();
                try {
                    DNSEntriesDB.getInstance().delete(s.id);
                } catch (Exception e) {
                    throw new GenericServerProcessingException(e);
                }
            }
        });
    }

    public static void remove(String id) throws Exception {
        for(Map.Entry<String,List<WrappedDNSRecord>> entry:dnsAddressMap.entrySet() ){
            List<WrappedDNSRecord> rec=   entry.getValue().stream().filter(s->!s.id.equalsIgnoreCase(id)).collect(Collectors.toList());
            entry.setValue(rec);
        }
        DNSEntriesDB.getInstance().delete(id);
    }
    public static boolean isEntryExist(String name){
        return baseMappedName(name)!=null;
    }
    public static DefaultDnsResponse get(String name,DnsQuery query,String type){
        String bName=baseMappedName(name);
        LOGGER.info("name=> {} mappedName=>{}",name,bName);
        if(bName!=null) {
            List<WrappedDNSRecord> dnsList = dnsAddressMap.computeIfAbsent(bName, k -> new ArrayList<>());
            try {
                LOGGER.info(SystemManager.getMapper().writeValueAsString(dnsList));
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
            return generateResponse(dnsList, query,type);
        }else{
            return null;
        }
    }

    private static String baseMappedName(String name){
        if(dnsAddressMap.containsKey(name)){
            return name;
        }else{
          List<String> sl=  dnsAddressMap.keySet().stream().filter(name::endsWith).collect(Collectors.toList());
          if(!sl.isEmpty()){
              return sl.get(0);
          }
        }
        return null;
    }
    private static Stream<WrappedDNSRecord> expand(WrappedDNSRecord ddr)  {
        if(ddr.getIp().equalsIgnoreCase("base")){
            return SystemManager.readNetworksCached().stream().map(n-> new WrappedDNSRecord(ddr.getName(), n,ddr.type,ddr.ttl));
        }else return Stream.of(ddr);
    }
    private static DefaultDnsResponse generateResponse(List<WrappedDNSRecord> dnsList,DnsQuery query,String type) {
        DefaultDnsResponse response = new DefaultDnsResponse(query.id());
        response.setAuthoritativeAnswer(true);
        DnsQuestion question = query.recordAt(DnsSection.QUESTION);
        response.addRecord(DnsSection.QUESTION, question);
        dnsList.stream().flatMap(DNSAddressBook::expand).forEach(rec-> response.addRecord(DnsSection.ANSWER, rec.getRecord(question.name(),type)));
        return response;
    }
}
