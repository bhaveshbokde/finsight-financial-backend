package com.finsight.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finsight.auth.dto.AuthResponse;
import com.finsight.auth.dto.RegisterRequest;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Base class for integration tests that exercise the real application against a
 * real PostgreSQL.
 *
 * <h2>What is real here</h2>
 * Everything below the HTTP layer: Flyway runs the actual migrations, Hibernate
 * validates its mappings against the resulting schema, and PostgreSQL enforces
 * every constraint. Nothing is mocked. A test that passes here has proved the
 * query, the mapping and the constraint all agree — which a mocked repository
 * cannot show.
 *
 * <h2>Why MockMvc rather than a live port</h2>
 * {@code MockMvc} runs the full servlet stack — filters, security, argument
 * resolvers, the exception handler, message converters — in-process. It is
 * therefore a genuine test of the web layer, while avoiding a real socket and
 * keeping each test on one thread so {@code @Transactional} rollback works.
 *
 * <h2>Isolation</h2>
 * Subclasses annotate themselves {@code @Transactional} so each test rolls back.
 * Where that is not possible — anything that must observe a committed state —
 * the test cleans up after itself explicitly.
 *
 * <p>Every test account uses a unique email, so a leaked row from one test can
 * never make another fail mysteriously.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("integration")
public abstract class AbstractIntegrationTest {

    /** Shared across the JVM so parallel classes cannot generate the same address. */
    private static final AtomicInteger ACCOUNT_SEQUENCE = new AtomicInteger();

    protected static final String TEST_PASSWORD = "IntegrationTest1";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    /** A fresh, unique email for each account a test creates. */
    protected String uniqueEmail(String label) {
        return "%s.%d.%d@example.test".formatted(
                label, System.nanoTime(), ACCOUNT_SEQUENCE.incrementAndGet());
    }

    /**
     * Registers a new account and returns its tokens and identity.
     *
     * <p>Goes through the real HTTP endpoint rather than inserting a row directly,
     * so every test starts from a user created exactly the way a real one would be —
     * including the BCrypt hash and the seeded defaults.
     */
    protected AuthResponse registerUser(String label) throws Exception {
        RegisterRequest request = new RegisterRequest(
                uniqueEmail(label), TEST_PASSWORD, "Test User " + label);

        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        return objectMapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class);
    }

    /** The {@code Authorization} header value for a registered account. */
    protected String bearer(AuthResponse auth) {
        return "Bearer " + auth.accessToken();
    }

    /** Reads a JSON response body into the given type. */
    protected <T> T readBody(MvcResult result, Class<T> type) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(), type);
    }
}
