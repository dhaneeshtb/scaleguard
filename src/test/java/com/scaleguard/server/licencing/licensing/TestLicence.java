package com.scaleguard.server.licencing.licensing;

public class TestLicence {

    public static void main(String[] args) {
        try {
            LicenceRequest lr = new LicenceRequest();
            lr.setName("Test");
            lr.setPasscode("123456");
            String request=LicenceManager.generateLicenceRequest(lr);
            System.out.println("request->"+request);
            String licenceResponse = LicenceServer.generateLicence(request);
            System.out.println("licenceResponse->"+licenceResponse);
            LicenceManager.activateLicence(licenceResponse,lr.getPasscode());
            LicenceInfo linfo = LicenceManager.readLicence();
            System.out.println(linfo.getLicenceId());
            System.out.println(linfo.getExpiryTimestamp());
        }catch (Exception e){
            e.printStackTrace();
        }

    }
}
