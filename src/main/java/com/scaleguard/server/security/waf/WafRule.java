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

    private final String id;
    private final String name;
    private final String category;
    private final String description;
    private final String pattern;
    private final Set<Target> targets;
    private final Action defaultAction;
    private final boolean builtIn;
    private final transient Pattern compiled;

    public WafRule(String id, String name, String category, String description, String pattern,
                   Set<Target> targets, Action defaultAction, boolean builtIn) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.description = description;
        this.pattern = pattern;
        this.targets = targets == null || targets.isEmpty() ? EnumSet.allOf(Target.class) : EnumSet.copyOf(targets);
        this.defaultAction = defaultAction == null ? Action.BLOCK : defaultAction;
        this.builtIn = builtIn;
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

    Pattern compiled() { return compiled; }
}
