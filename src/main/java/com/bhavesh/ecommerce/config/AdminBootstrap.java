package com.bhavesh.ecommerce.config;

import com.bhavesh.ecommerce.user.entity.User;
import com.bhavesh.ecommerce.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the development administrator, once, from environment configuration.
 *
 * <p>Needed because the only public registration path hard-codes
 * {@code ROLE_CUSTOMER} — by design — so without this there would be no way to
 * reach an administrator endpoint on a fresh database. The migrations seed no
 * users at all, so no credential is ever committed.
 *
 * <h2>Three guards, each for a different failure</h2>
 * <ol>
 *   <li><strong>Not in {@code prod}.</strong> The {@code prod} profile blanks both
 *       values in {@code application.yml}, and this class checks the profile as
 *       well. Belt and braces: a leaked {@code ECOMMERCE_ADMIN_PASSWORD} must not be
 *       able to mint an administrator in production, even if the configuration is
 *       later edited.</li>
 *   <li><strong>Both values present.</strong> Absent configuration means no admin,
 *       not a default one. There is no fallback password in this file, because a
 *       default administrator credential in source is a backdoor regardless of how
 *       it is described in a comment.</li>
 *   <li><strong>Not already present.</strong> Idempotent across restarts, and it
 *       never <em>updates</em> an existing account — a restart must not silently
 *       reset the password of an account someone has since changed, nor promote an
 *       existing customer who happens to share the address.</li>
 * </ol>
 *
 * <p>An {@link ApplicationRunner} rather than {@code @PostConstruct}: it runs after
 * the context is fully refreshed, so Flyway has finished and the schema exists.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AdminProperties adminProperties;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final Environment environment;

    public AdminBootstrap(AdminProperties adminProperties,
                          UserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          Environment environment) {
        this.adminProperties = adminProperties;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.environment = environment;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (environment.matchesProfiles("prod")) {
            log.info("Administrator bootstrap skipped: prod profile is active");
            return;
        }

        if (!adminProperties.isConfigured()) {
            log.info("Administrator bootstrap skipped: ecommerce.admin.email/password are not set");
            return;
        }

        String email = User.normalizeEmail(adminProperties.email());

        if (userRepository.existsByEmailIgnoreCase(email)) {
            // Deliberately does not update the existing row — see the class comment.
            log.info("Administrator bootstrap skipped: an account already exists for that address");
            return;
        }

        User admin = User.createAdmin(email,
                passwordEncoder.encode(adminProperties.password()),
                "Administrator");
        User saved = userRepository.save(admin);

        // Logs the id, never the address or the password. The address is in .env if
        // the operator needs it, and a log line is not the right place for either.
        log.warn("Created development administrator account id={} — "
                + "this path is disabled under the prod profile", saved.getId());
    }
}
