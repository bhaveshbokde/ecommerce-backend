package com.bhavesh.ecommerce.catalog.repository;

import com.bhavesh.ecommerce.catalog.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** Data access for {@link Category}. */
public interface CategoryRepository extends JpaRepository<Category, Long> {

    /** Lower-cased in the query to match the functional index {@code uq_categories_name_lower}. */
    @Query("SELECT c FROM Category c WHERE lower(c.name) = lower(:name)")
    Optional<Category> findByNameIgnoreCase(@Param("name") String name);

    Optional<Category> findBySlug(String slug);

    @Query("SELECT COUNT(c) > 0 FROM Category c WHERE lower(c.name) = lower(:name)")
    boolean existsByNameIgnoreCase(@Param("name") String name);

    @Query("SELECT COUNT(c) > 0 FROM Category c WHERE lower(c.name) = lower(:name) AND c.id <> :excludeId")
    boolean existsByNameIgnoreCaseAndIdNot(@Param("name") String name, @Param("excludeId") Long excludeId);

    List<Category> findAllByActiveTrueOrderByNameAsc();

    List<Category> findAllByOrderByNameAsc();

    /**
     * Guards category deletion. {@code ON DELETE RESTRICT} would also stop it, but
     * as a 500-shaped constraint violation; asking first produces a 409 that says
     * what to do about it.
     */
    @Query("SELECT COUNT(p) FROM Product p WHERE p.category.id = :categoryId")
    long countProducts(@Param("categoryId") Long categoryId);
}
