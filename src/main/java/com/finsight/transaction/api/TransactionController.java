package com.finsight.transaction.api;

import com.finsight.auth.security.UserPrincipal;
import com.finsight.common.dto.PageResponse;
import com.finsight.common.error.ApiError;
import com.finsight.transaction.dto.TransactionFilter;
import com.finsight.transaction.dto.TransactionRequest;
import com.finsight.transaction.dto.TransactionResponse;
import com.finsight.transaction.service.TransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Transaction CRUD and search.
 *
 * <p>All six endpoints are scoped to the caller's own data. A request for another
 * user's transaction id returns 404, never 403 — see
 * {@link com.finsight.common.error.ResourceNotFoundException} for the reasoning.
 */
@RestController
@RequestMapping("/api/v1/transactions")
@Tag(name = "Transactions", description = "Income and expense records")
@SecurityRequirement(name = "bearerAuth")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @GetMapping
    @Operation(
            summary = "List and filter transactions",
            description = """
                    Every filter is optional. Dates are inclusive on both ends.
                    Sort with `sort=field,direction`, for example `sort=amount,desc`.
                    Page size is capped at 200 regardless of the requested value.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of transactions"),
            @ApiResponse(responseCode = "400", description = "Invalid filter combination or date range",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<PageResponse<TransactionResponse>> list(
            @AuthenticationPrincipal UserPrincipal principal,
            @ParameterObject @Valid TransactionFilter filter,
            // Default sort is newest first, with id as a tiebreaker so that two
            // transactions on the same date keep a stable order across pages —
            // without it, page 2 can repeat or skip a row.
            @ParameterObject @PageableDefault(size = 20, sort = {"txnDate", "id"},
                    direction = Sort.Direction.DESC) Pageable pageable) {

        return ResponseEntity.ok(transactionService.list(principal.getId(), filter, pageable));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch one transaction")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Found"),
            @ApiResponse(responseCode = "404", description = "No such transaction owned by the caller",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<TransactionResponse> get(
            @AuthenticationPrincipal UserPrincipal principal,
            @Parameter(description = "Transaction id") @PathVariable Long id) {

        return ResponseEntity.ok(transactionService.get(id, principal.getId()));
    }

    @PostMapping
    @Operation(summary = "Create a transaction")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created"),
            @ApiResponse(responseCode = "400", description = "Validation failed",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Referenced category is not visible to the caller",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "422", description = "Category type does not match the transaction type",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<TransactionResponse> create(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody TransactionRequest request) {

        TransactionResponse created = transactionService.create(principal.getId(), request);
        return ResponseEntity
                .created(UriComponentsBuilder.fromPath("/api/v1/transactions/{id}")
                        .buildAndExpand(created.id()).toUri())
                .body(created);
    }

    @PutMapping("/{id}")
    @Operation(
            summary = "Replace a transaction",
            description = "Returns 409 if another request modified the same row concurrently.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated"),
            @ApiResponse(responseCode = "404", description = "No such transaction owned by the caller",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "Concurrent modification; re-read and retry",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<TransactionResponse> update(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody TransactionRequest request) {

        return ResponseEntity.ok(transactionService.update(id, principal.getId(), request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a transaction")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Deleted"),
            @ApiResponse(responseCode = "404", description = "No such transaction owned by the caller",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<Void> delete(@AuthenticationPrincipal UserPrincipal principal,
                                       @PathVariable Long id) {
        transactionService.delete(id, principal.getId());
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
