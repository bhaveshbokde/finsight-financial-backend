package com.finsight.integration;

import com.finsight.auth.dto.AuthResponse;
import com.finsight.budget.dto.BudgetRequest;
import com.finsight.category.domain.CategoryType;
import com.finsight.category.dto.CategoryRequest;
import com.finsight.transaction.domain.TransactionType;
import com.finsight.transaction.dto.TransactionRequest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The security property this application cares about most: one user can never
 * read or modify another user's financial data.
 *
 * <p>Two accounts are created, Alice populates hers, and every endpoint is then
 * attacked with Bob's valid token and Alice's resource ids. Bob is fully
 * authenticated throughout — this is not an authentication test, it is an
 * <em>authorization</em> test, and those are the ones that get missed.
 *
 * <p>Every cross-user attempt must return <strong>404</strong>, never 403.
 * A 403 would confirm that the id exists, letting an attacker enumerate the
 * table. Ownership is part of the WHERE clause, so "someone else's" and
 * "nonexistent" are genuinely the same case.
 */
@Transactional
class UserDataIsolationIT extends AbstractIntegrationTest {

    private AuthResponse alice;
    private AuthResponse bob;

    private Long aliceTransactionId;
    private Long aliceCategoryId;
    private Long aliceBudgetId;
    private Long aliceImportId;

