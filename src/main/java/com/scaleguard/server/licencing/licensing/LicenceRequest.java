package com.scaleguard.server.licencing.licensing;

public class LicenceRequest {
    private String email;
    private String name;
    private String address;
    private String phone;
    private String country;
    private String zipPostalCode;
    private String deviceId;

    public String getPlan() {
        return plan;
    }

    public void setPlan(String plan) {
        this.plan = plan;
    }

    private String plan;


    public boolean isEvaluation() {
        return isEvaluation;
    }

    public void setEvaluation(boolean evaluation) {
        isEvaluation = evaluation;
    }

    private boolean isEvaluation;

    private final long timestamp=System.currentTimeMillis();

    public long getTimestamp() {
        return timestamp;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public String getZipPostalCode() {
        return zipPostalCode;
    }

    public void setZipPostalCode(String zipPostalCode) {
        this.zipPostalCode = zipPostalCode;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getPasscode() {
        return passcode;
    }

    public void setPasscode(String passcode) {
        this.passcode = passcode;
    }

    private String passcode;

}
