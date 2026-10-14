package com.finsight.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Login payload.
 *
 * <p>Note the absence of {@code @Email} and of any password complexity rule: the
 * login endpoint must not reveal the registration policy, and a user whose
 * password predates a policy change must still be able to sign in. Only presence
 * and an upper length bound are enforced here — the latter so an enormous string
 * is rejected before BCrypt is asked to hash it.
 */
@Schema(name = "LoginRequest", description = "Credentials")
public record LoginRequest(

        @NotBlank(message = "email is required")
        @Size(max = 254)
        @Schema(example = "asha@example.com")
        String email,

        @NotBlank(message = "password is required")
        @Size(max = 200)
        @Schema(example = "Str0ngPassphrase", format = "password")
        String password
) {
    @Override
    public String toString() {
        return "LoginRequest{email='%s', password=***}".formatted(email);
    }
}
