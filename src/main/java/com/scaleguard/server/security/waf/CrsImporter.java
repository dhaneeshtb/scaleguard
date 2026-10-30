package com.scaleguard.server.security.waf;

import com.scaleguard.server.security.waf.WafRule.Action;
import com.scaleguard.server.security.waf.WafRule.Severity;
import com.scaleguard.server.security.waf.WafRule.Target;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Imports detection rules from ModSecurity / OWASP Core Rule Set (CRS) {@code .conf} files.
 *
 * <p>ScaleGuard's engine matches regular expressions against normalised request parts, so the importer keeps the
 * CRS rules that fit that model and reports everything it skips, with the reason:
 * <ul>
 *   <li>Supported: {@code SecRule} with {@code @rx} (or no operator), {@code @pm}, {@code @pmFromFile} (when the
 *       {@code .data} file is uploaded too), {@code @contains}, {@code @beginsWith}, {@code @endsWith},
 *       {@code @streq}; request variables ARGS*, QUERY_STRING, REQUEST_URI/FILENAME/BASENAME/LINE, REQUEST_HEADERS,
 *       REQUEST_COOKIES*, REQUEST_BODY, XML, FILES*. Severity and {@code paranoia-level/N} tags are kept, so anomaly
 *       scoring and paranoia levels behave as in CRS.</li>
 *   <li>Skipped: chained rules (multi-condition), libinjection operators ({@code @detectSQLi/@detectXSS}), numeric and
 *       IP operators, CRS bookkeeping rules (TX variables, no {@code msg}), response-phase rules, and regexes Java
 *       can't compile.</li>
 * </ul>
 * ScaleGuard's own normalisation (multi-pass URL decoding, HTML entities, SQL comments, whitespace) stands in for the
 * CRS {@code t:} transformation pipelines, so detection is close to, but not identical with, ModSecurity/Coraza.
 */
public final class CrsImporter {

    private CrsImporter() {}

    public record Result(List<WafRule> rules, Map<String, Integer> skipped, List<String> examples) {}

    /** {variables, operator, actions} of a SecRule statement (quotes removed, \" kept escaped), or null. */
    static String[] splitSecRule(String stmt) {
        int i = "SecRule".length();
        int n = stmt.length();
        while (i < n && Character.isWhitespace(stmt.charAt(i))) i++;
        int vs = i;
        while (i < n && !Character.isWhitespace(stmt.charAt(i))) i++;
        String vars = stmt.substring(vs, i);
        String[] quoted = new String[2];
        for (int q = 0; q < 2; q++) {
            while (i < n && Character.isWhitespace(stmt.charAt(i))) i++;
            if (i >= n || stmt.charAt(i) != '"') {
                if (q == 0) return null;
                break;
            }
            StringBuilder sb = new StringBuilder();
            i++;
            boolean closed = false;
            while (i < n) {
                char c = stmt.charAt(i);
                if (c == '\\' && i + 1 < n) { sb.append(c).append(stmt.charAt(i + 1)); i += 2; continue; }
                if (c == '"') { closed = true; i++; break; }
                sb.append(c);
                i++;
            }
            if (!closed) return null;
            quoted[q] = sb.toString();
        }
        return new String[]{vars, quoted[0], quoted[1]};
    }

