package com.scaleguard.server.http.reverse;

public interface Server {

    /**
     * Keeps the server waiting for requests.
     */
     void listen();

    /**
     * Starts the server.
     */
     void start() throws Exception;

    /**
     * Stops the server.
     */
     void stop();

}