import com.scaleguard.server.http.reverse.AppServer;
import com.scaleguard.server.http.reverse.EventSubscriber;
import com.scaleguard.server.http.router.ConfigManager;
public class ClusterHarness {
    public static void main(String[] a) throws Exception {
        AppServer server = new AppServer();
        ConfigManager.getPublisher().subscribe(new EventSubscriber(server));
        server.start();
        System.out.println("NODE READY");
        server.listen();
    }
}
