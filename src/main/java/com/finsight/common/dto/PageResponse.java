package com.finsight.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * A stable, minimal envelope for paginated results.
 *
 * <p>Spring's {@code Page} serialises to a large, unstable JSON structure (it leaks
 * {@code pageable}, {@code sort.unsorted}, and in newer versions logs a warning
 * about exactly this). Mapping to our own record keeps the public API contract
 * under our control and independent of the Spring Data version.
 *
 * @param <T> element type after DTO mapping
 */
@Schema(name = "PageResponse", description = "Paginated collection envelope")
public record PageResponse<T>(

        List<T> content,

        @Schema(example = "0", description = "Zero-based page index")
        int page,

        @Schema(example = "20")
        int size,

        @Schema(example = "137", description = "Total matching elements across all pages")
        long totalElements,

        @Schema(example = "7")
        int totalPages,

        boolean first,
        boolean last,

        @Schema(example = "false", description = "True when this page contains no elements")
        boolean empty
) {

    /** Maps a Spring Data {@code Page<E>} of entities into a {@code PageResponse<T>} of DTOs. */
    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast(),
                page.isEmpty()
        );
    }

    /** Overload for a page whose elements are already DTOs. */
    public static <T> PageResponse<T> from(Page<T> page) {
        return from(page, Function.identity());
    }
}
