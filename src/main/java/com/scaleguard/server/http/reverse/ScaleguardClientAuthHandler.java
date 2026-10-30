package com.scaleguard.server.http.reverse;

import com.scaleguard.server.http.router.RouteTarget;
import com.scaleguard.server.security.oauth.OAuthGuard;

/** Kept for callers of the old API; the check itself is {@link OAuthGuard}. */
public class ScaleguardClientAuthHandler {
    public static OAuthGuard.Result check(RouteTarget rt) {
        return OAuthGuard.check(rt);
    }

    public static boolean checkClientAuth(RouteTarget rt) {
        return OAuthGuard.check(rt).ok();
    }
}
