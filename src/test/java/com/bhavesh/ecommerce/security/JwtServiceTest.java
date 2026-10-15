package com.bhavesh.ecommerce.security;

import com.bhavesh.ecommerce.config.JwtProperties;
import com.bhavesh.ecommerce.exception.ApiException;
import com.bhavesh.ecommerce.exception.ErrorCode;
import com.bhavesh.ecommerce.user.entity.Role;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Token issuing and verification.
 *
 * <p>Expiry is tested by moving an injected {@link Clock}, never by sleeping. A
 * test that sleeps for the token lifetime would take thirty minutes; one that
 * sleeps for a shortened lifetime is a race waiting to flake on a loaded CI
 * machine.
 */
class JwtServiceTest {

    /** 32 bytes of key material, the RFC 7518 §3.2 minimum for HS256. */
    private static final String SECRET =
            Base64.getEncoder().encodeToString("test-secret-key-for-unit-tests!!".getBytes(StandardCharsets.UTF_8));

    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");

    private JwtProperties properties;
    private JwtService jwtService;
    private UserPrincipal customer;

    @BeforeEach
    void setUp() {
        properties = new JwtProperties(SECRET, "ecommerce-backend",
                Duration.ofMinutes(30), Duration.ofDays(7), Duration.ofSeconds(30));
        jwtService = new JwtService(properties, Clock.fixed(NOW, ZoneOffset.UTC));
        customer = new UserPrincipal(7L, "priya@example.com", null, Role.ROLE_CUSTOMER, true);
    }

    @Nested
    @DisplayName("issuing and verifying")
    class RoundTrip {

        @Test
        @DisplayName("a freshly issued access token verifies and carries the identity")
        void roundTrip() {
            UserPrincipal verified = jwtService.verify(
                    jwtService.generateAccessToken(customer), TokenType.ACCESS);

            assertThat(verified.getId()).isEqualTo(7L);
            assertThat(verified.getEmail()).isEqualTo("priya@example.com");
            assertThat(verified.getRole()).isEqualTo(Role.ROLE_CUSTOMER);
        }

