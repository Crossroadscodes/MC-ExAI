package com.exai.web;

import java.net.InetAddress;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Stateless-password and in-memory-session authentication for the web admin. */
public final class WebAuth {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int ITERATIONS = 210000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;
    private static final long SESSION_MILLIS = 24L * 60L * 60L * 1000L;
    private static final int MAX_FAILURES = 5;
    private static final long FAILURE_WINDOW_MILLIS = 15L * 60L * 1000L;

    private static final Map<String, Long> sessions = new ConcurrentHashMap<>();
    private static final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    private WebAuth() {}

    public static String hashPassword(String password) throws Exception {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] hash = derive(password.toCharArray(), salt, ITERATIONS, HASH_BYTES);
        return "pbkdf2-sha256$" + ITERATIONS + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(hash);
    }

    static boolean verifyPassword(String password, String encoded) {
        try {
            String[] parts = encoded.split("\\$", -1);
            if (parts.length != 4 || !"pbkdf2-sha256".equals(parts[0])) return false;
            int iterations = Integer.parseInt(parts[1]);
            if (iterations < 100000 || iterations > 1000000) return false;
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = derive(password.toCharArray(), salt, iterations, expected.length);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception ignored) {
            return false;
        }
    }

    static synchronized String login(String username, String password, String configuredUsername,
                                     String passwordHash, InetAddress address) {
        String key = address == null ? "unknown" : address.getHostAddress();
        long now = System.currentTimeMillis();
        Attempt attempt = attempts.get(key);
        if (attempt != null && now - attempt.firstAt < FAILURE_WINDOW_MILLIS && attempt.count >= MAX_FAILURES) {
            return null;
        }
        if (!safeEquals(username, configuredUsername) || !verifyPassword(password, passwordHash)) {
            if (attempt == null || now - attempt.firstAt >= FAILURE_WINDOW_MILLIS) attempt = new Attempt(now);
            attempt.count++;
            attempts.put(key, attempt);
            return null;
        }
        attempts.remove(key);
        byte[] token = new byte[32];
        RANDOM.nextBytes(token);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(token);
        sessions.put(value, now + SESSION_MILLIS);
        return value;
    }

    static boolean valid(String token) {
        if (token == null || token.isEmpty()) return false;
        Long expires = sessions.get(token);
        if (expires == null) return false;
        if (expires <= System.currentTimeMillis()) {
            sessions.remove(token);
            return false;
        }
        return true;
    }

    static void logout(String token) {
        if (token != null) sessions.remove(token);
    }

    static void invalidateAll() {
        sessions.clear();
        attempts.clear();
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations, int length) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, length * 8);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    private static boolean safeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static final class Attempt {
        final long firstAt;
        int count;
        Attempt(long firstAt) { this.firstAt = firstAt; }
    }
}
