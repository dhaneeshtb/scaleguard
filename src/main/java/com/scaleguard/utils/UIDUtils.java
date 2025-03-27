package com.scaleguard.utils;

import java.util.Random;

public class UIDUtils {

    public static String create(int length) {
        char[] chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890".toCharArray();
        Random random = new Random(System.currentTimeMillis());
        char[] id = new char[length];
        for (int i = 0; i < length; i++) {
            id[i] = chars[random.nextInt(chars.length)];
        }
        return new String(id);
    }
}
