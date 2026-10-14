package com.finsight.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Tokens plus the minimum identity a client needs to render a session.
 *
 * <p>{@code expiresInSeconds} is the access token's remaining lifetime. Returning
 * it saves the client from decoding the JWT to find {@code exp}, and means the
 * server can change the TTL without a client release.
 */
@Schema(name = "AuthResponse", description = "Issued tokens and the authenticated identity")
public record AuthResponse(

        @Schema(description = "Bearer token for the Authorization header")
        String accessToken,

        @Schema(description = "Token accepted only by POST /api/v1/auth/refresh")
        String refreshToken,

        @Schema(example = "Bearer")
        String tokenType,

        @Schema(example = "1800", description = "Access-token lifetime in seconds")
        long expiresInSeconds,

        @Schema(example = "1")
        Long userId,

        @Schema(example = "asha@example.com")
        String email,

        @Schema(example = "Asha Iyer")
        String fullName,

        @Schema(example = "ROLE_USER")
        String role
) {
    public static AuthResponse of(String accessToken, String refreshToken, long expiresInSeconds,
                                  Long userId, String email, String fullName, String role) {
        return new AuthResponse(accessToken, refreshToken, "Bearer", expiresInSeconds,
                userId, email, fullName, role);
    }

    /** Never prints token material. */
    @Override
    public String toString() {
        return "AuthResponse{userId=%d, email='%s', role=%s, accessToken=***, refreshToken=***}"
                .formatted(userId, email, role);
    }
}
