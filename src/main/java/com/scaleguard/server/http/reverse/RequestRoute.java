package com.scaleguard.server.http.reverse;

import org.shredzone.acme4j.exception.AcmeException;

import java.io.IOException;

public interface RequestRoute {

    boolean isAuthNeeded();
    RequestRoutingResponse handle(String method,String uri,String body) throws Exception;

}
