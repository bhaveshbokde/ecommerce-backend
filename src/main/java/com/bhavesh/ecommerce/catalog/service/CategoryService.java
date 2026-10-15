package com.bhavesh.ecommerce.catalog.service;

import com.bhavesh.ecommerce.catalog.dto.CategoryRequest;
import com.bhavesh.ecommerce.catalog.dto.CategoryResponse;
import com.bhavesh.ecommerce.catalog.entity.Category;
import com.bhavesh.ecommerce.catalog.repository.CategoryRepository;
import com.bhavesh.ecommerce.exception.ApiException;
import com.bhavesh.ecommerce.exception.ErrorCode;
import com.bhavesh.ecommerce.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Category management.
 *
 * <h2>Caching</h2>
 * Categories are read on nearly every catalogue request and written perhaps twice
 * a month — the ideal cache profile. Every mutation evicts both caches with
 * {@code allEntries = true}: the list cache cannot be selectively updated for a
 * single category, and a stale list is worse than a cold one.
 *
 * <p>Eviction happens after the method returns normally. If the transaction later
 * rolls back, the cache has been cleared for a change that did not happen — an
 * extra database read, not a wrong answer. The opposite ordering would serve stale
 * data, so this is the right way round.
 */
@Service
public class CategoryService {

    /** Named constants so an eviction cannot drift from the cache it is meant to clear. */
    public static final String CACHE_CATEGORY = "categories";
    public static final String CACHE_CATEGORY_LIST = "categoryList";

    private static final Logger log = LoggerFactory.getLogger(CategoryService.class);

    private final CategoryRepository categoryRepository;

    public CategoryService(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CACHE_CATEGORY_LIST, key = "'active'")
    public List<CategoryResponse> listActive() {
        return categoryRepository.findAllByActiveTrueOrderByNameAsc().stream()
                .map(CategoryResponse::from)
                .toList();
    }

    /** Administrative listing — includes deactivated categories, so it is not cached. */
    @Transactional(readOnly = true)
    public List<CategoryResponse> listAll() {
        return categoryRepository.findAllByOrderByNameAsc().stream()
                .map(CategoryResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CACHE_CATEGORY, key = "#id")
    public CategoryResponse get(Long id) {
        return CategoryResponse.from(findOrThrow(id));
    }

    @Transactional
    @CacheEvict(cacheNames = CACHE_CATEGORY_LIST, allEntries = true)
    public CategoryResponse create(CategoryRequest request) {
        if (categoryRepository.existsByNameIgnoreCase(request.name())) {
            throw new ApiException(ErrorCode.DUPLICATE_CATEGORY);
        }
        Category category = new Category(request.name(), request.description());
        if (category.getSlug() == null) {
            // A name of "!!!" slugifies to nothing, which would violate
            // ck_categories_slug as an opaque 500. Answer 400 instead.
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "name must contain at least one letter or digit");
        }
        Category saved = categoryRepository.save(category);
        log.info("Created category id={} slug={}", saved.getId(), saved.getSlug());
        return CategoryResponse.from(saved);
    }

    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = CACHE_CATEGORY, key = "#id"),
            @CacheEvict(cacheNames = CACHE_CATEGORY_LIST, allEntries = true)
    })
    public CategoryResponse update(Long id, CategoryRequest request) {
        Category category = findOrThrow(id);
        if (categoryRepository.existsByNameIgnoreCaseAndIdNot(request.name(), id)) {
            throw new ApiException(ErrorCode.DUPLICATE_CATEGORY);
        }
        category.rename(request.name());
        category.setDescription(request.description());
        // No explicit save: the entity is managed inside this transaction, and the
        // dirty check flushes the change at commit.
        return CategoryResponse.from(category);
    }

    /**
     * Soft-disables a category, hiding it and its products from the catalogue.
     *
     * <p>Preferred over deletion, which {@code ON DELETE RESTRICT} would block for
     * any category that has ever held a product — and which would in any case
     * destroy the context of past orders.
     */
    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = CACHE_CATEGORY, key = "#id"),
            @CacheEvict(cacheNames = CACHE_CATEGORY_LIST, allEntries = true)
    })
    public CategoryResponse setActive(Long id, boolean active) {
        Category category = findOrThrow(id);
        category.setActive(active);
        log.info("Category id={} active={}", id, active);
        return CategoryResponse.from(category);
    }

    /**
     * Hard-deletes an empty category.
     *
     * <p>Counts products first so a category in use produces a 409 that says why,
     * rather than the foreign-key violation {@code ON DELETE RESTRICT} would raise.
     */
    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = CACHE_CATEGORY, key = "#id"),
            @CacheEvict(cacheNames = CACHE_CATEGORY_LIST, allEntries = true)
    })
    public void delete(Long id) {
        Category category = findOrThrow(id);
        long products = categoryRepository.countProducts(id);
        if (products > 0) {
            throw new ApiException(ErrorCode.CATEGORY_IN_USE,
                    "Category '%s' still has %d product(s); deactivate it instead"
                            .formatted(category.getName(), products));
        }
        categoryRepository.delete(category);
        log.info("Deleted category id={}", id);
    }

    /** Package-private: {@code ProductService} needs the entity, not a DTO. */
    Category findOrThrow(Long id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Category", id));
    }

    /** Exposed to {@code ProductService}, which must resolve a category on create. */
    public Category requireCategory(Long id) {
        return findOrThrow(id);
    }
}
