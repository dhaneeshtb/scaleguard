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
    public static RequestRoutingResponse succes(String body){
        return new RequestRoutingResponse(body);
    }
    public static RequestRoutingResponse response(int status,String body){
        return new RequestRoutingResponse(status,body);
    }

}
