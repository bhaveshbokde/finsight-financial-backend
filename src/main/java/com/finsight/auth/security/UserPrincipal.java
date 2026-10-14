package com.finsight.auth.security;

import com.finsight.user.domain.Role;
import com.finsight.user.domain.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * The authenticated caller, as seen by the rest of the application.
 *
 * <p>This is the object behind {@code SecurityContextHolder.getContext()
 * .getAuthentication().getPrincipal()} and behind every
 * {@code @AuthenticationPrincipal UserPrincipal} controller parameter.
 *
 * <p>It carries the database {@code id} in addition to the username, which is the
 * detail that makes per-user data isolation cheap: a controller passes
 * {@code principal.getId()} straight into a repository method such as
 * {@code findByIdAndUserId(...)}, so ownership is part of the SQL predicate rather
 * than an afterthought check on a loaded row.
 *
 * <p>It is a distinct type from {@link User} on purpose — the entity is a
 * persistence concern and should not be attached to the security context, where a
 * stale or lazily-initialised instance could outlive its persistence context.
 */
public class UserPrincipal implements UserDetails {

    private final Long id;
    private final String email;
    private final String passwordHash;
    private final Role role;
    private final boolean enabled;

    public UserPrincipal(Long id, String email, String passwordHash, Role role, boolean enabled) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.enabled = enabled;
    }

    public static UserPrincipal from(User user) {
        return new UserPrincipal(user.getId(), user.getEmail(), user.getPasswordHash(),
                user.getRole(), user.isEnabled());
    }

    /**
     * Rebuilds a principal from verified JWT claims, without a database round trip.
     *
     * <p>This is what makes the filter chain stateless. The trade-off is explicit:
     * a role change or an account disablement does not take effect until the
     * current access token expires (30 minutes by default). See
     * docs/07-SECURITY-AND-AUTHENTICATION.md.
     */
    public static UserPrincipal fromClaims(Long id, String email, Role role) {
        return new UserPrincipal(id, email, null, role, true);
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public Role getRole() {
        return role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(role.authority()));
    }

    /** Null for a principal reconstructed from a token — no hash is ever put in a JWT. */
    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    /** Never includes the password hash. */
    @Override
    public String toString() {
        return "UserPrincipal{id=%d, email='%s', role=%s}".formatted(id, email, role);
    }
}
