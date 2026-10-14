package com.finsight.budget.api;

import com.finsight.auth.security.UserPrincipal;
import com.finsight.budget.dto.BudgetRequest;
import com.finsight.budget.dto.BudgetResponse;
import com.finsight.budget.service.BudgetService;
import com.finsight.common.error.ApiError;
import com.finsight.common.util.DateRanges;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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

import java.time.YearMonth;
import java.util.List;

/**
 * Budget endpoints.
 *
 * <p>Every response includes live budget-versus-actual figures, so a client does
 * not have to fetch budgets and transactions separately and reconcile them itself.
 */
@RestController
@RequestMapping("/api/v1/budgets")
@Tag(name = "Budgets", description = "Monthly spending caps with live utilisation")
@SecurityRequirement(name = "bearerAuth")
public class BudgetController {

    private final BudgetService budgetService;

    public BudgetController(BudgetService budgetService) {
        this.budgetService = budgetService;
    }

    @GetMapping
    @Operation(
            summary = "List budgets for a month",
            description = "Defaults to the current UTC month when `month` is omitted.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Budgets with utilisation"),
            @ApiResponse(responseCode = "400", description = "Malformed month",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<List<BudgetResponse>> list(
            @AuthenticationPrincipal UserPrincipal principal,
            @Parameter(description = "Budget month in YYYY-MM form", example = "2026-03")
            @RequestParam(required = false) String month) {

        // Default resolved in UTC, matching the rest of the application's time
        // semantics; documented in docs/03 so a client in IST is not surprised on
        // the first/last day of a month.
        YearMonth target = (month == null || month.isBlank())
                ? YearMonth.now(java.time.ZoneOffset.UTC)
                : DateRanges.parseMonth(month);

        return ResponseEntity.ok(budgetService.listForMonth(principal.getId(), target));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch one budget with its utilisation")
    public ResponseEntity<BudgetResponse> get(@AuthenticationPrincipal UserPrincipal principal,
                                              @PathVariable Long id) {
        return ResponseEntity.ok(budgetService.get(id, principal.getId()));
    }

    @PostMapping
    @Operation(
            summary = "Create a budget",
            description = "Omit `categoryId` for an overall monthly cap.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created"),
            @ApiResponse(responseCode = "400", description = "Validation failed",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "A budget already exists for this scope and month",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "422", description = "Category is not an EXPENSE category",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<BudgetResponse> create(@AuthenticationPrincipal UserPrincipal principal,
                                                  @Valid @RequestBody BudgetRequest request) {
        BudgetResponse created = budgetService.create(principal.getId(), request);
        return ResponseEntity
                .created(UriComponentsBuilder.fromPath("/api/v1/budgets/{id}")
                        .buildAndExpand(created.id()).toUri())
                .body(created);
    }

    @PutMapping("/{id}")
    @Operation(
            summary = "Change a budget's amount or alert threshold",
            description = "The month and category are immutable; changing either returns 422.")
    public ResponseEntity<BudgetResponse> update(@AuthenticationPrincipal UserPrincipal principal,
                                                  @PathVariable Long id,
                                                  @Valid @RequestBody BudgetRequest request) {
        return ResponseEntity.ok(budgetService.update(id, principal.getId(), request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a budget")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Deleted"),
            @ApiResponse(responseCode = "404", description = "No such budget owned by the caller",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<Void> delete(@AuthenticationPrincipal UserPrincipal principal,
                                       @PathVariable Long id) {
        budgetService.delete(id, principal.getId());
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
