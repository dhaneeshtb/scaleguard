package com.scaleguard.server.http.reverse;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.util.Hashtable;

public class DnsTxtLookup {

    public static final String getTXTDNSRecord(String name,String domain){

        return getTXTDNSRecord(name+"."+domain);
    }

    public static final String getTXTDNSRecord(String fqdn){
        try {
            Hashtable<String, String> env = new Hashtable<>();
            env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");

            DirContext ctx = new InitialDirContext(env);
            Attributes attrs = ctx.getAttributes(fqdn, new String[]{"TXT"});
            Attribute attr = attrs.get("TXT");

            if (attr != null) {
                NamingEnumeration<?> enumeration = attr.getAll();
                while (enumeration.hasMore()) {
                    String recordTxt=enumeration.next().toString();
                    System.out.println("TXT Record: " + recordTxt);
                    return recordTxt;
                }
            } else {
                return null;
            }
        } catch (Exception e) {
            System.err.println("Error retrieving TXT records: " + e.getMessage());
            return null;
        }
        return null;

    }
    public static void main(String[] args){
        String domain = "sivasgems.com";
        String name="_acme-challenge";
        System.out.println(getTXTDNSRecord(name,domain));

    }
}