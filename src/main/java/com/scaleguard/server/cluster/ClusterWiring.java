package com.scaleguard.server.cluster;

import com.scaleguard.server.acmechallengeroute.AcmeChannelgeRoute;
import com.scaleguard.server.analytics.AccessLogStore;
import com.scaleguard.server.application.Application;
import com.scaleguard.server.application.ClientInfo;
import com.scaleguard.server.certificates.CertificatesRoute;
import com.scaleguard.server.dns.DNSAddressBook;
import com.scaleguard.server.http.router.ConfigManager;
import com.scaleguard.server.http.router.HostGroup;
import com.scaleguard.server.http.router.PassiveHealthManager;
import com.scaleguard.server.http.router.RateLimitManager;
import com.scaleguard.server.http.router.RouteTable;
import com.scaleguard.server.http.router.SourceSystem;
import com.scaleguard.server.security.waf.WafEngine;
import io.netty.handler.ssl.CertificateStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Connects the cluster's change notifications to the subsystems that must reload, and supplies heartbeat metrics. */
public final class ClusterWiring {

    private ClusterWiring() {}

    private static long lastRequests = -1;
    private static long lastAt;

    public static void start() {
        ClusterManager cm = ClusterManager.get();

        Runnable routes = () -> {
            RouteTable.getInstance().reload();
            // a route on a new port needs a listener on this node too
            for (SourceSystem s : RouteTable.getInstance().getSourceSystsems()) ConfigManager.announce(s);
        };
        cm.on("source_systems", routes);
        cm.on("target_systems", routes);
        cm.on("host_groups", routes);

        cm.on("system_properties/ratelimit", () -> {
            RateLimitManager.getInstance().reloadSettings();
            com.scaleguard.server.http.router.AccessLists.get().reload();
        });
        cm.on("system_properties/waf", () -> WafEngine.getInstance().reload());
        cm.on("system_properties/health", () -> PassiveHealthManager.getInstance().reload());
        cm.on("system_properties/analytics", () -> AccessLogStore.getInstance().reloadSettings());

        cm.on("dns_entries", DNSAddressBook::reload);
        cm.on("applications", Application::reload);
        cm.on("clientinfos", () -> {
            ClientInfo.reload();
            Application.reload();
        });

        Runnable certs = () -> {
            AcmeChannelgeRoute.invalidateTokenCache();   // HTTP-01 tokens ordered on another node
            try {
                CertificatesRoute.getCm().invalidateCache();
            } catch (Exception ignored) {
                // certificate manager not initialised on this node yet
            }
            CertificateStore.loadAllCerts();
        };
        cm.on("certificate_orders", certs);
        cm.on("cert_files", certs);

        cm.metrics(ClusterWiring::metrics);
        cm.start();
    }

    private static synchronized Map<String, Object> metrics() {
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> rl = RateLimitManager.getInstance().stats();
        long allowed = ((Number) rl.getOrDefault("allowed", 0L)).longValue();
        long rejected = ((Number) rl.getOrDefault("rejected", 0L)).longValue();
        long requests = allowed + rejected;
        long now = System.currentTimeMillis();
        if (lastRequests >= 0 && now > lastAt) {
            m.put("rps", Math.round((requests - lastRequests) * 10000.0 / (now - lastAt)) / 10.0);
        } else {
            m.put("rps", 0);
        }
        lastRequests = requests;
        lastAt = now;
        m.put("requests", requests);
        m.put("rateLimited", rejected);
        try {
            Object blocked = WafEngine.getInstance().stats().get("blocked");
            m.put("wafBlocked", blocked == null ? 0 : blocked);
        } catch (Exception ignored) {
            m.put("wafBlocked", 0);
        }
        RouteTable rt = RouteTable.getInstance();
        m.put("routes", rt.getSourceSystsems().size());
        List<HostGroup> hosts = rt.getHostGroups();
        m.put("backends", hosts.size());
        m.put("backendsHealthy", hosts.stream().filter(HostGroup::isReachable).count());
        m.put("activeConnections", hosts.stream().mapToInt(h -> h.getActiveConnections().get()).sum());
        try {
            Map<String, Object> a = AccessLogStore.getInstance().status();
            m.put("analyticsRows", a.getOrDefault("written", 0));
        } catch (Exception ignored) {
            // analytics off
        }
        m.put("http3Ports", com.scaleguard.server.http.h3.Http3Listener.ports());
        m.put("uptimeMs", java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime());
        return m;
    }
}
