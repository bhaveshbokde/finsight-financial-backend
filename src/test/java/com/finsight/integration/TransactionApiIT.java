package com.finsight.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.finsight.auth.dto.AuthResponse;
import com.finsight.transaction.domain.TransactionType;
import com.finsight.transaction.dto.TransactionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Transaction CRUD, validation, filtering and pagination over the real stack. */
@Transactional
class TransactionApiIT extends AbstractIntegrationTest {

    private AuthResponse user;
    private Long groceriesId;
    private Long salaryId;

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser("txn");

        String categories = mockMvc.perform(get("/api/v1/categories")
                        .header("Authorization", bearer(user)))
                .andReturn().getResponse().getContentAsString();

        for (JsonNode node : objectMapper.readTree(categories)) {
            if ("Groceries".equals(node.get("name").asText())) {
                groceriesId = node.get("id").asLong();
            }
            if ("Salary".equals(node.get("name").asText())) {
                salaryId = node.get("id").asLong();
            }
        }
    }

    private TransactionRequest request(TransactionType type, String amount, LocalDate date,
                                       String description, Long categoryId) {
        return new TransactionRequest(type, new BigDecimal(amount), date,
                description, "Test Merchant", null, categoryId);
    }

    private Long create(TransactionRequest request) throws Exception {
        String body = mockMvc.perform(post("/api/v1/transactions")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    // ------------------------------------------------------------------- CRUD --

    @Nested
    @DisplayName("CRUD")
    class Crud {

        @Test
        @DisplayName("creates with 201 and a Location header")
        void create201() throws Exception {
            mockMvc.perform(post("/api/v1/transactions")
                            .header("Authorization", bearer(user))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request(
                                    TransactionType.EXPENSE, "1249.50",
                                    LocalDate.of(2026, 3, 14), "Weekly groceries", groceriesId))))
                    .andExpect(status().isCreated())
                    .andExpect(header().exists("Location"))
                    .andExpect(jsonPath("$.id").isNumber())
                    .andExpect(jsonPath("$.amount").value(1249.50))
                    .andExpect(jsonPath("$.categoryName").value("Groceries"))
                    .andExpect(jsonPath("$.source").value("MANUAL"));
        }

        @Test
        @DisplayName("stores money exactly, with no floating-point drift")
        void exactDecimalRoundTrip() throws Exception {
            // 0.1 + 0.2 territory. If any layer used double this would come back
            // as 1249.5000000000001 or 1249.49.
            Long id = create(request(TransactionType.EXPENSE, "1249.55",
                    LocalDate.of(2026, 3, 14), "Precision check", null));

            mockMvc.perform(get("/api/v1/transactions/" + id)
                            .header("Authorization", bearer(user)))
                    .andExpect(jsonPath("$.amount").value(1249.55));
        }

        @Test
        @DisplayName("updates in place and returns the new state")
        void update() throws Exception {
            Long id = create(request(TransactionType.EXPENSE, "100.00",
                    LocalDate.of(2026, 3, 14), "Original", groceriesId));

            mockMvc.perform(put("/api/v1/transactions/" + id)
                            .header("Authorization", bearer(user))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request(
                                    TransactionType.EXPENSE, "250.00",
                                    LocalDate.of(2026, 3, 15), "Edited", groceriesId))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.amount").value(250.00))
                    .andExpect(jsonPath("$.description").value("Edited"))
                    .andExpect(jsonPath("$.txnDate").value("2026-03-15"));
        }

        @Test
        @DisplayName("deletes with 204 and the row is then gone")
        void delete204() throws Exception {
            Long id = create(request(TransactionType.EXPENSE, "100.00",
                    LocalDate.of(2026, 3, 14), "To delete", null));

            mockMvc.perform(delete("/api/v1/transactions/" + id)
                            .header("Authorization", bearer(user)))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get("/api/v1/transactions/" + id)
                            .header("Authorization", bearer(user)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("an unknown id is 404")
        void unknownIs404() throws Exception {
            mockMvc.perform(get("/api/v1/transactions/999999")
                            .header("Authorization", bearer(user)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        }
    }

    // ------------------------------------------------------------- validation --

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("rejects a negative amount")
        void rejectsNegative() throws Exception {
            mockMvc.perform(post("/api/v1/transactions")
                            .header("Authorization", bearer(user))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request(
                                    TransactionType.EXPENSE, "-100.00",
                                    LocalDate.of(2026, 3, 14), "Negative", null))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[?(@.field == 'amount')]").exists());
        }

        @Test
        @DisplayName("rejects a zero amount")
        void rejectsZero() throws Exception {
            mockMvc.perform(post("/api/v1/transactions")
                            .header("Authorization", bearer(user))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request(
                                    TransactionType.EXPENSE, "0.00",
                                    LocalDate.of(2026, 3, 14), "Zero", null))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("rejects more than four decimal places")
        void rejectsExcessPrecision() throws Exception {
            // Mirrors NUMERIC(19,4). Without this the value would be silently
            // rounded on the way in and the client would never know.
            mockMvc.perform(post("/api/v1/transactions")
                            .header("Authorization", bearer(user))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request(
                                    TransactionType.EXPENSE, "100.123456",
                                    LocalDate.of(2026, 3, 14), "Too precise", null))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("rejects a blank description")
        void rejectsBlankDescription() throws Exception {
            mockMvc.perform(post("/api/v1/transactions")
                            .header("Authorization", bearer(user))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request(
                                    TransactionType.EXPENSE, "100.00",
                                    LocalDate.of(2026, 3, 14), "   ", null))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("rejects an EXPENSE filed under an INCOME category with 422")
        void rejectsCategoryTypeMismatch() throws Exception {
            // 422, not 400: the body parsed and bound correctly, but a domain rule
            // forbids the instruction. Clients routinely need that distinction.
            mockMvc.perform(post("/api/v1/transactions")
                            .header("Authorization", bearer(user))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request(
                                    TransactionType.EXPENSE, "100.00",
                                    LocalDate.of(2026, 3, 14), "Mismatch", salaryId))))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
        }

        @Test
        @DisplayName("rejects a nonexistent category with 404")
        void rejectsUnknownCategory() throws Exception {
            mockMvc.perform(post("/api/v1/transactions")
                            .header("Authorization", bearer(user))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request(
                                    TransactionType.EXPENSE, "100.00",
                                    LocalDate.of(2026, 3, 14), "Unknown category", 999999L))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("allows an uncategorised transaction")
        void allowsNullCategory() throws Exception {
            mockMvc.perform(post("/api/v1/transactions")
                            .header("Authorization", bearer(user))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request(
                                    TransactionType.EXPENSE, "100.00",
                                    LocalDate.of(2026, 3, 14), "No category", null))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.categoryId").doesNotExist());
        }
    }

    // ---------------------------------------------------- filtering and paging --

    @Nested
    @DisplayName("filtering and pagination")
    class FilteringAndPaging {

        @BeforeEach
        void seed() throws Exception {
            create(request(TransactionType.INCOME, "95000.00",
                    LocalDate.of(2026, 3, 1), "March salary", salaryId));
            create(request(TransactionType.EXPENSE, "5000.00",
                    LocalDate.of(2026, 3, 10), "Big grocery run", groceriesId));
            create(request(TransactionType.EXPENSE, "250.00",
                    LocalDate.of(2026, 3, 15), "Small purchase", groceriesId));
            create(request(TransactionType.EXPENSE, "1200.00",
                    LocalDate.of(2026, 4, 5), "April spend", null));
        }

        @Test
        @DisplayName("filters by date range, inclusively")
        void filtersByDate() throws Exception {
            mockMvc.perform(get("/api/v1/transactions")
                            .param("from", "2026-03-01").param("to", "2026-03-31")
                            .header("Authorization", bearer(user)))
                    .andExpect(jsonPath("$.totalElements").value(3));
        }

        @Test
        @DisplayName("filters by type")
        void filtersByType() throws Exception {
            mockMvc.perform(get("/api/v1/transactions")
                            .param("type", "INCOME")
                            .header("Authorization", bearer(user)))
                    .andExpect(jsonPath("$.totalElements").value(1));
        }

        @Test
        @DisplayName("filters by category")
        void filtersByCategory() throws Exception {
            mockMvc.perform(get("/api/v1/transactions")
                            .param("categoryIds", groceriesId.toString())
                            .header("Authorization", bearer(user)))
                    .andExpect(jsonPath("$.totalElements").value(2));
        }

        @Test
        @DisplayName("filters by amount range")
        void filtersByAmount() throws Exception {
            mockMvc.perform(get("/api/v1/transactions")
                            .param("minAmount", "1000").param("maxAmount", "10000")
                            .header("Authorization", bearer(user)))
                    .andExpect(jsonPath("$.totalElements").value(2));
        }

        @Test
        @DisplayName("filters by a case-insensitive substring search")
        void filtersBySearch() throws Exception {
            mockMvc.perform(get("/api/v1/transactions")
                            .param("search", "GROCERY")
                            .header("Authorization", bearer(user)))
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].description").value("Big grocery run"));
        }

        @Test
        @DisplayName("finds uncategorised transactions")
        void filtersUncategorised() throws Exception {
            mockMvc.perform(get("/api/v1/transactions")
                            .param("uncategorized", "true")
                            .header("Authorization", bearer(user)))
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].description").value("April spend"));
        }

        @Test
        @DisplayName("combines filters with AND semantics")
        void combinesFilters() throws Exception {
            mockMvc.perform(get("/api/v1/transactions")
                            .param("from", "2026-03-01").param("to", "2026-03-31")
                            .param("type", "EXPENSE")
                            .param("minAmount", "1000")
                            .header("Authorization", bearer(user)))
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].description").value("Big grocery run"));
        }

        @Test
        @DisplayName("defaults to newest first")
        void defaultSortIsNewestFirst() throws Exception {
            mockMvc.perform(get("/api/v1/transactions")
                            .header("Authorization", bearer(user)))
                    .andExpect(jsonPath("$.content[0].txnDate").value("2026-04-05"));
        }

        @Test
        @DisplayName("honours an explicit sort")
        void explicitSort() throws Exception {
            mockMvc.perform(get("/api/v1/transactions")
                            .param("sort", "amount,desc")
                            .header("Authorization", bearer(user)))
                    .andExpect(jsonPath("$.content[0].amount").value(95000.00));
        }

        @Test
        @DisplayName("pages without repeating or skipping rows")
        void pagesCleanly() throws Exception {
            String page0 = mockMvc.perform(get("/api/v1/transactions")
                            .param("page", "0").param("size", "2")
                            .header("Authorization", bearer(user)))
                    .andExpect(jsonPath("$.totalElements").value(4))
                    .andExpect(jsonPath("$.totalPages").value(2))
                    .andExpect(jsonPath("$.first").value(true))
                    .andReturn().getResponse().getContentAsString();

            String page1 = mockMvc.perform(get("/api/v1/transactions")
                            .param("page", "1").param("size", "2")
                            .header("Authorization", bearer(user)))
                    .andExpect(jsonPath("$.last").value(true))
                    .andReturn().getResponse().getContentAsString();

            var idsPage0 = objectMapper.readTree(page0).get("content").findValuesAsText("id");
            var idsPage1 = objectMapper.readTree(page1).get("content").findValuesAsText("id");

            // The default sort includes id as a tiebreaker precisely so this holds
            // even when several rows share a date.
            assertThat(idsPage0).doesNotContainAnyElementsOf(idsPage1);
            assertThat(idsPage0).hasSize(2);
            assertThat(idsPage1).hasSize(2);
        }

        @Test
        @DisplayName("caps an absurd page size instead of trying to serve it")
        void capsPageSize() throws Exception {
            mockMvc.perform(get("/api/v1/transactions")
                            .param("size", "1000000")
                            .header("Authorization", bearer(user)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.size").value(200));
        }

        @Test
        @DisplayName("rejects contradictory filters with 422")
        void rejectsContradictoryFilters() throws Exception {
            mockMvc.perform(get("/api/v1/transactions")
                            .param("uncategorized", "true")
                            .param("categoryIds", groceriesId.toString())
                            .header("Authorization", bearer(user)))
                    .andExpect(status().isUnprocessableEntity());
        }
    }
}
