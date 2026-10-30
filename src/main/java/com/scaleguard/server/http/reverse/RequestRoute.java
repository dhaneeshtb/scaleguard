package com.scaleguard.server.http.reverse;

public interface RequestRoute {

    boolean isAuthNeeded();
    RequestRoutingResponse handle(String method,String uri,String body) throws Exception;


    /** True if {@link #handle} should get the query string too ("/path?a=b"); otherwise just the path. */
    default boolean receivesQueryString() {
        return false;
    }
}
