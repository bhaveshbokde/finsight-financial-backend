package com.finsight.category.domain;

import com.finsight.common.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.Objects;

/**
 * An income or expense category.
 *
 * <p>Two flavours share this table:
 * <ul>
 *   <li><strong>System</strong> ({@code userId == null}, {@code system == true}) —
 *       the seeded set from migration V7. Visible to everyone, editable by no one.</li>
 *   <li><strong>User</strong> ({@code userId != null}) — owned by exactly one account.</li>
 * </ul>
 * The database enforces that combination with {@code ck_categories_system_ownership},
 * so the invariant survives direct SQL as well as application bugs.
 *
 * <p>{@code userId} is a plain {@code Long}, not a {@code @ManyToOne User}. The
 * association is never navigated — every query filters by the id — and a mapped
 * association would invite an extra SELECT (or a join) on each load for no benefit.
 * Where a relationship <em>is</em> navigated, as in {@code Transaction.category},
 * it is mapped properly.
 */
@Entity
@Table(name = "categories")
public class Category extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null for a system category. */
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "name", nullable = false, length = 60)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 10)
    private CategoryType type;

    /** Optional {@code #RRGGBB} hint for a UI; validated by a CHECK constraint. */
    @Column(name = "color", length = 7)
    private String color;

    @Column(name = "system", nullable = false)
    private boolean system;

    /**
     * Soft delete. Historical transactions keep pointing at an archived category, so
     * last year's report still reads correctly; the category simply stops being
     * offered for new transactions.
     */
    @Column(name = "archived", nullable = false)
    private boolean archived;

    protected Category() {
    }

    private Category(Long userId, String name, CategoryType type, String color, boolean system) {
        this.userId = userId;
        this.name = name;
        this.type = type;
        this.color = color;
        this.system = system;
        this.archived = false;
    }

    /** Creates a category owned by {@code userId}. */
    public static Category forUser(Long userId, String name, CategoryType type, String color) {
        return new Category(Objects.requireNonNull(userId, "userId"), name.trim(), type, color, false);
    }

    /** True when {@code userId} may rename, recolour or archive this category. */
    public boolean isEditableBy(Long candidateUserId) {
        return !system && userId != null && userId.equals(candidateUserId);
    }

    /** True when {@code userId} may file a transaction under this category. */
    public boolean isUsableBy(Long candidateUserId) {
        return !archived && (system || (userId != null && userId.equals(candidateUserId)));
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name == null ? null : name.trim();
    }

    public CategoryType getType() {
        return type;
    }

    public void setType(CategoryType type) {
        this.type = type;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public boolean isSystem() {
        return system;
    }

    public boolean isArchived() {
        return archived;
    }

    public void setArchived(boolean archived) {
        this.archived = archived;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Category category)) {
            return false;
        }
        return id != null && id.equals(category.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(Category.class);
    }

    @Override
    public String toString() {
        return "Category{id=%d, userId=%s, name='%s', type=%s, system=%s, archived=%s}"
                .formatted(id, userId, name, type, system, archived);
    }
}
