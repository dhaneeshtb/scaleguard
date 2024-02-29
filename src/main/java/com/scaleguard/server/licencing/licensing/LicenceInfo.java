package com.scaleguard.server.licencing.licensing;

import java.io.Serializable;

public class LicenceInfo extends LicenceRequest implements Serializable {

    private String email;
    private String name;
    private String address;
    private String phone;
    private String country;
    private String zipPostalCode;
    private String deviceId;
    private String activationKey;
    private String activationHash;

    private String dbKey;

    public String getDbKey() {
        return dbKey;
    }

    public void setDbKey(String dbKey) {
        this.dbKey = dbKey;
    }

    public String getActivationHash() {
        return activationHash;
    }

    public void setActivationHash(String activationHash) {
        this.activationHash = activationHash;
    }

    public String getActivationKey() {
        return activationKey;
    }

    public void setActivationKey(String activationKey) {
        this.activationKey = activationKey;
    }

    public String getPasscode() {
        return passcode;
    }

    public void setPasscode(String passcode) {
        this.passcode = passcode;
    }

    private String passcode;


    private long timestamp;
    private long activationTimestamp;



    private long approver;

    private String licenceId;

    public String getEmail() {
        return email;
    }

    public String getName() {
        return name;
    }

    public String getAddress() {
        return address;
    }

    public String getPhone() {
        return phone;
    }

    public String getCountry() {
        return country;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public String getZipPostalCode() {
        return zipPostalCode;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public long getActivationTimestamp() {
        return activationTimestamp;
    }

    public long getApprover() {
        return approver;
    }

    public String getLicenceId() {
        return licenceId;
    }

    public long getExpiryTimestamp() {
        return expiryTimestamp;
    }

    public String getPlan() {
        return plan;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public void setZipPostalCode(String zipPostalCode) {
        this.zipPostalCode = zipPostalCode;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }



    public void setActivationTimestamp(long activationTimestamp) {
        this.activationTimestamp = activationTimestamp;
    }

    public void setApprover(long approver) {
        this.approver = approver;
    }

    public void setLicenceId(String licenceId) {
        this.licenceId = licenceId;
    }

    public void setExpiryTimestamp(long expiryTimestamp) {
        this.expiryTimestamp = expiryTimestamp;
    }

    public void setPlan(String plan) {
        this.plan = plan;
    }

    private long expiryTimestamp;

    private String plan;


}
