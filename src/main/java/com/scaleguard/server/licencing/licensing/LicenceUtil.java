package com.scaleguard.server.licencing.licensing;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import java.io.*;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.security.*;
import java.security.spec.EncodedKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

public class LicenceUtil {

    private static String macAddress =null;

    public static String APP_ROOT=null;

    static {
        APP_ROOT = System.getProperty("appRoot","");
        if(!APP_ROOT.isEmpty()){
            APP_ROOT+="/";
        }
    }

    public static String TEMP_DIR=null;
    public static String CONF_DIR=null;

    public static String dataDir(){
        if(TEMP_DIR==null) {
            String home = System.getProperty("user.home");
            TEMP_DIR = home + "/.scaleguard";
            try {
                new File(TEMP_DIR).mkdirs();
            } catch (Exception e) {

            }
        }
       return TEMP_DIR;
    }

    public static String getConfDir(){
        if(CONF_DIR==null) {
            String home = System.getProperty("user.home");
            CONF_DIR = home + "/.scaleguard/conf";
            try {
                File conf= new File(CONF_DIR);
                if(!conf.exists()) {
                    new File(CONF_DIR).mkdirs();
                    //copyDirectory(new File(LicenceUtil.APP_ROOT+"/conf"),conf);
                }
            } catch (Exception e) {

            }
        }
        return CONF_DIR;
    }

    public static void copyFile(String sourceLocation , String targetLocation)throws IOException {
        copyFile(new File(sourceLocation),new File(targetLocation));
    }
    public static void copyFile(File sourceLocation , File targetLocation)
            throws IOException {

            if(!targetLocation.exists()) {

                try{
                    new File(targetLocation.getParent()).mkdirs();
                }catch (Exception e){

                }

                InputStream in = new FileInputStream(sourceLocation);
                OutputStream out = new FileOutputStream(targetLocation);

                // Copy the bits from instream to outstream
                byte[] buf = new byte[1024];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
                in.close();
                out.close();
            }

    }

    public static String generateHash(String key) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] messageDigest = md.digest((key).getBytes());
        BigInteger no = new BigInteger(1, messageDigest);
        // Convert message digest into hex value
        String hashtext = no.toString(16);
        while (hashtext.length() < 32) {
            hashtext = "0" + hashtext;
        }
        return hashtext;
    }
    public static PrivateKey loadPrivateKey(String keyName) throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        // reading from resource folder
        String filePath=APP_ROOT+keyName + ".priv";
        return loadPrivateKeyFromPath(filePath);
    }

    public static PrivateKey loadPrivateKeyFromPath(String filePath) throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        try {
            byte[] privateKeyBytes = new FileInputStream(filePath).readAllBytes();
            KeyFactory privateKeyFactory = KeyFactory.getInstance("RSA");
            EncodedKeySpec privateKeySpec = new PKCS8EncodedKeySpec(privateKeyBytes);
            PrivateKey privateKey = privateKeyFactory.generatePrivate(privateKeySpec);
            return privateKey;
        }catch (Exception e){
            throw new RuntimeException(filePath);
        }
    }

    public static PublicKey loadPublicKey(String keyName) throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        String filePath=APP_ROOT+keyName+".pub";
        return loadPublicKeyFromPath(filePath);
    }

    public static PublicKey loadPublicKeyFromPath(String filePath) throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        byte[] privateKeyBytes = new FileInputStream(filePath).readAllBytes();
        KeyFactory privateKeyFactory = KeyFactory.getInstance("RSA");
        EncodedKeySpec publicKeySpec = new X509EncodedKeySpec(privateKeyBytes);
        PublicKey publicKey = privateKeyFactory.generatePublic(publicKeySpec);
        return publicKey;
    }
    public static String encryptWithPublicKey(PublicKey publicKey,String instring) throws IOException, NoSuchAlgorithmException, InvalidKeySpecException, NoSuchPaddingException, InvalidKeyException, IllegalBlockSizeException, BadPaddingException {
        Cipher encryptCipher = Cipher.getInstance("RSA");
        encryptCipher.init(Cipher.ENCRYPT_MODE, publicKey);
        return Base64.getEncoder().encodeToString(encryptCipher.doFinal(instring.getBytes()));
    }

    public static String decryptWithPrivateKey(PrivateKey publicKey,String instring) throws IOException, NoSuchAlgorithmException, InvalidKeySpecException, NoSuchPaddingException, InvalidKeyException, IllegalBlockSizeException, BadPaddingException {
        Cipher encryptCipher = Cipher.getInstance("RSA");
        encryptCipher.init(Cipher.DECRYPT_MODE, publicKey);
        return new String(encryptCipher.doFinal(Base64.getDecoder().decode(instring)));
    }

    public static String getMACId() throws Exception {
        if(macAddress!=null){
            return macAddress;
        }

        File f = Arrays.stream(new File(LicenceUtil.dataDir()).listFiles(s->s.getName().endsWith(".priv"))).findFirst().orElse(null);
        if(f!=null){
            macAddress = f.getName().split("[.]")[0];
            return macAddress;
        }


        List<NetworkInterface> nil = NetworkInterface.networkInterfaces().collect(Collectors.toList());

        for(NetworkInterface ni:nil) {
            byte[] hardwareAddress = ni.getHardwareAddress();
            if(hardwareAddress!=null) {
                String ra = getResolvedAddress(hardwareAddress);
                System.out.println(ni.getName()+",path=>"+LicenceUtil.dataDir()+"/"+ra.replace(":","")+".priv");
                if(new File(LicenceUtil.dataDir()+"/"+ra.replace(":","")+".priv").exists()){
                    macAddress=ra;
                    return macAddress;
                }
            }
        }

        InetAddress localHost = InetAddress.getLocalHost();
        NetworkInterface networkInterface  = NetworkInterface.getByInetAddress(localHost);
        byte[] macAddressBytes = networkInterface.getHardwareAddress();
        if(macAddressBytes!=null){
            macAddress = getResolvedAddress(macAddressBytes);
            return macAddress;
        }

        for(NetworkInterface ni:nil) {
            byte[] hardwareAddress = ni.getHardwareAddress();
            if(hardwareAddress!=null) {
                macAddress = getResolvedAddress(hardwareAddress);
                return macAddress;
            }
        }
        return null;
    }



    public static String getResolvedAddress(byte[] hardwareAddress){
        String[] hexadecimal = new String[hardwareAddress.length];
        for (int i = 0; i < hardwareAddress.length; i++) {
            hexadecimal[i] = String.format("%02X", hardwareAddress[i]);
        }
        return String.join(":", hexadecimal).toLowerCase();
    }

    public static String getResolvedIPAddress(byte[] hardwareAddress){
        String[] hexadecimal = new String[hardwareAddress.length];

        return new String(hardwareAddress);
    }



}
