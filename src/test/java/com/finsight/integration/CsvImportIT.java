package com.finsight.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.finsight.auth.dto.AuthResponse;
import com.finsight.transaction.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end CSV import against a real database.
 *
 * <p>The unit tests prove the <em>decisions</em>; this proves the
 * <em>guarantee</em>. Specifically, that re-importing the same file inserts
 * nothing: the partial unique index {@code uq_transactions_user_external_ref} is
 * what enforces that, and only a real PostgreSQL can demonstrate it.
 *
 * <p>These tests use the synchronous path — the test profile sets
 * {@code async-threshold-rows} high — so each upload completes within the request
 * and the assertions are deterministic. The asynchronous path shares the same
 * {@link com.finsight.imports.service.ImportProcessor} code and is exercised by
 * {@code OutboxIT}.
 */
@Transactional
class CsvImportIT extends AbstractIntegrationTest {

    @Autowired
    private TransactionRepository transactionRepository;

    private AuthResponse user;

    private static final String GOOD_CSV = """
            date,type,amount,description,merchant,category
            2026-03-01,INCOME,95000.00,Monthly salary,Lentra,Salary
            2026-03-02,EXPENSE,3480.75,Weekly groceries,Big Bazaar,Groceries
            2026-03-03,EXPENSE,1250.00,Electricity bill,MSEDCL,Utilities
            2026-03-04,EXPENSE,420.50,Lunch,Cafe Mocha,Dining
            """;

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser("import");
    }

    private JsonNode upload(String filename, String content) throws Exception {
        String body = mockMvc.perform(multipart("/api/v1/imports")
                        .file(new MockMultipartFile("file", filename, "text/csv",
                                content.getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", bearer(user)))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    // ------------------------------------------------------------- happy path --

    @Nested
    @DisplayName("a clean file")
    class CleanFile {

        @Test
        @DisplayName("imports every row and returns 200 with final counts")
        void importsAllRows() throws Exception {
            mockMvc.perform(multipart("/api/v1/imports")
                            .file(new MockMultipartFile("file", "march.csv", "text/csv",
                                    GOOD_CSV.getBytes(StandardCharsets.UTF_8)))
                            .header("Authorization", bearer(user)))
                    // 200, not 202: this file was small enough to finish inline, so
                    // there is nothing left for the client to poll.
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("COMPLETED"))
                    .andExpect(jsonPath("$.terminal").value(true))
                    .andExpect(jsonPath("$.totalRows").value(4))
                    .andExpect(jsonPath("$.importedRows").value(4))
                    .andExpect(jsonPath("$.duplicateRows").value(0))
                    .andExpect(jsonPath("$.failedRows").value(0));
        }

        @Test
        @DisplayName("the imported rows are retrievable and correctly typed")
        void rowsAreQueryable() throws Exception {
            upload("march.csv", GOOD_CSV);

            mockMvc.perform(get("/api/v1/transactions")
                            .param("from", "2026-03-01").param("to", "2026-03-31")
                            .header("Authorization", bearer(user)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(4));

            mockMvc.perform(get("/api/v1/transactions")
                            .param("type", "INCOME")
                            .header("Authorization", bearer(user)))
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].amount").value(95000.00));
        }

        @Test
        @DisplayName("category names in the file are resolved to real categories")
        void categoriesResolved() throws Exception {
            upload("march.csv", GOOD_CSV);

            mockMvc.perform(get("/api/v1/reports/categories")
                            .param("from", "2026-03-01").param("to", "2026-03-31")
                            .header("Authorization", bearer(user)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.grandTotal").value(5151.25))
                    .andExpect(jsonPath("$.categories.content[?(@.categoryName == 'Groceries')]").exists());
        }

        @Test
        @DisplayName("imported rows are marked CSV_IMPORT and linked to their job")
        void importedRowsAreTagged() throws Exception {
            JsonNode job = upload("march.csv", GOOD_CSV);

            mockMvc.perform(get("/api/v1/transactions")
                            .param("source", "CSV_IMPORT")
                            .header("Authorization", bearer(user)))
                    .andExpect(jsonPath("$.totalElements").value(4))
                    .andExpect(jsonPath("$.content[0].importJobId").value(job.get("id").asLong()));
        }
    }

    // ------------------------------------------------------------ idempotency --

    @Nested
    @DisplayName("idempotency")
    class Idempotency {

        @Test
        @DisplayName("re-uploading the identical file imports nothing")
        void reimportIsANoOp() throws Exception {
            JsonNode first = upload("march.csv", GOOD_CSV);
            assertThat(first.get("importedRows").asInt()).isEqualTo(4);

            JsonNode second = upload("march.csv", GOOD_CSV);

            // This is the guarantee. The second pass computes the same
            // sha256(fileChecksum + ':' + rowNumber) keys, finds them present, and
            // skips every row.
            assertThat(second.get("importedRows").asInt()).isZero();
            assertThat(second.get("duplicateRows").asInt()).isEqualTo(4);
            assertThat(second.get("failedRows").asInt()).isZero();
            assertThat(second.get("status").asText()).isEqualTo("COMPLETED");
            assertThat(second.get("previouslyUploaded").asBoolean()).isTrue();
        }

        @Test
        @DisplayName("the database holds four rows, not eight, after a double import")
        void noDuplicateRowsInDatabase() throws Exception {
            upload("march.csv", GOOD_CSV);
            upload("march.csv", GOOD_CSV);

            mockMvc.perform(get("/api/v1/transactions?size=100")
                            .header("Authorization", bearer(user)))
                    .andExpect(jsonPath("$.totalElements").value(4));
        }

        @Test
        @DisplayName("the totals do not double either")
        void reportsAreUnaffectedByReimport() throws Exception {
            upload("march.csv", GOOD_CSV);

            String before = mockMvc.perform(get("/api/v1/reports/summary")
                            .param("from", "2026-03-01").param("to", "2026-03-31")
                            .header("Authorization", bearer(user)))
                    .andReturn().getResponse().getContentAsString();

            upload("march.csv", GOOD_CSV);

            String after = mockMvc.perform(get("/api/v1/reports/summary")
                            .param("from", "2026-03-01").param("to", "2026-03-31")
                            .header("Authorization", bearer(user)))
                    .andReturn().getResponse().getContentAsString();

            assertThat(objectMapper.readTree(after).get("totalExpense"))
                    .isEqualTo(objectMapper.readTree(before).get("totalExpense"));
            assertThat(objectMapper.readTree(after).get("totalIncome"))
                    .isEqualTo(objectMapper.readTree(before).get("totalIncome"));
        }

        @Test
        @DisplayName("the filename is irrelevant; only the content decides")
        void filenameDoesNotAffectIdempotency() throws Exception {
            // The key is derived from the content hash, so renaming the file does
            // not make it a new import.
            upload("march.csv", GOOD_CSV);
            JsonNode renamed = upload("march-copy-final-v2.csv", GOOD_CSV);

            assertThat(renamed.get("importedRows").asInt()).isZero();
            assertThat(renamed.get("duplicateRows").asInt()).isEqualTo(4);
        }

        @Test
        @DisplayName("a genuinely different file does import")
        void differentContentImports() throws Exception {
            upload("march.csv", GOOD_CSV);

            String april = """
                    date,type,amount,description,merchant,category
                    2026-04-01,EXPENSE,500.00,April spend,Shop,Groceries
                    """;
            JsonNode second = upload("april.csv", april);

            // One changed byte gives a different checksum and therefore entirely
            // different row keys.
            assertThat(second.get("importedRows").asInt()).isEqualTo(1);
            assertThat(second.get("duplicateRows").asInt()).isZero();
        }

        @Test
        @DisplayName("two identical rows within one file are both imported")
        void identicalRowsInOneFileAreBothKept() throws Exception {
            // Two coffees at the same price on the same day is legitimate data.
            // Keying on content alone would wrongly reject the second; keying on
            // content AND row position keeps both.
            String twoCoffees = """
                    date,type,amount,description,merchant,category
                    2026-03-01,EXPENSE,120.00,Coffee,Third Wave,Dining
                    2026-03-01,EXPENSE,120.00,Coffee,Third Wave,Dining
                    """;

            JsonNode result = upload("coffees.csv", twoCoffees);

            assertThat(result.get("importedRows").asInt()).isEqualTo(2);
            assertThat(result.get("duplicateRows").asInt()).isZero();
        }
    }

    // --------------------------------------------------------- partial failure --

    @Nested
    @DisplayName("partial failure")
    class PartialFailure {

        private static final String MIXED_CSV = """
                date,type,amount,description,merchant,category
                2026-03-01,EXPENSE,100.00,Good row one,Shop,Groceries
                31/02/2026,EXPENSE,200.00,Impossible date,Shop,Groceries
                2026-03-03,EXPENSE,300.00,Good row two,Shop,Groceries
                2026-03-04,TRANSFER,400.00,Unsupported type,Shop,Groceries
                2026-03-05,EXPENSE,abc,Not a number,Shop,Groceries
                2026-03-06,EXPENSE,600.00,Unknown category,Shop,Cryptocurrency
                2026-03-07,EXPENSE,700.00,Good row three,Shop,Groceries
                """;

        @Test
        @DisplayName("imports the valid rows and reports the rest")
        void importsGoodRowsOnly() throws Exception {
            // All-or-nothing would mean a 2 000-row statement with one typo
            // imports zero rows. That is a worse outcome for the user than
            // importing 1 999 and listing the one to fix.
            JsonNode result = upload("mixed.csv", MIXED_CSV);

            assertThat(result.get("status").asText()).isEqualTo("COMPLETED_WITH_ERRORS");
            assertThat(result.get("totalRows").asInt()).isEqualTo(7);
            assertThat(result.get("importedRows").asInt()).isEqualTo(3);
            assertThat(result.get("failedRows").asInt()).isEqualTo(4);
            assertThat(result.get("terminal").asBoolean()).isTrue();
        }

        @Test
        @DisplayName("counters always reconcile")
        void countersReconcile() throws Exception {
            JsonNode result = upload("mixed.csv", MIXED_CSV);

            assertThat(result.get("totalRows").asInt())
                    .isEqualTo(result.get("importedRows").asInt()
                            + result.get("duplicateRows").asInt()
                            + result.get("failedRows").asInt());
        }

        @Test
        @DisplayName("lists each rejected row with its line number, code and reason")
        void listsRowErrors() throws Exception {
            JsonNode job = upload("mixed.csv", MIXED_CSV);

            String errors = mockMvc.perform(get("/api/v1/imports/" + job.get("id").asLong() + "/errors")
                            .header("Authorization", bearer(user)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(4))
                    .andReturn().getResponse().getContentAsString();

            JsonNode content = objectMapper.readTree(errors).get("content");

            assertThat(content).hasSize(4);
            assertThat(content.findValuesAsText("errorCode"))
                    .contains("INVALID_DATE", "INVALID_TYPE", "INVALID_AMOUNT", "UNKNOWN_CATEGORY");

            // Line numbers point at the real file lines so the user can fix them.
            for (JsonNode error : content) {
                assertThat(error.get("rowNumber").asInt()).isBetween(2, 8);
                assertThat(error.get("message").asText()).isNotBlank();
            }
        }

        @Test
        @DisplayName("the good rows really are in the database")
        void goodRowsPersisted() throws Exception {
            upload("mixed.csv", MIXED_CSV);

            mockMvc.perform(get("/api/v1/transactions?size=100")
                            .header("Authorization", bearer(user)))
                    .andExpect(jsonPath("$.totalElements").value(3));
        }

        @Test
        @DisplayName("re-uploading a partially failed file re-reports the same errors, not double")
        void retryDoesNotDoubleErrors() throws Exception {
            JsonNode first = upload("mixed.csv", MIXED_CSV);
            JsonNode second = upload("mixed.csv", MIXED_CSV);

            // Previous errors are deleted before each pass, and counters are
            // assigned rather than incremented.
            assertThat(second.get("failedRows").asInt())
                    .isEqualTo(first.get("failedRows").asInt())
                    .isEqualTo(4);
            assertThat(second.get("importedRows").asInt()).isZero();
            assertThat(second.get("duplicateRows").asInt()).isEqualTo(3);
        }
    }

    // ------------------------------------------------------------- rejections --

    @Nested
    @DisplayName("rejected uploads")
    class Rejections {

        @Test
        @DisplayName("rejects an empty file")
        void emptyFile() throws Exception {
            mockMvc.perform(multipart("/api/v1/imports")
                            .file(new MockMultipartFile("file", "empty.csv", "text/csv", new byte[0]))
                            .header("Authorization", bearer(user)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_CSV"));
        }

        @Test
        @DisplayName("rejects a file whose header lacks a required column")
        void missingRequiredHeader() throws Exception {
            // Structural, so it is one clear message rather than N identical row errors.
            mockMvc.perform(multipart("/api/v1/imports")
                            .file(new MockMultipartFile("file", "bad.csv", "text/csv",
                                    "date,description\n2026-03-01,No amount column\n"
                                            .getBytes(StandardCharsets.UTF_8)))
                            .header("Authorization", bearer(user)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_CSV"))
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("missing required column")));
        }

        @Test
        @DisplayName("rejects a header with no data rows")
        void headerOnly() throws Exception {
            mockMvc.perform(multipart("/api/v1/imports")
                            .file(new MockMultipartFile("file", "header.csv", "text/csv",
                                    "date,type,amount,description\n".getBytes(StandardCharsets.UTF_8)))
                            .header("Authorization", bearer(user)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("rejects a non-.csv filename")
        void wrongExtension() throws Exception {
            mockMvc.perform(multipart("/api/v1/imports")
                            .file(new MockMultipartFile("file", "statement.pdf", "text/csv",
                                    GOOD_CSV.getBytes(StandardCharsets.UTF_8)))
                            .header("Authorization", bearer(user)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("rejects an unsupported content type")
        void wrongContentType() throws Exception {
            mockMvc.perform(multipart("/api/v1/imports")
                            .file(new MockMultipartFile("file", "statement.csv", "application/pdf",
                                    GOOD_CSV.getBytes(StandardCharsets.UTF_8)))
                            .header("Authorization", bearer(user)))
                    .andExpect(status().isUnsupportedMediaType());
        }

        @Test
        @DisplayName("requires authentication")
        void requiresAuth() throws Exception {
            mockMvc.perform(multipart("/api/v1/imports")
                            .file(new MockMultipartFile("file", "x.csv", "text/csv",
                                    GOOD_CSV.getBytes(StandardCharsets.UTF_8))))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("sanitises a path-traversal filename rather than storing it verbatim")
        void sanitisesFilename() throws Exception {
            // Nothing opens a file by this name, but an attacker-controlled string
            // that a UI later renders is worth neutralising at the boundary.
            JsonNode result = upload("../../../etc/passwd.csv", GOOD_CSV);

            assertThat(result.get("filename").asText()).isEqualTo("passwd.csv");
            assertThat(result.get("filename").asText()).doesNotContain("..");
            assertThat(result.get("filename").asText()).doesNotContain("/");
        }
    }

    // ---------------------------------------------------------------- listing --

    @Test
    @DisplayName("import history is listed newest first")
    void listsImports() throws Exception {
        upload("first.csv", GOOD_CSV);
        upload("second.csv", """
                date,type,amount,description
                2026-04-01,EXPENSE,50.00,April row
                """);

        mockMvc.perform(get("/api/v1/imports").header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].filename").value("second.csv"));
    }

    @Test
    @DisplayName("status can be polled by id")
    void statusIsPollable() throws Exception {
        JsonNode job = upload("march.csv", GOOD_CSV);

        mockMvc.perform(get("/api/v1/imports/" + job.get("id").asLong())
                        .header("Authorization", bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.terminal").value(true))
                .andExpect(jsonPath("$.importedRows").value(4));
    }

    @Test
    @DisplayName("an unknown import id is 404")
    void unknownImportIs404() throws Exception {
        mockMvc.perform(get("/api/v1/imports/999999").header("Authorization", bearer(user)))
                .andExpect(status().isNotFound());
    }
}
