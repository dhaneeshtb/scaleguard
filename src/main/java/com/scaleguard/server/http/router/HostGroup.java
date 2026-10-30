package com.scaleguard.server.http.router;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.concurrent.atomic.AtomicInteger;

public class HostGroup {

    private String id;
    private String host;
    private String port;
    private String health;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getHealth() {
        return health;
    }

    public void setHealth(String health) {
        this.health = health;
    }

    private String scheme;

    public String getScheme() {
        return scheme;
    }

    public void setScheme(String scheme) {
        this.scheme = scheme;
    }

    private int priority;
    private boolean isActive=true;

    public boolean isActive() {
        return isActive;
    }

    public void setActive(boolean active) {
        isActive = active;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    private String type;//Active,Standby

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    private String groupId;

    /**
     * Optional cluster label for multi-datacenter / disaster-recovery topologies.
     * Operators can set this to a logical name such as {@code "primary-dc"} or
     * {@code "dr-dc"}.  The load-balancer can use it to prefer hosts within the
     * same cluster before crossing cluster boundaries.
     */
    private String cluster;

    public String getCluster() {
        return cluster;
    }

    public void setCluster(String cluster) {
        this.cluster = cluster;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getPort() {
        return port;
    }

    public void setPort(String port) {
        this.port = port;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public float getLoadFactor() {
        return loadFactor;
    }

    public void setLoadFactor(float loadFactor) {
        this.loadFactor = loadFactor;
    }

    /**
     * True when the host may receive traffic: its active health check passes (or it has none)
     * and passive health checking hasn't ejected it. See {@link PassiveHealthManager}.
     */
    @JsonIgnore
    public boolean isReachable() {
        return isReachable && !isEjected();
    }

    /** Result of the active (polling) health check only. */
    @JsonIgnore
    public boolean isActivelyHealthy() {
        return isReachable;
    }

    /** Sets the active health-check result. */
    @JsonIgnore
    public void setReachable(boolean reachable) {
        isReachable = reachable;
    }

    /** Ejected by passive health checking (recent request failures) and still inside its ejection window. */
    @JsonIgnore
    public boolean isEjected() {
        long until = passive.ejectedUntilNanos;
        return until != 0 && System.nanoTime() - until < 0;
    }

    @JsonIgnore
    public PassiveState getPassive() {
        return passive;
    }

    /** Passive health bookkeeping, updated by {@link PassiveHealthManager}. */
    public static final class PassiveState {
        public final AtomicInteger consecutiveFailures = new AtomicInteger();
        public volatile long ejectedUntilNanos;     // 0 = not ejected
        public volatile long ejectedAtMillis;
        public volatile int ejections;              // consecutive ejections, drives the back-off
        public volatile long lastFailureMillis;
        public volatile String lastFailure;
        public final java.util.concurrent.atomic.LongAdder requests = new java.util.concurrent.atomic.LongAdder();
        public final java.util.concurrent.atomic.LongAdder failures = new java.util.concurrent.atomic.LongAdder();
        public final java.util.concurrent.atomic.LongAdder totalEjections = new java.util.concurrent.atomic.LongAdder();
    }

    @JsonIgnore
    private final transient PassiveState passive = new PassiveState();

    public int getWeight() {
        return weight;
    }

    public void setWeight(int weight) {
        this.weight = weight;
    }

    @JsonIgnore
    public AtomicInteger getActiveConnections() {
        return activeConnections;
    }

    public void incrementConnections() {
        activeConnections.incrementAndGet();
    }

    public void decrementConnections() {
        activeConnections.decrementAndGet();
    }

    /*
     * Per-server limits, after nginx's "server" parameters in an upstream block. null or <= 0 = default.
     *   maxConns            max_conns    open connections from ScaleGuard to this server (0 = unlimited)
     *   maxFails            max_fails    failed requests in a row before ejecting (default: Backend health setting)
     *   failTimeoutSeconds  fail_timeout first ejection length (default: Backend health setting; doubles on repeats)
     * "down" is active=false, "backup" is type=Standby.
     */
    private Integer maxConns;
    private Integer maxFails;
    private Integer failTimeoutSeconds;

    public Integer getMaxConns() { return maxConns; }
    public void setMaxConns(Integer v) { this.maxConns = v; }
    public Integer getMaxFails() { return maxFails; }
    public void setMaxFails(Integer v) { this.maxFails = v; }
    public Integer getFailTimeoutSeconds() { return failTimeoutSeconds; }
    public void setFailTimeoutSeconds(Integer v) { this.failTimeoutSeconds = v; }

    /** True when max_conns is set and reached. */
    @JsonIgnore
    public boolean isAtCapacity() {
        return maxConns != null && maxConns > 0 && activeConnections.get() >= maxConns;
    }

    /** Backup server: only used when no primary server can take the request (nginx "backup"). */
    @JsonIgnore
    public boolean isBackup() {
        return "Standby".equalsIgnoreCase(type);
    }

    private float loadFactor;
    @JsonIgnore
    private boolean isReachable=true;
    private int weight = 1;
    @JsonIgnore
    private final AtomicInteger activeConnections = new AtomicInteger(0);
}
