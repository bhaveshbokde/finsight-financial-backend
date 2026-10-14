package com.finsight.auth.security;

import com.finsight.common.error.ApiException;
import com.finsight.common.error.ErrorCode;
import com.finsight.config.JwtProperties;
import com.finsight.user.domain.Role;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Token issuing and verification.
 *
 * <p>A fixed {@link Clock} is injected so expiry can be exercised by moving time
 * forward instead of by sleeping — the test is then both instant and deterministic.
 */
class JwtServiceTest {

    private static final String SECRET =
            Base64.getEncoder().encodeToString(
                    "a-test-signing-key-that-is-definitely-long-enough-for-hs256".getBytes(StandardCharsets.UTF_8));

    private static final Instant NOW = Instant.parse("2026-10-14T10:00:00Z");

    private JwtProperties properties;
    private JwtService jwtService;
    private UserPrincipal principal;

    @BeforeEach
    void setUp() {
        properties = new JwtProperties(SECRET, "finsight",
                Duration.ofMinutes(30), Duration.ofDays(7), Duration.ofSeconds(30));
        jwtService = new JwtService(properties, Clock.fixed(NOW, ZoneOffset.UTC));
        principal = new UserPrincipal(42L, "asha@example.com", null, Role.ROLE_USER, true);
    }

    @Nested
    @DisplayName("issuing")
    class Issuing {

        @Test
        @DisplayName("an access token round-trips the identity")
        void accessTokenRoundTrip() {
            String token = jwtService.generateAccessToken(principal);
            UserPrincipal verified = jwtService.verify(token, TokenType.ACCESS);

            assertThat(verified.getId()).isEqualTo(42L);
            assertThat(verified.getEmail()).isEqualTo("asha@example.com");
            assertThat(verified.getRole()).isEqualTo(Role.ROLE_USER);
        }

        @Test
        @DisplayName("a refresh token round-trips too")
        void refreshTokenRoundTrip() {
            String token = jwtService.generateRefreshToken(principal);
            assertThat(jwtService.verify(token, TokenType.REFRESH).getId()).isEqualTo(42L);
        }

        @Test
        @DisplayName("the payload carries no password material")
        void noSecretsInPayload() {
            // A JWT is signed, not encrypted — anyone holding it can read the body.
            // Decoding it here is exactly what an attacker would do.
            String token = jwtService.generateAccessToken(
                    new UserPrincipal(42L, "asha@example.com", "$2a$10$somebcrypthash",
                            Role.ROLE_USER, true));

            String payload = new String(Base64.getUrlDecoder()
                    .decode(token.split("\\.")[1]), StandardCharsets.UTF_8);

            assertThat(payload).doesNotContain("$2a$10$");
            assertThat(payload).doesNotContain("password");
            assertThat(payload).contains("asha@example.com");   // not secret
        }

        @Test
        @DisplayName("every token gets a distinct jti")
        void uniqueTokenIds() {
            // Distinct ids are what a future revocation list would key on.
            String first = jwtService.generateAccessToken(principal);
            String second = jwtService.generateAccessToken(principal);
            assertThat(first).isNotEqualTo(second);
        }
    }

    @Nested
    @DisplayName("verification")
    class Verification {

