package org.jumpserver.chen.framework.utils;

public class HexUtils {
    public static String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        hexString.append("0x");
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
