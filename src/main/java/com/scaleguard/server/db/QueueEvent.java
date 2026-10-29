package com.scaleguard.server.db;

/**
 * DB model for a single queue event managed by the embedded async engine.
 *
 * <p>Lifecycle of {@code status}:
 * <ul>
 *   <li>{@code pending}     — written to DB on intake, not yet dispatched</li>
 *   <li>{@code dispatching} — subscriber picked it up, HTTP call in-flight</li>
 *   <li>{@code success}     — backend returned a 2xx response</li>
 *   <li>{@code failed}      — dispatch attempt failed; will be retried up to MAX_ATTEMPTS</li>
 *   <li>{@code dead}        — exhausted all retry attempts; needs manual intervention</li>
 * </ul>
 * </p>
 */
public class QueueEvent implements DBObject {

    /** Unique event ID (UUID). Maps to DBObject.getId(). */
    private String id;

    /** The async engine instance that owns this event. */
    private String engineId;

    /** Current lifecycle status (pending / dispatching / success / failed / dead). */
    private String status;

    /** JSON-serialised {@link com.scaleguard.server.http.cache.ProxyRequest}. */
    private String payload;

    /** Number of dispatch attempts made so far. */
    private int attempt;

    /** HTTP response code returned by the backend on the last attempt. 0 if not dispatched yet. */
    private int responseCode;

    /** Truncated response body (≤500 chars) from the last dispatch attempt. */
    private String responseBody;

    /** Last error message captured on a failed dispatch attempt. */
    private String errorMessage;

    /** Epoch millis when the event was first created (= mts). */
    private long createdAt;

    /** Epoch millis when the event was last updated (= uts). */
    private long updatedAt;

    /** Epoch millis when the last dispatch attempt was started. */
    private long dispatchedAt;

    // -------------------------------------------------------------------------
    // DBObject contract
    // -------------------------------------------------------------------------

    @Override
    public String getId() {
        return id;
    }

    @Override
    public void setId(String id) {
        this.id = id;
    }

    // -------------------------------------------------------------------------
    // Getters / Setters
    // -------------------------------------------------------------------------

    public String getEngineId() {
        return engineId;
    }

    public void setEngineId(String engineId) {
        this.engineId = engineId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public int getAttempt() {
        return attempt;
    }

    public void setAttempt(int attempt) {
        this.attempt = attempt;
    }

    public int getResponseCode() {
        return responseCode;
    }

    public void setResponseCode(int responseCode) {
        this.responseCode = responseCode;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public void setResponseBody(String responseBody) {
        this.responseBody = responseBody;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public long getDispatchedAt() {
        return dispatchedAt;
    }

    public void setDispatchedAt(long dispatchedAt) {
        this.dispatchedAt = dispatchedAt;
    }
}
