package com.bhavesh.ecommerce.catalog.repository;

import com.bhavesh.ecommerce.catalog.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Data access for {@link Product}.
 *
 * <p>Two things recur in these queries and are worth naming once.
 *
 * <h2>JOIN FETCH</h2>
 * {@code Product.category} is {@code LAZY}, so a plain {@code findAll} followed by
 * {@code product.getCategory().getName()} in a mapper fires one extra SELECT per
 * row — twenty products become twenty-one queries. The listing queries fetch the
 * category in the same statement.
 *
 * <p>On a paginated query, {@code JOIN FETCH} across a to-<em>one</em> association
 * is safe. Across a to-<em>many</em> it would not be: Hibernate cannot apply
 * {@code LIMIT} to a result set the join has multiplied, so it silently falls back
 * to loading everything and paginating in memory. That is why no query here
 * fetch-joins a collection.
 *
 * <h2>The count query</h2>
 * Paginated finders declare an explicit {@code countQuery} without the fetch join.
 * Counting rows needs no associated data, and the derived count query would
 * otherwise inherit the join for nothing.
 */
public interface ProductRepository extends JpaRepository<Product, Long> {

    @Query("SELECT p FROM Product p JOIN FETCH p.category WHERE p.id = :id")
    Optional<Product> findByIdWithCategory(@Param("id") Long id);

    Optional<Product> findBySku(String sku);

    boolean existsBySku(String sku);

    /** Batch load for cart and checkout; avoids one query per line. */
    @Query("SELECT p FROM Product p JOIN FETCH p.category WHERE p.id IN :ids")
    List<Product> findAllByIdWithCategory(@Param("ids") Collection<Long> ids);

    /** The public catalogue listing: active products in active categories only. */
    @Query(value = """
            SELECT p FROM Product p
            JOIN FETCH p.category c
            WHERE p.active = TRUE AND c.active = TRUE
            """,
            countQuery = """
            SELECT COUNT(p) FROM Product p
            WHERE p.active = TRUE AND p.category.active = TRUE
            """)
    Page<Product> findPurchasable(Pageable pageable);

    /**
     * Catalogue search with optional filters.
     *
     * <p>Numeric filters are written as {@code (:param IS NULL OR <predicate>)} so
     * one query serves all combinations. The alternative — a {@code Specification}
     * or a hand-built Criteria tree — buys flexibility this endpoint does not need
     * and costs readability it does.
     *
     * <h2>Why the name filter is a pre-built pattern</h2>
     * The obvious form, {@code (:search IS NULL OR lower(p.name) LIKE
     * lower(concat('%', :search, '%')))}, fails at runtime against PostgreSQL with
     * {@code ERROR: function lower(bytea) does not exist}.
     *
     * <p>A bind parameter passed to a function has no column to take its type from,
     * so the server has to infer one — and PostgreSQL resolves an untyped null
     * parameter inside {@code lower()} to {@code bytea}, for which no such function
     * exists. The numeric filters escape this because {@code p.price >= ?} anchors
     * the parameter against a typed column; {@code lower(?)} anchors it against
     * nothing. Type resolution happens when the statement is parsed, so {@code OR}
     * short-circuiting at execution time does not save it.
     *
     * <p>Building the pattern in Java removes the problem rather than papering over
     * it with a cast: the parameter now sits directly beside {@code LIKE}, where
     * {@code lower(p.name)} gives it a type. A null search becomes {@code "%"},
     * which matches every row, so no null check is needed at all — and the
     * per-row {@code concat} and {@code lower} on the pattern are gone too.
     *
     * <p>The parameter is still bound, never concatenated into the statement. A
     * search for {@code "'; DROP TABLE products; --"} is compared as a literal
     * string.
     *
     * @param namePattern a lower-cased SQL LIKE pattern; {@code "%"} to match all.
     *                    Never null — see {@code ProductService.search}.
     */
    @Query(value = """
            SELECT p FROM Product p
            JOIN FETCH p.category c
            WHERE p.active = TRUE AND c.active = TRUE
              AND (:categoryId IS NULL OR c.id = :categoryId)
              AND (:minPrice   IS NULL OR p.price >= :minPrice)
              AND (:maxPrice   IS NULL OR p.price <= :maxPrice)
              AND lower(p.name) LIKE :namePattern
            """,
            countQuery = """
            SELECT COUNT(p) FROM Product p
            WHERE p.active = TRUE AND p.category.active = TRUE
              AND (:categoryId IS NULL OR p.category.id = :categoryId)
              AND (:minPrice   IS NULL OR p.price >= :minPrice)
              AND (:maxPrice   IS NULL OR p.price <= :maxPrice)
              AND lower(p.name) LIKE :namePattern
            """)
    Page<Product> search(@Param("categoryId") Long categoryId,
                         @Param("minPrice") BigDecimal minPrice,
                         @Param("maxPrice") BigDecimal maxPrice,
                         @Param("namePattern") String namePattern,
                         Pageable pageable);

    /** Administrative listing: includes inactive products and inactive categories. */
    @Query(value = "SELECT p FROM Product p JOIN FETCH p.category",
            countQuery = "SELECT COUNT(p) FROM Product p")
    Page<Product> findAllWithCategory(Pageable pageable);
}
