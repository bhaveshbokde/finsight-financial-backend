package com.finsight.user.repository;

import com.finsight.user.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Data access for {@link User}.
 *
 * <p>Spring Data generates the implementation at runtime from the method names and
 * {@code @Query} annotations; there is no hand-written class behind this interface.
 *
 * <p>Both finders match {@code uq_users_email_lower} by lower-casing in the query,
 * so the index is actually used rather than scanned around.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Case-insensitive lookup used by login and by {@code UserDetailsService}.
     *
     * <p>The parameter is bound by JPA, not concatenated, so a value such as
     * {@code "' OR 1=1 --"} is compared as a literal string and cannot alter the
     * statement.
     */
    @Query("SELECT u FROM User u WHERE lower(u.email) = lower(:email)")
    Optional<User> findByEmailIgnoreCase(@Param("email") String email);

    /** Registration pre-check. A race is still possible; the unique index is the real guarantee. */
    @Query("SELECT COUNT(u) > 0 FROM User u WHERE lower(u.email) = lower(:email)")
    boolean existsByEmailIgnoreCase(@Param("email") String email);
}
