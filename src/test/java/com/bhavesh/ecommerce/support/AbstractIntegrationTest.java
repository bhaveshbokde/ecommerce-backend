package com.bhavesh.ecommerce.support;

import com.bhavesh.ecommerce.common.web.TraceIdFilter;
import com.bhavesh.ecommerce.user.entity.Role;
import com.bhavesh.ecommerce.user.entity.User;
import com.bhavesh.ecommerce.user.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Shared base for every integration test.
 *
 * <h2>Why not {@code @Transactional} on the test class</h2>
 * The usual trick — annotate the test and let Spring roll back afterwards — is
 * wrong for this project. The behaviour under test <em>is</em> transactional:
 * checkout reserves stock and commits, a failed payment releases it and commits.
 * Wrapping the test in an outer transaction would make the service's own
 * boundaries invisible, hide every commit-time constraint violation, and make
 * {@code REQUIRES_NEW} in {@link
 * com.bhavesh.ecommerce.idempotency.service.IdempotencyStore} silently useless.
 * It would also make the concurrency tests meaningless, since a second thread
 * cannot see an uncommitted row.
 *
 * <p>So these tests commit for real and clean up explicitly in {@link #resetDatabase}.
 * The seeded catalogue from V9 is left alone; everything a test creates is removed.
 *
 * <h2>MockMvc, not a real port</h2>
 * {@code MockMvc} exercises the full filter chain, the dispatcher, argument
 * resolution, validation, the exception handler and JSON serialisation — the whole
 * web layer — without binding a socket. A random port would add a client, an HTTP
 * connection and a source of flakiness for nothing.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
public abstract class AbstractIntegrationTest {

    @Autowired
    protected WebApplicationContext webApplicationContext;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    @Autowired
    protected TransactionTemplate transactionTemplate;

    @Autowired
    protected EntityManager entityManager;

    @Autowired
    protected TraceIdFilter traceIdFilter;

    protected MockMvc mockMvc;

    /** The password every test account is created with. */
    protected static final String TEST_PASSWORD = "integration-test-password";

    @BeforeEach
    void baseSetUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())   // without this the security filter chain is absent
                // springSecurity() adds *only* the security chain. Ordinary Filter
                // beans are registered by Boot's servlet container setup, which
                // MockMvc does not run — so TraceIdFilter has to be added by hand or
                // X-Trace-Id is missing in tests while working perfectly in
                // production. Registered first, matching its HIGHEST_PRECEDENCE order.
                .addFilter(traceIdFilter, "/*")
                .build();
        resetDatabase();
        assertSeedCatalogueIsPristine();
    }

    /**
     * SKU prefix reserved for products a test creates for itself.
     *
     * <p>Anything named this way is deleted by {@link #resetDatabase}. A test that
     * needs to <em>mutate</em> a product — reprice it, rename it, retire it — must
     * create its own rather than editing a seeded one. See the note on catalogue
     * restoration below for why.
     */
    public static final String TEST_SKU_PREFIX = "TEST-";

    /**
     * Returns the database to a known state between test methods.
     *
     * <p>Deletion order follows the foreign keys from child to parent — removing
     * {@code users} first would fail on {@code orders.user_id}, which is
     * {@code ON DELETE RESTRICT} precisely so an order cannot be orphaned.
     *
     * <h2>Why stock is reset but prices are not restored</h2>
     * Stock is reset because every checkout changes it, and a test that reserved
     * three units must not leave the next one starting from a depleted shelf.
     *
     * <p>Catalogue <em>attributes</em> are a different matter. Restoring them would
     * mean duplicating V9's seed data here, in a second place that can drift from
     * the migration. Instead the rule is that the seeded catalogue is read-only:
     * a test that wants to change a product creates one with the
     * {@link #TEST_SKU_PREFIX} and changes that, and those are deleted below.
     *
     * <p>This was not the original design. The first version restored only
     * {@code active} and stock, and a test that repriced the seeded laptop to 1.00
     * left it that way — so the suite passed once and failed on every rerun, which
     * is the "green on CI, red locally" failure mode in miniature.
     */
    protected void resetDatabase() {
        transactionTemplate.executeWithoutResult(status -> {
            entityManager.createNativeQuery("DELETE FROM idempotency_keys").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM payments").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM order_items").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM orders").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM cart_items").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM carts").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM refresh_tokens").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM users").executeUpdate();

            // Products a test created for itself. inventory.product_id is
            // ON DELETE CASCADE, so the stock rows go with them.
            entityManager.createNativeQuery(
                            "DELETE FROM products WHERE sku LIKE :prefix")
                    .setParameter("prefix", TEST_SKU_PREFIX + "%")
                    .executeUpdate();
            entityManager.createNativeQuery(
                            "DELETE FROM categories WHERE name LIKE :prefix")
                    .setParameter("prefix", TEST_SKU_PREFIX + "%")
                    .executeUpdate();

            // Restore the seeded stock levels. V9 gives ELEC-LAPTOP-001 five units
            // deliberately, so the concurrency tests have a small, known ceiling.
            entityManager.createNativeQuery("""
                    UPDATE inventory i
                       SET available = CASE WHEN p.sku = 'ELEC-LAPTOP-001' THEN 5 ELSE 50 END,
                           reserved  = 0
                      FROM products p
                     WHERE p.id = i.product_id
                    """).executeUpdate();

            // Deactivation is the one seeded-catalogue change tests are allowed to
            // make, because it is reversible without knowing the seeded value.
            entityManager.createNativeQuery("UPDATE products   SET active = TRUE").executeUpdate();
            entityManager.createNativeQuery("UPDATE categories SET active = TRUE").executeUpdate();
        });
    }

    /**
     * Asserts that the seeded catalogue is still as V9 left it.
     *
     * <p>Called by {@link #baseSetUp} so a test that edits a seeded product fails
     * loudly at the start of the <em>next</em> test, rather than silently poisoning
     * the rest of the run.
     */
    private void assertSeedCatalogueIsPristine() {
        Long tampered = transactionTemplate.execute(status -> ((Number) entityManager
                .createNativeQuery("""
                        SELECT COUNT(*) FROM products
                         WHERE sku = 'ELEC-LAPTOP-001' AND price <> 89999.0000
                        """)
                .getSingleResult()).longValue());

        if (tampered != null && tampered > 0) {
            throw new IllegalStateException(
                    "The seeded catalogue has been modified by an earlier test. Products with the "
                    + TEST_SKU_PREFIX + " prefix are the only ones a test may mutate; see "
                    + "AbstractIntegrationTest.resetDatabase(). Re-run ./scripts/migrate.sh test "
                    + "against a clean ecommerce_test_db to recover.");
        }
    }

    // ------------------------------------------------------------ test accounts --

    @Transactional
    protected User createCustomer(String email) {
        return userRepository.saveAndFlush(User.registerCustomer(
                email, passwordEncoder.encode(TEST_PASSWORD), "Test Customer"));
    }

    @Transactional
    protected User createAdmin(String email) {
        return userRepository.saveAndFlush(User.createAdmin(
                email, passwordEncoder.encode(TEST_PASSWORD), "Test Administrator"));
    }

    /** Logs in through the real endpoint, so the token is one the filter chain will accept. */
    protected String tokenFor(String email) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}
                                """.formatted(email, TEST_PASSWORD)))
                .andReturn().getResponse().getContentAsString();

        return "Bearer " + objectMapper.readTree(body).get("accessToken").asText();
    }

    protected String customerToken(String email) throws Exception {
        createCustomer(email);
        return tokenFor(email);
    }

    protected String adminToken(String email) throws Exception {
        createAdmin(email);
        return tokenFor(email);
    }

    protected JsonNode json(String body) throws Exception {
        return objectMapper.readTree(body);
    }

    /** The id of a seeded product, looked up by its stable SKU rather than a guessed id. */
    protected Long productIdBySku(String sku) {
        return transactionTemplate.execute(status -> ((Number) entityManager
                .createNativeQuery("SELECT id FROM products WHERE sku = :sku")
                .setParameter("sku", sku)
                .getSingleResult()).longValue());
    }

    /** The category a product belongs to, so a test need not hard-code a seeded id. */
    protected Long categoryIdOf(Long productId) {
        return transactionTemplate.execute(status -> ((Number) entityManager
                .createNativeQuery("SELECT category_id FROM products WHERE id = :id")
                .setParameter("id", productId)
                .getSingleResult()).longValue());
    }

    protected int availableStock(String sku) {
        return transactionTemplate.execute(status -> ((Number) entityManager
                .createNativeQuery("""
                        SELECT i.available FROM inventory i
                        JOIN products p ON p.id = i.product_id
                        WHERE p.sku = :sku
                        """)
                .setParameter("sku", sku)
                .getSingleResult()).intValue());
    }

    protected int reservedStock(String sku) {
        return transactionTemplate.execute(status -> ((Number) entityManager
                .createNativeQuery("""
                        SELECT i.reserved FROM inventory i
                        JOIN products p ON p.id = i.product_id
                        WHERE p.sku = :sku
                        """)
                .setParameter("sku", sku)
                .getSingleResult()).intValue());
    }

    protected long countRows(String table) {
        return transactionTemplate.execute(status -> ((Number) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM " + table)
                .getSingleResult()).longValue());
    }

    protected Role roleOf(String email) {
        return userRepository.findByEmailIgnoreCase(User.normalizeEmail(email))
                .map(User::getRole)
                .orElseThrow();
    }
}