        @Test
        @DisplayName("rejects a refresh token presented as an access token")
        void rejectsTokenTypeConfusion() {
            // Without the typ claim this would succeed, silently turning a 7-day
            // refresh token into a 7-day API credential.
            String refresh = jwtService.generateRefreshToken(principal);

            assertThatThrownBy(() -> jwtService.verify(refresh, TokenType.ACCESS))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).errorCode())
                    .isEqualTo(ErrorCode.TOKEN_INVALID);
        }

        @Test
        @DisplayName("rejects an access token presented for refresh")
        void rejectsReverseTypeConfusion() {
            String access = jwtService.generateAccessToken(principal);
            assertThatThrownBy(() -> jwtService.verify(access, TokenType.REFRESH))
                    .isInstanceOf(ApiException.class);
        }

        @Test
        @DisplayName("reports an expired token distinctly from an invalid one")
        void detectsExpiry() {
            String token = jwtService.generateAccessToken(principal);

            // Move 31 minutes past issue — beyond the 30-minute TTL and the
            // 30-second allowed clock skew.
            JwtService later = new JwtService(properties,
                    Clock.fixed(NOW.plus(Duration.ofMinutes(31)), ZoneOffset.UTC));

            assertThatThrownBy(() -> later.verify(token, TokenType.ACCESS))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).errorCode())
                    // TOKEN_EXPIRED, not TOKEN_INVALID: the client should refresh,
                    // not force the user to log in again.
                    .isEqualTo(ErrorCode.TOKEN_EXPIRED);
        }

        @Test
        @DisplayName("tolerates a small clock difference")
        void allowsConfiguredSkew() {
            String token = jwtService.generateAccessToken(principal);

            // 15 seconds past expiry, within the 30-second skew allowance.
            JwtService slightlyLater = new JwtService(properties,
                    Clock.fixed(NOW.plus(Duration.ofMinutes(30)).plusSeconds(15), ZoneOffset.UTC));

            assertThat(slightlyLater.verify(token, TokenType.ACCESS).getId()).isEqualTo(42L);
        }

        @Test
        @DisplayName("rejects a token signed with a different key")
        void rejectsForeignSignature() {
            // The core guarantee: possession of the payload is not enough, you
            // must hold the signing key.
            JwtProperties attackerProps = new JwtProperties(
                    Base64.getEncoder().encodeToString(
                            "a-completely-different-key-also-long-enough-xx".getBytes(StandardCharsets.UTF_8)),
                    "finsight", Duration.ofMinutes(30), Duration.ofDays(7), Duration.ofSeconds(30));

            String forged = new JwtService(attackerProps, Clock.fixed(NOW, ZoneOffset.UTC))
                    .generateAccessToken(principal);

            assertThatThrownBy(() -> jwtService.verify(forged, TokenType.ACCESS))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).errorCode())
                    .isEqualTo(ErrorCode.TOKEN_INVALID);
        }

        @Test
        @DisplayName("rejects a tampered payload")
        void rejectsTampering() {
            String token = jwtService.generateAccessToken(principal);
            String[] parts = token.split("\\.");

            // Re-encode the body claiming to be user 1 instead of user 42, keeping
            // the original signature. HMAC verification must catch this.
            String tamperedBody = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    ("{\"sub\":\"1\",\"iss\":\"finsight\",\"typ\":\"ACCESS\","
                            + "\"eml\":\"attacker@example.com\",\"rol\":\"ROLE_ADMIN\","
                            + "\"exp\":" + NOW.plusSeconds(3600).getEpochSecond() + "}")
                            .getBytes(StandardCharsets.UTF_8));

            String tampered = parts[0] + "." + tamperedBody + "." + parts[2];

            assertThatThrownBy(() -> jwtService.verify(tampered, TokenType.ACCESS))
                    .isInstanceOf(ApiException.class);
        }

        @Test
        @DisplayName("rejects an unsigned 'alg: none' token")
        void rejectsAlgNone() {
            // The classic JWT bypass. Building verification with verifyWith(SecretKey)
            // means JJWT only accepts a MAC algorithm, so an unsigned token cannot
            // be honoured however its header is written.
            String unsigned = Jwts.builder()
                    .subject("42")
                    .issuer("finsight")
                    .claim("typ", "ACCESS")
                    .claim("eml", "attacker@example.com")
                    .claim("rol", "ROLE_ADMIN")
                    .expiration(java.util.Date.from(NOW.plusSeconds(3600)))
                    .compact();   // no signWith => alg: none

            assertThatThrownBy(() -> jwtService.verify(unsigned, TokenType.ACCESS))
                    .isInstanceOf(ApiException.class);
        }

        @Test
        @DisplayName("rejects a token from a different issuer")
        void rejectsWrongIssuer() {
            // Prevents a token minted by a sibling service that happens to share
            // the key from being accepted here.
            String foreign = Jwts.builder()
                    .subject("42")
                    .issuer("some-other-service")
                    .claim("typ", "ACCESS")
                    .claim("eml", "asha@example.com")
                    .claim("rol", "ROLE_USER")
                    .expiration(java.util.Date.from(NOW.plusSeconds(3600)))
                    .signWith(Keys.hmacShaKeyFor(Base64.getDecoder().decode(SECRET)), Jwts.SIG.HS256)
                    .compact();

            assertThatThrownBy(() -> jwtService.verify(foreign, TokenType.ACCESS))
                    .isInstanceOf(ApiException.class);
        }

        @Test
        @DisplayName("rejects structurally broken input without leaking why")
        void rejectsGarbage() {
            for (String garbage : new String[]{"", "not.a.token", "a.b.c", "....", "null"}) {
                assertThatThrownBy(() -> jwtService.verify(garbage, TokenType.ACCESS))
                        .isInstanceOf(ApiException.class)
                        // Always the same generic message: a verbose error is an
                        // oracle an attacker can probe.
                        .hasMessage(ErrorCode.TOKEN_INVALID.defaultMessage());
            }
        }
    }

    @Nested
    @DisplayName("key validation")
    class KeyValidation {

        @Test
        @DisplayName("refuses to start with a key shorter than 256 bits")
        void rejectsWeakKey() {
            // Fails at construction, so a weak secret breaks startup rather than
            // the first login in production.
            JwtProperties weak = new JwtProperties("c2hvcnQ=", "finsight",
                    Duration.ofMinutes(30), Duration.ofDays(7), Duration.ofSeconds(30));

            assertThatThrownBy(() -> new JwtService(weak))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("256 bits");
        }

        @Test
        @DisplayName("accepts a long raw (non-base64) secret")
        void acceptsRawSecret() {
            JwtProperties raw = new JwtProperties(
                    "this-is-a-plain-text-secret-well-over-thirty-two-bytes-long",
                    "finsight", Duration.ofMinutes(30), Duration.ofDays(7), Duration.ofSeconds(30));

            JwtService service = new JwtService(raw);
            String token = service.generateAccessToken(principal);
            assertThat(service.verify(token, TokenType.ACCESS).getId()).isEqualTo(42L);
        }
    }
}
