package com.finsight.category.api;

import com.finsight.auth.security.UserPrincipal;
import com.finsight.category.domain.CategoryType;
import com.finsight.category.dto.CategoryRequest;
import com.finsight.category.dto.CategoryResponse;
import com.finsight.category.service.CategoryService;
import com.finsight.common.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;

/**
 * Category endpoints. Every operation is scoped to the authenticated caller.
 *
 * <p>Note that no method takes a {@code userId} parameter. The owner always comes
 * from the verified token via {@code @AuthenticationPrincipal}. Accepting a user
 * id from the client — even "just for filtering" — is how IDOR vulnerabilities get
 * written.
 */
@RestController
@RequestMapping("/api/v1/categories")
@Tag(name = "Categories", description = "Income and expense categories")
@SecurityRequirement(name = "bearerAuth")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping
    @Operation(summary = "List categories visible to the caller",
               description = "Returns the caller's own categories plus the shared system set.")
    public ResponseEntity<List<CategoryResponse>> list(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "false") boolean includeArchived,
            @RequestParam(required = false) CategoryType type) {

        // A plain List, not a PageResponse: a user has tens of categories, not
        // thousands, so paging would add a round trip for every client and buy nothing.
        List<CategoryResponse> body = (type == null)
                ? categoryService.list(principal.getId(), includeArchived)
                : categoryService.listByType(principal.getId(), type);

        return ResponseEntity.ok(body);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch one category")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Found"),
            @ApiResponse(responseCode = "404", description = "No such category, or it belongs to another user",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<CategoryResponse> get(@AuthenticationPrincipal UserPrincipal principal,
                                                @PathVariable Long id) {
        return ResponseEntity.ok(categoryService.get(id, principal.getId()));
    }

    @PostMapping
    @Operation(summary = "Create a category owned by the caller")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created"),
            @ApiResponse(responseCode = "400", description = "Validation failed",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "Name already used by this caller",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<CategoryResponse> create(@AuthenticationPrincipal UserPrincipal principal,
                                                   @Valid @RequestBody CategoryRequest request) {
        CategoryResponse created = categoryService.create(principal.getId(), request);
        // 201 with a Location header pointing at the new resource, per RFC 9110.
        return ResponseEntity
                .created(UriComponentsBuilder.fromPath("/api/v1/categories/{id}")
                        .buildAndExpand(created.id()).toUri())
                .body(created);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Replace a category the caller owns",
               description = "System categories are not editable and return 404 here.")
    public ResponseEntity<CategoryResponse> update(@AuthenticationPrincipal UserPrincipal principal,
                                                   @PathVariable Long id,
                                                   @Valid @RequestBody CategoryRequest request) {
        // PUT because the body carries every mutable field; a partial update would
        // be PATCH with a different, nullable DTO.
        return ResponseEntity.ok(categoryService.update(id, principal.getId(), request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Archive a category",
               description = "Soft delete. Existing transactions keep their category assignment.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Archived (or already archived)"),
            @ApiResponse(responseCode = "404", description = "No such category owned by the caller",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<Void> archive(@AuthenticationPrincipal UserPrincipal principal,
                                        @PathVariable Long id) {
        categoryService.archive(id, principal.getId());
        // 204 No Content: the action succeeded and there is nothing useful to return.
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
