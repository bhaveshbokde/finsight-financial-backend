package com.finsight.user.domain;

import com.finsight.common.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.Locale;
import java.util.Objects;

/**
 * A registered FinSight account.
 *
 * <p>Maps to {@code users}. The schema is owned by Flyway (V1); this class only
 * describes it, and {@code spring.jpa.hibernate.ddl-auto=validate} fails startup
 * if the two drift apart.
 *
 * <p>Deliberate omissions:
 * <ul>
 *   <li>No {@code @OneToMany} to transactions, categories or budgets. A user can
 *       own tens of thousands of transactions; a mapped collection invites
 *       someone to call {@code user.getTransactions()} and load all of them.
 *       Navigation goes the other way, through repositories that always take a
 *       {@code userId} and a {@code Pageable}.</li>
 *   <li>{@code passwordHash} has no public setter taking a raw password — the only
 *       way in is {@link #setPasswordHash(String)}, called with an already-encoded
 *       value by {@code UserService}.</li>
 * </ul>
 */
@Entity
@Table(name = "users")
public class User extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email", nullable = false, length = 254)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 120)
    private String fullName;

    /**
     * {@code EnumType.STRING}, never {@code ORDINAL}. An ordinal mapping stores the
     * enum's position, so inserting a constant in the middle of the enum silently
     * re-labels every existing row.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private Role role = Role.ROLE_USER;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /** JPA requires a no-arg constructor; it is not meant for application code. */
    protected User() {
    }

    private User(String email, String passwordHash, String fullName, Role role) {
        this.email = normalizeEmail(email);
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.role = role;
        this.enabled = true;
    }

    /**
     * Factory for a new account.
     *
     * @param passwordHash an <em>already encoded</em> password. Callers must pass the
     *                     output of a {@code PasswordEncoder}; this class never encodes
     *                     and never sees the plain text.
     */
    public static User register(String email, String passwordHash, String fullName) {
        return new User(email, passwordHash, fullName, Role.ROLE_USER);
    }

    /** Canonical form used for both storage and lookup, matching {@code uq_users_email_lower}. */
    public static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = normalizeEmail(email);
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Identity is the persistent id, and only once assigned.
     *
     * <p>Two transient instances are never equal, which is the behaviour that keeps
     * a {@code HashSet} of unsaved entities from collapsing them into one.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof User user)) {
            return false;
        }
        return id != null && id.equals(user.id);
    }

    /**
     * A constant hash code is the standard recipe for a JPA entity with a generated
     * id: it stays stable across the transition from transient to persistent, which
     * {@code Objects.hash(id)} would not.
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(User.class);
    }

    /** Never includes {@code passwordHash}. */
    @Override
    public String toString() {
        return "User{id=%d, email='%s', role=%s, enabled=%s}".formatted(id, email, role, enabled);
    }
}
