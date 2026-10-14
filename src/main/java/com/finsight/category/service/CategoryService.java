package com.finsight.category.service;

import com.finsight.category.domain.Category;
import com.finsight.category.domain.CategoryType;
import com.finsight.category.dto.CategoryRequest;
import com.finsight.category.dto.CategoryResponse;
import com.finsight.category.repository.CategoryRepository;
import com.finsight.common.cache.UserCacheEvictor;
import com.finsight.common.error.BusinessRuleException;
import com.finsight.common.error.DuplicateResourceException;
import com.finsight.common.error.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Category business logic.
 *
 * <h2>Caching</h2>
 * The visible-category list is cached under a key that always begins with the
 * user id, so two users can never read each other's entry. Mutations drop that
 * user's keys through {@link UserCacheEvictor}; see
 * docs/09-REDIS-AND-CACHING.md for why a plain {@code @CacheEvict} is not used.
 */
@Service
public class CategoryService {

    private static final Logger log = LoggerFactory.getLogger(CategoryService.class);

    /** Cache name; must match the entry registered in {@code RedisCacheConfig}. */
    public static final String CACHE_CATEGORIES = "categories";

    private final CategoryRepository categoryRepository;
    private final UserCacheEvictor cacheEvictor;

    public CategoryService(CategoryRepository categoryRepository, UserCacheEvictor cacheEvictor) {
        this.categoryRepository = categoryRepository;
        this.cacheEvictor = cacheEvictor;
    }

    /**
     * All categories the user may choose from: their own plus the system set.
     *
     * <p>The key includes {@code includeArchived}; leaving it out would let an
     * "include archived" response be served to a later "exclude archived" request.
     */
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CACHE_CATEGORIES,
               key = "#userId + ':all:' + #includeArchived",
               unless = "#result == null")
    public List<CategoryResponse> list(Long userId, boolean includeArchived) {
        return categoryRepository.findVisibleTo(userId, includeArchived).stream()
                .map(c -> CategoryResponse.from(c, userId))
                .toList();
    }

    /** Not cached: a narrow, cheap query whose result changes with the same writes. */
    @Transactional(readOnly = true)
    public List<CategoryResponse> listByType(Long userId, CategoryType type) {
        return categoryRepository.findVisibleToByType(userId, type).stream()
                .map(c -> CategoryResponse.from(c, userId))
                .toList();
    }

    @Transactional(readOnly = true)
    public CategoryResponse get(Long categoryId, Long userId) {
        Category category = categoryRepository.findVisibleToUser(categoryId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Category", categoryId));
        return CategoryResponse.from(category, userId);
    }

    /**
     * Loads a category for another service, enforcing visibility <em>and</em> type
     * agreement.
     *
     * @param expectedType the referring record's type; an EXPENSE may not be filed
     *                     under an INCOME category, or every category-breakdown
     *                     report would silently disagree with the income/expense totals
     */
    @Transactional(readOnly = true)
    public Category requireUsable(Long categoryId, Long userId, CategoryType expectedType) {
        Category category = categoryRepository.findVisibleToUser(categoryId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Category", categoryId));

        if (category.isArchived()) {
            throw new BusinessRuleException(
                    "Category '%s' is archived and cannot be assigned to new records"
                            .formatted(category.getName()));
        }
        if (expectedType != null && category.getType() != expectedType) {
            throw new BusinessRuleException(
                    "Category '%s' is %s and cannot be used for a %s record"
                            .formatted(category.getName(), category.getType(), expectedType));
        }
        return category;
    }

    @Transactional
    public CategoryResponse create(Long userId, CategoryRequest request) {
        String name = request.name().trim();

        if (categoryRepository.existsForUserByName(userId, name)) {
            throw new DuplicateResourceException(
                    "You already have a category named '%s'".formatted(name));
        }

        Category category = Category.forUser(userId, name, request.type(), request.color());
        try {
            category = categoryRepository.saveAndFlush(category);
        } catch (DataIntegrityViolationException race) {
            // Two concurrent creates both passed the check above; the partial unique
            // index uq_categories_user_name is the authority and one of them lost.
            throw new DuplicateResourceException(
                    "You already have a category named '%s'".formatted(name));
        }

        cacheEvictor.evictUser(CACHE_CATEGORIES, userId);
        log.info("Created category id={} for user id={}", category.getId(), userId);
        return CategoryResponse.from(category, userId);
    }

    @Transactional
    public CategoryResponse update(Long categoryId, Long userId, CategoryRequest request) {
        // findByIdAndUserId can never match a system category (their user_id is NULL),
        // so "built-in categories are read-only" needs no separate branch.
        Category category = categoryRepository.findByIdAndUserId(categoryId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Category", categoryId));

        String newName = request.name().trim();
        if (!newName.equalsIgnoreCase(category.getName())
                && categoryRepository.existsForUserByName(userId, newName)) {
            throw new DuplicateResourceException(
                    "You already have a category named '%s'".formatted(newName));
        }

        category.setName(newName);
        category.setType(request.type());
        category.setColor(request.color());
        // No save() call: the entity is managed within this transaction, so
        // Hibernate's dirty checking emits the UPDATE at flush time. @Version makes
        // that UPDATE optimistic-locked.

        cacheEvictor.evictUser(CACHE_CATEGORIES, userId);
        return CategoryResponse.from(category, userId);
    }

    /**
     * Archives a category instead of deleting it.
     *
     * <p>A hard delete would set {@code category_id} to NULL on every transaction
     * that referenced it (ON DELETE SET NULL), silently rewriting the user's
     * spending history. Archiving keeps history intact and merely stops the
     * category being offered for new records.
     */
    @Transactional
    public void archive(Long categoryId, Long userId) {
        Category category = categoryRepository.findByIdAndUserId(categoryId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Category", categoryId));

        if (category.isArchived()) {
            return;   // idempotent: archiving twice is not an error
        }
        category.setArchived(true);
        cacheEvictor.evictUser(CACHE_CATEGORIES, userId);
        log.info("Archived category id={} for user id={}", categoryId, userId);
    }
}
