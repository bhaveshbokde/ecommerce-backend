package com.bhavesh.ecommerce.cart.service;

import com.bhavesh.ecommerce.cart.dto.AddCartItemRequest;
import com.bhavesh.ecommerce.cart.dto.CartItemResponse;
import com.bhavesh.ecommerce.cart.dto.CartResponse;
import com.bhavesh.ecommerce.cart.entity.Cart;
import com.bhavesh.ecommerce.cart.entity.CartItem;
import com.bhavesh.ecommerce.cart.repository.CartRepository;
import com.bhavesh.ecommerce.catalog.entity.Product;
import com.bhavesh.ecommerce.catalog.service.ProductService;
import com.bhavesh.ecommerce.common.util.Money;
import com.bhavesh.ecommerce.exception.ApiException;
import com.bhavesh.ecommerce.exception.ErrorCode;
import com.bhavesh.ecommerce.exception.ResourceNotFoundException;
import com.bhavesh.ecommerce.inventory.entity.Inventory;
import com.bhavesh.ecommerce.inventory.repository.InventoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Cart management.
 *
 * <h2>Pricing at read time</h2>
 * {@code cart_items} stores no price. Every read of the cart resolves current
 * prices from the catalogue, so a cart left open for a week shows this week's
 * prices. The snapshot happens exactly once, when the order is created — which is
 * the moment the price becomes a commitment.
 *
 * <h2>No reservation</h2>
 * Adding to the cart does not reserve stock. Carts are abandoned constantly; if
 * each one held inventory, a handful of browsers would make a product unbuyable.
 * Stock is reserved at checkout, which means a cart can contain more than is
 * available and the customer finds out at checkout. That is the standard
 * trade-off; the alternative — a reservation with a timeout — is a scheduler, a
 * sweeper and a new class of bug.
 *
 * <p>{@code available} and {@code purchasable} on each line exist so the UI can warn
 * earlier. They are advisory: the authoritative check is the conditional UPDATE at
 * checkout.
 */
@Service
public class CartService {

    private static final Logger log = LoggerFactory.getLogger(CartService.class);

    private static final String CURRENCY = "INR";

    private final CartRepository cartRepository;
    private final ProductService productService;
    private final InventoryRepository inventoryRepository;

    public CartService(CartRepository cartRepository,
                       ProductService productService,
                       InventoryRepository inventoryRepository) {
        this.cartRepository = cartRepository;
        this.productService = productService;
        this.inventoryRepository = inventoryRepository;
    }

    @Transactional(readOnly = true)
    public CartResponse getCart(Long userId) {
        return cartRepository.findByUserIdWithItems(userId)
                .map(this::toResponse)
                .orElseGet(() -> emptyCartResponse(null));
    }

    /**
     * Adds a product, or increases an existing line.
     *
     * <p>Rejects an unpurchasable product up front — adding something that can
     * never be bought only defers the disappointment to checkout.
     */
    @Transactional
    public CartResponse addItem(Long userId, AddCartItemRequest request) {
        Product product = productService.requireProduct(request.productId());
        if (!product.isPurchasable()) {
            throw new ApiException(ErrorCode.PRODUCT_UNAVAILABLE,
                    "'%s' is not available for purchase".formatted(product.getName()));
        }

        Cart cart = getOrCreateCart(userId);
        cart.addItem(product.getId(), request.quantity());
        cartRepository.save(cart);

        log.debug("Cart {}: added {} x product {}", cart.getId(), request.quantity(), product.getId());
        return toResponse(reload(userId));
    }

    /** Sets an absolute quantity for an existing line. */
    @Transactional
    public CartResponse updateItem(Long userId, Long productId, int quantity) {
        Cart cart = requireCart(userId);
        if (cart.findItem(productId).isEmpty()) {
            throw new ResourceNotFoundException(
                    "Product %d is not in the cart".formatted(productId));
        }
        cart.updateQuantity(productId, quantity);
        cartRepository.save(cart);
        return toResponse(reload(userId));
    }

