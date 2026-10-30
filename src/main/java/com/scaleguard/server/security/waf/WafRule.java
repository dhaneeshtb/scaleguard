package com.scaleguard.server.security.waf;

import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One WAF rule: a case-insensitive regular expression checked against selected parts of a request.
 */
public final class WafRule {

    /** Which part of the request a rule inspects. */
    public enum Target { PATH, QUERY, HEADERS, USER_AGENT, BODY }

    public enum Action { BLOCK, LOG }

    /** CRS-style severity; in anomaly-scoring mode a match adds this many points to the request's score. */
    public enum Severity {
        CRITICAL(5), ERROR(4), WARNING(3), NOTICE(2);
        public final int score;
        Severity(int score) { this.score = score; }
    }

    private final String id;
    private final String name;
    private final String category;
    private final String description;
    private final String pattern;
    private final Set<Target> targets;
    private final Action defaultAction;
    private final boolean builtIn;
    private final Severity severity;
    private final int paranoia;          // 1–4: only runs when the route's paranoia level is at least this
    private final String ruleSet;        // imported rule set this rule belongs to (null = built-in/custom)
    private final boolean guarded;       // matching is time-limited (imported regexes)
    private final Set<String> headerNames; // HEADERS target limited to these (lower-case) header names; null = all
    private final transient Pattern compiled;

    public WafRule(String id, String name, String category, String description, String pattern,
                   Set<Target> targets, Action defaultAction, boolean builtIn) {
        this(id, name, category, description, pattern, targets, defaultAction, builtIn, Severity.CRITICAL, 1, null, false);
    }

    public WafRule(String id, String name, String category, String description, String pattern,
                   Set<Target> targets, Action defaultAction, boolean builtIn,
                   Severity severity, int paranoia, String ruleSet, boolean guarded) {
        this(id, name, category, description, pattern, targets, defaultAction, builtIn, severity, paranoia, ruleSet, guarded, null);
    }

    public WafRule(String id, String name, String category, String description, String pattern,
                   Set<Target> targets, Action defaultAction, boolean builtIn,
                   Severity severity, int paranoia, String ruleSet, boolean guarded, Set<String> headerNames) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.description = description;
        this.pattern = pattern;
        this.targets = targets == null || targets.isEmpty() ? EnumSet.allOf(Target.class) : EnumSet.copyOf(targets);
        this.defaultAction = defaultAction == null ? Action.BLOCK : defaultAction;
        this.builtIn = builtIn;
        this.severity = severity == null ? Severity.CRITICAL : severity;
        this.paranoia = Math.max(1, Math.min(4, paranoia));
        this.ruleSet = ruleSet;
        this.guarded = guarded;
        this.headerNames = headerNames == null || headerNames.isEmpty() ? null : Set.copyOf(headerNames);
        this.compiled = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getCategory() { return category; }
    public String getDescription() { return description; }
    public String getPattern() { return pattern; }
    public Set<Target> getTargets() { return targets; }
    public Action getDefaultAction() { return defaultAction; }
    public boolean isBuiltIn() { return builtIn; }
    public Severity getSeverity() { return severity; }
    public int getParanoia() { return paranoia; }
    public String getRuleSet() { return ruleSet; }
    public Set<String> getHeaderNames() { return headerNames; }

    /** Time budget for one guarded match (imported regexes come from PCRE and weren't written for Java's engine). */
    static final long MATCH_BUDGET_NANOS = Long.getLong("scaleguard.waf.matchBudgetMicros", 20_000) * 1_000;
    static final boolean GUARD = !"false".equalsIgnoreCase(System.getProperty("scaleguard.waf.guard", "true"));
    static final java.util.concurrent.atomic.LongAdder TIMEOUTS = new java.util.concurrent.atomic.LongAdder();

    // ---- result cache for imported rules (per I/O thread, no locking) ----------------------------------------

    private static final java.util.concurrent.atomic.AtomicInteger NEXT_INDEX = new java.util.concurrent.atomic.AtomicInteger();
    /** Stable small index of an imported rule, for the per-value result cache below. */
    private final int index = NEXT_INDEX.getAndIncrement();
    static final int CACHE_MAX_VALUE = 256, CACHE_ENTRIES = Integer.getInteger("scaleguard.waf.cacheEntries", 4096);
    static final java.util.concurrent.atomic.LongAdder CACHE_HITS = new java.util.concurrent.atomic.LongAdder();

    /** value → bit pairs per rule index: bit 2i = evaluated, bit 2i+1 = matched. LRU, one per thread. */
    private static final ThreadLocal<java.util.LinkedHashMap<String, long[]>> CACHE = ThreadLocal.withInitial(() ->
            new java.util.LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, long[]> e) {
                    return size() > CACHE_ENTRIES;
                }
            });

    /**
     * Whether this rule matches {@code input}. For short values (headers, parameter names, common paths and
     * parameters repeat constantly) the answer is remembered per thread, so CRS's large regexes run once per
     * distinct value instead of once per request.
     */
    boolean matches(String input) {
        if (!guarded || input.length() > CACHE_MAX_VALUE) return find(input) != null;
        java.util.LinkedHashMap<String, long[]> cache = CACHE.get();
        long[] bits = cache.get(input);
        int word = (index * 2) >>> 6, bit = (index * 2) & 63;
        if (bits != null && word < bits.length && (bits[word] & (1L << bit)) != 0) {
            CACHE_HITS.increment();
            return (bits[word] & (1L << (bit + 1))) != 0;
        }
        boolean hit = find(input) != null;
        if (bits == null || word >= bits.length) {
            long[] grown = new long[Math.max(word + 1, bits == null ? 4 : bits.length * 2)];
            if (bits != null) System.arraycopy(bits, 0, grown, 0, bits.length);
            bits = grown;
            cache.put(input, bits);
        }
        bits[word] |= (1L << bit) | (hit ? 1L << (bit + 1) : 0);
        return hit;
    }

    /** Finds a match; guarded rules give up (no match) after the time budget instead of backtracking forever. */
    java.util.regex.Matcher find(String input) {
        java.util.regex.Matcher m = compiled.matcher(guarded && GUARD ? new Deadline(input, System.nanoTime() + MATCH_BUDGET_NANOS) : input);
        try {
            return m.find() ? m : null;
        } catch (Deadline.Expired e) {
            TIMEOUTS.increment();
            return null;
        } catch (StackOverflowError e) {
            // Java's regex engine recurses on alternation inside loops; a very long input can exhaust the stack.
            // Treat as no match (counted) instead of killing the I/O thread's request.
            TIMEOUTS.increment();
            return null;
        }
    }

    /** CharSequence that aborts the regex engine once its deadline passes (checked every 1,024 reads). */
    private static final class Deadline implements CharSequence {
        static final class Expired extends RuntimeException {
            Expired() { super(null, null, false, false); }
        }
        private final String s;
        private final long deadline;
        private int reads;
        Deadline(String s, long deadline) { this.s = s; this.deadline = deadline; }
        public char charAt(int i) {
            if ((++reads & 1023) == 0 && System.nanoTime() > deadline) throw new Expired();
            return s.charAt(i);
        }
        public int length() { return s.length(); }
        public CharSequence subSequence(int a, int b) { return new Deadline(s.substring(a, b), deadline); }
        public String toString() { return s; }
    }

    Pattern compiled() { return compiled; }
}
