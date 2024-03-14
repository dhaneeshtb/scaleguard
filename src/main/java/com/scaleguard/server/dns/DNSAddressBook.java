package com.scaleguard.server.dns;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.dns.*;
import io.netty.util.NetUtil;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DNSAddressBook {
    public static class WrappedDNSRecord{
        private String name;
        private String ip;

        public DefaultDnsRawRecord getRecord(String inName) {
            return  new DefaultDnsRawRecord(
                    inName!=null?inName:name,
                    DnsRecordType.A, DEFAULT_TTL, Unpooled.wrappedBuffer(NetUtil.createByteArrayFromIpAddressString(ip)));
        }
        public String getName() {
            return name;
        }
        public String getIp() {
            return ip;
        }
        WrappedDNSRecord(String name, String ip){

            this.ip=ip;
            this.name=name;
        }
    }
    private static long DEFAULT_TTL=600l;
    private static Map<String, List<WrappedDNSRecord>> dnsAddressMap=new ConcurrentHashMap<>();
    public static void add(String name,String ip){
        List<WrappedDNSRecord> dnsList = dnsAddressMap.computeIfAbsent(name,(k)->new ArrayList<>());
        dnsList.add(new WrappedDNSRecord(name,ip));
    }
    public static void remove(String name,String ip){
        List<WrappedDNSRecord> dnsList = dnsAddressMap.computeIfAbsent(name,(k)->new ArrayList<>());
        Iterator<WrappedDNSRecord> iterator = dnsList.iterator();
        iterator.forEachRemaining(s->{
            if(s.getIp().equalsIgnoreCase(ip)){
                iterator.remove();
            }
        });
    }
    public static boolean isEntryExist(String name){
        return dnsAddressMap.containsKey(name);
    }
    public static DefaultDnsResponse get(String name,DnsQuery query){
        List<WrappedDNSRecord> dnsList = dnsAddressMap.computeIfAbsent(name,k->new ArrayList<>());
        return generateResponse(dnsList,query);
    }
    private static DefaultDnsResponse generateResponse(List<WrappedDNSRecord> dnsList,DnsQuery query) {
        DefaultDnsResponse response = new DefaultDnsResponse(query.id());
        DnsQuestion question = query.recordAt(DnsSection.QUESTION);
        response.addRecord(DnsSection.QUESTION, question);
        dnsList.forEach(rec->{
            response.addRecord(DnsSection.ANSWER, rec.getRecord(question.name()));
        });
        return response;
    }
}
