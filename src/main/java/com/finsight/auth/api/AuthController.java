package com.finsight.auth.api;

import com.finsight.auth.dto.AuthResponse;
import com.finsight.auth.dto.LoginRequest;
import com.finsight.auth.dto.RefreshRequest;
import com.finsight.auth.dto.RegisterRequest;
import com.finsight.auth.security.UserPrincipal;
import com.finsight.auth.service.AuthService;
import com.finsight.common.error.ApiError;
import com.finsight.user.dto.UserResponse;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication endpoints.
 *
 * <p>Controllers in this application do three things and nothing else: bind and
 * validate input, delegate to a service, and shape the HTTP response. No business
 * rules, no repository access, no transaction management.
 *
 * <p>{@code @RestController} = {@code @Controller} + {@code @ResponseBody}, so
 * every return value is serialised to the response body rather than resolved as a
 * view name.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Registration, login and token lifecycle")
public class AuthController {

    private final AuthService authService;

    /**
     * Constructor injection. With a single constructor Spring injects it without
     * {@code @Autowired}, the dependency is {@code final} and therefore immutable,
     * and the class can be instantiated in a unit test with {@code new}.
     */
    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @Operation(
            summary = "Register a new account",
            description = "Creates an account and returns an access/refresh token pair. Public endpoint.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Account created"),
            @ApiResponse(responseCode = "400", description = "Validation failed",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "Email already registered",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        // 201 Created: a new resource now exists. The tokens are returned in the
        // body so the client does not have to immediately call /login.
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/login")
    @Operation(
            summary = "Authenticate and obtain tokens",
            description = "Returns 401 for both an unknown email and a wrong password, deliberately.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Authenticated"),
            @ApiResponse(responseCode = "401", description = "Invalid credentials or disabled account",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        // 200, not 201: logging in creates no server-side resource. This API is
        // stateless, so there is no session object to point a Location header at.
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/refresh")
    @Operation(
            summary = "Exchange a refresh token for a new token pair",
            description = "Send the refresh token in the body, not the Authorization header.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "New tokens issued"),
            @ApiResponse(responseCode = "401", description = "Refresh token expired or invalid",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(authService.refresh(request));
    }

    @GetMapping("/me")
    @Operation(
            summary = "Details of the authenticated account",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Account details"),
            @ApiResponse(responseCode = "401", description = "Missing, expired or invalid token",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    public ResponseEntity<UserResponse> me(@AuthenticationPrincipal UserPrincipal principal) {
        // @AuthenticationPrincipal injects whatever JwtAuthenticationFilter put in
        // the SecurityContext. Never read an identity from a request parameter or
        // header: the caller controls those, but not the verified token.
        return ResponseEntity.ok(UserResponse.from(authService.currentUser(principal.getId())));
    }
}
