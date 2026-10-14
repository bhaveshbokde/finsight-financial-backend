package com.finsight.auth.service;

import com.finsight.auth.dto.AuthResponse;
import com.finsight.auth.dto.LoginRequest;
import com.finsight.auth.dto.RefreshRequest;
import com.finsight.auth.dto.RegisterRequest;
import com.finsight.auth.security.JwtService;
import com.finsight.auth.security.TokenType;
import com.finsight.auth.security.UserPrincipal;
import com.finsight.common.error.DuplicateResourceException;
import com.finsight.common.error.ErrorCode;
import com.finsight.common.error.ResourceNotFoundException;
import com.finsight.user.domain.User;
import com.finsight.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration, login and token refresh.
 *
 * <p>Transaction boundaries sit on this class, not on the controller and not on
 * the repository: a service method is the unit of business work, so it is the
 * natural unit of atomicity. Read-only methods are annotated
 * {@code @Transactional(readOnly = true)}, which lets Hibernate skip dirty-checking
 * and lets the driver flag the transaction as read-only to PostgreSQL.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       AuthenticationManager authenticationManager,
                       JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
    }

    /**
     * Creates an account and immediately issues tokens.
     *
     * <p>The {@code existsByEmailIgnoreCase} pre-check gives a clean 409 for the
     * ordinary case, but it is <em>not</em> the guarantee: two concurrent requests
     * can both see "no such user" before either inserts. The unique index
     * {@code uq_users_email_lower} is the guarantee, and the catch below converts
     * the loser's constraint violation into the same 409 rather than a 500.
     * Checking without the constraint would be a race; relying on the constraint
     * without the check would make the common path throw.
     */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = User.normalizeEmail(request.email());

        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new DuplicateResourceException(ErrorCode.EMAIL_ALREADY_REGISTERED,
                    "An account with this email already exists");
        }

        // The plain password exists only as a local here and is never logged,
        // stored, or placed on an entity.
        User user = User.register(email, passwordEncoder.encode(request.password()), request.fullName().trim());

        try {
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException race) {
            throw new DuplicateResourceException(ErrorCode.EMAIL_ALREADY_REGISTERED,
                    "An account with this email already exists");
        }

        log.info("Registered user id={} email={}", user.getId(), maskEmail(user.getEmail()));
        return issueTokens(UserPrincipal.from(user), user.getFullName());
    }

    /**
     * Authenticates credentials and issues tokens.
     *
     * <p>Delegating to {@link AuthenticationManager} rather than comparing hashes
     * by hand means the {@code enabled} flag, the password-encoder upgrade path and
     * the "do not reveal which half was wrong" behaviour all come from Spring
     * Security instead of being re-implemented (and eventually mis-implemented) here.
     */
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        String email = User.normalizeEmail(request.email());

        // Throws BadCredentialsException / DisabledException, mapped to 401 by
        // GlobalExceptionHandler. No catch here: there is nothing useful to add.
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(email, request.password()));

        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new ResourceNotFoundException("User", email));

        log.info("Login succeeded for user id={}", principal.getId());
        return issueTokens(principal, user.getFullName());
    }

    /**
     * Exchanges a refresh token for a fresh pair.
     *
     * <p>The user is re-read from the database here — unlike the per-request filter
     * path, which trusts the token's claims. Refresh is rare, so the extra query is
     * cheap, and it is the point at which a disabled account or a changed role
     * actually takes effect.
     *
     * <p><strong>Known limitation:</strong> refresh tokens are stateless and are not
     * stored, so a leaked refresh token cannot be revoked before it expires and
     * refresh-token rotation is not implemented. Documented in docs/07 and docs/12.
     */
    @Transactional(readOnly = true)
    public AuthResponse refresh(RefreshRequest request) {
        UserPrincipal fromToken = jwtService.verify(request.refreshToken(), TokenType.REFRESH);

        User user = userRepository.findById(fromToken.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User", fromToken.getId()));

        if (!user.isEnabled()) {
            throw new com.finsight.common.error.ApiException(ErrorCode.ACCOUNT_DISABLED,
                    ErrorCode.ACCOUNT_DISABLED.defaultMessage());
        }

        log.info("Refreshed tokens for user id={}", user.getId());
        return issueTokens(UserPrincipal.from(user), user.getFullName());
    }

    /** Current account details for the authenticated caller. */
    @Transactional(readOnly = true)
    public User currentUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
    }

    private AuthResponse issueTokens(UserPrincipal principal, String fullName) {
        return AuthResponse.of(
                jwtService.generateAccessToken(principal),
                jwtService.generateRefreshToken(principal),
                jwtService.accessTokenTtl().toSeconds(),
                principal.getId(),
                principal.getEmail(),
                fullName,
                principal.getRole().name());
    }

    /** {@code asha@example.com} becomes {@code a***a@example.com} for logs. */
    private String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 1) {
            return "***" + (at < 0 ? "" : email.substring(at));
        }
        return email.charAt(0) + "***" + email.charAt(at - 1) + email.substring(at);
    }
}
