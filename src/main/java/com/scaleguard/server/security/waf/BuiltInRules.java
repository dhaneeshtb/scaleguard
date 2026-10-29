package com.scaleguard.server.security.waf;

import com.scaleguard.server.security.waf.WafRule.Action;
import com.scaleguard.server.security.waf.WafRule.Target;

import java.util.EnumSet;
import java.util.List;

/**
 * Built-in rule set covering the most common web attacks (in the spirit of the OWASP Top 10).
 *
 * <p>Patterns run against decoded, normalised input (see {@link WafEngine}) and avoid nested
 * quantifiers so a crafted request can't cause catastrophic regex backtracking. Inputs are also
 * length-capped before matching.
 */
final class BuiltInRules {

    private BuiltInRules() {}

    private static final EnumSet<Target> PARAMS = EnumSet.of(Target.PATH, Target.QUERY, Target.BODY);
    private static final EnumSet<Target> PARAMS_HEADERS = EnumSet.of(Target.PATH, Target.QUERY, Target.BODY, Target.HEADERS);

    static List<WafRule> all() {
        return List.of(
                // ---- SQL injection
                rule("sqli-union", "SQL injection: UNION SELECT", "SQL injection",
                        "UNION-based data extraction, e.g. ' UNION SELECT username, password FROM users",
                        "\\bunion\\b[\\s(]{1,20}(?:all[\\s(]{1,20}|distinct[\\s(]{1,20})?select\\b", PARAMS_HEADERS, Action.BLOCK),
                rule("sqli-tautology", "SQL injection: always-true condition", "SQL injection",
                        "Classic login bypass such as ' OR '1'='1 or \" or 1=1 --",
                        "['\"`)]\\s{0,10}(?:or|and|\\|\\||&&)\\s{1,10}['\"`(]?\\s{0,5}[\\w'\"]{1,30}\\s{0,5}(?:=|<>|!=|like\\b|>|<)\\s{0,5}['\"`(]?[\\w'\"]{0,30}", PARAMS_HEADERS, Action.BLOCK),
                rule("sqli-comment", "SQL injection: quote then comment", "SQL injection",
                        "A quote followed by a SQL comment to cut off the rest of the query, e.g. admin'--",
                        "['\"`]\\s{0,10}(?:--|#|/\\*)", PARAMS, Action.BLOCK),
                rule("sqli-stacked", "SQL injection: stacked query", "SQL injection",
                        "A second statement appended after ;, e.g. 1; DROP TABLE users",
                        ";\\s{0,10}(?:drop|delete|insert|update|alter|create|truncate|exec(?:ute)?|shutdown|grant|declare)\\s", PARAMS_HEADERS, Action.BLOCK),
                rule("sqli-functions", "SQL injection: time-based or system functions", "SQL injection",
                        "Blind-injection timing and file/system functions: SLEEP(), BENCHMARK(), WAITFOR DELAY, LOAD_FILE, xp_cmdshell, information_schema",
                        "\\b(?:sleep|benchmark|pg_sleep|load_file|extractvalue|updatexml)\\s{0,5}\\(|\\bwaitfor\\s{1,10}delay\\b|\\binto\\s{1,10}(?:out|dump)file\\b|\\binformation_schema\\b|\\bxp_cmdshell\\b|\\bsys\\.(?:user_)?objects\\b", PARAMS_HEADERS, Action.BLOCK),

                // ---- Cross-site scripting
                rule("xss-script", "XSS: script tag", "Cross-site scripting",
                        "Injected <script> elements",
                        "<\\s{0,5}/?\\s{0,5}script\\b", PARAMS_HEADERS, Action.BLOCK),
                rule("xss-event-handler", "XSS: inline event handler", "Cross-site scripting",
                        "HTML attributes that run script, e.g. <img src=x onerror=alert(1)>",
                        "<[a-z][^>]{0,200}?[\\s\"'/]on[a-z]{3,25}\\s{0,5}=", PARAMS_HEADERS, Action.BLOCK),
                rule("xss-js-uri", "XSS: javascript: URL", "Cross-site scripting",
                        "javascript: or vbscript: URLs in parameters",
                        "(?:javascript|vbscript|livescript)\\s{0,5}:\\s{0,10}[\\w(\"'`/]", PARAMS, Action.BLOCK),
                rule("xss-dangerous-tags", "XSS: dangerous HTML elements", "Cross-site scripting",
                        "Elements commonly used to run or load script: iframe, object, embed, svg, base, meta refresh",
                        "<\\s{0,5}(?:iframe|frame|object|embed|applet|svg|math|base|meta|link|form)\\b", PARAMS, Action.BLOCK),
                rule("xss-js-functions", "XSS: script execution functions", "Cross-site scripting",
                        "eval(), document.cookie, String.fromCharCode and similar in parameters",
                        "\\b(?:eval|settimeout|setinterval|execscript)\\s{0,5}\\(|document\\s{0,5}\\.\\s{0,5}(?:cookie|domain|write)|string\\s{0,5}\\.\\s{0,5}fromcharcode\\b", PARAMS, Action.BLOCK),

                // ---- Path traversal / file disclosure
                rule("lfi-traversal", "Path traversal", "Path traversal & file access",
                        "Climbing out of the web root with ../ or ..\\",
                        "(?:^|[\\\\/=])\\.\\.[\\\\/]", EnumSet.of(Target.PATH, Target.QUERY, Target.BODY), Action.BLOCK),
                rule("lfi-sensitive-files", "Sensitive system files", "Path traversal & file access",
                        "Requests for OS files such as /etc/passwd, /proc/self/environ, win.ini",
                        "/etc/(?:passwd|shadow|group|hosts|issue)\\b|/proc/(?:self|\\d{1,7})/|\\b(?:boot|win|system)\\.ini\\b|c:\\\\windows\\\\", PARAMS_HEADERS, Action.BLOCK),
                rule("probe-sensitive-paths", "Probe for secrets and source files", "Path traversal & file access",
                        "Scans for .env, .git, cloud credentials, backups and config files",
                        "/\\.(?:env|git|svn|hg|aws|ssh|htpasswd|htaccess|ds_store|npmrc|dockerenv)(?:/|$|\\.)|/(?:wp-config\\.php|web\\.config|id_rsa|id_ed25519)\\b|\\.(?:bak|swp|orig)$", EnumSet.of(Target.PATH), Action.BLOCK),
                rule("rfi-wrappers", "Remote/local file inclusion wrappers", "Path traversal & file access",
                        "PHP and URL wrappers used for file inclusion: php://, phar://, expect://, file://, gopher://",
                        "\\b(?:php|phar|expect|file|gopher|dict|zip|glob|ogg|ssh2)://", PARAMS, Action.BLOCK),

                // ---- Command / code injection
                rule("rce-shell", "Command injection", "Command & code injection",
                        "Shell commands chained with ; | ` or $( ), e.g. ; cat /etc/passwd",
                        "(?:[;|`]|\\$\\(|&&|\\|\\|)\\s{0,10}(?:cat|ls|id|whoami|uname|wget|curl|nc|ncat|netcat|bash|sh|zsh|python[23]?|perl|ruby|php|powershell|cmd(?:\\.exe)?|ping|nslookup|chmod|rm)\\b", PARAMS, Action.BLOCK), // not headers: "Cookie: a=1; id=5" is normal
                rule("rce-log4shell", "Log4Shell / JNDI lookup", "Command & code injection",
                        "Log4j JNDI lookups like ${jndi:ldap://...}, including obfuscated forms",
                        "\\$\\{\\s{0,5}(?:jndi|env|sys|java|lower|upper|date|main|ctx|::-)[^}]{0,200}", PARAMS_HEADERS, Action.BLOCK),
                rule("rce-php", "Server-side code injection", "Command & code injection",
                        "PHP tags and template/expression injection markers such as <?php or {{7*7}}",
                        "<\\?php\\b|<\\?=|\\{\\{[^}]{0,50}(?:\\*|__class__|config|self|request)[^}]{0,50}\\}\\}|#\\{[^}]{0,50}\\}", PARAMS, Action.BLOCK),
                rule("xxe", "XML external entity (XXE)", "Command & code injection",
                        "XML DOCTYPE/ENTITY declarations that read files or reach internal hosts",
                        "<!\\s{0,5}(?:doctype|entity)\\b[^>]{0,500}\\b(?:system|public)\\b", EnumSet.of(Target.BODY), Action.BLOCK),
                rule("ssrf-metadata", "SSRF: cloud metadata and internal hosts", "Command & code injection",
                        "Parameters pointing at cloud metadata endpoints (169.254.169.254, metadata.google.internal)",
                        "169\\.254\\.169\\.254|\\bmetadata\\.google\\.internal\\b|\\bmetadata\\.azure\\.com\\b|\\[?fd00:ec2::254\\]?", PARAMS_HEADERS, Action.BLOCK),
                rule("nosql-operators", "NoSQL injection operators", "Command & code injection",
                        "MongoDB-style operators in parameters ($where, $ne, $regex). Logged only by default because some APIs use them legitimately.",
                        "(?:\\[\\s{0,3}|[\"']\\s{0,3})\\$(?:where|ne|gt|gte|lt|lte|regex|expr|nin|or|and|function)\\b", EnumSet.of(Target.QUERY, Target.BODY), Action.LOG),

                // ---- Bots and scanners
                rule("scanner-ua", "Known attack tools", "Scanners & bots",
                        "User-Agent of vulnerability scanners and attack tools (sqlmap, nikto, nuclei, masscan …)",
                        "\\b(?:sqlmap|nikto|nmap|masscan|zgrab|acunetix|nessus|openvas|wpscan|dirbuster|gobuster|feroxbuster|ffuf|nuclei|havij|w3af|arachni|joomscan|commix|hydra|jaeles|xsstrike)\\b", EnumSet.of(Target.USER_AGENT), Action.BLOCK)
        );
    }

    private static WafRule rule(String id, String name, String category, String description, String regex,
                                EnumSet<Target> targets, Action action) {
        return new WafRule(id, name, category, description, regex, targets, action, true);
    }
}
