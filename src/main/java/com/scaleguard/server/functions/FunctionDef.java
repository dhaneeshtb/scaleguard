package com.scaleguard.server.functions;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A cloud function: its Groovy source and everything the admin has decided it may do. Stored as JSON in the payload column. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class FunctionDef {
    public String id;
    public String name;
    public String description;
    public String code = "return [message: 'hello from ' + req.path]\n";
    public boolean enabled = true;

    /** HTTP methods the function answers; empty = any method. Others get 405 with an Allow header. */
    public List<String> methods = new ArrayList<>();

    public int timeoutMs = 5000;
    public int maxRequestBytes = 1_048_576;
    public int maxResponseBytes = 1_048_576;
    public int maxConcurrent = 10;
    /** Budget of bytes the run may allocate (cumulative allocation volume, not live heap) before it is stopped. */
    public int maxMemoryMb = 512;

    // --- outbound network: off unless the admin turns it on and lists where it may go
    public boolean networkEnabled = false;
    /** [scheme://]host[:port], host may start with "*." ; no scheme means https. */
    public List<String> allowedHosts = new ArrayList<>();
    /** Lets calls reach loopback / private (RFC 1918, ULA, CGNAT) addresses. Link-local and cloud-metadata addresses stay blocked. */
    public boolean allowPrivateNetworks = false;
    public int maxHttpCalls = 5;
    public int maxHttpBytes = 1_048_576;

    /** Names of gateway secrets (Functions page → Secrets) this function can read with ctx.secret(name). */
    public List<String> secrets = new ArrayList<>();
    /** Plain, non-secret values readable as config.KEY. */
    public Map<String, String> config = new LinkedHashMap<>();

    public long updated;

    static final int MAX_TIMEOUT_MS = Integer.getInteger("scaleguard.functions.maxTimeoutMs", 60_000);
    static final int MAX_BODY = Integer.getInteger("scaleguard.functions.maxBodyBytes", 10 * 1024 * 1024);
    static final int MAX_CODE = 200_000;

    /** @return null if valid, else what is wrong */
    String validate() {
        if (name == null || name.isBlank() || name.length() > 100) return "name is required (max 100 characters)";
        if (code == null || code.isBlank()) return "code is required";
        if (code.length() > MAX_CODE) return "code is too long";
        if (timeoutMs < 10 || timeoutMs > MAX_TIMEOUT_MS) return "timeoutMs must be 10.." + MAX_TIMEOUT_MS;
        if (maxRequestBytes < 0 || maxRequestBytes > MAX_BODY) return "maxRequestBytes must be 0.." + MAX_BODY;
        if (maxResponseBytes < 1 || maxResponseBytes > MAX_BODY) return "maxResponseBytes must be 1.." + MAX_BODY;
        if (maxHttpBytes < 1 || maxHttpBytes > MAX_BODY) return "maxHttpBytes must be 1.." + MAX_BODY;
        if (maxConcurrent < 1 || maxConcurrent > 1000) return "maxConcurrent must be 1..1000";
        if (maxMemoryMb < 1 || maxMemoryMb > 8192) return "maxMemoryMb must be 1..8192";
        if (maxHttpCalls < 0 || maxHttpCalls > 100) return "maxHttpCalls must be 0..100";
        if (methods == null) methods = new ArrayList<>();
        for (String m : methods) if (!m.matches("(?i)GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS")) return "unsupported method " + m;
        if (allowedHosts == null) allowedHosts = new ArrayList<>();
        for (String h : allowedHosts) if (SandboxHttp.HostRule.parse(h) == null) return "invalid allowed host '" + h + "' (use [http://|https://]host[:port], host may start with *.)";
        if (networkEnabled && allowedHosts.isEmpty()) return "network is enabled but no host is allowed; add at least one host";
        if (secrets == null) secrets = new ArrayList<>();
        for (String s : secrets) if (!FunctionSecrets.validName(s)) return "invalid secret name " + s;
        if (config == null) config = new LinkedHashMap<>();
        return null;
    }

    boolean allows(String method) {
        return methods == null || methods.isEmpty() || methods.stream().anyMatch(m -> m.equalsIgnoreCase(method));
    }
}
