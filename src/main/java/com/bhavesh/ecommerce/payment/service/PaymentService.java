package com.bhavesh.ecommerce.payment.service;

import com.bhavesh.ecommerce.common.dto.PageResponse;
import com.bhavesh.ecommerce.common.util.ReferenceGenerator;
import com.bhavesh.ecommerce.exception.ApiException;
import com.bhavesh.ecommerce.exception.BusinessRuleException;
import com.bhavesh.ecommerce.exception.ErrorCode;
import com.bhavesh.ecommerce.exception.ResourceNotFoundException;
import com.bhavesh.ecommerce.inventory.service.InventoryService;
import com.bhavesh.ecommerce.order.entity.Order;
import com.bhavesh.ecommerce.order.entity.OrderStatus;
import com.bhavesh.ecommerce.order.service.OrderService;
import com.bhavesh.ecommerce.payment.dto.PaymentRequest;
import com.bhavesh.ecommerce.payment.dto.PaymentResponse;
import com.bhavesh.ecommerce.payment.entity.Payment;
import com.bhavesh.ecommerce.payment.entity.PaymentStatus;
import com.bhavesh.ecommerce.payment.entity.SimulatedOutcome;
import com.bhavesh.ecommerce.payment.provider.PaymentProvider;
import com.bhavesh.ecommerce.payment.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Charges an order against the simulated provider, and compensates when that fails.
 *
 * <h2>Idempotency without an application-level check</h2>
 * The guarantee is {@code uq_payments_user_idempotency}. A pre-read ("have I seen
 * this key?") serves the common case cheaply, but it is a read followed by a write
 * and two concurrent retries both pass it. The insert is where the race is
 * actually decided: exactly one succeeds, the other gets a constraint violation,
 * and this class turns that violation into a replay of the winner's outcome. The
 * check is an optimisation; the index is the correctness argument.
 *
 * <p>A second index, {@code uq_payments_order_active}, is partial over
 * {@code status IN ('PENDING','SUCCEEDED')}. So an order may collect any number of
 * FAILED attempts — retrying a declined card is ordinary — but never two live ones,
 * and never a second charge after one succeeded.
 *
 * <h2>Compensation</h2>
 * Checkout reserved stock. A failed charge must give it back, or the units sit
 * held against an order that will never ship. Release and the FAILED payment row
 * are written in the same transaction, so there is no state where one happened and
 * the other did not.
 *
 * <p>A <em>declined</em> charge is therefore not an exception. Throwing would roll
 * back the very rows that record the failure, and the next attempt would find no
 * trace of it. The method returns normally with {@code status = FAILED}, and the
 * controller maps that to an HTTP status.
 *
 * <h2>The hard case this does not solve</h2>
 * {@link SimulatedOutcome#PROVIDER_ERROR} means the answer is unknown: the charge
 * may or may not have landed. This application treats it as a failure and releases
 * stock, which risks releasing stock for a charge that actually succeeded. The
 * correct fix is reconciliation against the provider, which needs a provider to
 * reconcile against. The choice is recorded in {@code payments.failure_reason} so
 * the row is findable, rather than silently resolved one way and forgotten.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepository;
    private final PaymentProvider paymentProvider;
    private final OrderService orderService;
    private final InventoryService inventoryService;
    private final ReferenceGenerator referenceGenerator;
    private final boolean productionProfile;

    public PaymentService(PaymentRepository paymentRepository,
                          PaymentProvider paymentProvider,
                          OrderService orderService,
                          InventoryService inventoryService,
                          ReferenceGenerator referenceGenerator,
                          Environment environment) {
        this.paymentRepository = paymentRepository;
        this.paymentProvider = paymentProvider;
        this.orderService = orderService;
        this.inventoryService = inventoryService;
        this.referenceGenerator = referenceGenerator;
        this.productionProfile = environment.matchesProfiles("prod");
    }

    /**
     * Pays for an order.
     *
     * @param idempotencyKey the client's {@code Idempotency-Key} header. Required —
     *                       unlike checkout, a duplicate charge is not something a
     *                       caller gets to opt into.
     */
    @Transactional
    public Outcome pay(Long userId, PaymentRequest request, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "An Idempotency-Key header is required for payments");
        }

        // Cheap path for an ordinary retry: the key is already known.
        var alreadySeen = paymentRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
        if (alreadySeen.isPresent()) {
            Payment payment = alreadySeen.get();
            if (!payment.getOrderId().equals(request.orderId())) {
                throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                        "This Idempotency-Key was already used for a different order");
            }
            log.info("Replaying payment {} for idempotency key", payment.getPaymentReference());
            return new Outcome(PaymentResponse.from(payment), true);
        }

        Order order = orderService.requireOwnedOrder(request.orderId(), userId);
        assertPayable(order);

        Payment payment = createPendingPayment(userId, order, idempotencyKey);

        // From here the payment row exists and is PENDING, so uq_payments_order_active
        // blocks any concurrent second charge for the same order.
        payment.recordAttempt();

        PaymentProvider.ChargeResult result = paymentProvider.charge(
                payment.getPaymentReference(), payment.getAmount(), payment.getCurrency(),
                resolveOutcome(request.simulateOutcome()));

        if (result.successful()) {
            payment.markSucceeded(result.providerReference());
            order.confirm();                       // CREATED -> CONFIRMED, payment SUCCEEDED
            log.info("Payment {} succeeded for order {}",
                    payment.getPaymentReference(), order.getOrderNumber());
        } else {
            payment.markFailed(result.failureReason());
            order.markPaymentFailed();             // stays CREATED, so the customer may retry

            // The compensation. Same transaction as the rows above, so the two can
            // never disagree about whether the stock went back.
            Map<Long, Integer> quantities = orderService.quantitiesByProduct(order);
            inventoryService.releaseAll(quantities);

            log.warn("Payment {} failed for order {}: {} (released {} line(s) of stock)",
                    payment.getPaymentReference(), order.getOrderNumber(),
                    result.failureReason(), quantities.size());
        }

        paymentRepository.save(payment);
        orderService.save(order);

        return new Outcome(PaymentResponse.from(payment), false);
    }

    /**
     * A payment result, and whether it came from a replay.
     *
     * @param replayed true when this key had already been processed, so nothing was
     *                 charged this time. The controller answers 200 instead of 201 —
     *                 claiming to have created an attempt that already existed would
     *                 be a false statement about what the request did, and a client
     *                 distinguishing "my retry went through" from "my retry was
     *                 absorbed" has no other signal to read.
     */
    public record Outcome(PaymentResponse payment, boolean replayed) {
    }

    /**
     * Inserts the PENDING row, letting the database arbitrate both races.
     *
     * <p>{@code saveAndFlush} forces the INSERT now rather than at commit, so a
     * constraint violation surfaces here where it can be interpreted, instead of
     * during commit where it would be an opaque 500.
     *
     * <h2>Why the catch block must not touch the repository</h2>
     * This is the subtle part, and getting it wrong produced a 500 for every loser
     * of the race.
     *
     * <p>Once a flush fails, the Hibernate persistence context is <strong>no longer
     * usable</strong>. The entity it tried to insert is still in the session with a
     * null identifier, so the next operation on that session triggers another flush
     * and dies with {@code org.hibernate.AssertionFailure: Entry for instance ...
     * has a null identifier (this can happen if the session is flushed after an
     * exception occurs)}. The original version re-read the payment here to work out
     * <em>which</em> constraint had fired — a perfectly reasonable-looking query
     * that turned a clean 409 into a 500.
     *
     * <p>So the constraint is identified by inspecting the driver's message instead,
     * which is pure string handling and touches nothing. Which index fired is the
     * only question that needs answering:
     *
     * <ul>
     *   <li>{@code uq_payments_user_idempotency} — a concurrent retry of <em>this
     *       same request</em>. The winner will produce an outcome, so the right
     *       answer is "retry shortly" and the retry will replay it.</li>
     *   <li>{@code uq_payments_order_active} — a <em>different</em> request charging
     *       the same order. There is already a live payment; this one must not
     *       proceed.</li>
     * </ul>
     *
     * <p>The exception also rolls this transaction back, which is what we want: no
     * half-written PENDING row is left behind to block the order forever.
     *
     * <p>Found by {@code InventoryConcurrencyIT}, which fires ten simultaneous
     * charges at one order. Every single-threaded test passed throughout.
     */
    private Payment createPendingPayment(Long userId, Order order, String idempotencyKey) {
        Payment payment = new Payment(
                referenceGenerator.paymentReference(),
                order.getId(),
                userId,
                order.getTotal(),
                order.getCurrency(),
                idempotencyKey);
        try {
            return paymentRepository.saveAndFlush(payment);
        } catch (DataIntegrityViolationException e) {
            throw interpretViolation(e);
        }
    }

    /**
     * Maps a unique-index violation to the right error, using only the exception.
     *
     * <p>Deliberately performs no database access — see {@link #createPendingPayment}
     * for why the session is unusable by the time this runs.
     */
    private ApiException interpretViolation(DataIntegrityViolationException e) {
        String cause = e.getMostSpecificCause().getMessage();
        String constraint = cause == null ? "" : cause;

        if (constraint.contains("uq_payments_user_idempotency")) {
            return new ApiException(ErrorCode.CONCURRENT_MODIFICATION,
                    "A payment with this Idempotency-Key is already being processed; retry shortly");
        }
        if (constraint.contains("uq_payments_order_active")) {
            return new ApiException(ErrorCode.PAYMENT_ALREADY_EXISTS,
                    "A payment is already in progress or complete for this order");
        }

        log.error("Unrecognised constraint violation while creating a payment", e);
        return new ApiException(ErrorCode.PAYMENT_ALREADY_EXISTS,
                "The payment could not be created because it conflicts with an existing record");
    }

    private void assertPayable(Order order) {
        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw new BusinessRuleException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "A cancelled order cannot be paid");
        }
        if (order.getPaymentStatus() == PaymentStatus.SUCCEEDED) {
            throw new ApiException(ErrorCode.PAYMENT_ALREADY_EXISTS,
                    "This order has already been paid");
        }
        if (order.getStatus() != OrderStatus.CREATED) {
            throw new BusinessRuleException(ErrorCode.INVALID_STATUS_TRANSITION,
                    "An order that is %s is not awaiting payment".formatted(order.getStatus()));
        }
        // Belt and braces: the partial index guarantees this, and checking gives a
        // precise 409 instead of a constraint violation to interpret.
        List<Payment> active = paymentRepository.findActiveByOrderId(order.getId());
        if (!active.isEmpty()) {
            throw new ApiException(ErrorCode.PAYMENT_ALREADY_EXISTS,
                    "A payment is already in progress for this order");
        }
    }

    /**
     * Honours the per-request outcome hint outside production only.
     *
     * <p>In the {@code prod} profile the hint is dropped and the configured default
     * applies, so no request can talk a production deployment into declining or
     * succeeding on demand. The field stays in the DTO because the simulator is the
     * only provider this project has; a real gateway implementation would ignore it
     * entirely.
     */
    private SimulatedOutcome resolveOutcome(SimulatedOutcome requested) {
        if (productionProfile && requested != null) {
            log.warn("Ignoring simulateOutcome hint: not permitted in the prod profile");
            return null;
        }
        return requested;
    }

    // ---------------------------------------------------------------- reading --

    @Transactional(readOnly = true)
    public PaymentResponse get(Long paymentId, Long userId) {
        return paymentRepository.findByIdAndUserId(paymentId, userId)
                .map(PaymentResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));
    }

    @Transactional(readOnly = true)
    public PageResponse<PaymentResponse> listForUser(Long userId, Pageable pageable) {
        return PageResponse.from(
                paymentRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable),
                PaymentResponse::from);
    }

    /** All attempts against one order, newest first — including the failed ones. */
    @Transactional(readOnly = true)
    public List<PaymentResponse> listForOrder(Long orderId, Long userId) {
        // Scoped through the order, so a customer cannot read another's attempts.
        orderService.requireOwnedOrder(orderId, userId);
        return paymentRepository.findByOrderIdOrderByCreatedAtDesc(orderId).stream()
                .map(PaymentResponse::from)
                .toList();
    }
}
