package com.scaleguard.server.http.reverse;

public class RequestRoutingResponse {

    private int status=200;
    private String body;

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    private String contentType="application/json";

    public RequestRoutingResponse(int status,String body){
        this.status=status;
        this.body=body;
    }
    public RequestRoutingResponse(String body){
        this.body=body;
    }
    public RequestRoutingResponse(int status,String body,String contentType){
       this(status,body);
       this.contentType=contentType;
    }
    /**
     * A response produced later, off the Netty I/O thread (e.g. a DuckDB query). DefaultResponseHandler writes it
     * when the future completes, so slow admin work never blocks an event loop.
     */
    private java.util.concurrent.CompletableFuture<RequestRoutingResponse> deferred;

    public java.util.concurrent.CompletableFuture<RequestRoutingResponse> getDeferred() {
        return deferred;
    }

    /** Runs {@code work} on a virtual thread and responds with its result. */
    public static RequestRoutingResponse later(java.util.concurrent.Callable<RequestRoutingResponse> work) {
        RequestRoutingResponse r = new RequestRoutingResponse(202, null);
        r.deferred = new java.util.concurrent.CompletableFuture<>();
        Thread.ofVirtual().name("sg-admin-async").start(() -> {
            try {
                r.deferred.complete(work.call());
            } catch (Throwable t) {
                r.deferred.complete(new RequestRoutingResponse(500,
                        "{\"message\":\"" + String.valueOf(t.getMessage()).replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"}"));
            }
        });
        return r;
    }

    public static RequestRoutingResponse succes(String body){
        return new RequestRoutingResponse(body);
    }
    public static RequestRoutingResponse response(int status,String body){
        return new RequestRoutingResponse(status,body);
    }

}
