package com.bhavesh.ecommerce.catalog.service;

import com.bhavesh.ecommerce.catalog.dto.ProductRequest;
import com.bhavesh.ecommerce.catalog.dto.ProductResponse;
import com.bhavesh.ecommerce.catalog.entity.Category;
import com.bhavesh.ecommerce.catalog.entity.Product;
import com.bhavesh.ecommerce.catalog.repository.ProductRepository;
import com.bhavesh.ecommerce.common.dto.PageResponse;
import com.bhavesh.ecommerce.exception.ApiException;
import com.bhavesh.ecommerce.exception.ErrorCode;
import com.bhavesh.ecommerce.exception.ResourceNotFoundException;
import com.bhavesh.ecommerce.inventory.entity.Inventory;
import com.bhavesh.ecommerce.inventory.repository.InventoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Catalogue management and browsing.
 *
 * <h2>The N+1 problem, twice</h2>
 * A product listing needs two things each product does not carry: its category
 * name, and whether it is in stock. The naive version fires two extra queries per
 * row — twenty products become forty-one queries.
 *
 * <p>Both are solved by batching. The category comes from a {@code JOIN FETCH} in
 * the repository query. Stock comes from one {@code findByProductIdIn} over the
 * page's ids, collected into a map. Twenty products: two queries, not forty-one.
 *
 * <h2>Caching</h2>
 * Only {@link #get} is cached. Listings are not: they are parameterised by page,
 * size, sort and four filters, so the key space is effectively unbounded and the
 * hit rate would be poor — caching them would consume memory to serve misses.
 */
@Service
public class ProductService {

    public static final String CACHE_PRODUCT = "products";

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final CategoryService categoryService;

    public ProductService(ProductRepository productRepository,
                          InventoryRepository inventoryRepository,
                          CategoryService categoryService) {
        this.productRepository = productRepository;
        this.inventoryRepository = inventoryRepository;
        this.categoryService = categoryService;
    }

    // ---------------------------------------------------------------- reading --

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CACHE_PRODUCT, key = "#id")
    public ProductResponse get(Long id) {
        Product product = productRepository.findByIdWithCategory(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product", id));
        return ProductResponse.from(product, isInStock(id));
    }

    /**
     * Public catalogue search.
     *
     * @param minPrice  null to leave the bound open; the query handles null natively
     * @param maxPrice  null to leave the bound open
     * @param search    substring match on the name, case-insensitive; bound, never concatenated
     */
    @Transactional(readOnly = true)
    public PageResponse<ProductResponse> search(Long categoryId, BigDecimal minPrice,
                                                BigDecimal maxPrice, String search,
                                                Pageable pageable) {
        if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0) {
            throw new ApiException(ErrorCode.INVALID_PARAMETER,
                    "minPrice must not be greater than maxPrice");
        }
        Page<Product> page = productRepository.search(categoryId, minPrice, maxPrice,
                namePattern(search), pageable);
        return toPageResponse(page);
    }

    /** Administrative listing: includes inactive products and inactive categories. */
    @Transactional(readOnly = true)
    public PageResponse<ProductResponse> listAll(Pageable pageable) {
        return toPageResponse(productRepository.findAllWithCategory(pageable));
    }

    // ---------------------------------------------------------------- writing --

    @Transactional
    public ProductResponse create(ProductRequest request) {
        String sku = Product.normalizeSku(request.sku());
        if (productRepository.existsBySku(sku)) {
            throw new ApiException(ErrorCode.DUPLICATE_SKU);
        }

        Category category = categoryService.requireCategory(request.categoryId());
        Product product = productRepository.save(
                new Product(sku, request.name(), request.description(), request.price(), category));

        // Every product gets a stock row, even at zero. A product without one would
        // make the reservation UPDATE match nothing, which reads as "out of stock"
        // but is really a missing row — a far more confusing failure.
        int openingStock = request.initialStock() == null ? 0 : request.initialStock();
        inventoryRepository.save(new Inventory(product.getId(), openingStock));

        log.info("Created product id={} sku={} stock={}", product.getId(), sku, openingStock);
        return ProductResponse.from(product, openingStock > 0);
    }

    /**
     * Updates catalogue details. Stock is deliberately untouched.
     *
     * <p>{@code sku} is immutable: it is the stable business identifier, and
     * {@code order_items} snapshots it. Letting it change would make a past receipt
     * refer to a code that now means something else.
     */
    @Transactional
    @CacheEvict(cacheNames = CACHE_PRODUCT, key = "#id")
    public ProductResponse update(Long id, ProductRequest request) {
        Product product = productRepository.findByIdWithCategory(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product", id));

        if (!product.getSku().equals(Product.normalizeSku(request.sku()))) {
            throw new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "sku cannot be changed; it is referenced by existing orders");
        }

        product.setName(request.name());
        product.setDescription(request.description());
        // Affects future orders only — existing order_items keep their snapshot.
        product.setPrice(request.price());

        if (!product.getCategory().getId().equals(request.categoryId())) {
            product.setCategory(categoryService.requireCategory(request.categoryId()));
        }

        log.info("Updated product id={}", id);
        return ProductResponse.from(product, isInStock(id));
    }

    @Transactional
    @CacheEvict(cacheNames = CACHE_PRODUCT, key = "#id")
    public ProductResponse setActive(Long id, boolean active) {
        Product product = productRepository.findByIdWithCategory(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product", id));
        product.setActive(active);
        log.info("Product id={} active={}", id, active);
        return ProductResponse.from(product, isInStock(id));
    }

    // ---------------------------------------------------------------- helpers --

    /**
     * Loads products for a set of ids with their categories, keyed by id.
     *
     * <p>Used by the cart and checkout, both of which need many products at once.
     * One query for the whole basket rather than one per line.
     */
    @Transactional(readOnly = true)
    public Map<Long, Product> loadByIds(Set<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return productRepository.findAllByIdWithCategory(ids).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
    }

    /** Package-private: {@code OrderService} needs the entity to snapshot its fields. */
    public Product requireProduct(Long id) {
        return productRepository.findByIdWithCategory(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product", id));
    }

    /**
     * Turns a user's search term into a lower-cased SQL LIKE pattern.
     *
     * <p>Never returns null: {@code ProductRepository.search} binds this parameter
     * directly beside {@code LIKE}, and a blank search becomes {@code "%"} rather
     * than a null that PostgreSQL would have to guess a type for. See that method's
     * comment for the failure this avoids.
     *
     * <p>{@code %} and {@code _} in the input are escaped so a search for
     * {@code "50%"} means a literal percent sign rather than a wildcard. The
     * {@code ESCAPE} clause is Hibernate's default backslash.
     */
    private String namePattern(String search) {
        if (search == null || search.isBlank()) {
            return "%";
        }
        String escaped = search.trim().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private boolean isInStock(Long productId) {
        return inventoryRepository.findById(productId)
                .map(Inventory::isInStock)
                .orElse(false);
    }

    /**
     * Maps a page of products, resolving stock for the whole page in one query.
     *
     * <p>This is the batching that keeps a listing at two queries. Doing it inside
     * {@code map()} per element would be the N+1 this method exists to avoid.
     */
    private PageResponse<ProductResponse> toPageResponse(Page<Product> page) {
        List<Long> ids = page.getContent().stream().map(Product::getId).toList();
        Map<Long, Boolean> stockById = ids.isEmpty()
                ? Map.of()
                : inventoryRepository.findByProductIdIn(ids).stream()
                        .collect(Collectors.toMap(Inventory::getProductId, Inventory::isInStock));

        return PageResponse.from(page, product ->
                ProductResponse.from(product,
                        stockById.getOrDefault(product.getId(), Boolean.FALSE)));
    }
}
