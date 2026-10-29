import com.scaleguard.server.http.reverse.AppServer;
import com.scaleguard.server.http.router.*;
import java.util.*;

/** Starts the real ScaleGuard AppServer with an in-memory route: localhost:8080 -> target t1 -> host group h1 (backends). */
public class LbHarness {
    public static void main(String[] a) throws Exception {
        String[] backends = a.length > 0 ? a[0].split(",") : new String[]{"127.0.0.1:9000"};
        RouteTable.setLoader(new SystemLoader() {
            public List<HostGroup> loadHostGroups() {
                List<HostGroup> l = new ArrayList<>();
                for (String b : backends) {
                    HostGroup h = new HostGroup();
                    h.setId(UUID.randomUUID().toString()); h.setScheme("http");
                    h.setHost(b.split(":")[0]); h.setPort(b.split(":")[1]);
                    h.setGroupId("h1"); h.setType("active"); h.setActive(true); h.setPriority(1); h.setLoadFactor(1f);
                    l.add(h);
                }
                return l;
            }
            public List<TargetSystem> loadTargets(List<HostGroup> hgs) {
                TargetSystem t = new TargetSystem();
                t.setId("t1"); t.setName("bench"); t.setGroupId("t1"); t.setScheme("http"); t.setBasePath("/");
                t.setHostGroupId("h1"); t.setHostGroups(new ArrayList<>(hgs));
                return List.of(t);
            }
            public List<SourceSystem> loadSources() {
                SourceSystem s = new SourceSystem();
                s.setId("s1"); s.setName("bench"); s.setScheme("http"); s.setHost("localhost"); s.setPort("8080");
                s.setBasePath("/"); s.setTarget("t1");
                return new ArrayList<>(List.of(s));
            }
        });
        AppServer server = new AppServer();
        server.start();
        System.out.println("LB READY");
        server.listen();
    }
}
