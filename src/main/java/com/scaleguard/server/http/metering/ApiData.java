package com.scaleguard.server.http.metering;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

public class ApiData {

    private String id;

    private String path;

    private String method;

    private String target;

    private int status;

    private LocalDateTime started;

    private LocalDateTime ended;

    private Map<String, String> headers;

    private Map<String, String> requestParams;

    public String getId() {
        return id;
    }

    public ApiData setId(String id) {
        this.id = id;
        return this;
    }

    public String getPath() {
        return path;
    }

    public ApiData setPath(String path) {
        this.path = path;
        return this;
    }

    public String getTarget() {
        return target;
    }

    public ApiData setTarget(String target) {
        this.target = target;
        return this;
    }

    public int getStatus() {
        return status;
    }

    public ApiData setStatus(int status) {
        this.status = status;
        return this;
    }

    public LocalDateTime getStarted() {
        return started;
    }

    public ApiData setStarted(LocalDateTime started) {
        this.started = started;
        return this;
    }

    public LocalDateTime getEnded() {
        return ended;
    }

    public ApiData setEnded(LocalDateTime ended) {
        this.ended = ended;
        return this;
    }

    public Map<String, String> getHeaders() {
        if (this.headers == null) {
            this.headers = new HashMap<>();
        }
        return headers;
    }

    public ApiData setHeaders(Map<String, String> headers) {
        this.headers = headers;
        return this;
    }

    public Map<String, String> getRequestParams() {
        return requestParams;
    }

    public ApiData setRequestParams(Map<String, String> requestParams) {
        this.requestParams = requestParams;
        return this;
    }

    public String getMethod() {
        return method;
    }

    public ApiData setMethod(String method) {
        this.method = method;
        return this;
    }

    public long getDuration() {
        if (this.ended == null) {
            ended = LocalDateTime.now(ZoneOffset.UTC);
        }
        return ChronoUnit.MILLIS.between(started, ended);
    }

    @Override
    public String toString() {
        return "ApiData{" +
                "id='" + id + '\'' +
                ", path='" + path + '\'' +
                ", target='" + target + '\'' +
                ", status=" + status +
                ", started=" + started +
                ", ended=" + ended +
                ", headers=" + headers +
                ", requestParams=" + requestParams +
                '}';
    }
}