    /**
     * @param setName name of the rule set (stored with each rule)
     * @param files   file name → content; {@code .conf} files are parsed, {@code .data} files feed {@code @pmFromFile}
     */
    public static Result parse(String setName, Map<String, String> files) {
        Map<String, List<String>> dataFiles = new LinkedHashMap<>();
        files.forEach((name, content) -> {
            if (name.endsWith(".data") || name.endsWith(".txt")) dataFiles.put(baseName(name), dataLines(content));
        });
        List<WafRule> rules = new ArrayList<>();
        Map<String, Integer> skipped = new TreeMap<>();
        List<String> examples = new ArrayList<>();
        for (Map.Entry<String, String> f : files.entrySet()) {
            if (!f.getKey().endsWith(".conf") && !f.getKey().endsWith(".conf.example")) continue;
            String category = categoryFor(f.getKey());
            boolean chainContinues = false;
            for (String stmt : statements(f.getValue())) {
                if (!stmt.startsWith("SecRule ")) continue;
                String[] parts = splitSecRule(stmt);
                if (parts == null) { skip(skipped, "unparseable rule", examples, stmt); continue; }
                String vars = parts[0], op = unescape(parts[1]), actions = parts[2] == null ? "" : unescape(parts[2]);
                Map<String, String> act = actions(actions);
                if (chainContinues) {             // a chained rule's later links have no id; the whole chain was skipped
                    chainContinues = act.containsKey("chain");
                    continue;
                }
                if (act.containsKey("chain")) {
                    chainContinues = true;
                    skip(skipped, "chained (multi-condition) rule", examples, act.get("id"));
                    continue;
                }
                String id = act.get("id");
                String msg = act.get("msg");
                if (id == null) continue;
                if (msg == null) { skip(skipped, "CRS bookkeeping rule (no msg)", null, id); continue; }
                String phase = act.getOrDefault("phase", "2");
                if (phase.equals("3") || phase.equals("4") || phase.equals("response")) { skip(skipped, "response-phase rule", null, id); continue; }

                EnumSet<Target> targets = targets(vars);
                if (targets.isEmpty()) { skip(skipped, "unsupported variables (" + firstVar(vars) + ")", examples, id); continue; }
                java.util.Set<String> headerNames = targets.contains(Target.HEADERS) ? headerSelectors(vars) : null;

                String regex;
                try {
                    regex = operatorToRegex(op, dataFiles);
                } catch (IllegalArgumentException e) {
                    skip(skipped, e.getMessage(), examples, id);
                    continue;
                }
                if (regex == null) continue;
                Severity sev = severity(act.get("severity"));
                int pl = paranoia(actions);
                try {
                    WafRule r = new WafRule("crs-" + id, msg, category, "OWASP CRS rule " + id + " (" + f.getKey() + "), paranoia level " + pl,
                            regex, targets, Action.BLOCK, false, sev, pl, setName, true, headerNames);
                    rules.add(r);
                } catch (PatternSyntaxException e) {
                    skip(skipped, "regex not supported by Java", examples, id + ": " + e.getDescription());
                }
            }
        }
        return new Result(rules, skipped, examples);
    }

    private static void skip(Map<String, Integer> skipped, String reason, List<String> examples, String what) {
        skipped.merge(reason, 1, Integer::sum);
        if (examples != null && examples.size() < 12 && what != null) examples.add(reason + ": " + (what.length() > 120 ? what.substring(0, 120) + "…" : what));
    }