    @Transactional
    public CartResponse removeItem(Long userId, Long productId) {
        Cart cart = requireCart(userId);
        if (!cart.removeItem(productId)) {
            throw new ResourceNotFoundException(
                    "Product %d is not in the cart".formatted(productId));
        }
        cartRepository.save(cart);
        return toResponse(reload(userId));
    }

    @Transactional
    public CartResponse clear(Long userId) {
        cartRepository.findByUserIdWithItems(userId).ifPresent(cart -> {
            cart.clear();
            cartRepository.save(cart);
        });
        return toResponse(reload(userId));
    }

    // ----------------------------------------------------------------- shared --

    /**
     * Returns the user's cart, creating one if needed.
     *
     * <p>{@code uq_carts_user} is what makes this safe under concurrency: two
     * simultaneous "add to cart" requests can both find no cart and both insert,
     * and the index rejects the loser. That request then re-reads and finds the
     * winner's cart, so both succeed and there is still exactly one cart.
     */
    @Transactional
    public Cart getOrCreateCart(Long userId) {
        return cartRepository.findByUserIdWithItems(userId)
                .orElseGet(() -> {
                    try {
                        return cartRepository.saveAndFlush(new Cart(userId));
                    } catch (DataIntegrityViolationException raced) {
                        return cartRepository.findByUserIdWithItems(userId)
                                .orElseThrow(() -> raced);
                    }
                });
    }

    /** Used by checkout, which needs the entity to read and then empty. */
    @Transactional(readOnly = true)
    public Cart requireCart(Long userId) {
        return cartRepository.findByUserIdWithItems(userId)
                .orElseThrow(() -> new ResourceNotFoundException("The cart is empty"));
    }

    private Cart reload(Long userId) {
        return cartRepository.findByUserIdWithItems(userId).orElse(null);
    }

    // --------------------------------------------------------------- mapping --

    /**
     * Prices a cart.
     *
     * <p>Two batched queries for the whole cart — one for the products, one for the
     * stock — rather than two per line. Ten lines: two queries, not twenty.
     */
    private CartResponse toResponse(Cart cart) {
        if (cart == null || cart.isEmpty()) {
            return emptyCartResponse(cart == null ? null : cart.getId());
        }

        List<CartItem> items = cart.getItems();
        Set<Long> productIds = items.stream().map(CartItem::getProductId)
                .collect(Collectors.toSet());

        Map<Long, Product> products = productService.loadByIds(productIds);
        Map<Long, Integer> available = inventoryRepository.findByProductIdIn(productIds).stream()
                .collect(Collectors.toMap(Inventory::getProductId, Inventory::getAvailable));

        List<CartItemResponse> lines = new ArrayList<>(items.size());
        BigDecimal subtotal = BigDecimal.ZERO;
        boolean blocked = false;

        for (CartItem item : items) {
            Product product = products.get(item.getProductId());
            if (product == null) {
                // The catalogue row was hard-deleted under us. Flag the line rather
                // than fail the whole cart read.
                blocked = true;
                lines.add(new CartItemResponse(item.getId(), item.getProductId(), "(removed)",
                        "This product is no longer available", Money.ZERO, item.getQuantity(),
                        Money.ZERO, 0, false));
                continue;
            }

            int stock = available.getOrDefault(product.getId(), 0);
            boolean purchasable = product.isPurchasable();
            BigDecimal lineTotal = Money.lineTotal(product.getPrice(), item.getQuantity());
            subtotal = subtotal.add(lineTotal);

            if (!purchasable || stock < item.getQuantity()) {
                blocked = true;
            }

            lines.add(new CartItemResponse(
                    item.getId(),
                    product.getId(),
                    product.getSku(),
                    product.getName(),
                    Money.display(product.getPrice()),
                    item.getQuantity(),
                    Money.display(lineTotal),
                    stock,
                    purchasable));
        }

        return new CartResponse(cart.getId(), lines, cart.totalQuantity(),
                Money.display(subtotal), CURRENCY, blocked);
    }

    private CartResponse emptyCartResponse(Long cartId) {
        return new CartResponse(cartId, List.of(), 0, Money.ZERO, CURRENCY, false);
    }
}
