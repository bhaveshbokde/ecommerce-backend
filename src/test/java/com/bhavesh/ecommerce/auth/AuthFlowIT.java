package com.bhavesh.ecommerce.auth;

import com.bhavesh.ecommerce.support.AbstractIntegrationTest;
import com.bhavesh.ecommerce.user.entity.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Registration, login, refresh and logout, through the real filter chain. */
class AuthFlowIT extends AbstractIntegrationTest {

    @Nested
    @DisplayName("registration")
    class Registration {

        @Test
        @DisplayName("creates an account and issues a working token pair")
        void registersAndIssuesTokens() throws Exception {
            String body = mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"priya@example.com",
                                     "password":"correct-horse-battery",
                                     "fullName":"Priya Sharma"}
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.user.role").value("ROLE_CUSTOMER"))
                    .andExpect(jsonPath("$.tokenType").value("Bearer"))
                    .andExpect(jsonPath("$.expiresInSeconds").value(1800))
                    .andReturn().getResponse().getContentAsString();

            // The issued access token really works against a protected endpoint.
            mockMvc.perform(get("/api/v1/auth/me")
                            .header(HttpHeaders.AUTHORIZATION,
                                    "Bearer " + json(body).get("accessToken").asText()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.email").value("priya@example.com"));
        }

        @Test
        @DisplayName("never returns the password hash")
        void doesNotLeakTheHash() throws Exception {
            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"priya@example.com","password":"correct-horse-battery",
                                     "fullName":"Priya Sharma"}
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(content().string(org.hamcrest.Matchers.not(containsString("$2a$"))))
                    .andExpect(content().string(
                            org.hamcrest.Matchers.not(containsString("correct-horse-battery"))))
                    .andExpect(jsonPath("$.user.passwordHash").doesNotExist());
        }

        /**
         * There is no {@code role} field on {@code RegisterRequest} at all, so the
         * value is simply ignored during binding. This test pins that: if someone
         * ever adds the field "for flexibility", it fails.
         */
        @Test
        @DisplayName("a role in the request body cannot escalate the account")
        void roleInjectionIsIgnored() throws Exception {
            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"sneaky@example.com","password":"password-abcdef",
                                     "fullName":"Sneaky","role":"ROLE_ADMIN",
                                     "authorities":["ROLE_ADMIN"],"enabled":true}
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.user.role").value("ROLE_CUSTOMER"));

            assertThat(roleOf("sneaky@example.com"))
                    .as("the database must agree with the response")
                    .isEqualTo(Role.ROLE_CUSTOMER);
        }

