package com.bhavesh.ecommerce.order.dto;

import com.bhavesh.ecommerce.common.util.Money;
import com.bhavesh.ecommerce.order.entity.Order;
import com.bhavesh.ecommerce.order.entity.OrderStatus;
import com.bhavesh.ecommerce.payment.entity.PaymentStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * An order.
 *
 * <p>Two mappers, because the two views have genuinely different costs.
 * {@link #summary} is for the paginated history and omits the lines — that listing
 * query deliberately does not fetch them, and touching {@code order.getItems()}
 * here would fire one SELECT per order.
 *
 * <p>{@code cancellable} is derived from {@link OrderStatus#isCancellable()}, the
 * same method the service calls before cancelling, so the button the client shows
 * and the rule the server enforces cannot drift apart.
 */
@Schema(name = "OrderResponse", description = "A placed order")
public record OrderResponse(

        @Schema(example = "42")
        Long id,

        @Schema(example = "ORD-20261014-7F3A91", description = "Quote this in support conversations")
        String orderNumber,

        @Schema(example = "CONFIRMED")
        OrderStatus status,

        @Schema(example = "SUCCEEDED")
        PaymentStatus paymentStatus,

        @Schema(example = "149998.00")
        BigDecimal subtotal,

        @Schema(example = "149998.00")
        BigDecimal total,

        @Schema(example = "INR")
        String currency,

        @Schema(description = "Null in the list view, populated in the detail view")
        List<OrderItemResponse> items,

        @Schema(example = "true")
        boolean cancellable,

        Instant createdAt,
        Instant confirmedAt,
        Instant cancelledAt
) {

    /** Full view, including lines. The order must have been loaded with its items. */
    public static OrderResponse detail(Order order) {
        return build(order, order.getItems().stream().map(OrderItemResponse::from).toList());
    }

    /** List view. Omits lines so a paginated history stays one query. */
    public static OrderResponse summary(Order order) {
        return build(order, null);
    }

    private static OrderResponse build(Order order, List<OrderItemResponse> items) {
        return new OrderResponse(
                order.getId(),
                order.getOrderNumber(),
                order.getStatus(),
                order.getPaymentStatus(),
                Money.display(order.getSubtotal()),
                Money.display(order.getTotal()),
                order.getCurrency(),
                items,
                order.getStatus().isCancellable(),
                order.getCreatedAt(),
                order.getConfirmedAt(),
                order.getCancelledAt());
    }
}