    /** Joins backslash-continued lines and drops comments. */
    static List<String> statements(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.strip();
            if (cur.length() == 0 && (line.isEmpty() || line.startsWith("#"))) continue;
            if (line.endsWith("\\")) {
                cur.append(line, 0, line.length() - 1).append(' ');
                continue;
            }
            cur.append(line);
            out.add(cur.toString().strip());
            cur.setLength(0);
        }
        if (cur.length() > 0) out.add(cur.toString().strip());
        return out;
    }

    private static String unescape(String s) {
        return s.replace("\\\"", "\"");
    }

    /** id, msg, severity, phase, chain … from "id:942100,phase:2,block,msg:'SQL…',tag:'…'". Tags are kept raw. */
    static Map<String, String> actions(String a) {
        Map<String, String> m = new LinkedHashMap<>();
        int i = 0, n = a.length();
        while (i < n) {
            while (i < n && (a.charAt(i) == ',' || Character.isWhitespace(a.charAt(i)))) i++;
            int start = i;
            boolean quoted = false;
            while (i < n) {
                char c = a.charAt(i);
                if (c == '\'' ) quoted = !quoted;
                else if (c == ',' && !quoted) break;
                i++;
            }
            String item = a.substring(start, i).trim();
            if (item.isEmpty()) continue;
            int colon = item.indexOf(':');
            String k = (colon < 0 ? item : item.substring(0, colon)).trim().toLowerCase(Locale.ROOT);
            String v = colon < 0 ? "" : item.substring(colon + 1).trim();
            if (v.length() >= 2 && v.startsWith("'") && v.endsWith("'")) v = v.substring(1, v.length() - 1);
            if (!m.containsKey(k)) m.put(k, v);   // first value wins (tags repeat; paranoia read separately)
        }
        return m;
    }

    private static int paranoia(String actions) {
        Matcher m = Pattern.compile("paranoia-level/(\\d)").matcher(actions);
        return m.find() ? Math.max(1, Math.min(4, Integer.parseInt(m.group(1)))) : 1;
    }

    private static Severity severity(String s) {
        if (s == null) return Severity.CRITICAL;
        return switch (s.toUpperCase(Locale.ROOT)) {
            case "0", "1", "2", "EMERGENCY", "ALERT", "CRITICAL" -> Severity.CRITICAL;
            case "3", "ERROR" -> Severity.ERROR;
            case "4", "WARNING" -> Severity.WARNING;
            default -> Severity.NOTICE;
        };
    }

    /** ModSecurity variables → the request parts ScaleGuard inspects. Negated/excluded variables are ignored. */
    static EnumSet<Target> targets(String vars) {
        EnumSet<Target> t = EnumSet.noneOf(Target.class);
        for (String v : vars.split("\\|")) {
            if (v.startsWith("!") || v.startsWith("&")) continue;
            String name = v.contains(":") ? v.substring(0, v.indexOf(':')) : v;
            String sel = v.contains(":") ? v.substring(v.indexOf(':') + 1).toLowerCase(Locale.ROOT) : "";
            switch (name.toUpperCase(Locale.ROOT)) {
                case "ARGS", "ARGS_NAMES", "ARGS_COMBINED_SIZE" -> { t.add(Target.QUERY); t.add(Target.BODY); }
                case "ARGS_GET", "ARGS_GET_NAMES", "QUERY_STRING" -> t.add(Target.QUERY);
                case "ARGS_POST", "ARGS_POST_NAMES", "REQUEST_BODY", "XML" -> t.add(Target.BODY);
                // FILES*, MULTIPART_*: need a multipart parser (per-part headers/file names) — not supported, rule skipped
                case "REQUEST_FILENAME", "REQUEST_BASENAME", "PATH_INFO" -> t.add(Target.PATH);
                case "REQUEST_URI", "REQUEST_URI_RAW", "REQUEST_LINE" -> { t.add(Target.PATH); t.add(Target.QUERY); }
                case "REQUEST_HEADERS" -> {
                    if (sel.equals("user-agent")) t.add(Target.USER_AGENT);
                    else t.add(Target.HEADERS);
                }
                case "REQUEST_HEADERS_NAMES", "REQUEST_COOKIES", "REQUEST_COOKIES_NAMES" -> t.add(Target.HEADERS);
                default -> { }
            }
        }
        return t;
    }

    /**
     * Which headers a rule looks at: "REQUEST_HEADERS:Content-Type" → {content-type}; cookies → {cookie};
     * a plain REQUEST_HEADERS (or a /regex/ selector) means all headers → null.
     */
    static java.util.Set<String> headerSelectors(String vars) {
        java.util.Set<String> names = new java.util.TreeSet<>();
        for (String v : vars.split("\\|")) {
            if (v.startsWith("!") || v.startsWith("&")) continue;
            String name = (v.contains(":") ? v.substring(0, v.indexOf(':')) : v).toUpperCase(Locale.ROOT);
            String sel = v.contains(":") ? v.substring(v.indexOf(':') + 1).toLowerCase(Locale.ROOT) : "";
            switch (name) {
                case "REQUEST_HEADERS" -> {
                    if (sel.isEmpty() || sel.startsWith("/")) return null;
                    if (!sel.equals("user-agent")) names.add(sel);
                }
                case "REQUEST_COOKIES", "REQUEST_COOKIES_NAMES" -> names.add("cookie");
                case "REQUEST_HEADERS_NAMES" -> { return null; }
                default -> { }
            }
        }
        return names.isEmpty() ? null : names;
    }

    private static String firstVar(String vars) {
        String v = vars.split("\\|")[0];
        return v.contains(":") ? v.substring(0, v.indexOf(':')) : v;
    }

    /** CRS operator → Java regex (case-insensitive matching is applied by the engine). */
    static String operatorToRegex(String op, Map<String, List<String>> dataFiles) {
        String o = op.trim();
        if (o.startsWith("!")) throw new IllegalArgumentException("negated operator");
        if (!o.startsWith("@")) return javaRegex(o);
        int sp = o.indexOf(' ');
        String name = (sp < 0 ? o : o.substring(0, sp)).toLowerCase(Locale.ROOT);
        String arg = sp < 0 ? "" : o.substring(sp + 1).trim();
        return switch (name) {
            case "@rx" -> javaRegex(arg);
            case "@pm" -> phrases(List.of(arg.split("\\s+")));
            case "@pmfromfile", "@pmf" -> {
                List<String> all = new ArrayList<>();
                for (String file : arg.split("\\s+")) {
                    List<String> lines = dataFiles.get(baseName(file));
                    if (lines == null) throw new IllegalArgumentException("needs data file " + baseName(file) + " (upload it with the .conf files)");
                    all.addAll(lines);
                }
                yield phrases(all);
            }
            case "@contains" -> Pattern.quote(arg);
            case "@beginswith" -> "^" + Pattern.quote(arg);
            case "@endswith" -> Pattern.quote(arg) + "$";
            case "@streq" -> "^" + Pattern.quote(arg) + "$";
            case "@detectsqli", "@detectxss" -> throw new IllegalArgumentException("libinjection operator (" + name + ")");
            default -> throw new IllegalArgumentException("unsupported operator " + name);
        };
    }

    private static String phrases(List<String> words) {
        StringBuilder sb = new StringBuilder("(?:");
        int n = 0;
        for (String w : words) {
            if (w.isBlank()) continue;
            if (n++ > 0) sb.append('|');
            sb.append(Pattern.quote(w.trim()));
        }
        if (n == 0) throw new IllegalArgumentException("empty phrase list");
        return sb.append(')').toString();
    }

    /** PCRE → Java: POSIX bracket classes and a few escapes Java spells differently. */
    static String javaRegex(String pcre) {
        String out = pcre.replace("[:alnum:]", "\\p{Alnum}").replace("[:alpha:]", "\\p{Alpha}").replace("[:digit:]", "\\p{Digit}")
                .replace("[:space:]", "\\s").replace("[:upper:]", "\\p{Upper}").replace("[:lower:]", "\\p{Lower}")
                .replace("[:punct:]", "\\p{Punct}").replace("[:xdigit:]", "\\p{XDigit}").replace("[:word:]", "\\w")
                .replace("\\Z", "$");
        return escapeLiteralBraces(out);
    }

    private static final Pattern QUANTIFIER = Pattern.compile("\\{\\d+(,\\d*)?}");

    /** PCRE reads "{" that doesn't start a {n,m} quantifier as a literal; Java rejects it ("Illegal repetition"). */
    static String escapeLiteralBraces(String re) {
        StringBuilder sb = new StringBuilder(re.length() + 8);
        for (int i = 0; i < re.length(); i++) {
            char c = re.charAt(i);
            if (c == '\\' && i + 1 < re.length()) {
                sb.append(c).append(re.charAt(++i));
                continue;
            }
            if (c == '{' && !QUANTIFIER.matcher(re).region(i, re.length()).lookingAt()) {
                sb.append("\\{");
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static List<String> dataLines(String content) {
        List<String> out = new ArrayList<>();
        for (String l : content.split("\\r?\\n")) {
            String s = l.strip();
            if (!s.isEmpty() && !s.startsWith("#")) out.add(s);
        }
        return out;
    }

    private static String baseName(String path) {
        int i = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return i < 0 ? path : path.substring(i + 1);
    }

    /** "REQUEST-942-APPLICATION-ATTACK-SQLI.conf" → "SQL injection (CRS)". */
    static String categoryFor(String file) {
        String f = baseName(file).toUpperCase(Locale.ROOT);
        if (f.contains("-SQLI")) return "SQL injection (CRS)";
        if (f.contains("-XSS")) return "Cross-site scripting (CRS)";
        if (f.contains("-LFI")) return "Local file inclusion (CRS)";
        if (f.contains("-RFI")) return "Remote file inclusion (CRS)";
        if (f.contains("-RCE")) return "Remote code execution (CRS)";
        if (f.contains("-PHP")) return "PHP injection (CRS)";
        if (f.contains("-JAVA")) return "Java attacks (CRS)";
        if (f.contains("-GENERIC")) return "Generic attacks (CRS)";
        if (f.contains("SESSION-FIXATION")) return "Session fixation (CRS)";
        if (f.contains("SCANNER")) return "Scanner detection (CRS)";
        if (f.contains("PROTOCOL-ENFORCEMENT")) return "Protocol enforcement (CRS)";
        if (f.contains("PROTOCOL-ATTACK")) return "Protocol attacks (CRS)";
        if (f.contains("MULTIPART")) return "Multipart attacks (CRS)";
        if (f.contains("METHOD")) return "Method enforcement (CRS)";
        return "CRS";
    }
}