    @BeforeEach
    void createTwoUsersAndPopulateAlice() throws Exception {
        alice = registerUser("alice");
        bob = registerUser("bob");

        // --- Alice's category ---
        String categoryBody = mockMvc.perform(post("/api/v1/categories")
                        .header("Authorization", bearer(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CategoryRequest("Alice Only", CategoryType.EXPENSE, "#112233"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        aliceCategoryId = objectMapper.readTree(categoryBody).get("id").asLong();

        // --- Alice's transaction ---
        String txnBody = mockMvc.perform(post("/api/v1/transactions")
                        .header("Authorization", bearer(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransactionRequest(
                                TransactionType.EXPENSE, new BigDecimal("4200.00"),
                                LocalDate.of(2026, 3, 14), "Alice private spend",
                                "Secret Merchant", "Alice's private note", aliceCategoryId))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        aliceTransactionId = objectMapper.readTree(txnBody).get("id").asLong();

        // --- Alice's budget ---
        String budgetBody = mockMvc.perform(post("/api/v1/budgets")
                        .header("Authorization", bearer(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new BudgetRequest("2026-03", new BigDecimal("50000.00"), null, (short) 80))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        aliceBudgetId = objectMapper.readTree(budgetBody).get("id").asLong();

        // --- Alice's import ---
        String csv = """
                date,type,amount,description,merchant,category
                2026-03-01,EXPENSE,100.00,Alice import row,Shop,Groceries
                2026-03-02,EXPENSE,abc,Bad row so there is an error to read,Shop,Groceries
                """;
        String importBody = mockMvc.perform(multipart("/api/v1/imports")
                        .file(new MockMultipartFile("file", "alice.csv", "text/csv",
                                csv.getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", bearer(alice)))
                .andReturn().getResponse().getContentAsString();
        aliceImportId = objectMapper.readTree(importBody).get("id").asLong();
    }

    // ----------------------------------------------------------- transactions --

    @Test
    @DisplayName("Bob cannot read Alice's transaction")
    void cannotReadForeignTransaction() throws Exception {
        mockMvc.perform(get("/api/v1/transactions/" + aliceTransactionId)
                        .header("Authorization", bearer(bob)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("Bob cannot update Alice's transaction")
    void cannotUpdateForeignTransaction() throws Exception {
        mockMvc.perform(put("/api/v1/transactions/" + aliceTransactionId)
                        .header("Authorization", bearer(bob))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransactionRequest(
                                TransactionType.EXPENSE, new BigDecimal("1.00"),
                                LocalDate.of(2026, 3, 14), "Hijacked", null, null, null))))
                .andExpect(status().isNotFound());

        // And Alice's row is untouched.
        mockMvc.perform(get("/api/v1/transactions/" + aliceTransactionId)
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(4200.00))
                .andExpect(jsonPath("$.description").value("Alice private spend"));
    }

    @Test
    @DisplayName("Bob cannot delete Alice's transaction")
    void cannotDeleteForeignTransaction() throws Exception {
        mockMvc.perform(delete("/api/v1/transactions/" + aliceTransactionId)
                        .header("Authorization", bearer(bob)))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/transactions/" + aliceTransactionId)
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Bob's transaction list never contains Alice's rows")
    void listIsScopedToCaller() throws Exception {
        String body = mockMvc.perform(get("/api/v1/transactions?size=200")
                        .header("Authorization", bearer(bob)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("Alice private spend");
        assertThat(body).doesNotContain("Secret Merchant");
        assertThat(objectMapper.readTree(body).get("totalElements").asInt()).isZero();
    }

    @Test
    @DisplayName("a filter cannot be used to reach another user's rows")
    void filtersCannotEscapeTheOwnerPredicate() throws Exception {
        // The ownership predicate is applied unconditionally and first, so no
        // combination of user-supplied filters can widen the result set.
        // The range stays inside DateRanges.MAX_RANGE_DAYS (3660). A wider span is
        // rejected with 400 by design — that guard is covered by its own test in
        // DateRangesTest, and exercising it here would mask what this test is for.
        String body = mockMvc.perform(get("/api/v1/transactions")
                        .param("search", "Alice private spend")
                        .param("from", "2020-01-01")
                        .param("to", "2029-12-31")
                        .param("minAmount", "0")
                        .header("Authorization", bearer(bob)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(body).get("totalElements").asInt()).isZero();
    }

    @Test
    @DisplayName("a SQL-injection attempt in a filter is treated as a literal string")
    void searchTermIsNotInterpretedAsSql() throws Exception {
        // The Criteria API binds this as a parameter; it can never alter the
        // statement. A 200 with zero results is exactly the right outcome.
        mockMvc.perform(get("/api/v1/transactions")
                        .param("search", "' OR 1=1 --")
                        .header("Authorization", bearer(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(get("/api/v1/transactions")
                        .param("search", "'; DROP TABLE transactions; --")
                        .header("Authorization", bearer(bob)))
                .andExpect(status().isOk());

        // The table is still there.
        mockMvc.perform(get("/api/v1/transactions/" + aliceTransactionId)
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------- categories --

    @Test
    @DisplayName("Bob cannot read Alice's category")
    void cannotReadForeignCategory() throws Exception {
        mockMvc.perform(get("/api/v1/categories/" + aliceCategoryId)
                        .header("Authorization", bearer(bob)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Bob cannot update Alice's category")
    void cannotUpdateForeignCategory() throws Exception {
        mockMvc.perform(put("/api/v1/categories/" + aliceCategoryId)
                        .header("Authorization", bearer(bob))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CategoryRequest("Hijacked", CategoryType.EXPENSE, null))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Bob's category list contains the system set but none of Alice's")
    void categoryListIsScoped() throws Exception {
        String body = mockMvc.perform(get("/api/v1/categories")
                        .header("Authorization", bearer(bob)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("Alice Only");
        assertThat(body).contains("Groceries");   // shared system category
    }

    @Test
    @DisplayName("Bob cannot file his transaction under Alice's category")
    void cannotBorrowForeignCategory() throws Exception {
        // Without the ownership check in requireUsable, this would create a
        // cross-user reference and leak Alice's category name into Bob's reports.
        mockMvc.perform(post("/api/v1/transactions")
                        .header("Authorization", bearer(bob))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransactionRequest(
                                TransactionType.EXPENSE, new BigDecimal("10.00"),
                                LocalDate.of(2026, 3, 14), "Borrowed category",
                                null, null, aliceCategoryId))))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- budgets --

    @Test
    @DisplayName("Bob cannot read Alice's budget")
    void cannotReadForeignBudget() throws Exception {
        mockMvc.perform(get("/api/v1/budgets/" + aliceBudgetId)
                        .header("Authorization", bearer(bob)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Bob cannot delete Alice's budget")
    void cannotDeleteForeignBudget() throws Exception {
        mockMvc.perform(delete("/api/v1/budgets/" + aliceBudgetId)
                        .header("Authorization", bearer(bob)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Bob's budget list for the same month is empty")
    void budgetListIsScoped() throws Exception {
        mockMvc.perform(get("/api/v1/budgets?month=2026-03")
                        .header("Authorization", bearer(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ---------------------------------------------------------------- imports --

    @Test
    @DisplayName("Bob cannot read the status of Alice's import")
    void cannotReadForeignImport() throws Exception {
        mockMvc.perform(get("/api/v1/imports/" + aliceImportId)
                        .header("Authorization", bearer(bob)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Bob cannot read the row errors of Alice's import")
    void cannotReadForeignImportErrors() throws Exception {
        // The errors endpoint loads the job scoped by user first, so there is no
        // path that reaches the error rows without owning the job.
        mockMvc.perform(get("/api/v1/imports/" + aliceImportId + "/errors")
                        .header("Authorization", bearer(bob)))
                .andExpect(status().isNotFound());

        // Alice can read them.
        mockMvc.perform(get("/api/v1/imports/" + aliceImportId + "/errors")
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Bob's import list is empty")
    void importListIsScoped() throws Exception {
        mockMvc.perform(get("/api/v1/imports").header("Authorization", bearer(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    // ---------------------------------------------------------------- reports --

    @Test
    @DisplayName("Bob's reports contain none of Alice's money")
    void reportsAreScoped() throws Exception {
        String summary = mockMvc.perform(get("/api/v1/reports/summary")
                        .param("from", "2026-03-01").param("to", "2026-03-31")
                        .header("Authorization", bearer(bob)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode node = objectMapper.readTree(summary);
        assertThat(node.get("totalExpense").asDouble()).isZero();
        assertThat(node.get("totalIncome").asDouble()).isZero();
        assertThat(node.get("transactionCount").asInt()).isZero();

        // Alice's own summary does contain it, proving the data exists and the
        // zero above is genuine isolation rather than an empty database.
        mockMvc.perform(get("/api/v1/reports/summary")
                        .param("from", "2026-03-01").param("to", "2026-03-31")
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalExpense").value(4300.00));   // 4200 manual + 100 imported
    }

    @Test
    @DisplayName("Bob's category breakdown does not name Alice's categories")
    void categoryBreakdownIsScoped() throws Exception {
        String body = mockMvc.perform(get("/api/v1/reports/categories")
                        .param("from", "2026-03-01").param("to", "2026-03-31")
                        .header("Authorization", bearer(bob)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("Alice Only");
        assertThat(objectMapper.readTree(body).get("grandTotal").asDouble()).isZero();
    }
}
