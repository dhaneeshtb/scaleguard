package com.scaleguard.server.dns;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class DnsRecordTypesTest {

    private static byte[] rdata(String name, int qtype) {
        DNSAddressBook.AuthAnswer a = DNSAddressBook.resolve(name, qtype);
        assertNotNull(a);
        assertEquals(1, a.answers().size());
        assertEquals(qtype, a.answers().get(0).type());
        return a.answers().get(0).rdata();
    }

    @Test
    public void srvCaaPtrAreAnsweredWithTheRightBytes() {
        String z = "types" + System.nanoTime() + ".test.";
        DNSAddressBook.add("_sip._tcp." + z, "10 5 5060 sip." + z, "srv", 60);
        DNSAddressBook.add("caa." + z, "0 issue letsencrypt.org", "caa", 60);
        DNSAddressBook.add("7." + z, "host." + z, "ptr", 60);

        byte[] srv = rdata("_sip._tcp." + z, DnsWire.TYPE_SRV);
        assertArrayEquals(new byte[]{0, 10, 0, 5, 0x13, (byte) 0xC4}, Arrays.copyOf(srv, 6)); // 5060 = 0x13C4
        assertEquals("sip." + z, DnsWire.nameFromRdata(Arrays.copyOfRange(srv, 6, srv.length)));

        byte[] caa = rdata("caa." + z, DnsWire.TYPE_CAA);
        assertEquals(0, caa[0]);
        assertEquals(5, caa[1]);
        assertEquals("issueletsencrypt.org", new String(caa, 2, caa.length - 2, StandardCharsets.US_ASCII));

        assertEquals("host." + z, DnsWire.nameFromRdata(rdata("7." + z, DnsWire.TYPE_PTR)));
    }

    @Test
    public void valuesAreCheckedPerType() {
        assertNull(DNSRoute.validateValue("a", "203.0.113.10"));
        assertNotNull(DNSRoute.validateValue("a", "2001:db8::1"));
        assertNull(DNSRoute.validateValue("aaaa", "2001:db8::1"));
        assertNotNull(DNSRoute.validateValue("aaaa", "1.2.3.4"));
        assertNull(DNSRoute.validateValue("cname", "target.example.com"));
        assertNotNull(DNSRoute.validateValue("cname", "not a host"));
        assertNull(DNSRoute.validateValue("mx", "10 mail.example.com."));
        assertNotNull(DNSRoute.validateValue("mx", "mail.example.com"));
        assertNotNull(DNSRoute.validateValue("mx", "99999 mail.example.com"));
        assertNull(DNSRoute.validateValue("srv", "10 5 5060 sip.example.com"));
        assertNotNull(DNSRoute.validateValue("srv", "10 5 sip.example.com"));
        assertNull(DNSRoute.validateValue("caa", "0 issue letsencrypt.org"));
        assertNotNull(DNSRoute.validateValue("caa", "0 bogus x"));
        assertNull(DNSRoute.validateValue("txt", "v=spf1 -all"));
    }
}
