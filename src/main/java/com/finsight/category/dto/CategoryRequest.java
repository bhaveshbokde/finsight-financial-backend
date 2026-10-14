package com.finsight.category.dto;

import com.finsight.category.domain.CategoryType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Create/update payload for a user-owned category. */
@Schema(name = "CategoryRequest")
public record CategoryRequest(

        @NotBlank(message = "name is required")
        @Size(min = 1, max = 60, message = "must be between 1 and 60 characters")
        @Schema(example = "Gym & Fitness")
        String name,

        @NotNull(message = "type is required")
        @Schema(example = "EXPENSE", allowableValues = {"INCOME", "EXPENSE"})
        CategoryType type,

        @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "must be a hex colour such as #3F51B5")
        @Schema(example = "#3F51B5", description = "Optional UI colour hint")
        String color
) {
}
