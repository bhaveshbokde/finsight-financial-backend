package com.finsight.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.finsight.auth.dto.AuthResponse;
import com.finsight.budget.dto.BudgetRequest;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Report correctness against a real database.
 *
 * <p>The fixture below is deliberately arranged so that every expected figure can
 * be checked by hand:
 * <pre>
 *   2026-02-15  INCOME   10 000.00
 *   2026-02-20  EXPENSE   2 000.00   Groceries
 *   2026-03-01  INCOME   95 000.00   Salary
 *   2026-03-10  EXPENSE   5 000.00   Groceries
 *   2026-03-20  EXPENSE   3 000.00   Dining
 *   2026-03-31  EXPENSE   2 000.00   (uncategorised)   <- last day, boundary case
 * </pre>
 * March: income 95 000, expense 10 000, net 85 000.
 */
@Transactional
class ReportApiIT extends AbstractIntegrationTest {

    private AuthResponse user;
    private Long groceriesId;
    private Long diningId;
    private Long salaryId;

    @BeforeEach
    void seedTransactions() throws Exception {
        user = registerUser("report");

        String categories = mockMvc.perform(get("/api/v1/categories")
                        .header("Authorization", bearer(user)))
                .andReturn().getResponse().getContentAsString();

        groceriesId = idOfCategory(categories, "Groceries");
        diningId = idOfCategory(categories, "Dining");
        salaryId = idOfCategory(categories, "Salary");

        createTransaction(TransactionType.INCOME, "10000.00", LocalDate.of(2026, 2, 15),
                "February income", salaryId);
        createTransaction(TransactionType.EXPENSE, "2000.00", LocalDate.of(2026, 2, 20),
                "February groceries", groceriesId);

        createTransaction(TransactionType.INCOME, "95000.00", LocalDate.of(2026, 3, 1),
                "March salary", salaryId);
        createTransaction(TransactionType.EXPENSE, "5000.00", LocalDate.of(2026, 3, 10),
                "March groceries", groceriesId);
        createTransaction(TransactionType.EXPENSE, "3000.00", LocalDate.of(2026, 3, 20),
                "March dining", diningId);
        // On the last day of the month: proves the upper bound is inclusive.
        createTransaction(TransactionType.EXPENSE, "2000.00", LocalDate.of(2026, 3, 31),
                "March uncategorised", null);
    }

    private Long idOfCategory(String categoriesJson, String name) throws Exception {
        for (var node : objectMapper.readTree(categoriesJson)) {
            if (name.equals(node.get("name").asText())) {
                return node.get("id").asLong();
            }
        }
        throw new IllegalStateException("System category '" + name + "' was not seeded");
    }

