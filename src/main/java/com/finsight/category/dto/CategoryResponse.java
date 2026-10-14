package com.finsight.category.dto;

import com.finsight.category.domain.Category;
import com.finsight.category.domain.CategoryType;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A category as returned by the API.
 *
 * <p>{@code editable} is computed per caller rather than stored: the same system
 * category is read-only for every user, while a user's own category is editable
 * only by them. Sending it saves a client from re-deriving the rule and getting
 * it subtly wrong.
 */
@Schema(name = "CategoryResponse")
public record CategoryResponse(
        Long id,
        String name,
        CategoryType type,
        String color,

        @Schema(description = "True for built-in categories shared by all users")
        boolean system,

        boolean archived,

        @Schema(description = "True when the authenticated caller may modify this category")
        boolean editable
) {
    public static CategoryResponse from(Category category, Long viewerUserId) {
        return new CategoryResponse(
                category.getId(),
                category.getName(),
                category.getType(),
                category.getColor(),
                category.isSystem(),
                category.isArchived(),
                category.isEditableBy(viewerUserId));
    }
}
