package com.bhavesh.ecommerce.auth.service;

import com.bhavesh.ecommerce.auth.dto.LoginRequest;
import com.bhavesh.ecommerce.auth.dto.RefreshRequest;
import com.bhavesh.ecommerce.auth.dto.RegisterRequest;
import com.bhavesh.ecommerce.auth.entity.RefreshToken;
import com.bhavesh.ecommerce.auth.repository.RefreshTokenRepository;
import com.bhavesh.ecommerce.config.JwtProperties;
import com.bhavesh.ecommerce.exception.ApiException;
import com.bhavesh.ecommerce.exception.ErrorCode;
import com.bhavesh.ecommerce.security.JwtService;
import com.bhavesh.ecommerce.security.TokenType;
import com.bhavesh.ecommerce.user.entity.Role;
import com.bhavesh.ecommerce.user.entity.User;
import com.bhavesh.ecommerce.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Registration, login, refresh and logout.
 *
 * <p>Uses a real {@link JwtService} and a real {@link BCryptPasswordEncoder}
 * rather than mocks. Both are pure, fast enough, and mocking them would mean the
 * tests assert that the service called a collaborator rather than that a token
 * actually verifies or a password actually matches. BCrypt runs at strength 4 here
 * — the production strength of 12 is the point of the thing in production and only
 * slows a test suite down.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceTest {

    private static final String SECRET =
            Base64.getEncoder().encodeToString("test-secret-key-for-unit-tests!!".getBytes(StandardCharsets.UTF_8));

    @Mock
    private UserRepository userRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    private PasswordEncoder passwordEncoder;
    private JwtService jwtService;
    private AuthService authService;

    private User existingUser;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder(4);
        // The real system clock: nothing here tests expiry, and the clock-injecting
        // constructor is package-private to the security package by design.
        jwtService = new JwtService(new JwtProperties(SECRET, "ecommerce-backend",
                Duration.ofMinutes(30), Duration.ofDays(7), Duration.ofSeconds(30)));
        authService = new AuthService(userRepository, refreshTokenRepository,
                passwordEncoder, jwtService);

        existingUser = User.registerCustomer("priya@example.com",
                passwordEncoder.encode("correct-horse-battery"), "Priya Sharma");
        ReflectionTestUtils.setField(existingUser, "id", 7L);
    }

    @Nested
    @DisplayName("register")
    class Register {

        @Test
        @DisplayName("a new account is created as ROLE_CUSTOMER and issued both tokens")
        void registersCustomer() {
            when(userRepository.existsByEmailIgnoreCase(anyString())).thenReturn(false);
            when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> {
                User u = inv.getArgument(0);
                ReflectionTestUtils.setField(u, "id", 7L);
                return u;
            });

            var response = authService.register(new RegisterRequest(
                    "Priya@Example.com", "correct-horse-battery", "  Priya Sharma  "));

            assertThat(response.user().role()).isEqualTo(Role.ROLE_CUSTOMER);
            assertThat(response.user().email())
                    .as("the address is canonicalised to match uq_users_email_lower")
                    .isEqualTo("priya@example.com");
            assertThat(response.user().fullName()).isEqualTo("Priya Sharma");
            assertThat(response.tokenType()).isEqualTo("Bearer");
            assertThat(response.expiresInSeconds()).isEqualTo(1800);

            // The access token is a genuine, verifiable JWT, not a placeholder.
            assertThat(jwtService.verify(response.accessToken(), TokenType.ACCESS).getId())
                    .isEqualTo(7L);
        }

        @Test
        @DisplayName("the password is stored hashed, never in plain text")
        void passwordIsHashed() {
            when(userRepository.existsByEmailIgnoreCase(anyString())).thenReturn(false);
            ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
            when(userRepository.saveAndFlush(saved.capture())).thenAnswer(inv -> {
                User u = inv.getArgument(0);
                ReflectionTestUtils.setField(u, "id", 7L);
                return u;
            });

            authService.register(new RegisterRequest(
                    "priya@example.com", "correct-horse-battery", "Priya"));

            String stored = saved.getValue().getPasswordHash();
            assertThat(stored).isNotEqualTo("correct-horse-battery").startsWith("$2");
            assertThat(passwordEncoder.matches("correct-horse-battery", stored)).isTrue();
        }

        @Test
        @DisplayName("the same password hashes differently for two users — BCrypt salts per hash")
        void hashesAreSalted() {
            when(userRepository.existsByEmailIgnoreCase(anyString())).thenReturn(false);
            ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
            when(userRepository.saveAndFlush(saved.capture())).thenAnswer(inv -> {
                User u = inv.getArgument(0);
                ReflectionTestUtils.setField(u, "id", 7L);
                return u;
            });

            authService.register(new RegisterRequest("a@example.com", "same-password-xx", "A"));
            authService.register(new RegisterRequest("b@example.com", "same-password-xx", "B"));

            assertThat(saved.getAllValues().get(0).getPasswordHash())
                    .as("identical passwords must not produce identical hashes")
                    .isNotEqualTo(saved.getAllValues().get(1).getPasswordHash());
        }

        @Test
        @DisplayName("a duplicate caught by the pre-check is 409")
        void duplicateByPreCheck() {
            when(userRepository.existsByEmailIgnoreCase("priya@example.com")).thenReturn(true);

            assertThatThrownBy(() -> authService.register(new RegisterRequest(
                    "PRIYA@EXAMPLE.COM", "correct-horse-battery", "Imposter")))
                    .isInstanceOf(ApiException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMAIL_ALREADY_REGISTERED);

            verify(userRepository, never()).saveAndFlush(any());
        }

        /**
         * The pre-check and the insert are not atomic, so two concurrent
         * registrations can both pass it. The unique index decides, and the loser
         * must get the same 409 as if the check had caught it — otherwise a race
         * produces a 500.
         */
        @Test
        @DisplayName("a duplicate caught by the unique index gives the identical 409")
        void duplicateByConstraintRace() {
            when(userRepository.existsByEmailIgnoreCase(anyString())).thenReturn(false);
            when(userRepository.saveAndFlush(any(User.class)))
                    .thenThrow(new DataIntegrityViolationException("uq_users_email_lower"));

            assertThatThrownBy(() -> authService.register(new RegisterRequest(
                    "priya@example.com", "correct-horse-battery", "Priya")))
                    .isInstanceOf(ApiException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EMAIL_ALREADY_REGISTERED);
        }
    }

    @Nested
    @DisplayName("login")
    class Login {

        @Test
        @DisplayName("correct credentials issue tokens")
        void successfulLogin() {
            when(userRepository.findByEmailIgnoreCase("priya@example.com"))
                    .thenReturn(Optional.of(existingUser));

            var response = authService.login(
                    new LoginRequest("Priya@Example.com", "correct-horse-battery"));

            assertThat(response.user().id()).isEqualTo(7L);
            assertThat(jwtService.verify(response.accessToken(), TokenType.ACCESS).getId())
                    .isEqualTo(7L);
            verify(refreshTokenRepository).save(any(RefreshToken.class));
        }

        @Test
        @DisplayName("a wrong password is INVALID_CREDENTIALS")
        void wrongPassword() {
            when(userRepository.findByEmailIgnoreCase(anyString()))
                    .thenReturn(Optional.of(existingUser));

            assertThatThrownBy(() -> authService.login(
                    new LoginRequest("priya@example.com", "wrong-password-xx")))
                    .isInstanceOf(ApiException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_CREDENTIALS);

            verify(refreshTokenRepository, never()).save(any());
        }

        @Test
        @DisplayName("an unknown email gives the identical error, so the API is not an account oracle")
        void unknownEmailIsIndistinguishable() {
            when(userRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.login(
                    new LoginRequest("nobody@example.com", "any-password-here")))
                    .isInstanceOf(ApiException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_CREDENTIALS)
                    .hasMessage(ErrorCode.INVALID_CREDENTIALS.defaultDetail());
        }

        /**
         * The timing side-channel. Returning early on an unknown email would make a
         * miss cost microseconds while a hit costs a full BCrypt verification — a
         * difference large enough to measure over the network and enumerate
         * registered addresses with. The service hashes against a dummy instead.
         *
         * <p>Asserted structurally rather than by stopwatch: wall-clock assertions
         * flake on a loaded CI machine. What matters is that the expensive path runs.
         */
        @Test
        @DisplayName("an unknown email still performs a password comparison")
        void unknownEmailStillHashes() {
            PasswordEncoder counting = new PasswordEncoder() {
                int matchCalls = 0;

                @Override
                public String encode(CharSequence raw) {
                    return "encoded:" + raw;
                }

                @Override
                public boolean matches(CharSequence raw, String encoded) {
                    matchCalls++;
                    return false;
                }

                @Override
                public String toString() {
                    return String.valueOf(matchCalls);
                }
            };

            AuthService service = new AuthService(userRepository, refreshTokenRepository,
                    counting, jwtService);
            when(userRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.login(
                    new LoginRequest("nobody@example.com", "any-password-here")))
                    .isInstanceOf(ApiException.class);

            assertThat(counting.toString())
                    .as("matches() must be called even when no account exists")
                    .isEqualTo("1");
        }

        @Test
        @DisplayName("a disabled account is refused, but only after the password checks out")
        void disabledAccount() {
            existingUser.setEnabled(false);
            when(userRepository.findByEmailIgnoreCase(anyString()))
                    .thenReturn(Optional.of(existingUser));

            assertThatThrownBy(() -> authService.login(
                    new LoginRequest("priya@example.com", "correct-horse-battery")))
                    .isInstanceOf(ApiException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ACCOUNT_DISABLED);
        }

        @Test
        @DisplayName("a disabled account with a wrong password reports bad credentials, not disabled")
        void disabledWithWrongPasswordDoesNotLeak() {
            existingUser.setEnabled(false);
            when(userRepository.findByEmailIgnoreCase(anyString()))
                    .thenReturn(Optional.of(existingUser));

            assertThatThrownBy(() -> authService.login(
                    new LoginRequest("priya@example.com", "wrong-password-xx")))
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_CREDENTIALS);
        }
    }

    @Nested
    @DisplayName("refresh")
    class Refresh {

        private String rawToken;
        private RefreshToken storedToken;

        @BeforeEach
        void issueAToken() {
            rawToken = jwtService.generateRefreshTokenValue();
            storedToken = new RefreshToken(7L, jwtService.hashRefreshToken(rawToken),
                    Instant.now().plus(Duration.ofDays(7)));
        }

        @Test
        @DisplayName("a usable token is exchanged and the presented one is revoked")
        void rotatesOnEveryUse() {
            when(refreshTokenRepository.findByTokenHash(jwtService.hashRefreshToken(rawToken)))
                    .thenReturn(Optional.of(storedToken));
            when(userRepository.findById(7L)).thenReturn(Optional.of(existingUser));

            var response = authService.refresh(new RefreshRequest(rawToken));

            assertThat(storedToken.isRevoked())
                    .as("the presented token must die, whether or not it was stolen")
                    .isTrue();
            assertThat(response.refreshToken()).isNotEqualTo(rawToken);
            verify(refreshTokenRepository).save(storedToken);
        }

        @Test
        @DisplayName("only the hash is ever looked up — the raw value is not stored")
        void looksUpByHash() {
            when(refreshTokenRepository.findByTokenHash(anyString()))
                    .thenReturn(Optional.of(storedToken));
            when(userRepository.findById(7L)).thenReturn(Optional.of(existingUser));

            authService.refresh(new RefreshRequest(rawToken));

            ArgumentCaptor<String> looked = ArgumentCaptor.forClass(String.class);
            verify(refreshTokenRepository).findByTokenHash(looked.capture());
            assertThat(looked.getValue()).isNotEqualTo(rawToken).hasSize(64);
        }

        @Test
        @DisplayName("an unknown token is refused")
        void unknownToken() {
            when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.refresh(new RefreshRequest("made-up-token")))
                    .isInstanceOf(ApiException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.REFRESH_TOKEN_INVALID);
        }

        @Test
        @DisplayName("a rotated (revoked) token cannot be reused — this is how theft surfaces")
        void revokedTokenRefused() {
            storedToken.revoke();
            when(refreshTokenRepository.findByTokenHash(anyString()))
                    .thenReturn(Optional.of(storedToken));

            assertThatThrownBy(() -> authService.refresh(new RefreshRequest(rawToken)))
                    .isInstanceOf(ApiException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.REFRESH_TOKEN_INVALID);
        }

        @Test
        @DisplayName("an expired token is refused with the same message as a revoked one")
        void expiredTokenRefused() {
            RefreshToken expired = new RefreshToken(7L, jwtService.hashRefreshToken(rawToken),
                    Instant.now().minus(Duration.ofDays(1)));
            when(refreshTokenRepository.findByTokenHash(anyString()))
                    .thenReturn(Optional.of(expired));

            assertThatThrownBy(() -> authService.refresh(new RefreshRequest(rawToken)))
                    .isInstanceOf(ApiException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.REFRESH_TOKEN_INVALID)
                    .as("revoked and expired must be indistinguishable from outside")
                    .hasMessage(ErrorCode.REFRESH_TOKEN_INVALID.defaultDetail());
        }

        /**
         * Refresh is the point at which a disablement takes effect — the stateless
         * access token cannot be revoked, so this is the window closing.
         */
        @Test
        @DisplayName("refreshing a disabled account revokes every token it holds")
        void disabledAccountRevokesEverything() {
            existingUser.setEnabled(false);
            when(refreshTokenRepository.findByTokenHash(anyString()))
                    .thenReturn(Optional.of(storedToken));
            when(userRepository.findById(7L)).thenReturn(Optional.of(existingUser));

            assertThatThrownBy(() -> authService.refresh(new RefreshRequest(rawToken)))
                    .isInstanceOf(ApiException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ACCOUNT_DISABLED);

            verify(refreshTokenRepository).revokeAllForUser(anyLong(), any(Instant.class));
        }

        @Test
        @DisplayName("a token for a deleted user is refused")
        void deletedUser() {
            when(refreshTokenRepository.findByTokenHash(anyString()))
                    .thenReturn(Optional.of(storedToken));
            when(userRepository.findById(7L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.refresh(new RefreshRequest(rawToken)))
                    .isInstanceOf(ApiException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.REFRESH_TOKEN_INVALID);
        }
    }

    @Nested
    @DisplayName("logout")
    class Logout {

        @Test
        void revokesTheToken() {
            String raw = jwtService.generateRefreshTokenValue();
            RefreshToken stored = new RefreshToken(7L, jwtService.hashRefreshToken(raw),
                    Instant.now().plus(Duration.ofDays(7)));
            when(refreshTokenRepository.findByTokenHash(anyString()))
                    .thenReturn(Optional.of(stored));

            authService.logout(raw);

            assertThat(stored.isRevoked()).isTrue();
            verify(refreshTokenRepository).save(stored);
        }

        /**
         * Answering 404 for an unknown token would confirm which tokens exist, so
         * logout succeeds silently either way.
         */
        @Test
        @DisplayName("an unknown token is silently accepted")
        void unknownTokenIsSilent() {
            when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

            authService.logout("never-issued");   // must not throw

            verify(refreshTokenRepository, never()).save(any());
        }

        @Test
        @DisplayName("logging out everywhere revokes the whole set")
        void logoutEverywhere() {
            when(refreshTokenRepository.revokeAllForUser(anyLong(), any(Instant.class))).thenReturn(3);

            assertThat(authService.logoutEverywhere(7L)).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("currentUser")
    class CurrentUser {

        /**
         * {@code /auth/me} re-reads the database rather than echoing the token's
         * claims, so a role change or a disablement is visible there immediately
         * instead of waiting for the access token to expire.
         */
        @Test
        @DisplayName("reads the user from the database, not from the token")
        void readsFromDatabase() {
            when(userRepository.findById(7L)).thenReturn(Optional.of(existingUser));

            assertThat(authService.currentUser(7L).email()).isEqualTo("priya@example.com");
            verify(userRepository).findById(7L);
        }

        @Test
        @DisplayName("a token for an account that has since been deleted is refused")
        void deletedAccount() {
            when(userRepository.findById(7L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.currentUser(7L))
                    .isInstanceOf(ApiException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.AUTHENTICATION_REQUIRED);
        }
    }
}
