package com.finsight.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Exchanges a refresh token for a new access token.
 *
 * <p>The token travels in the body rather than the {@code Authorization} header so
 * that {@link com.finsight.auth.security.JwtAuthenticationFilter} — which only ever
 * accepts {@link com.finsight.auth.security.TokenType#ACCESS} — is not involved.
 */
@Schema(name = "RefreshRequest")
public record RefreshRequest(

        @NotBlank(message = "refreshToken is required")
        @Schema(description = "The refresh token issued at login")
        String refreshToken
) {
    @Override
    public String toString() {
        return "RefreshRequest{refreshToken=***}";
    }
}
