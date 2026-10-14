package com.finsight.imports.api;

import com.finsight.auth.security.UserPrincipal;
import com.finsight.common.dto.PageResponse;
import com.finsight.common.error.ApiError;
import com.finsight.imports.dto.ImportJobResponse;
import com.finsight.imports.dto.ImportRowErrorResponse;
import com.finsight.imports.service.ImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * CSV import endpoints.
 *
 * <h2>The two response codes, and why both exist</h2>
 * <ul>
 *   <li><strong>200 OK</strong> — the file was small enough to process inline and
 *       the body already carries the final counts. Nothing to poll.</li>
 *   <li><strong>202 Accepted</strong> — the upload is durably stored and queued.
 *       The body carries a job id and {@code status: ACCEPTED}; poll
 *       {@code GET /api/v1/imports/{id}} until {@code terminal} is true.</li>
 * </ul>
 * Returning 200 for a queued job would tell the client the work is done when no
 * transaction has been inserted yet. 202 is the status that means exactly
 * "accepted, not yet performed".
 */
@RestController
@RequestMapping("/api/v1/imports")
@Tag(name = "CSV Imports", description = "Bulk transaction import with asynchronous processing")
@SecurityRequirement(name = "bearerAuth")
public class ImportController {

    private final ImportService importService;

    public ImportController(ImportService importService) {
        this.importService = importService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "Upload a CSV of transactions",
            description = """
                    Expected columns: `date,type,amount,description,merchant,category`.
                    The first four are required. Header names are matched case-insensitively
                    and common aliases are accepted.

                    Files with more rows than `finsight.csv-import.async-threshold-rows`
                    are queued and return 202; smaller files are processed inline and
                    return 200 with final counts.

                    Re-uploading an identical file imports nothing: every row carries an
                    idempotency key derived from the file checksum and the row number, so
                    the rows are reported as duplicates instead of being inserted twice.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Processed inline; body holds the final result"),
            @ApiResponse(responseCode = "202", description = "Accepted for asynchronous processing"),
            @ApiResponse(responseCode = "400", description = "The file is empty, not CSV, or has no usable header",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "413", description = "File exceeds the size or row limit",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "415", description = "Unsupported content type",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<ImportJobResponse> upload(
            @AuthenticationPrincipal UserPrincipal principal,
            @Parameter(description = "The CSV file", required = true)
            @RequestParam("file") MultipartFile file) {

        ImportJobResponse job = importService.upload(principal.getId(), file);

        var location = UriComponentsBuilder.fromPath("/api/v1/imports/{id}")
                .buildAndExpand(job.id()).toUri();

        // The status the service produced decides the code: a terminal status means
        // the work is finished, a non-terminal one means it is merely queued.
        HttpStatus status = job.terminal() ? HttpStatus.OK : HttpStatus.ACCEPTED;

        return ResponseEntity.status(status).location(location).body(job);
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "Import status and result summary",
            description = "Poll this after a 202 until `terminal` is true.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Current status"),
            @ApiResponse(responseCode = "404", description = "No such import owned by the caller",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<ImportJobResponse> status(@AuthenticationPrincipal UserPrincipal principal,
                                                     @PathVariable Long id) {
        return ResponseEntity.ok(importService.getStatus(id, principal.getId()));
    }

    @GetMapping
    @Operation(summary = "List the caller's imports, newest first")
    public ResponseEntity<PageResponse<ImportJobResponse>> list(
            @AuthenticationPrincipal UserPrincipal principal,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {

        return ResponseEntity.ok(importService.listJobs(principal.getId(), pageable));
    }

    @GetMapping("/{id}/errors")
    @Operation(
            summary = "Rows that were rejected",
            description = """
                    Paginated, ordered by line number. Each entry names the line, a stable
                    error code, a human-readable reason and the original line text, so the
                    user can fix those rows and re-upload just them.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of row errors; empty when none failed"),
            @ApiResponse(responseCode = "404", description = "No such import owned by the caller",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<PageResponse<ImportRowErrorResponse>> errors(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @ParameterObject @PageableDefault(size = 50) Pageable pageable) {

        return ResponseEntity.ok(importService.listErrors(id, principal.getId(), pageable));
    }
}
