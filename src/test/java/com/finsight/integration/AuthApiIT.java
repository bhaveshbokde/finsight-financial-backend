package com.finsight.integration;

import com.finsight.auth.dto.AuthResponse;
import com.finsight.auth.dto.LoginRequest;
import com.finsight.auth.dto.RefreshRequest;
import com.finsight.auth.dto.RegisterRequest;
import com.finsight.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Registration, login, token refresh and the shape of authentication failures. */
@Transactional
class AuthApiIT extends AbstractIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // ------------------------------------------------------------ registration --

    @Nested
    @DisplayName("registration")
    class Registration {

        @Test
        @DisplayName("creates the account and returns 201 with tokens")
        void registersSuccessfully() throws Exception {
            RegisterRequest request = new RegisterRequest(
                    uniqueEmail("new"), TEST_PASSWORD, "Asha Iyer");

            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.accessToken").isNotEmpty())
                    .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                    .andExpect(jsonPath("$.tokenType").value("Bearer"))
                    .andExpect(jsonPath("$.userId").isNumber())
                    .andExpect(jsonPath("$.role").value("ROLE_USER"))
                    .andExpect(jsonPath("$.expiresInSeconds").value(1800));
        }

        @Test
        @DisplayName("stores a BCrypt hash, never the password")
        void passwordIsHashed() throws Exception {
            String email = uniqueEmail("hash");
            RegisterRequest request = new RegisterRequest(email, TEST_PASSWORD, "Hash Test");

            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());

            var user = userRepository.findByEmailIgnoreCase(email).orElseThrow();

            assertThat(user.getPasswordHash()).isNotEqualTo(TEST_PASSWORD);
            // $2a$ is the BCrypt marker; the cost factor follows.
            assertThat(user.getPasswordHash()).startsWith("$2a$10$");
            assertThat(passwordEncoder.matches(TEST_PASSWORD, user.getPasswordHash())).isTrue();
        }

        @Test
        @DisplayName("normalises the email to lower case")
        void normalisesEmail() throws Exception {
            String mixedCase = "Mixed.Case." + System.nanoTime() + "@Example.TEST";

            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new RegisterRequest(mixedCase, TEST_PASSWORD, "Mixed Case"))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.email").value(mixedCase.toLowerCase()));
        }

        @Test
        @DisplayName("rejects a duplicate email with 409, case-insensitively")
        void rejectsDuplicateEmail() throws Exception {
            String email = uniqueEmail("dup");

            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new RegisterRequest(email, TEST_PASSWORD, "First"))))
                    .andExpect(status().isCreated());

            // Upper-cased: uq_users_email_lower must still catch it.
            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new RegisterRequest(email.toUpperCase(), TEST_PASSWORD, "Second"))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
        }

        @Test
        @DisplayName("rejects a weak password and names the failing field")
        void rejectsWeakPassword() throws Exception {
            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new RegisterRequest(uniqueEmail("weak"), "short", "Weak"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.fieldErrors[?(@.field == 'password')]").exists());
        }

        @Test
        @DisplayName("never echoes the rejected password back to the caller")
        void doesNotEchoPassword() throws Exception {
            // Deliberately fails the complexity rule, so the request is rejected.
            String submittedValue = "weakpassphrase";

            String body = mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new RegisterRequest(uniqueEmail("echo"), submittedValue, "Echo"))))
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();

            // The validation error must name the field without repeating its value —
            // error bodies end up in logs, bug reports and screenshots.
            assertThat(body).doesNotContain(submittedValue);
            assertThat(body).contains("password");
        }

        @Test
        @DisplayName("rejects a malformed email")
        void rejectsMalformedEmail() throws Exception {
            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new RegisterRequest("not-an-email", TEST_PASSWORD, "Bad"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[?(@.field == 'email')]").exists());
        }

        @Test
        @DisplayName("rejects an unparseable body with 400, not 500")
        void rejectsMalformedJson() throws Exception {
            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\": \"a@b.test\", "))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        }
    }

    // ------------------------------------------------------------------- login --

    @Nested
    @DisplayName("login")
    class Login {

        @Test
        @DisplayName("returns 200 with tokens for correct credentials")
        void loginSucceeds() throws Exception {
            AuthResponse registered = registerUser("login");

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new LoginRequest(registered.email(), TEST_PASSWORD))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").isNotEmpty())
                    .andExpect(jsonPath("$.userId").value(registered.userId()));
        }

        @Test
        @DisplayName("accepts the email in any case")
        void loginIsCaseInsensitive() throws Exception {
            AuthResponse registered = registerUser("case");

            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new LoginRequest(registered.email().toUpperCase(), TEST_PASSWORD))))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("gives an identical 401 for a wrong password and an unknown email")
        void doesNotRevealWhichHalfWasWrong() throws Exception {
            AuthResponse registered = registerUser("enum");

            String wrongPassword = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new LoginRequest(registered.email(), "WrongPassword1"))))
                    .andExpect(status().isUnauthorized())
                    .andReturn().getResponse().getContentAsString();

            String unknownEmail = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new LoginRequest("nobody." + System.nanoTime() + "@example.test",
                                            TEST_PASSWORD))))
                    .andExpect(status().isUnauthorized())
                    .andReturn().getResponse().getContentAsString();

            // Identical codes and messages. Any difference turns this endpoint into
            // an account-enumeration oracle.
            assertThat(objectMapper.readTree(wrongPassword).get("code").asText())
                    .isEqualTo(objectMapper.readTree(unknownEmail).get("code").asText())
                    .isEqualTo("INVALID_CREDENTIALS");
            assertThat(objectMapper.readTree(wrongPassword).get("message").asText())
                    .isEqualTo(objectMapper.readTree(unknownEmail).get("message").asText());
        }
    }

    // ----------------------------------------------------------------- refresh --

    @Nested
    @DisplayName("token refresh")
    class Refresh {

        @Test
        @DisplayName("exchanges a refresh token for a new pair")
        void refreshSucceeds() throws Exception {
            AuthResponse registered = registerUser("refresh");

            mockMvc.perform(post("/api/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new RefreshRequest(registered.refreshToken()))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").isNotEmpty())
                    .andExpect(jsonPath("$.userId").value(registered.userId()));
        }

        @Test
        @DisplayName("rejects an access token presented for refresh")
        void rejectsWrongTokenType() throws Exception {
            AuthResponse registered = registerUser("wrongtype");

            mockMvc.perform(post("/api/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new RefreshRequest(registered.accessToken()))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
        }

        @Test
        @DisplayName("refuses a refresh token used as a bearer credential")
        void refreshTokenIsNotABearerCredential() throws Exception {
            // Without the typ claim check, a 7-day refresh token would work as a
            // 7-day API key on every endpoint.
            AuthResponse registered = registerUser("bearer");

            mockMvc.perform(get("/api/v1/transactions")
                            .header("Authorization", "Bearer " + registered.refreshToken()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
        }
    }

    // --------------------------------------------------------- protected access --

    @Nested
    @DisplayName("protected endpoints")
    class ProtectedEndpoints {

        @Test
        @DisplayName("/auth/me returns the caller's account without the password hash")
        void meReturnsCurrentUser() throws Exception {
            AuthResponse registered = registerUser("me");

            mockMvc.perform(get("/api/v1/auth/me").header("Authorization", bearer(registered)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.email").value(registered.email()))
                    .andExpect(jsonPath("$.role").value("ROLE_USER"))
                    .andExpect(jsonPath("$.enabled").value(true))
                    .andExpect(jsonPath("$.passwordHash").doesNotExist())
                    .andExpect(jsonPath("$.password").doesNotExist());
        }

        @Test
        @DisplayName("401 with the standard error body when no token is sent")
        void rejectsMissingToken() throws Exception {
            mockMvc.perform(get("/api/v1/auth/me"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                    .andExpect(jsonPath("$.traceId").isNotEmpty())
                    .andExpect(header().exists("WWW-Authenticate"));
        }

        @Test
        @DisplayName("401 for a forged token")
        void rejectsForgedToken() throws Exception {
            mockMvc.perform(get("/api/v1/auth/me")
                            .header("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.forged"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
        }

        @Test
        @DisplayName("401 for an Authorization header that is not a Bearer header")
        void rejectsNonBearerScheme() throws Exception {
            mockMvc.perform(get("/api/v1/auth/me")
                            .header("Authorization", "Basic YWRtaW46YWRtaW4="))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("every error response carries a trace id and no stack trace")
        void errorBodiesAreSafe() throws Exception {
            String body = mockMvc.perform(get("/api/v1/auth/me"))
                    .andExpect(status().isUnauthorized())
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).contains("traceId");
            assertThat(body).doesNotContain("Exception");
            assertThat(body).doesNotContain("at com.finsight");
            assertThat(body).doesNotContain("org.springframework");
        }
    }
}
