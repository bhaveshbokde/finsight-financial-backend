package com.finsight.category.repository;

import com.finsight.category.domain.Category;
import com.finsight.category.domain.CategoryType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Data access for {@link Category}.
 *
 * <p>"Visible to user X" consistently means <em>X's own categories plus all system
 * categories</em>. That predicate is written once, here, rather than repeated at
 * every call site where it could drift.
 */
public interface CategoryRepository extends JpaRepository<Category, Long> {

    /** Everything the user may choose from, system entries first, then alphabetical. */
    @Query("""
            SELECT c FROM Category c
            WHERE (c.userId = :userId OR c.userId IS NULL)
              AND (:includeArchived = TRUE OR c.archived = FALSE)
            ORDER BY c.system DESC, c.name ASC
            """)
    List<Category> findVisibleTo(@Param("userId") Long userId,
                                 @Param("includeArchived") boolean includeArchived);

    /** Same visibility rule, narrowed to one type. */
    @Query("""
            SELECT c FROM Category c
            WHERE (c.userId = :userId OR c.userId IS NULL)
              AND c.type = :type
              AND c.archived = FALSE
            ORDER BY c.system DESC, c.name ASC
            """)
    List<Category> findVisibleToByType(@Param("userId") Long userId, @Param("type") CategoryType type);

    /**
     * Loads one category only if the user is allowed to see it.
     *
     * <p>Ownership is part of the WHERE clause rather than a check performed after
     * {@code findById}. A caller cannot forget it, and the result is a plain
     * "not found" for someone else's id — no information leak.
     */
    @Query("""
            SELECT c FROM Category c
            WHERE c.id = :id AND (c.userId = :userId OR c.userId IS NULL)
            """)
    Optional<Category> findVisibleToUser(@Param("id") Long id, @Param("userId") Long userId);

    /** Loads a category the user may modify: owned, never system. */
    Optional<Category> findByIdAndUserId(Long id, Long userId);

    /** Name-uniqueness pre-check within the user's own namespace. */
    @Query("""
            SELECT COUNT(c) > 0 FROM Category c
            WHERE c.userId = :userId AND lower(c.name) = lower(:name)
            """)
    boolean existsForUserByName(@Param("userId") Long userId, @Param("name") String name);

    /** Used by CSV import to resolve a category name to an id without a per-row query. */
    @Query("""
            SELECT c FROM Category c
            WHERE (c.userId = :userId OR c.userId IS NULL) AND c.archived = FALSE
            """)
    List<Category> findAllUsableBy(@Param("userId") Long userId);
}