        @Test
        @DisplayName("the principal rebuilt from claims carries no password hash")
        void noPasswordHashInToken() {
            String token = jwtService.generateAccessToken(
                    new UserPrincipal(7L, "priya@example.com", "$2a$12$averysecrethash",
                            Role.ROLE_CUSTOMER, true));

            // A JWT is signed, not encrypted — anyone holding it can read the payload.
            String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]),
                    StandardCharsets.UTF_8);
            assertThat(payload).doesNotContain("$2a$").doesNotContain("averysecrethash");

            assertThat(jwtService.verify(token, TokenType.ACCESS).getPassword()).isNull();
        }

        @Test
        @DisplayName("the role travels in the token, so an admin token verifies as admin")
        void roleTravels() {
            UserPrincipal admin = new UserPrincipal(1L, "admin@example.com", null,
                    Role.ROLE_ADMIN, true);
            assertThat(jwtService.verify(jwtService.generateAccessToken(admin), TokenType.ACCESS)
                    .isAdmin()).isTrue();
        }
    }

    @Nested
    @DisplayName("rejection")
    class Rejection {

        @Test
        @DisplayName("an expired token is TOKEN_EXPIRED, so the client knows to refresh")
        void expiredToken() {
            String token = jwtService.generateAccessToken(customer);

            // Move past the 30-minute TTL and the 30-second skew allowance.
            JwtService later = new JwtService(properties,
                    Clock.fixed(NOW.plus(Duration.ofMinutes(31)), ZoneOffset.UTC));

            assertThatThrownBy(() -> later.verify(token, TokenType.ACCESS))
                    .isInstanceOf(ApiException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TOKEN_EXPIRED);
        }

        @Test
        @DisplayName("the clock-skew allowance keeps a marginally-late clock working")
        void clockSkewTolerated() {
            String token = jwtService.generateAccessToken(customer);

            JwtService slightlyAhead = new JwtService(properties,
                    Clock.fixed(NOW.plus(Duration.ofMinutes(30)).plusSeconds(10), ZoneOffset.UTC));

            assertThat(slightlyAhead.verify(token, TokenType.ACCESS).getId()).isEqualTo(7L);
        }

        @Test
        @DisplayName("a tampered signature is TOKEN_INVALID")
        void tamperedSignature() {
            String token = jwtService.generateAccessToken(customer);
            String tampered = token.substring(0, token.length() - 2)
                    + (token.endsWith("A") ? "B" : "A");

            assertThatThrownBy(() -> jwtService.verify(tampered, TokenType.ACCESS))
                    .isInstanceOf(ApiException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TOKEN_INVALID);
        }

        /**
         * Algorithm confusion. Verification is built with {@code verifyWith(SecretKey)},
         * so JJWT will only accept a MAC algorithm — the header's {@code alg} is never
         * consulted to choose a strategy. A token claiming {@code "alg":"none"} fails
         * signature verification rather than being honoured.
         */
        @Test
        @DisplayName("an unsigned token is refused, however its header describes itself")
        void unsignedTokenRefused() {
            String unsigned = Jwts.builder()
                    .subject("1")
                    .issuer("ecommerce-backend")
                    .claim("typ", "ACCESS")
                    .claim("rol", "ROLE_ADMIN")
                    .expiration(Date.from(NOW.plus(Duration.ofHours(1))))
                    .compact();

            assertThatThrownBy(() -> jwtService.verify(unsigned, TokenType.ACCESS))
                    .isInstanceOf(ApiException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TOKEN_INVALID);
        }

        @Test
        @DisplayName("a token signed with a different key is refused")
        void wrongSigningKey() {
            String forged = Jwts.builder()
                    .subject("1")
                    .issuer("ecommerce-backend")
                    .claim("typ", "ACCESS")
                    .claim("rol", "ROLE_ADMIN")
                    .expiration(Date.from(NOW.plus(Duration.ofHours(1))))
                    .signWith(Keys.hmacShaKeyFor(
                            "a-completely-different-32-byte-key".getBytes(StandardCharsets.UTF_8)),
                            Jwts.SIG.HS256)
                    .compact();

            assertThatThrownBy(() -> jwtService.verify(forged, TokenType.ACCESS))
                    .isInstanceOf(ApiException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TOKEN_INVALID);
        }

        @Test
        @DisplayName("a token from a different issuer is refused")
        void wrongIssuer() {
            JwtProperties otherIssuer = new JwtProperties(SECRET, "some-other-service",
                    Duration.ofMinutes(30), Duration.ofDays(7), Duration.ofSeconds(30));
            String token = new JwtService(otherIssuer, Clock.fixed(NOW, ZoneOffset.UTC))
                    .generateAccessToken(customer);

            assertThatThrownBy(() -> jwtService.verify(token, TokenType.ACCESS))
                    .isInstanceOf(ApiException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TOKEN_INVALID);
        }

        @Test
        @DisplayName("garbage is refused without leaking why")
        void garbageInput() {
            assertThatThrownBy(() -> jwtService.verify("not-a-jwt", TokenType.ACCESS))
                    .isInstanceOf(ApiException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TOKEN_INVALID)
                    // The parser's reason is logged, never returned: telling an
                    // attacker *why* verification failed is free oracle access.
                    .hasMessage(ErrorCode.TOKEN_INVALID.defaultDetail());
        }
    }

    @Nested
    @DisplayName("token confusion")
    class TokenConfusion {

        /**
         * Without the {@code typ} claim, a seven-day refresh token presented in an
         * {@code Authorization} header would be accepted as a bearer credential —
         * silently turning it into a seven-day API key.
         */
        @Test
        @DisplayName("an access token is not accepted where a refresh token is expected")
        void accessTokenIsNotARefreshToken() {
            String access = jwtService.generateAccessToken(customer);

            assertThatThrownBy(() -> jwtService.verify(access, TokenType.REFRESH))
                    .isInstanceOf(ApiException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TOKEN_INVALID)
                    .hasMessageContaining("refresh");
        }

        @Test
        @DisplayName("a token with an unknown role is refused rather than defaulted")
        void unknownRoleRefused() {
            String token = Jwts.builder()
                    .subject("1")
                    .issuer("ecommerce-backend")
                    .claim("typ", "ACCESS")
                    .claim("rol", "ROLE_SUPERUSER")
                    .expiration(Date.from(NOW.plus(Duration.ofHours(1))))
                    .signWith(Keys.hmacShaKeyFor(Base64.getDecoder().decode(SECRET)), Jwts.SIG.HS256)
                    .compact();

            assertThatThrownBy(() -> jwtService.verify(token, TokenType.ACCESS))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("unknown role");
        }

        @Test
        @DisplayName("a non-numeric subject is refused")
        void nonNumericSubject() {
            String token = Jwts.builder()
                    .subject("not-a-number")
                    .issuer("ecommerce-backend")
                    .claim("typ", "ACCESS")
                    .claim("rol", "ROLE_CUSTOMER")
                    .expiration(Date.from(NOW.plus(Duration.ofHours(1))))
                    .signWith(Keys.hmacShaKeyFor(Base64.getDecoder().decode(SECRET)), Jwts.SIG.HS256)
                    .compact();

            assertThatThrownBy(() -> jwtService.verify(token, TokenType.ACCESS))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("subject");
        }
    }

    @Nested
    @DisplayName("refresh token material")
    class RefreshTokens {

        @Test
        @DisplayName("values are unique and high-entropy")
        void uniqueAndLong() {
            var values = new java.util.HashSet<String>();
            for (int i = 0; i < 500; i++) {
                values.add(jwtService.generateRefreshTokenValue());
            }
            assertThat(values).hasSize(500);
            // 32 bytes base64url without padding.
            assertThat(values.iterator().next()).hasSize(43);
        }

        @Test
        @DisplayName("the hash is deterministic, so a lookup by hash finds the row")
        void hashIsDeterministic() {
            String token = jwtService.generateRefreshTokenValue();
            assertThat(jwtService.hashRefreshToken(token))
                    .isEqualTo(jwtService.hashRefreshToken(token))
                    .hasSize(64)                    // SHA-256 as lowercase hex
                    .matches("[0-9a-f]{64}");
        }

        @Test
        @DisplayName("the hash does not reveal the token")
        void hashHidesTheToken() {
            String token = jwtService.generateRefreshTokenValue();
            assertThat(jwtService.hashRefreshToken(token)).isNotEqualTo(token);
        }

        @Test
        @DisplayName("different tokens hash differently")
        void distinctHashes() {
            assertThat(jwtService.hashRefreshToken(jwtService.generateRefreshTokenValue()))
                    .isNotEqualTo(jwtService.hashRefreshToken(jwtService.generateRefreshTokenValue()));
        }
    }

    @Nested
    @DisplayName("key validation")
    class KeyValidation {

        /**
         * A weak key must break startup, not the first login in production. The
         * message deliberately mentions the unresolved-placeholder case, because
         * {@code ${ECOMMERCE_JWT_SECRET}} reaching the validator verbatim is the
         * most common way this fails — it means the working directory was wrong and
         * {@code .env} never loaded.
         */
        @Test
        @DisplayName("a short secret fails fast at construction")
        void shortSecretRejected() {
            JwtProperties weak = new JwtProperties("too-short", "ecommerce-backend",
                    Duration.ofMinutes(30), Duration.ofDays(7), Duration.ofSeconds(30));

            assertThatThrownBy(() -> new JwtService(weak, Clock.systemUTC()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("256 bits")
                    .hasMessageContaining("ECOMMERCE_JWT_SECRET");
        }

        @Test
        @DisplayName("an unresolved placeholder is exactly the length that trips the check")
        void unresolvedPlaceholderIsRejected() {
            JwtProperties placeholder = new JwtProperties("${ECOMMERCE_JWT_SECRET}",
                    "ecommerce-backend", Duration.ofMinutes(30), Duration.ofDays(7),
                    Duration.ofSeconds(30));

            assertThatThrownBy(() -> new JwtService(placeholder, Clock.systemUTC()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("working directory");
        }

        @Test
        @DisplayName("a long raw (non-base64) secret is accepted")
        void rawSecretAccepted() {
            JwtProperties raw = new JwtProperties(
                    "a-perfectly-adequate-raw-secret-of-sufficient-length-for-hs256",
                    "ecommerce-backend", Duration.ofMinutes(30), Duration.ofDays(7),
                    Duration.ofSeconds(30));

            JwtService service = new JwtService(raw, Clock.fixed(NOW, ZoneOffset.UTC));
            assertThat(service.verify(service.generateAccessToken(customer), TokenType.ACCESS)
                    .getId()).isEqualTo(7L);
        }
    }
}
