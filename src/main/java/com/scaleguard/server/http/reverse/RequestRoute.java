package com.scaleguard.server.http.reverse;

public interface RequestRoute {

    boolean isAuthNeeded();
    RequestRoutingResponse handle(String method,String uri,String body) throws Exception;

}
