package com.bhavesh.ecommerce.config;

import com.bhavesh.ecommerce.security.JwtAuthenticationFilter;
import com.bhavesh.ecommerce.security.RestAccessDeniedHandler;
import com.bhavesh.ecommerce.security.RestAuthenticationEntryPoint;
import com.bhavesh.ecommerce.user.entity.Role;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * The security filter chain.
 *
 * <p>Authorization is expressed here as URL rules rather than scattered across
 * controllers, so the whole policy can be read in one place. Method-level
 * {@code @PreAuthorize} is enabled too, for the cases where the rule depends on
 * something a URL cannot express.
 */
@Configuration
@EnableMethodSecurity   // enables @PreAuthorize / @PostAuthorize
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;
    private final CorsProperties corsProperties;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          RestAuthenticationEntryPoint authenticationEntryPoint,
                          RestAccessDeniedHandler accessDeniedHandler,
                          CorsProperties corsProperties) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
        this.corsProperties = corsProperties;
    }

    /**
     * BCrypt at strength 12.
     *
     * <p>BCrypt is deliberately slow and salts automatically — a per-password salt
     * is embedded in the output, so identical passwords produce different hashes and
     * one rainbow table cannot cover the whole table. SHA-256 would be wrong here
     * for the opposite reason it is right for refresh tokens: a password is
     * low-entropy and guessable, so the hash must be expensive.
     *
     * <p>Strength 12 means 2^12 rounds, roughly 250ms on current hardware. The
     * default of 10 is about 60ms. The higher cost is paid once per login and makes
     * offline cracking of a leaked table four times more expensive.
     *
     * <p>The 72-byte input limit is enforced in {@code RegisterRequest}: BCrypt
     * silently ignores bytes past 72, and silent truncation of a credential is worse
     * than a validation error.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // CSRF protects cookie-authenticated browser sessions. This API
            // authenticates with a bearer token the client must attach explicitly,
            // so a cross-site form post carries no credentials and there is nothing
            // for a token to defend. Disabled deliberately, not by neglect.
            .csrf(AbstractHttpConfigurer::disable)

            .cors(cors -> cors.configurationSource(corsConfigurationSource()))

            // No session, no JSESSIONID, no server-side state to replicate. Every
            // request carries its own proof of identity.
            .sessionManagement(session ->
                    session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // Both emit the same RFC 9457 body as every other error. Without them,
            // Spring Security's defaults would return a differently-shaped 401/403.
            .exceptionHandling(handling -> handling
                    .authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler))

            .authorizeHttpRequests(auth -> auth
                    // --- public ---
                    .requestMatchers("/api/v1/auth/register", "/api/v1/auth/login",
                                     "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/products/**",
                                     "/api/v1/categories/**").permitAll()
                    // HEAD as well as GET. A matcher for GET does not match HEAD, so
                    // without this a caching proxy, an uptime monitor or a plain
                    // `curl -I` against a public catalogue URL falls through to
                    // `anyRequest().authenticated()` and gets a 401 — surprising, and
                    // for a resource that is deliberately public.
                    .requestMatchers(HttpMethod.HEAD, "/api/v1/products/**",
                                     "/api/v1/categories/**").permitAll()
                    .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                    .requestMatchers("/v3/api-docs/**", "/swagger-ui/**",
                                     "/swagger-ui.html").permitAll()

                    // --- administrator ---
                    // Listed before the authenticated rules below: the first matching
                    // rule wins, so a broader rule placed earlier would shadow these.
                    .requestMatchers("/api/v1/admin/**").hasRole(Role.ROLE_ADMIN.shortName())
                    .requestMatchers(HttpMethod.POST, "/api/v1/products", "/api/v1/categories")
                        .hasRole(Role.ROLE_ADMIN.shortName())
                    .requestMatchers(HttpMethod.PUT, "/api/v1/products/**", "/api/v1/categories/**")
                        .hasRole(Role.ROLE_ADMIN.shortName())
                    .requestMatchers(HttpMethod.PATCH, "/api/v1/products/**", "/api/v1/categories/**")
                        .hasRole(Role.ROLE_ADMIN.shortName())
                    .requestMatchers(HttpMethod.DELETE, "/api/v1/products/**", "/api/v1/categories/**")
                        .hasRole(Role.ROLE_ADMIN.shortName())
                    .requestMatchers("/actuator/**").hasRole(Role.ROLE_ADMIN.shortName())

                    // --- everything else needs a valid token ---
                    .anyRequest().authenticated())

            // Runs before the username/password filter so a request arrives at the
            // authorization rules already authenticated. Placed after, the rules
            // would evaluate an empty context and reject every call.
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)

            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)

            .headers(headers -> headers
                    .frameOptions(frame -> frame.sameOrigin())
                    .contentTypeOptions(Customizer.withDefaults()));

        return http.build();
    }

    /**
     * CORS, with origins from configuration rather than a wildcard.
     *
     * <p>{@code allowCredentials(false)} is correct here and worth stating: this API
     * authenticates with an {@code Authorization} header, not a cookie, so the
     * browser never needs to attach ambient credentials. Setting it true would also
     * forbid a wildcard origin — which this does not use anyway — and would widen
     * the attack surface for no benefit.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(corsProperties.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type",
                "Idempotency-Key", "Accept"));
        configuration.setExposedHeaders(List.of("X-Trace-Id"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
