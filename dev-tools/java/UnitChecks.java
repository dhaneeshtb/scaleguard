import com.scaleguard.server.db.DNSEntriesDB;
import com.scaleguard.server.dns.DNSAddressBook;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.InetAddress;

public class UnitChecks {
    static int pass, fail;

    static void check(String n, boolean ok, Object d) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "PASS " : "FAIL ") + n + (ok ? "" : "  -> " + d));
    }

    public static void main(String[] a) throws Exception {
        // --- rate limiter (package-private: use reflection)
        Class<?> rl = Class.forName("com.scaleguard.server.dns.DnsRateLimiter");
        Constructor<?> c = rl.getDeclaredConstructor(int.class, int.class);
        c.setAccessible(true);
        Object lim = c.newInstance(20, 20);
        Method check = rl.getDeclaredMethod("check", InetAddress.class);
        check.setAccessible(true);
        InetAddress ext = InetAddress.getByName("198.51.100.7");
        int allow = 0, drop = 0, slip = 0;
        for (int i = 0; i < 100; i++) {
            String r = check.invoke(lim, ext).toString();
            if (r.equals("ALLOW")) allow++; else if (r.equals("DROP")) drop++; else slip++;
        }
        check("rate limiter: burst of 20 allowed, rest dropped/slipped evenly", allow >= 20 && allow <= 22 && Math.abs(drop - slip) <= 1, allow + "/" + drop + "/" + slip);
        check("rate limiter: other clients unaffected", check.invoke(lim, InetAddress.getByName("198.51.100.8")).toString().equals("ALLOW"), "");
        check("rate limiter: loopback exempt", check.invoke(lim, InetAddress.getByName("127.0.0.1")).toString().equals("ALLOW"), "");
        Thread.sleep(1100);
        int after = 0;
        for (int i = 0; i < 30; i++) if (check.invoke(lim, ext).toString().equals("ALLOW")) after++;
        check("rate limiter: refills at 20/s", after >= 19 && after <= 22, after);

        // --- record management + DB call counts
        DNSAddressBook.get(); // trigger the one-time startup load first
        DNSEntriesDB db = DNSEntriesDB.getInstance();
        int c0 = db.creates, e0 = db.edits, r0 = db.reads;
        DNSAddressBook.add("Shop.Example.TEST", "10.9.9.9", "record", 300);
        check("add new record -> exactly 1 insert, no extra reads", db.creates - c0 == 1 && db.edits == e0 && db.reads == r0,
                (db.creates - c0) + "/" + (db.edits - e0) + "/" + (db.reads - r0));
        DNSAddressBook.add("shop.example.test.", "10.9.9.9", "record", 900);
        check("update same record -> 1 update, no duplicate",
                db.edits - e0 == 1 && DNSAddressBook.get().get("shop.example.test.").size() == 1
                        && DNSAddressBook.get().get("shop.example.test.").get(0).getTtl() == 900, DNSAddressBook.get().get("shop.example.test."));
        DNSAddressBook.add("shop.example.test.", "10.9.9.10", "record", 300);
        check("second IP on same name -> 2 records", DNSAddressBook.get().get("shop.example.test.").size() == 2, "");
        int d0 = db.deletes;
        DNSAddressBook.remove("shop.example.test.", "10.9.9.9"); // threw IllegalStateException before the fix
        check("remove(name, ip) works and deletes 1 row", DNSAddressBook.get().get("shop.example.test.").size() == 1 && db.deletes - d0 == 1, db.deletes - d0);
        String id = DNSAddressBook.get().get("shop.example.test.").get(0).getId();
        DNSAddressBook.remove(id);
        check("remove(id) drops the now-empty name", !DNSAddressBook.get().containsKey("shop.example.test.") && !DNSAddressBook.isEntryExist("shop.example.test."), DNSAddressBook.get().keySet());

        // --- SQL escaping
        Method q = Class.forName("com.scaleguard.server.db.DBManager").getDeclaredMethod("sqlValue", Object.class);
        q.setAccessible(true);
        Object esc = q.invoke(null, "x'); DROP TABLE dns_entries;--");
        check("SQL values escaped", "'x''); DROP TABLE dns_entries;--'".equals(esc), esc);

        System.out.println(pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
