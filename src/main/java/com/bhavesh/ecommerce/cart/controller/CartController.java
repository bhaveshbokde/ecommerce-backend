package com.bhavesh.ecommerce.cart.controller;

import com.bhavesh.ecommerce.cart.dto.AddCartItemRequest;
import com.bhavesh.ecommerce.cart.dto.CartResponse;
import com.bhavesh.ecommerce.cart.dto.UpdateCartItemRequest;
import com.bhavesh.ecommerce.cart.service.CartService;
import com.bhavesh.ecommerce.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The authenticated customer's cart.
 *
 * <h2>There is no cart id in any path</h2>
 * Every endpoint operates on "the caller's cart", identified by
 * {@code principal.getId()} — which comes from the verified token, not from the
 * request. A URL such as {@code /carts/{cartId}} would invite the classic broken
 * object-level authorization bug, where the id is trusted because it looked like a
 * path variable. Here there is no id to tamper with.
 *
 * <p>The same reasoning applies to the line endpoints: they are keyed by
 * {@code productId}, and the cart is still resolved from the principal, so one
 * customer cannot name another's cart line.
 */
@RestController
@RequestMapping("/api/v1/cart")
@Tag(name = "Cart", description = "The authenticated customer's shopping cart")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping
    @Operation(summary = "Read the cart",
            description = "Prices every line from the current catalogue — cart lines store no "
                    + "price, so a cart left open for a week shows this week's prices. "
                    + "`available` and `purchasable` are advisory; stock is only truly "
                    + "claimed at checkout.")
    public ResponseEntity<CartResponse> getCart(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(cartService.getCart(principal.getId()));
    }

    @PostMapping("/items")
    @Operation(summary = "Add a product, or increase an existing line",
            description = "Adding a product already in the cart increases its quantity rather "
                    + "than creating a second line — `uq_cart_items_cart_product` would "
                    + "reject that. Does not reserve stock.")
    public ResponseEntity<CartResponse> addItem(@AuthenticationPrincipal UserPrincipal principal,
                                                 @Valid @RequestBody AddCartItemRequest request) {
        return ResponseEntity.ok(cartService.addItem(principal.getId(), request));
    }

    @PatchMapping("/items/{productId}")
    @Operation(summary = "Set a line's quantity",
            description = "Absolute, not a delta, so retrying the request is safe.")
    public ResponseEntity<CartResponse> updateItem(@AuthenticationPrincipal UserPrincipal principal,
                                                    @PathVariable Long productId,
                                                    @Valid @RequestBody UpdateCartItemRequest request) {
        return ResponseEntity.ok(cartService.updateItem(principal.getId(), productId,
                request.quantity()));
    }

    @DeleteMapping("/items/{productId}")
    @Operation(summary = "Remove a line")
    public ResponseEntity<CartResponse> removeItem(@AuthenticationPrincipal UserPrincipal principal,
                                                    @PathVariable Long productId) {
        return ResponseEntity.ok(cartService.removeItem(principal.getId(), productId));
    }

    @DeleteMapping
    @Operation(summary = "Empty the cart",
            description = "Idempotent: emptying an already-empty cart succeeds.")
    public ResponseEntity<CartResponse> clear(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(cartService.clear(principal.getId()));
    }
}
