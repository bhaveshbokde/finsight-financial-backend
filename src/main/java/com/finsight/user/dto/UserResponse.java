package com.finsight.user.dto;

import com.finsight.user.domain.User;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * The public projection of a {@link User}.
 *
 * <p>Returning the entity directly would serialise {@code passwordHash} and the
 * optimistic-locking {@code version}, and would couple the wire format to the
 * table. A dedicated response record makes the exposed surface an explicit,
 * reviewable list.
 */
@Schema(name = "UserResponse", description = "Account details of the authenticated user")
public record UserResponse(
        Long id,
        String email,
        String fullName,
        String role,
        boolean enabled,
        Instant createdAt
) {
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getRole().name(),
                user.isEnabled(),
                user.getCreatedAt());
    }
}
