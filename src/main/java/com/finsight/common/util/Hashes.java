package com.finsight.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * SHA-256 helpers used for CSV file checksums, per-row idempotency keys and
 * duplicate fingerprints.
 *
 * <p>These hashes are <em>not</em> password hashes. Passwords go through BCrypt
 * ({@code PasswordEncoder}); a fast digest like SHA-256 would be entirely
 * inappropriate there because it is cheap to brute-force.
 */
public final class Hashes {

    private Hashes() {
        throw new AssertionError("Utility class — do not instantiate");
    }

    /** Lower-case hex SHA-256 of the given bytes. */
    public static String sha256Hex(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    /** Lower-case hex SHA-256 of the UTF-8 encoding of {@code value}. */
    public static String sha256Hex(String value) {
        return sha256Hex(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Joins the parts with a delimiter that cannot occur in the normalised inputs
     * and hashes the result.
     *
     * <p>Using {@code ''} (unit separator) rather than, say, {@code '|'}
     * prevents a crafted description like {@code "a|b"} from colliding with the
     * field pair {@code ("a", "b")}.
     */
    public static String fingerprint(Object... parts) {
        StringBuilder sb = new StringBuilder(128);
        for (Object part : parts) {
            sb.append(part == null ? "" : normalize(part.toString())).append('');
        }
        return sha256Hex(sb.toString());
    }

    /** Trims, collapses internal whitespace and lower-cases, so trivial formatting differences hash alike. */
    private static String normalize(String value) {
        return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS for every conforming JRE; unreachable.
            throw new IllegalStateException("SHA-256 unavailable in this JVM", e);
        }
    }
}
