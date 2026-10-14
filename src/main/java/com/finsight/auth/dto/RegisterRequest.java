package com.finsight.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Registration payload.
 *
 * <p>Validation lives on the DTO rather than in the service so that malformed
 * input is rejected at the edge, before any business code or database round trip.
 * {@code @Valid} on the controller parameter is what triggers it.
 *
 * <p>The password rule requires length plus three character classes. Length is the
 * dominant factor in resistance to offline cracking, which is why the minimum is
 * 10 rather than the once-conventional 8.
 */
@Schema(name = "RegisterRequest", description = "New account details")
public record RegisterRequest(

        @NotBlank(message = "email is required")
        @Email(message = "must be a well-formed email address")
        @Size(max = 254, message = "must not exceed 254 characters")
        @Schema(example = "asha@example.com")
        String email,

        @NotBlank(message = "password is required")
        @Size(min = 10, max = 100, message = "must be between 10 and 100 characters")
        @Pattern(
                regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+$",
                message = "must contain at least one lowercase letter, one uppercase letter and one digit")
        @Schema(example = "Str0ngPassphrase", format = "password")
        String password,

        @NotBlank(message = "fullName is required")
        @Size(min = 2, max = 120, message = "must be between 2 and 120 characters")
        @Schema(example = "Asha Iyer")
        String fullName
) {
    /** Keeps the password out of accidental {@code log.info("{}", request)} calls. */
    @Override
    public String toString() {
        return "RegisterRequest{email='%s', fullName='%s', password=***}".formatted(email, fullName);
    }
}