    private void createTransaction(TransactionType type, String amount, LocalDate date,
                                   String description, Long categoryId) throws Exception {
        mockMvc.perform(post("/api/v1/transactions")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransactionRequest(
                                type, new BigDecimal(amount), date, description, null, null, categoryId))))
                .andExpect(status().isCreated());
    }

    // ---------------------------------------------------------------- summary --

    @Nested
    @DisplayName("summary")
    class Summary {

        @Test
        @DisplayName("totals and net cash flow are correct for a month")
        void monthTotals() throws Exception {
            mockMvc.perform(get("/api/v1/reports/summary")
                            .param("from", "2026-03-01").param("to", "2026-03-31")
                            .header("Authorization", bearer(user)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalIncome").value(95000.00))
                    .andExpect(jsonPath("$.totalExpense").value(10000.00))
                    .andExpect(jsonPath("$.netCashFlow").value(85000.00))
                    .andExpect(jsonPath("$.transactionCount").value(4));
        }

        @Test
        @DisplayName("both bounds are inclusive")
        void boundsAreInclusive() throws Exception {
            // A single-day window on the last day of the month must find the
            // transaction dated that day. An exclusive upper bound — the classic
            // off-by-one in date-range reporting — would return zero here.
            mockMvc.perform(get("/api/v1/reports/summary")
                            .param("from", "2026-03-31").param("to", "2026-03-31")
                            .header("Authorization", bearer(user)))
                    .andExpect(jsonPath("$.totalExpense").value(2000.00))
                    .andExpect(jsonPath("$.transactionCount").value(1));

            // And the first day likewise.
            mockMvc.perform(get("/api/v1/reports/summary")
                            .param("from", "2026-03-01").param("to", "2026-03-01")
                            .header("Authorization", bearer(user)))
                    .andExpect(jsonPath("$.totalIncome").value(95000.00));
        }

        @Test
        @DisplayName("a narrower window excludes transactions outside it")
        void windowExcludesOutsideRows() throws Exception {
            mockMvc.perform(get("/api/v1/reports/summary")
                            .param("from", "2026-03-02").param("to", "2026-03-30")
                            .header("Authorization", bearer(user)))
                    .andExpect(jsonPath("$.totalIncome").value(0.00))       // 1 March excluded
                    .andExpect(jsonPath("$.totalExpense").value(8000.00))   // 31 March excluded
                    .andExpect(jsonPath("$.transactionCount").value(2));
        }

        @Test
        @DisplayName("a period with no transactions reports zeros, not nulls")
        void emptyPeriod() throws Exception {
            mockMvc.perform(get("/api/v1/reports/summary")
                            .param("from", "2019-01-01").param("to", "2019-12-31")
                            .header("Authorization", bearer(user)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalIncome").value(0.00))
                    .andExpect(jsonPath("$.totalExpense").value(0.00))
                    .andExpect(jsonPath("$.netCashFlow").value(0.00))
                    .andExpect(jsonPath("$.transactionCount").value(0))
                    .andExpect(jsonPath("$.expenseRatioPct").value(0.00));
        }

        @Test
        @DisplayName("spans several months when asked to")
        void multiMonthRange() throws Exception {
            mockMvc.perform(get("/api/v1/reports/summary")
                            .param("from", "2026-02-01").param("to", "2026-03-31")
                            .header("Authorization", bearer(user)))
                    .andExpect(jsonPath("$.totalIncome").value(105000.00))
                    .andExpect(jsonPath("$.totalExpense").value(12000.00))
                    .andExpect(jsonPath("$.netCashFlow").value(93000.00))
                    .andExpect(jsonPath("$.transactionCount").value(6));
        }

        @Test
        @DisplayName("rejects an inverted range with 400")
        void rejectsInvertedRange() throws Exception {
            mockMvc.perform(get("/api/v1/reports/summary")
                            .param("from", "2026-03-31").param("to", "2026-03-01")
                            .header("Authorization", bearer(user)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));
        }

        @Test
        @DisplayName("rejects a malformed date with 400, not 500")
        void rejectsMalformedDate() throws Exception {
            mockMvc.perform(get("/api/v1/reports/summary")
                            .param("from", "not-a-date").param("to", "2026-03-31")
                            .header("Authorization", bearer(user)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        }

        @Test
        @DisplayName("requires authentication")
        void requiresAuth() throws Exception {
            mockMvc.perform(get("/api/v1/reports/summary"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ---------------------------------------------------------------- monthly --

    @Nested
    @DisplayName("monthly series")
    class Monthly {

        @Test
        @DisplayName("returns one dense entry per requested month")
        void denseSeries() throws Exception {
            mockMvc.perform(get("/api/v1/reports/monthly")
                            .param("from", "2026-01").param("to", "2026-04")
                            .header("Authorization", bearer(user)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.months.length()").value(4))
                    .andExpect(jsonPath("$.months[0].month").value("2026-01"))
                    .andExpect(jsonPath("$.months[3].month").value("2026-04"));
        }

        @Test
        @DisplayName("months with no activity appear as zeros rather than gaps")
        void emptyMonthsAreZero() throws Exception {
            mockMvc.perform(get("/api/v1/reports/monthly")
                            .param("from", "2026-01").param("to", "2026-04")
                            .header("Authorization", bearer(user)))
                    .andExpect(jsonPath("$.months[0].totalIncome").value(0.00))
                    .andExpect(jsonPath("$.months[0].transactionCount").value(0))
                    .andExpect(jsonPath("$.months[3].totalExpense").value(0.00));
        }

        @Test
        @DisplayName("each month carries its own correct totals")
        void perMonthTotals() throws Exception {
            mockMvc.perform(get("/api/v1/reports/monthly")
                            .param("from", "2026-02").param("to", "2026-03")
                            .header("Authorization", bearer(user)))
                    .andExpect(jsonPath("$.months[0].totalIncome").value(10000.00))
                    .andExpect(jsonPath("$.months[0].totalExpense").value(2000.00))
                    .andExpect(jsonPath("$.months[0].netCashFlow").value(8000.00))
                    .andExpect(jsonPath("$.months[1].totalIncome").value(95000.00))
                    .andExpect(jsonPath("$.months[1].totalExpense").value(10000.00))
                    .andExpect(jsonPath("$.months[1].netCashFlow").value(85000.00));
        }

        @Test
        @DisplayName("averages divide by months requested, not months with data")
        void averagesUseRequestedSpan() throws Exception {
            // 105 000 income over the 4 requested months = 26 250, not 52 500.
            mockMvc.perform(get("/api/v1/reports/monthly")
                            .param("from", "2026-01").param("to", "2026-04")
                            .header("Authorization", bearer(user)))
                    .andExpect(jsonPath("$.totalIncome").value(105000.00))
                    .andExpect(jsonPath("$.averageMonthlyIncome").value(26250.00))
                    .andExpect(jsonPath("$.averageMonthlyExpense").value(3000.00));
        }

        @Test
        @DisplayName("rejects an inverted month range")
        void rejectsInvertedMonths() throws Exception {
            mockMvc.perform(get("/api/v1/reports/monthly")
                            .param("from", "2026-06").param("to", "2026-01")
                            .header("Authorization", bearer(user)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("rejects a malformed month")
        void rejectsMalformedMonth() throws Exception {
            mockMvc.perform(get("/api/v1/reports/monthly")
                            .param("from", "March").param("to", "2026-03")
                            .header("Authorization", bearer(user)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        }
    }

    // ------------------------------------------------------ category breakdown --

    @Nested
    @DisplayName("category breakdown")
    class Breakdown {

        @Test
        @DisplayName("groups spending by category with correct shares")
        void groupsByCategory() throws Exception {
            // March expenses: Groceries 5 000 (50%), Dining 3 000 (30%),
            // Uncategorized 2 000 (20%). Total 10 000.
            mockMvc.perform(get("/api/v1/reports/categories")
                            .param("from", "2026-03-01").param("to", "2026-03-31")
                            .header("Authorization", bearer(user)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.grandTotal").value(10000.00))
                    .andExpect(jsonPath("$.categories.totalElements").value(3))
                    .andExpect(jsonPath("$.categories.content[0].categoryName").value("Groceries"))
                    .andExpect(jsonPath("$.categories.content[0].total").value(5000.00))
                    .andExpect(jsonPath("$.categories.content[0].percentageOfTotal").value(50.00));
        }

        @Test
        @DisplayName("includes uncategorised spending so the breakdown sums to the total")
        void includesUncategorised() throws Exception {
            // An INNER JOIN would drop this group and the breakdown would silently
            // fail to match the summary's expense total.
            String body = mockMvc.perform(get("/api/v1/reports/categories")
                            .param("from", "2026-03-01").param("to", "2026-03-31")
                            .header("Authorization", bearer(user)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            // Asserted with Jackson rather than a JsonPath filter: the application
            // serialises with default-property-inclusion=non_null, so a null
            // categoryId is omitted from the object entirely. A JsonPath filter
            // selecting `.categoryId` would therefore return an empty result and
            // could not distinguish "absent because null" from "group missing".
            JsonNode uncategorised = null;
            for (JsonNode item : objectMapper.readTree(body).at("/categories/content")) {
                if ("Uncategorized".equals(item.path("categoryName").asText())) {
                    uncategorised = item;
                }
            }

            assertThat(uncategorised)
                    .as("the uncategorised group must survive the LEFT JOIN")
                    .isNotNull();
            assertThat(uncategorised.path("total").asDouble()).isEqualTo(2000.00);
            assertThat(uncategorised.has("categoryId"))
                    .as("a null categoryId is omitted, not serialised as null")
                    .isFalse();

            // The breakdown must add up to the period's expense total.
            double sum = 0;
            for (JsonNode item : objectMapper.readTree(body).at("/categories/content")) {
                sum += item.path("total").asDouble();
            }
            assertThat(sum).isEqualTo(objectMapper.readTree(body).path("grandTotal").asDouble());
        }

        @Test
        @DisplayName("orders categories by amount, largest first")
        void orderedByAmount() throws Exception {
            mockMvc.perform(get("/api/v1/reports/categories")
                            .param("from", "2026-03-01").param("to", "2026-03-31")
                            .header("Authorization", bearer(user)))
                    .andExpect(jsonPath("$.categories.content[0].total").value(5000.00))
                    .andExpect(jsonPath("$.categories.content[1].total").value(3000.00))
                    .andExpect(jsonPath("$.categories.content[2].total").value(2000.00));
        }

        @Test
        @DisplayName("can break down income instead of expenses")
        void incomeBreakdown() throws Exception {
            mockMvc.perform(get("/api/v1/reports/categories")
                            .param("type", "INCOME")
                            .param("from", "2026-03-01").param("to", "2026-03-31")
                            .header("Authorization", bearer(user)))
                    .andExpect(jsonPath("$.grandTotal").value(95000.00))
                    .andExpect(jsonPath("$.categories.content[0].categoryName").value("Salary"))
                    .andExpect(jsonPath("$.categories.content[0].percentageOfTotal").value(100.00));
        }

        @Test
        @DisplayName("percentages on page 2 are still shares of the whole period")
        void percentagesArePageIndependent() throws Exception {
            mockMvc.perform(get("/api/v1/reports/categories")
                            .param("from", "2026-03-01").param("to", "2026-03-31")
                            .param("page", "1").param("size", "2")
                            .header("Authorization", bearer(user)))
                    .andExpect(jsonPath("$.grandTotal").value(10000.00))       // not the page total
                    .andExpect(jsonPath("$.categories.content.length()").value(1))
                    .andExpect(jsonPath("$.categories.content[0].percentageOfTotal").value(20.00))
                    .andExpect(jsonPath("$.categories.last").value(true));
        }

        @Test
        @DisplayName("an empty period returns an empty breakdown, not an error")
        void emptyBreakdown() throws Exception {
            mockMvc.perform(get("/api/v1/reports/categories")
                            .param("from", "2019-01-01").param("to", "2019-12-31")
                            .header("Authorization", bearer(user)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.grandTotal").value(0.00))
                    .andExpect(jsonPath("$.categories.content.length()").value(0));
        }
    }

    // ----------------------------------------------------- budget utilisation --

    @Nested
    @DisplayName("budget utilisation")
    class BudgetUtilisation {

        @Test
        @DisplayName("measures spend against each configured budget")
        void measuresAgainstBudgets() throws Exception {
            // Groceries budget 10 000 against 5 000 spent = 50%, ON_TRACK.
            mockMvc.perform(post("/api/v1/budgets")
                            .header("Authorization", bearer(user))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new BudgetRequest(
                                    "2026-03", new BigDecimal("10000.00"), groceriesId, (short) 80))))
                    .andExpect(status().isCreated());

            mockMvc.perform(get("/api/v1/reports/budget-utilisation")
                            .param("month", "2026-03")
                            .header("Authorization", bearer(user)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].spent").value(5000.00))
                    .andExpect(jsonPath("$[0].remaining").value(5000.00))
                    .andExpect(jsonPath("$[0].utilisationPct").value(50.00))
                    .andExpect(jsonPath("$[0].status").value("ON_TRACK"));
        }

        @Test
        @DisplayName("an overall budget counts uncategorised spending too")
        void overallBudgetCoversEverything() throws Exception {
            // Overall budget 8 000 against 10 000 total March expenses (including
            // the 2 000 uncategorised) = EXCEEDED.
            mockMvc.perform(post("/api/v1/budgets")
                            .header("Authorization", bearer(user))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new BudgetRequest(
                                    "2026-03", new BigDecimal("8000.00"), null, (short) 80))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.spent").value(10000.00))
                    .andExpect(jsonPath("$.remaining").value(-2000.00))
                    .andExpect(jsonPath("$.status").value("EXCEEDED"))
                    .andExpect(jsonPath("$.categoryName").value("All categories"));
        }

        @Test
        @DisplayName("warns once spend crosses the configured threshold")
        void warningThreshold() throws Exception {
            // Dining budget 4 000 against 3 000 spent = 75%, threshold 70% -> WARNING.
            mockMvc.perform(post("/api/v1/budgets")
                            .header("Authorization", bearer(user))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new BudgetRequest(
                                    "2026-03", new BigDecimal("4000.00"), diningId, (short) 70))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.utilisationPct").value(75.00))
                    .andExpect(jsonPath("$.status").value("WARNING"));
        }

        @Test
        @DisplayName("returns an empty list when no budgets exist for the month")
        void noBudgets() throws Exception {
            mockMvc.perform(get("/api/v1/reports/budget-utilisation")
                            .param("month", "2026-07")
                            .header("Authorization", bearer(user)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }

        @Test
        @DisplayName("agrees with the summary report about the month's expenses")
        void agreesWithSummary() throws Exception {
            // Both read through the same totals query, so the two endpoints can
            // never disagree about how much was spent.
            mockMvc.perform(post("/api/v1/budgets")
                            .header("Authorization", bearer(user))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new BudgetRequest(
                                    "2026-03", new BigDecimal("50000.00"), null, (short) 80))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.spent").value(10000.00));

            mockMvc.perform(get("/api/v1/reports/summary")
                            .param("from", "2026-03-01").param("to", "2026-03-31")
                            .header("Authorization", bearer(user)))
                    .andExpect(jsonPath("$.totalExpense").value(10000.00));
        }
    }
}
