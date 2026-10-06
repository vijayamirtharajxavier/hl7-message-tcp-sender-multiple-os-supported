package io.hl7sender.core.auth;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Hashes passwords with PBKDF2-HMAC-SHA256 and a random salt, in the form
 * {@code pbkdf2-sha256$ITERATIONS$SALT$HASH} (Base64). Checking is constant-time, and a hash made with fewer
 * iterations still verifies, so the work factor can be raised later.
 */
public final class PasswordHasher {

    /** OWASP's 2023 recommendation for PBKDF2-HMAC-SHA256. */
    public static final int ITERATIONS = 600_000;
    /** Shortest password accepted. */
    public static final int MIN_LENGTH = 8;

    private static final String PREFIX = "pbkdf2-sha256";
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {
    }

    public static String hash(String password) {
        return hash(password, ITERATIONS);
    }

    static String hash(String password, int iterations) {
        check(password);
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] h = derive(password, salt, iterations);
        Base64.Encoder b64 = Base64.getEncoder().withoutPadding();
        return PREFIX + "$" + iterations + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(h);
    }

    /** True if {@code password} matches {@code stored}. A malformed hash never matches. */
    public static boolean verify(String password, String stored) {
        if (password == null || stored == null) {
            return false;
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !parts[0].equals(PREFIX)) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            if (iterations < 1 || iterations > 10_000_000) {
                return false;
            }
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            return MessageDigest.isEqual(expected, derive(password, salt, iterations));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Throws if the password is too short to accept. */
    public static void check(String password) {
        if (password == null || password.length() < MIN_LENGTH) {
            throw new IllegalArgumentException("A password needs at least " + MIN_LENGTH + " characters");
        }
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 is not available", e);
        } finally {
            spec.clearPassword();
        }
    }
}
