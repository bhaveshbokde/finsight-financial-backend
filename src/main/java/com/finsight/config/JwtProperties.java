package com.finsight.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Binds {@code finsight.jwt.*}.
 *
 * <p>Declared as a {@code record} so the values are immutable after binding and
 * cannot be mutated by any bean that receives them. {@code @Validated} makes a
 * missing or too-short signing secret a <em>startup</em> failure rather than a
 * runtime 500 on the first login.
 *
 * @param secret          base64-encoded HMAC-SHA key material; must decode to >= 32 bytes (256 bits)
 * @param issuer          value placed in, and required by, the {@code iss} claim
 * @param accessTokenTtl  lifetime of an access token
 * @param refreshTokenTtl lifetime of a refresh token
 * @param clockSkew       leeway allowed when validating {@code exp} / {@code nbf}
 */
@ConfigurationProperties(prefix = "finsight.jwt")
@Validated
public record JwtProperties(

        @NotBlank(message = "finsight.jwt.secret must be set (see .env.example)")
        String secret,

        @DefaultValue("finsight")
        String issuer,

        @DefaultValue("PT30M")
        Duration accessTokenTtl,

        @DefaultValue("P7D")
        Duration refreshTokenTtl,

        @DefaultValue("PT30S")
        Duration clockSkew
) {
}
