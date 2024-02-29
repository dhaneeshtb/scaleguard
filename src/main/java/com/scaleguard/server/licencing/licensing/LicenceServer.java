package com.scaleguard.server.licencing.licensing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.EncodedKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.UUID;

public class LicenceServer {



    private static PrivateKey loadPrivateKey() throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        // reading from resource folder
        byte[] privateKeyBytes =  new FileInputStream("key.priv").readAllBytes();
        KeyFactory privateKeyFactory = KeyFactory.getInstance("RSA");
        EncodedKeySpec privateKeySpec = new PKCS8EncodedKeySpec(privateKeyBytes);
        PrivateKey privateKey = privateKeyFactory.generatePrivate(privateKeySpec);
        return privateKey;
    }

    private static String getActivationKey(String licenceId,String macId) throws NoSuchAlgorithmException {
        String key=licenceId+macId;
        return LicenceUtil.generateHash(key);
    }

    private static String getActivationHash(String macId) throws NoSuchAlgorithmException {
        String key=macId;
        return LicenceUtil.generateHash(key);
    }
    public static String generateLicence(String licenceRequest) throws NoSuchPaddingException, NoSuchAlgorithmException, IOException, InvalidKeySpecException, InvalidKeyException, IllegalBlockSizeException, BadPaddingException, InvalidAlgorithmParameterException {

        return generateLicence(licenceRequest.trim(),true,30);
    }

    public static String generateLicence(String licenceRequest,int numDayes) throws NoSuchPaddingException, NoSuchAlgorithmException, IOException, InvalidKeySpecException, InvalidKeyException, IllegalBlockSizeException, BadPaddingException, InvalidAlgorithmParameterException {

        return generateLicence(licenceRequest.trim(),true,numDayes);
    }


    public static String generateLicence(String licenceRequest,boolean isEvaluation,int numDayes) throws NoSuchPaddingException, NoSuchAlgorithmException, IOException, InvalidKeySpecException, InvalidKeyException, IllegalBlockSizeException, BadPaddingException, InvalidAlgorithmParameterException {
        ObjectMapper om =new ObjectMapper();
        JsonNode node = om.readTree(Base64.getDecoder().decode(licenceRequest));
        String key = node.get("key").asText();
        String message = node.get("payload").asText();
        Cipher decryptCipher = Cipher.getInstance("RSA");
        decryptCipher.init(Cipher.DECRYPT_MODE, loadPrivateKey());
        byte[] inbytes = Base64.getDecoder().decode(key);
        byte[] decoded = decryptCipher.doFinal(inbytes);
        String decodedKey = new String(decoded,StandardCharsets.UTF_8);
        String decodedMessage = AESUtils.decrypt(message,decodedKey);
        LicenceInfo licence = om.readValue(decodedMessage,LicenceInfo.class);
        String lid=UUID.randomUUID().toString();
        licence.setLicenceId(lid);
        licence.setActivationKey(getActivationKey(lid,licence.getDeviceId()));
        licence.setActivationHash(getActivationHash(licence.getDeviceId()));
        licence.setEvaluation(isEvaluation);
        long timestamp = System.currentTimeMillis();
        licence.setActivationTimestamp(timestamp);
        long days30 = numDayes*24*60*60*1000l;
        licence.setExpiryTimestamp(timestamp+days30);
        String licenceJson =  om.valueToTree(licence).toString();
        String combinedPassword=(licence.getPasscode()!=null?licence.getPasscode():"")+licence.getDeviceId();
        licenceJson = AESUtils.encrypt(licenceJson,combinedPassword);
//        Cipher encryptCipher = Cipher.getInstance("RSA");
//        encryptCipher.init(Cipher.ENCRYPT_MODE, loadPrivateKey());
//        byte[] licenceBytes= encryptCipher.doFinal(licenceJson.getBytes());
        return licenceJson;//ase64.getEncoder().encodeToString(licenceJson);
    }


    public static void main(String[] args) {

        try {


            System.out.println(generateLicence("eyJrZXkiOiJ0NG02N3pNeGhKQVJKT3MydUVrenZIVXZtOTMzTlBLRDNUUFJQRXd3VzVOaEdxd1NmQXZ2T0c4aENKUGxjWWs4S0J2d1owNUZFaDlSSE5EZUk3NUJjY2Y4V3RDS3lCR0dNdjErT094aFBiaDAyVGlpNHZBcWVvTko5cHhzQWJXTTNkV1NRcHpYc3RaOUFDN0tIWDlqdGNLRmdacG82bm9LY1owVXk1eWlKSmhndzlsM1U1UWhwcFJDb1VJZEEyWGloQUQ4MFdRV0k1Zjh2b1p0Mzk4VFB6Q1pmLzYvUisxZk9FcEtheDJOWEN0N2NFVWxCR2xJT0xVMmE1UGpKRHJhMVhrVG92VXZUMXUycHBOY041b3UxOXI5Z3FrQkRhdWpJWFJLQWpKWnJaQ2FNTS9QMjIrT1d4VzhkdjFvOHRlck9GSHM0N3BSeTRPS0NsTW9oVmtrL2s1RFJmRHllODVwem9RWkgwcFhqTkFPTEVrbThrMVFuVldWNEU0Y205Y1RJeXhZNGpUd3hEb3MxdjlWY1dNY2lLMTNlWVdqT3dmcDBsaGZGVG9iTFpsbTNuM2VLbXlaVHNaeVlQeEQ0a1dRZDAxWUlpRCtRREtGaXpRQTVONzBqcjlDUFo1RHlTZWFNQ1QxcjNTVkhDQ2U0bHdyRHlCemliUnFHcUR6b3EzWFBhY2M1czVwbzY4dWpmaVc4ZCs0akIzVktEVmhnbFlOSlJpc1lUdVJZcEpEcGlabWNxOVhETkdCL1N5TmdGemp5NFVHZGRxMVVmaFppTmZtbU5id2lDNTIwdklyK2tWMTUwaEtFU3NOaXNaS3J6U0JjNmZoUzFYYzdyMk1LK0xYbFQvM29vVmVZQTNvNUE4L0FlK3lDWTFBTUZhS2xDZkQzYW1UVzM1RUxXcz0iLCJwYXlsb2FkIjoidE1YZWhzUnlSejRJK3lhRVlxdDVVeWpwdnE1RTBSZEx1YVRyWTE0NHlGWXpYVlphRkEvaVYzWXJCWHd4MnduNTZ1RGZ2WS9VMEdFNXdIZmNldGM2aSs1eHNHL1RvSmhuQmNSMmJZeDZBa29NY1ZKb3ZNdThEYzFiRFpGTjRKSE5yRmt3N2hqV254VDhueGI2SDdkeGNNa0lla2w2RWJxQlJUeTdBNW9ISWppckxFWERNMldjejNUQ1VZTVd5RDhIZUFKVEN6Uk1WZUZwcGtCSUZiWlZEa3N1NkVVS1hrMURORDRWcERDSWVobz0ifQ=="));


        }catch (Exception e){
            e.printStackTrace();
        }

    }

//    private static String getMACId() throws Exception {
//        InetAddress localHost = InetAddress.getLocalHost();
//        NetworkInterface ni = NetworkInterface.getByInetAddress(localHost);
//        byte[] hardwareAddress = ni.getHardwareAddress();
//
//        String[] hexadecimal = new String[hardwareAddress.length];
//        for (int i = 0; i < hardwareAddress.length; i++) {
//            hexadecimal[i] = String.format("%02X", hardwareAddress[i]);
//        }
//        String macAddress = String.join(":", hexadecimal);
//        return macAddress.toLowerCase();
//    }
}
