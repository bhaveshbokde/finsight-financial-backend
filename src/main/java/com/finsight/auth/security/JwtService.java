package com.finsight.auth.security;

import com.finsight.common.error.ApiException;
import com.finsight.common.error.ErrorCode;
import com.finsight.config.JwtProperties;
import com.finsight.user.domain.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/**
 * Issues and verifies JSON Web Tokens.
 *
 * <h2>Algorithm</h2>
 * HS256 (HMAC-SHA256) with a shared secret. For a single-service deployment this
 * is the right choice: there is no third party that needs to verify tokens without
 * also being able to mint them. A multi-service estate would want RS256/ES256 so
 * verifiers hold only a public key — noted in docs/12 as a deliberate trade-off,
 * not an oversight.
 *
 * <h2>Why the algorithm is pinned</h2>
 * Verification is built with {@code .verifyWith(SecretKey)}, so JJWT only accepts
 * a MAC algorithm. A token whose header claims {@code "alg":"none"} — or an
 * RS256 token where the attacker supplies the "public key" as the HMAC secret —
 * fails signature verification rather than being honoured. The header's {@code alg}
 * is never used to select the verification strategy.
 *
 * <h2>Claims</h2>
 * <pre>
 *   sub  user id (as a string)
 *   iss  configured issuer, verified on every parse
 *   iat  issued-at
 *   exp  expiry
 *   jti  unique token id (enables future revocation lists)
 *   typ  ACCESS | REFRESH   (custom; prevents token confusion)
 *   eml  email
 *   rol  role
 * </pre>
 * No password hash, and nothing a holder should not already know. A JWT is signed,
 * not encrypted — anyone with the token can read the payload.
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private static final String CLAIM_TYPE = "typ";
    private static final String CLAIM_EMAIL = "eml";
    private static final String CLAIM_ROLE = "rol";

    /** HS256 requires a key of at least 256 bits; RFC 7518 §3.2 makes this a hard rule. */
    private static final int MIN_KEY_BYTES = 32;

    private final JwtProperties properties;
    private final SecretKey signingKey;
    private final Clock clock;

    /**
     * The constructor Spring uses.
     *
     * <p>{@code @Autowired} is required here, not optional: this class has two
     * constructors, and with neither marked Spring cannot choose between them —
     * it falls back to looking for a no-arg constructor and fails at context
     * startup with {@code NoSuchMethodException: JwtService.<init>()}. A single
     * constructor would need no annotation; a second one makes it mandatory.
     */
    @Autowired
    public JwtService(JwtProperties properties) {
        this(properties, Clock.systemUTC());
    }

    /** Clock-injecting constructor used by tests to exercise expiry without sleeping. */
    JwtService(JwtProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.signingKey = buildKey(properties.secret());
    }

    // --------------------------------------------------------------- issuing --

    public String generateAccessToken(UserPrincipal principal) {
        return generate(principal, TokenType.ACCESS, properties.accessTokenTtl());
    }

    public String generateRefreshToken(UserPrincipal principal) {
        return generate(principal, TokenType.REFRESH, properties.refreshTokenTtl());
    }

    public Duration accessTokenTtl() {
        return properties.accessTokenTtl();
    }

    private String generate(UserPrincipal principal, TokenType type, Duration ttl) {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(String.valueOf(principal.getId()))
                .issuer(properties.issuer())
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .claim(CLAIM_TYPE, type.name())
                .claim(CLAIM_EMAIL, principal.getEmail())
                .claim(CLAIM_ROLE, principal.getRole().name())
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    // ------------------------------------------------------------ verifying --

    /**
     * Verifies signature, issuer, expiry and token type, then rebuilds the principal.
     *
     * @throws ApiException {@link ErrorCode#TOKEN_EXPIRED} or {@link ErrorCode#TOKEN_INVALID}
     */
    public UserPrincipal verify(String token, TokenType expectedType) {
        Claims claims = parse(token);

        String actualType = claims.get(CLAIM_TYPE, String.class);
        if (!expectedType.name().equals(actualType)) {
            // e.g. someone presenting a refresh token as a bearer credential.
            throw new ApiException(ErrorCode.TOKEN_INVALID,
                    "Expected a %s token".formatted(expectedType.name().toLowerCase()));
        }

        Long userId;
        try {
            userId = Long.valueOf(claims.getSubject());
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCode.TOKEN_INVALID, "Token subject is not a valid user id");
        }

        Role role;
        try {
            role = Role.valueOf(claims.get(CLAIM_ROLE, String.class));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ApiException(ErrorCode.TOKEN_INVALID, "Token carries an unknown role");
        }

        return UserPrincipal.fromClaims(userId, claims.get(CLAIM_EMAIL, String.class), role);
    }

    private Claims parse(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(properties.issuer())
                    .clockSkewSeconds(properties.clockSkew().toSeconds())
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            throw new ApiException(ErrorCode.TOKEN_EXPIRED, ErrorCode.TOKEN_EXPIRED.defaultMessage());
        } catch (JwtException | IllegalArgumentException e) {
            // Covers a bad signature, a malformed token, an unexpected issuer and
            // an unsupported algorithm. The reason is logged but NOT returned:
            // telling an attacker *why* verification failed is free oracle access.
            log.debug("JWT rejected: {}", e.getMessage());
            throw new ApiException(ErrorCode.TOKEN_INVALID, ErrorCode.TOKEN_INVALID.defaultMessage());
        }
    }

    // ----------------------------------------------------------------- key --

    /**
     * Builds the HMAC key, accepting either a base64 secret (what
     * {@code scripts/provision-db.sh} generates) or a long raw string.
     *
     * <p>Fails fast at construction time: a weak key must break startup, not the
     * first login attempt in production.
     */
    private static SecretKey buildKey(String secret) {
        byte[] material;
        try {
            material = Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException notBase64) {
            material = secret.getBytes(StandardCharsets.UTF_8);
        }
        if (material.length < MIN_KEY_BYTES) {
            throw new IllegalStateException(
                    "finsight.jwt.secret must provide at least %d bytes (256 bits) of key material; got %d. "
                            .formatted(MIN_KEY_BYTES, material.length)
                            + "Generate one with:  head -c 64 /dev/urandom | base64 | tr -d '\\n'");
        }
        return Keys.hmacShaKeyFor(material);
    }
}
