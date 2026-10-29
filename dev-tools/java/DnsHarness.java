import com.scaleguard.server.dns.DNSAddressBook;
import com.scaleguard.server.dns.DnsServer;

public class DnsHarness {
    public static void main(String[] a) throws Exception {
        DNSAddressBook.get();                       // load from DB
        DNSAddressBook.add("acme.test.", "base", "base", 600);
        DNSAddressBook.add("api.acme.test.", "10.1.2.3", "record", 120);
        DNSAddressBook.add("api.acme.test.", "2001:db8::10", "record", 120);
        DNSAddressBook.add("_acme-challenge.acme.test.", "token-abc123", "record", 60);
        DNSAddressBook.add("www.acme.test.", "api.acme.test.", "cname", 300);
        DNSAddressBook.add("acme.test.", "10 mx.acme.test.", "mx", 300);
        new DnsServer().startBlocking();
        System.out.println("HARNESS READY");
        Thread.currentThread().join();
    }
}
