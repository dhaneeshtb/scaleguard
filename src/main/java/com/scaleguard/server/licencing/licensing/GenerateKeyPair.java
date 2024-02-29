package com.scaleguard.server.licencing.licensing;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.*;

public class GenerateKeyPair {

    public static void main(String[] args) throws IOException, NoSuchAlgorithmException {
        generatePir("key");
    }
    public static void generatePir(String name) throws IOException, NoSuchAlgorithmException{
        generatePir( name, true);
    }
    public static void generatePir(String name,boolean notExists) throws IOException, NoSuchAlgorithmException{
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(4096);
        KeyPair pair = generator.generateKeyPair();
        PrivateKey privateKey = pair.getPrivate();
        PublicKey publicKey = pair.getPublic();
        if(!new File(name+".priv").exists()) {
            try (FileOutputStream outPrivate = new FileOutputStream(name + ".priv")) {
                outPrivate.write(privateKey.getEncoded());
            }
            try (FileOutputStream outPublic = new FileOutputStream(name + ".pub")) {
                outPublic.write(publicKey.getEncoded());
            }
        }
    }
    public static PublicKey generatePirSaveOnlyPrivate(String name) throws IOException, NoSuchAlgorithmException{
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(4096);
        KeyPair pair = generator.generateKeyPair();

        PrivateKey privateKey = pair.getPrivate();
        PublicKey publicKey = pair.getPublic();
        try (FileOutputStream outPrivate = new FileOutputStream(name+".priv")) {
            outPrivate.write(privateKey.getEncoded());
        }
        return publicKey;
    }

}
