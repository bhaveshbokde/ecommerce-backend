package com.bhavesh.ecommerce.order.service;

import com.bhavesh.ecommerce.cart.entity.Cart;
import com.bhavesh.ecommerce.cart.entity.CartItem;
import com.bhavesh.ecommerce.cart.repository.CartRepository;
import com.bhavesh.ecommerce.cart.service.CartService;
import com.bhavesh.ecommerce.catalog.entity.Product;
import com.bhavesh.ecommerce.catalog.service.ProductService;
import com.bhavesh.ecommerce.common.dto.PageResponse;
import com.bhavesh.ecommerce.common.util.ReferenceGenerator;
import com.bhavesh.ecommerce.exception.ApiException;
import com.bhavesh.ecommerce.exception.BusinessRuleException;
import com.bhavesh.ecommerce.exception.ErrorCode;
import com.bhavesh.ecommerce.exception.ResourceNotFoundException;
import com.bhavesh.ecommerce.inventory.service.InventoryService;
import com.bhavesh.ecommerce.order.dto.OrderResponse;
import com.bhavesh.ecommerce.order.entity.Order;
import com.bhavesh.ecommerce.order.entity.OrderItem;
import com.bhavesh.ecommerce.order.entity.OrderStatus;
import com.bhavesh.ecommerce.order.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Checkout, order history and the fulfilment lifecycle.
 *
 * <h2>Checkout is one transaction</h2>
 * Reserving stock, snapshotting prices, creating the order and emptying the cart
 * either all happen or none do. {@link #checkout} is a single
 * {@code @Transactional} method, and {@code InventoryService.reserve} is declared
 * {@code MANDATORY} so a reservation cannot escape into its own transaction and
 * leave stock held for an order that was never created.
 *
 * <p>If line three is out of stock, the exception rolls the transaction back and
 * the reservations for lines one and two are undone by the database — no
 * compensating logic to write, and none to get wrong. That is the main reason
 * reservation lives inside the checkout transaction rather than in a separate
 * call.
 *
 * <h2>Deterministic lock ordering</h2>
 * Lines are reserved in ascending product id. Two customers buying {A, B} and
 * {B, A} would otherwise take the row locks in opposite orders and deadlock;
 * PostgreSQL would detect it and kill one transaction, turning a perfectly valid
 * pair of orders into a 500. Sorting makes the cycle impossible rather than merely
 * recoverable.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private static final String CURRENCY = "INR";

    /** Retries on the (rare) order-number collision caught by the unique index. */
    private static final int ORDER_NUMBER_ATTEMPTS = 5;

    private final OrderRepository orderRepository;
    private final CartRepository cartRepository;
    private final CartService cartService;
    private final ProductService productService;
    private final InventoryService inventoryService;
    private final ReferenceGenerator referenceGenerator;

    public OrderService(OrderRepository orderRepository,
                        CartRepository cartRepository,
                        CartService cartService,
                        ProductService productService,
                        InventoryService inventoryService,
                        ReferenceGenerator referenceGenerator) {
        this.orderRepository = orderRepository;
        this.cartRepository = cartRepository;
        this.cartService = cartService;
        this.productService = productService;
        this.inventoryService = inventoryService;
        this.referenceGenerator = referenceGenerator;
    }

    // --------------------------------------------------------------- checkout --

    /**
     * Turns the cart into an order.
     *
     * <p>Order of operations matters and is deliberate:
     * <ol>
     *   <li>read the cart, fail fast if empty;</li>
     *   <li>load every product in one query;</li>
     *   <li>validate each line is purchasable, <em>before</em> taking any lock;</li>
     *   <li>reserve stock line by line, in ascending product id;</li>
     *   <li>snapshot prices into order items;</li>
     *   <li>empty the cart.</li>
     * </ol>
     *
     * <p>Validation precedes reservation so an obviously bad basket does not lock
     * rows it will not use. Reservation precedes order creation so the order never
     * exists without the stock behind it.
     *
     * <p>The resulting order is {@code CREATED} with {@code PENDING} payment, and
     * the stock is held. It becomes {@code CONFIRMED} only when a payment succeeds.
     */
    @Transactional
    public OrderResponse checkout(Long userId) {
        Cart cart = cartService.requireCart(userId);
        if (cart.isEmpty()) {
            throw new BusinessRuleException(ErrorCode.EMPTY_CART,
                    "Add something to the cart before checking out");
        }

        List<CartItem> lines = cart.getItems();
        Set<Long> productIds = lines.stream().map(CartItem::getProductId)
                .collect(Collectors.toSet());
        Map<Long, Product> products = productService.loadByIds(productIds);

        // Validate everything before locking anything.
        for (CartItem line : lines) {
            Product product = products.get(line.getProductId());
            if (product == null) {
                throw new BusinessRuleException(ErrorCode.PRODUCT_UNAVAILABLE,
                        "A product in the cart no longer exists; remove it and try again");
            }
            if (!product.isPurchasable()) {
                throw new BusinessRuleException(ErrorCode.PRODUCT_UNAVAILABLE,
                        "'%s' is no longer available for purchase".formatted(product.getName()));
            }
        }

        // Deterministic lock order — see the class comment.
        List<CartItem> ordered = lines.stream()
                .sorted(Comparator.comparing(CartItem::getProductId))
                .toList();

        Order order = newOrderWithUniqueNumber(userId);

        for (CartItem line : ordered) {
            Product product = products.get(line.getProductId());

            // Throws InsufficientStockException on a zero-row UPDATE, which rolls
            // back this transaction and every reservation taken above it.
            inventoryService.reserve(product.getId(), product.getName(), line.getQuantity());

            // The price snapshot. From here the order is independent of the catalogue.
            order.addItem(product.getId(), product.getSku(), product.getName(),
                    product.getPrice(), line.getQuantity());
        }

        Order saved = orderRepository.save(order);

        cart.clear();
        cartRepository.save(cart);

        log.info("Checkout complete: order={} user={} lines={} total={}",
                saved.getOrderNumber(), userId, ordered.size(), saved.getTotal());

        return OrderResponse.detail(saved);
    }

    /**
     * Allocates an order number, retrying on the unique-index collision.
     *
     * <p>Retrying inside this transaction is safe because the insert has not
     * happened yet — this only produces a candidate string. The collision is caught
     * at save time by {@code uq_orders_order_number}, so the pre-check is an
     * optimisation and the index is the guarantee.
     */
    private Order newOrderWithUniqueNumber(Long userId) {
        for (int attempt = 0; attempt < ORDER_NUMBER_ATTEMPTS; attempt++) {
            String candidate = referenceGenerator.orderNumber();
            if (!orderRepository.existsByOrderNumber(candidate)) {
                return new Order(candidate, userId, CURRENCY);
            }
            log.warn("Order number collision on '{}', retrying", candidate);
        }
        throw new ApiException(ErrorCode.INTERNAL_ERROR,
                "Could not allocate a unique order number");
    }

    // ---------------------------------------------------------------- reading --

    /** Ownership-scoped: a miss means "not found or not yours", and answers 404 either way. */
    @Transactional(readOnly = true)
    public OrderResponse get(Long orderId, Long userId) {
        Order order = orderRepository.findByIdAndUserIdWithItems(orderId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));
        return OrderResponse.detail(order);
    }

    /** Administrative read, not scoped by owner. */
    @Transactional(readOnly = true)
    public OrderResponse getAsAdmin(Long orderId) {
        Order order = orderRepository.findByIdWithItems(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));
        return OrderResponse.detail(order);
    }

    /** Paginated history. Summary view: the listing query does not fetch lines. */
    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> listForUser(Long userId, OrderStatus status, Pageable pageable) {
        var page = status == null
                ? orderRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable)
                : orderRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, status, pageable);
        return PageResponse.from(page, OrderResponse::summary);
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> listAll(OrderStatus status, Pageable pageable) {
        return PageResponse.from(orderRepository.findAllByOptionalStatus(status, pageable),
                OrderResponse::summary);
    }

    // ------------------------------------------------------------- lifecycle --

    /**
     * Cancels an order and releases its stock.
     *
     * <p>{@code Order.cancel()} enforces the transition rule, so cancelling a
     * shipped order is a 409 rather than a silent no-op.
     *
     * <p>Release is skipped when the order no longer holds a reservation —
     * calling it twice would be harmless thanks to the {@code reserved >= quantity}
     * guard, but not calling it is clearer about intent.
     *
     * <p>A paid order is marked {@code REFUNDED}. No money moves, because none
     * moved on the way in; the state is recorded so the reconciliation story is
     * complete rather than silently absent.
     */
    @Transactional
    public OrderResponse cancel(Long orderId, Long userId) {
        Order order = orderRepository.findByIdAndUserIdWithItems(orderId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));
        return cancelInternal(order);
    }

    @Transactional
    public OrderResponse cancelAsAdmin(Long orderId) {
        Order order = orderRepository.findByIdWithItems(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));
        return cancelInternal(order);
    }

    private OrderResponse cancelInternal(Order order) {
        boolean releaseStock = order.holdsReservation();
        boolean wasPaid = order.isPaid();

        order.cancel();   // throws 409 if the status forbids it

        if (releaseStock) {
            inventoryService.releaseAll(quantitiesByProduct(order));
        }
        if (wasPaid) {
            order.markRefunded();
        }

        log.info("Cancelled order={} releasedStock={} refunded={}",
                order.getOrderNumber(), releaseStock, wasPaid);
        return OrderResponse.detail(order);
    }

    /**
     * Administrative fulfilment move.
     *
     * <p>Shipping consumes the reservation: the units have physically left, so the
     * hold is cleared rather than returned to {@code available}. Doing it here
     * rather than at confirmation keeps {@code reserved} meaning "allocated but
     * still on the shelf", which is what a warehouse needs it to mean.
     */
    @Transactional
    public OrderResponse updateStatus(Long orderId, OrderStatus target) {
        Order order = orderRepository.findByIdWithItems(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));

        if (target == OrderStatus.CANCELLED) {
            return cancelInternal(order);
        }

        if (target == OrderStatus.CONFIRMED && !order.isPaid()) {
            throw new BusinessRuleException(ErrorCode.INVALID_STATUS_TRANSITION,
                    "An order cannot be confirmed before its payment succeeds");
        }

        order.advanceTo(target);

        if (target == OrderStatus.SHIPPED) {
            inventoryService.commitAll(quantitiesByProduct(order));
        }

        log.info("Order {} advanced to {}", order.getOrderNumber(), target);
        return OrderResponse.detail(order);
    }

    // ------------------------------------------------- used by PaymentService --

    /** Loads an order for the payment flow, scoped to its owner. */
    @Transactional(readOnly = true)
    public Order requireOwnedOrder(Long orderId, Long userId) {
        return orderRepository.findByIdAndUserIdWithItems(orderId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));
    }

    /**
     * The quantities to release if a payment fails.
     *
     * <p>A {@code LinkedHashMap} keyed by product id in ascending order, so release
     * takes row locks in the same order reservation did. The merge function sums
     * duplicates — an order should not contain the same product twice, but summing
     * is the behaviour that stays correct if it ever does, and
     * {@code Collectors.toMap} would throw instead.
     */
    public Map<Long, Integer> quantitiesByProduct(Order order) {
        Map<Long, Integer> quantities = new LinkedHashMap<>();
        order.getItems().stream()
                .filter(item -> item.getProductId() != null)
                .sorted(Comparator.comparing(OrderItem::getProductId))
                .forEach(item -> quantities.merge(item.getProductId(), item.getQuantity(), Integer::sum));
        return quantities;
    }

    /** Persists an order mutated by the payment flow. */
    @Transactional
    public void save(Order order) {
        try {
            orderRepository.save(order);
        } catch (DataIntegrityViolationException e) {
            log.error("Failed to persist order {}", order.getOrderNumber(), e);
            throw e;
        }
    }
}
