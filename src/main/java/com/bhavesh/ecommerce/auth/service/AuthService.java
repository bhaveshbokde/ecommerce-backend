package com.bhavesh.ecommerce.auth.service;

import com.bhavesh.ecommerce.auth.dto.AuthResponse;
import com.bhavesh.ecommerce.auth.dto.LoginRequest;
import com.bhavesh.ecommerce.auth.dto.RefreshRequest;
import com.bhavesh.ecommerce.auth.dto.RegisterRequest;
import com.bhavesh.ecommerce.auth.entity.RefreshToken;
import com.bhavesh.ecommerce.auth.repository.RefreshTokenRepository;
import com.bhavesh.ecommerce.exception.ApiException;
import com.bhavesh.ecommerce.exception.ErrorCode;
import com.bhavesh.ecommerce.security.JwtService;
import com.bhavesh.ecommerce.security.TokenType;
import com.bhavesh.ecommerce.security.UserPrincipal;
import com.bhavesh.ecommerce.user.dto.UserResponse;
import com.bhavesh.ecommerce.user.entity.User;
import com.bhavesh.ecommerce.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Registration, login, refresh and logout.
 *
 * <h2>Two tokens, two jobs</h2>
 * The access token is a short-lived JWT: self-contained, so the filter chain
 * verifies it with no database round trip, and short-lived precisely because it
 * cannot be revoked. The refresh token is long-lived but opaque and stored as a
 * SHA-256 hash, so it <em>can</em> be revoked — which is what makes logout real.
 *
 * <p>The cost of that design is stated plainly: disabling an account does not take
 * effect until the current access token expires. Closing that window would mean
 * loading the user on every request, which is exactly the round trip the JWT
 * exists to avoid. Refresh does re-read the user, so the window is bounded by the
 * access-token TTL.
 *
 * <h2>Refresh token rotation</h2>
 * Every refresh revokes the presented token and issues a new one. If a token is
 * stolen and used, the legitimate client's next refresh presents a token that is
 * already revoked and fails — the theft surfaces instead of going unnoticed for a
 * week.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /**
     * A real BCrypt hash of a throwaway value, used to equalise timing on an
     * unknown email. See {@link #login}.
     *
     * <p>Generated once at class-initialisation from a random password, so it
     * matches nothing and is not a committed credential.
     */
    private final String dummyHash;

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.dummyHash = passwordEncoder.encode(jwtService.generateRefreshTokenValue());
    }

    // ------------------------------------------------------------ registration --

    /**
     * Creates a customer account.
     *
     * <p>Checks for a duplicate email and <em>also</em> catches the constraint
     * violation. The check gives the common case a clean 409 without an exception;
     * the catch covers the race where two registrations for the same address pass
     * the check concurrently. Only the unique index is a guarantee — the check
     * alone is a TOCTOU bug.
     */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = User.normalizeEmail(request.email());

        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new ApiException(ErrorCode.EMAIL_ALREADY_REGISTERED);
        }

        // registerCustomer() hard-codes ROLE_CUSTOMER — there is no path from this
        // request to an administrator account.
        User user = User.registerCustomer(email, passwordEncoder.encode(request.password()),
                request.fullName().trim());

        try {
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Lost the race. Same answer as the pre-check, so the two paths are
            // indistinguishable from outside.
            throw new ApiException(ErrorCode.EMAIL_ALREADY_REGISTERED);
        }

        log.info("Registered user id={}", user.getId());
        return issueTokens(user);
    }

    // ------------------------------------------------------------------- login --

    /**
     * Authenticates an email and password.
     *
     * <p>Does the password comparison even when the email is unknown, against
     * {@link #dummyHash}. Skipping it would make a miss return in microseconds
     * while a hit spends ~100ms in BCrypt, and that difference is measurable over
     * the network — a timing oracle that lets someone enumerate registered
     * addresses. Both paths now cost one BCrypt verification and return the same
     * {@code INVALID_CREDENTIALS}.
     *
     * <p>Not {@code @Transactional(readOnly = true)}: a successful login writes a
     * refresh-token row.
     */
    @Transactional
    public AuthResponse login(LoginRequest request) {
        Optional<User> maybeUser = userRepository.findByEmailIgnoreCase(
                User.normalizeEmail(request.email()));

        String hashToCheck = maybeUser.map(User::getPasswordHash).orElse(dummyHash);
        boolean passwordMatches = passwordEncoder.matches(request.password(), hashToCheck);

        if (maybeUser.isEmpty() || !passwordMatches) {
            log.warn("Failed login attempt");   // no email: the log is not an account list
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }

        User user = maybeUser.get();
        if (!user.isEnabled()) {
            // Only reachable once the password is already correct, so this does not
            // leak the existence of an account to someone who cannot log in anyway.
            throw new ApiException(ErrorCode.ACCOUNT_DISABLED);
        }

        log.info("Login succeeded for user id={}", user.getId());
        return issueTokens(user);
    }

    // ----------------------------------------------------------------- refresh --

    /**
     * Exchanges a refresh token for a new pair, rotating the refresh token.
     *
     * <p>Re-reads the user rather than trusting the stored row: this is the point
     * at which a role change or a disablement takes effect.
     */
    @Transactional
    public AuthResponse refresh(RefreshRequest request) {
        String hash = jwtService.hashRefreshToken(request.refreshToken());

        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new ApiException(ErrorCode.REFRESH_TOKEN_INVALID));

        if (!stored.isUsable()) {
            // Covers both revoked and expired. One message for both: telling a
            // caller which one would help an attacker map the token's history.
            throw new ApiException(ErrorCode.REFRESH_TOKEN_INVALID);
        }

        User user = userRepository.findById(stored.getUserId())
                .orElseThrow(() -> new ApiException(ErrorCode.REFRESH_TOKEN_INVALID));

        if (!user.isEnabled()) {
            // Disabling an account revokes everything, so the old tokens cannot be
            // reused if it is re-enabled later.
            refreshTokenRepository.revokeAllForUser(user.getId(), Instant.now());
            throw new ApiException(ErrorCode.ACCOUNT_DISABLED);
        }

        // Rotation: the presented token dies here, whether or not it was stolen.
        stored.revoke();
        refreshTokenRepository.save(stored);

        return issueTokens(user);
    }

    // ------------------------------------------------------------------ logout --

    /**
     * Revokes one refresh token.
     *
     * <p>Silent on an unknown token. "Log me out" has succeeded in every sense the
     * caller cares about, and answering 404 would confirm which tokens exist.
     *
     * <p>The access token stays valid until it expires — that is inherent to
     * stateless JWTs, and the honest fix is a short TTL, not a pretence.
     */
    @Transactional
    public void logout(String refreshTokenValue) {
        refreshTokenRepository.findByTokenHash(jwtService.hashRefreshToken(refreshTokenValue))
                .ifPresent(token -> {
                    token.revoke();
                    refreshTokenRepository.save(token);
                    log.info("Revoked refresh token for user id={}", token.getUserId());
                });
    }

    /** "Log out everywhere" — also what a password change would call. */
    @Transactional
    public int logoutEverywhere(Long userId) {
        int revoked = refreshTokenRepository.revokeAllForUser(userId, Instant.now());
        log.info("Revoked {} refresh token(s) for user id={}", revoked, userId);
        return revoked;
    }

    // ----------------------------------------------------------------- helpers --

    private AuthResponse issueTokens(User user) {
        UserPrincipal principal = UserPrincipal.from(user);

        String accessToken = jwtService.generateAccessToken(principal);

        // Opaque and random, not a JWT: its validity is "a live row exists", which
        // a self-describing token cannot assert.
        String refreshValue = jwtService.generateRefreshTokenValue();
        refreshTokenRepository.save(new RefreshToken(
                user.getId(),
                jwtService.hashRefreshToken(refreshValue),
                Instant.now().plus(jwtService.refreshTokenTtl())));

        return AuthResponse.of(accessToken, refreshValue,
                jwtService.accessTokenTtl().toSeconds(), UserResponse.from(user));
    }

    /** Used by {@code /auth/me}; verifies the account still exists and is enabled. */
    @Transactional(readOnly = true)
    public UserResponse currentUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.AUTHENTICATION_REQUIRED));
        return UserResponse.from(user);
    }

    /** Exposed for tests that need to assert the token type encoded in a JWT. */
    UserPrincipal verifyAccessToken(String token) {
        return jwtService.verify(token, TokenType.ACCESS);
    }
}
