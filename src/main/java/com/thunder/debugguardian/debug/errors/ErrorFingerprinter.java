package com.thunder.debugguardian.debug.errors;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class ErrorFingerprinter {
    public static String fingerprint(Throwable t) {
        StackTraceElement[] st = t.getStackTrace();
        StringBuilder sb = new StringBuilder(t.getClass().getName());
        int limit = Math.min(st.length, 5);
        for (int i = 0; i < limit; i++) {
            sb.append(";").append(st[i].toString());
        }
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(sb.toString().getBytes());
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(sb.toString().hashCode());
        }
    }
}