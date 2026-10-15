package com.bhavesh.ecommerce.security;

import com.bhavesh.ecommerce.user.entity.User;
import com.bhavesh.ecommerce.user.repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Looks a user up by email for Spring Security.
 *
 * <p>Exists for two reasons, and the second is the one worth recording.
 *
 * <p>The first is ordinary: anything in the framework that needs to resolve a
 * username — method security expressions, a future {@code AuthenticationManager} —
 * goes through this type, and it should read the real user table.
 *
 * <p>The second is that <strong>without a {@code UserDetailsService} bean, Spring
 * Boot's {@code UserDetailsServiceAutoConfiguration} creates one for you</strong>:
 * a single in-memory user named {@code user}, with a random password printed to the
 * startup log. Harmless here — nothing in the bearer-token path consults it — but it
 * is a credential sitting in the application context and in the logs for no reason,
 * and the warning it prints is exactly the kind of noise that trains people to
 * ignore warnings. Defining this bean suppresses that auto-configuration.
 *
 * <p>Note that {@link JwtAuthenticationFilter} does <em>not</em> call this class.
 * Rebuilding the principal from verified token claims is what keeps the filter
 * chain stateless; routing every request through a database lookup would undo that.
 * The documented consequence is that a role change takes effect at the next refresh
 * rather than the next request.
 */
@Service
public class DatabaseUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public DatabaseUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmailIgnoreCase(User.normalizeEmail(email))
                .orElseThrow(() -> new UsernameNotFoundException("No account for that address"));
        return UserPrincipal.from(user);
    }
}
