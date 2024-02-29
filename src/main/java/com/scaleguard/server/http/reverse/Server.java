package com.scaleguard.server.http.reverse;

public interface Server {

    /**
     * Keeps the server waiting for requests.
     */
    public void listen();

    /**
     * Starts the server.
     */
    public void start() throws Exception;

    /**
     * Stops the server.
     */
    public void stop();

}