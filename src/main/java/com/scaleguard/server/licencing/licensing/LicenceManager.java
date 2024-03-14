package com.scaleguard.server.licencing.licensing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

public class LicenceManager {
    private static final Logger LOGGER
            = LoggerFactory.getLogger(LicenceManager.class);

    public static class ActivateRequest{
        private String payload;
        private String passcode;

        public String getPayload() {
            return payload;
        }

        public void setPayload(String payload) {
            this.payload = payload;
        }

        public String getPasscode() {
            return passcode;
        }

        public void setPasscode(String passcode) {
            this.passcode = passcode;
        }
    }



    public static ObjectMapper om =new ObjectMapper();

    private static LicenceInfo currentLicence=null;

    public static PublicKey loadPublicKey() throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        // reading from resource folder
       // byte[] privateKeyBytes = getClass().getResourceAsStream("/key.pub").readAllBytes();
       return LicenceUtil.loadPublicKey("key");
    }

    public void loadLicence(Consumer<LicenceInfo> consumer){
        LicenceInfo li=  getLicence();
        consumer.accept(li);
    }

    private LicenceInfo getLicence(){
        return null;
    }

    public static String readLicenceAsString() throws Exception {
        return om.valueToTree(readLicence()).toString();
    }

    public static LicenceInfo getCurrentLicenceInfo(String devId)throws Exception{

        PrivateKey key = LicenceUtil.loadPrivateKeyFromPath(LicenceUtil.dataDir()+"/"+devId.replace(":","")+".priv");
        JsonNode licence = om.readTree(new FileInputStream(LicenceUtil.dataDir()+"/"+"licence.key"));
        String keyInfo = LicenceUtil.decryptWithPrivateKey(key, licence.get("keyInfo").asText());
        System.out.println(keyInfo);
        String activation = AESUtils.decrypt(licence.get("activationInfo").asText(), keyInfo);
        System.out.println(activation);
        String licenceDetails = AESUtils.decrypt(licence.get("licenceDetails").asText(), keyInfo);
        LicenceInfo linfo = om.readValue(licenceDetails, LicenceInfo.class);
        JsonNode ainfo = om.readTree(activation);
        linfo.setActivationKey(ainfo.get("activationKey").asText());
        linfo.setActivationHash(ainfo.get("activationHash").asText());
        linfo.setDbKey(ainfo.has("dbKey")?ainfo.get("dbKey").asText():ainfo.get("activationKey").asText());
        return linfo;
    }

    public static LicenceInfo readLicence() throws Exception {
        if(currentLicence==null) {
            String devId = getMACId();
            LicenceInfo linfo=getCurrentLicenceInfo(devId);
            checkValidity(linfo);
            if (!LicenceUtil.generateHash(devId).equalsIgnoreCase(LicenceUtil.generateHash(linfo.getDeviceId().replace(":","")))) {
                throw new RuntimeException("licence is not valid");
            }
            currentLicence = linfo;
        }else{
            checkValidity(currentLicence);
        }
        return currentLicence;
    }


    public static String activateLicence(String licenceBody) throws Exception {
        ActivateRequest activateRequest = om.readValue(licenceBody,ActivateRequest.class);//.getBytes(StandardCharsets.UTF_8);
        String licenceKey =  activateLicence(activateRequest.getPayload().trim(),activateRequest.getPasscode().trim());
        ObjectNode on = om.createObjectNode();
        on.put("licenceId",licenceKey);
        return on.toString();
    }

    public static String activateLicence(String licenceString,String passcode) throws Exception {

        LicenceInfo cLn=null;
        try{
            String devId = getMACId();
            cLn=getCurrentLicenceInfo(devId);
        }catch (Exception e){
            LOGGER.error("activate licence failed ",e);

        }

        String combinedPassword=(passcode!=null?passcode:"")+getMACId();
        if(combinedPassword!=null){
            licenceString= AESUtils.decrypt(licenceString,combinedPassword);
        }
        ObjectNode licenceNode = (ObjectNode) om.readTree(licenceString);
        String licencePath =LicenceUtil.dataDir()+"/"+(licenceNode.get("deviceId").asText().replace(":",""));
        PublicKey publicKey = GenerateKeyPair.generatePirSaveOnlyPrivate(licencePath);
        ObjectNode node = om.createObjectNode();
        node.put("licenceId",licenceNode.get("licenceId").asText());
        node.put("activationKey",licenceNode.get("activationKey").asText());
        node.put("activationHash",licenceNode.get("activationHash").asText());
        node.put("localActivationTime",new Date().getTime());

        if(cLn!=null) {
            node.put("dbKey", Optional.ofNullable(cLn.getDbKey()).orElse(cLn.getActivationKey()));
        }

        String activationInfo = AESUtils.encrypt(node.toString(),combinedPassword);
        licenceNode.remove("passcode");
        licenceNode.remove("activationKey");
        licenceNode.remove("activationHash");



        String licenceDetails = AESUtils.encrypt(licenceNode.toString(),combinedPassword);
        String keyInfo = LicenceUtil.encryptWithPublicKey(publicKey,combinedPassword);
        ObjectNode licenceInfo = om.createObjectNode();
        licenceInfo.put("activationInfo",activationInfo);
        licenceInfo.put("licenceDetails",licenceDetails);
        licenceInfo.put("keyInfo",keyInfo);
        try (FileOutputStream outPrivate = new FileOutputStream(LicenceUtil.dataDir()+"/"+"licence.key")) {
            outPrivate.write(licenceInfo.toString().getBytes());
        }
        currentLicence=null;
        return licenceNode.get("licenceId").asText();
    }

    private static void  checkValidity(LicenceInfo info){
        if(info.getExpiryTimestamp()<System.currentTimeMillis()){
            throw new RuntimeException("Licence expired");
        }
    }

    public static boolean  checkValidity(){
        return  !(currentLicence==null || currentLicence.getExpiryTimestamp()<System.currentTimeMillis());
    }



    public static String generateLicenceRequest(String request) throws Exception {
        LicenceRequest li = om.readValue(request,LicenceRequest.class);
        return generateLicenceRequest(li);
    }
    public static String generateLicenceRequest(LicenceRequest lr) throws IOException, NoSuchAlgorithmException, InvalidKeySpecException, NoSuchPaddingException, InvalidKeyException, IllegalBlockSizeException, BadPaddingException, InvalidAlgorithmParameterException {
        PublicKey publicKey = loadPublicKey();
        Cipher encryptCipher = Cipher.getInstance("RSA");
        encryptCipher.init(Cipher.ENCRYPT_MODE, publicKey);
        lr.setDeviceId(getMACId());
        String secretMessage = om.valueToTree(lr).toString();
        String key =UUID.randomUUID().toString();
        String encryptedKey = new String(Base64.getEncoder().encode(encryptCipher.doFinal(key.getBytes())));
        String encryptedMessage = AESUtils.encrypt(secretMessage,key);
        ObjectNode node=om.createObjectNode();
        node.put("key",encryptedKey);
        node.put("payload",encryptedMessage);
        return new String(Base64.getEncoder().encode(node.toString().getBytes(StandardCharsets.UTF_8)));
    }



    public static String getMACId() throws SocketException, UnknownHostException {
        return LicenceUtil.getMACId();
    }


    public static String getFailureMessageJson(String message,String code){
        ObjectNode node = om.createObjectNode();
        node.put("message",message);
        node.put("code",code);
        return node.toString();
    }
    public static String licenceExpiredJSON(){
        return getFailureMessageJson("Licence expired","1001").toString();
    }


}