        @Test
        @DisplayName("a duplicate address is 409, case-insensitively")
        void duplicateAddress() throws Exception {
            createCustomer("priya@example.com");

            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"PRIYA@EXAMPLE.COM","password":"another-password",
                                     "fullName":"Imposter"}
                                    """))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));

            assertThat(countRows("users")).isEqualTo(1);
        }

        @Test
        @DisplayName("validation failures list the offending fields")
        void validationErrors() throws Exception {
            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"not-an-email","password":"short","fullName":""}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(
                            MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors").isArray())
                    .andExpect(jsonPath("$.errors.length()").value(3));
        }

        /**
         * Validation error bodies end up in logs, bug reports and screenshots. A
         * rejected password echoed back into one of those is a credential in a log
         * aggregator.
         */
        @Test
        @DisplayName("a rejected password is redacted in the error body")
        void rejectedPasswordIsRedacted() throws Exception {
            String body = mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"priya@example.com","password":"short",
                                     "fullName":"Priya"}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).doesNotContain("short").contains("***");
        }
    }

    @Nested
    @DisplayName("login")
    class Login {

        @Test
        void correctCredentialsSucceed() throws Exception {
            createCustomer("priya@example.com");

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"Priya@Example.com","password":"%s"}
                                    """.formatted(TEST_PASSWORD)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").isNotEmpty())
                    .andExpect(jsonPath("$.refreshToken").isNotEmpty());
        }

        /**
         * Identical bodies for both cases, so the endpoint cannot be used to
         * enumerate which addresses are registered.
         */
        @Test
        @DisplayName("a wrong password and an unknown address are indistinguishable")
        void failuresAreIndistinguishable() throws Exception {
            createCustomer("priya@example.com");

            String wrongPassword = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"priya@example.com","password":"wrong-password"}
                                    """))
                    .andExpect(status().isUnauthorized())
                    .andReturn().getResponse().getContentAsString();

            String unknownEmail = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"nobody@example.com","password":"wrong-password"}
                                    """))
                    .andExpect(status().isUnauthorized())
                    .andReturn().getResponse().getContentAsString();

            // Everything but the per-request traceId and timestamp must match.
            assertThat(json(wrongPassword).get("code").asText())
                    .isEqualTo(json(unknownEmail).get("code").asText())
                    .isEqualTo("INVALID_CREDENTIALS");
            assertThat(json(wrongPassword).get("detail").asText())
                    .isEqualTo(json(unknownEmail).get("detail").asText());
        }

        @Test
        @DisplayName("a disabled account cannot log in")
        void disabledAccount() throws Exception {
            var user = createCustomer("priya@example.com");
            transactionTemplate.executeWithoutResult(status -> entityManager
                    .createNativeQuery("UPDATE users SET enabled = FALSE WHERE id = :id")
                    .setParameter("id", user.getId())
                    .executeUpdate());

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"priya@example.com","password":"%s"}
                                    """.formatted(TEST_PASSWORD)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
        }
    }

    @Nested
    @DisplayName("refresh")
    class Refresh {

        private String refreshTokenFor(String email) throws Exception {
            String body = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"%s","password":"%s"}
                                    """.formatted(email, TEST_PASSWORD)))
                    .andReturn().getResponse().getContentAsString();
            return json(body).get("refreshToken").asText();
        }

        @Test
        @DisplayName("exchanges for a new pair and rotates the refresh token")
        void rotates() throws Exception {
            createCustomer("priya@example.com");
            String original = refreshTokenFor("priya@example.com");

            String body = mockMvc.perform(post("/api/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"refreshToken":"%s"}
                                    """.formatted(original)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            assertThat(json(body).get("refreshToken").asText()).isNotEqualTo(original);
        }

        /**
         * If a token is stolen and used, the legitimate client's next refresh
         * presents one that is already revoked and fails — so the theft surfaces
         * instead of going unnoticed for a week.
         */
        @Test
        @DisplayName("a rotated token cannot be reused")
        void rotatedTokenIsDead() throws Exception {
            createCustomer("priya@example.com");
            String original = refreshTokenFor("priya@example.com");

            mockMvc.perform(post("/api/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"refreshToken":"%s"}""".formatted(original)))
                    .andExpect(status().isOk());

            mockMvc.perform(post("/api/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"refreshToken":"%s"}""".formatted(original)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));
        }

        @Test
        @DisplayName("only the hash is stored — the raw token is nowhere in the database")
        void onlyTheHashIsStored() throws Exception {
            createCustomer("priya@example.com");
            String raw = refreshTokenFor("priya@example.com");

            Long matches = transactionTemplate.execute(status -> ((Number) entityManager
                    .createNativeQuery("SELECT COUNT(*) FROM refresh_tokens WHERE token_hash = :raw")
                    .setParameter("raw", raw)
                    .getSingleResult()).longValue());

            assertThat(matches).isZero();
            assertThat(countRows("refresh_tokens")).isEqualTo(1);
        }

        @Test
        @DisplayName("an unknown token is refused")
        void unknownToken() throws Exception {
            mockMvc.perform(post("/api/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"refreshToken":"never-issued-anywhere"}"""))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));
        }
    }

    @Nested
    @DisplayName("logout")
    class Logout {

        @Test
        @DisplayName("revokes the refresh token")
        void revokes() throws Exception {
            createCustomer("priya@example.com");
            String body = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"priya@example.com","password":"%s"}
                                    """.formatted(TEST_PASSWORD)))
                    .andReturn().getResponse().getContentAsString();
            String refresh = json(body).get("refreshToken").asText();

            mockMvc.perform(post("/api/v1/auth/logout")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"refreshToken":"%s"}""".formatted(refresh)))
                    .andExpect(status().isNoContent());

            mockMvc.perform(post("/api/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"refreshToken":"%s"}""".formatted(refresh)))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("an unknown token still returns 204 — a 404 would confirm what exists")
        void unknownTokenIsSilent() throws Exception {
            mockMvc.perform(post("/api/v1/auth/logout")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"refreshToken":"never-issued-anywhere"}"""))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("logout-all requires authentication and revokes every token")
        void logoutEverywhere() throws Exception {
            String token = customerToken("priya@example.com");
            tokenFor("priya@example.com");   // a second session

            assertThat(countRows("refresh_tokens")).isEqualTo(2);

            mockMvc.perform(post("/api/v1/auth/logout-all")
                            .header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(status().isNoContent());

            Long live = transactionTemplate.execute(status -> ((Number) entityManager
                    .createNativeQuery("SELECT COUNT(*) FROM refresh_tokens WHERE revoked_at IS NULL")
                    .getSingleResult()).longValue());
            assertThat(live).isZero();
        }
    }

    @Test
    @DisplayName("every response carries a trace id the caller can quote")
    void traceIdHeader() throws Exception {
        mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"));
    }
}
