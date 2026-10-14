package com.finsight.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that the Flyway migrations produce the schema the application expects.
 *
 * <p>This test is the reason {@code spring.jpa.hibernate.ddl-auto} is
 * {@code validate} rather than {@code update}: the migrations are the single
 * source of truth, and the context would fail to start if an entity mapping had
 * drifted from them. Reaching the first assertion already proves that much.
 *
 * <p>Beyond startup, these checks assert the things Hibernate does <em>not</em>
 * validate: indexes, CHECK constraints, partial unique indexes and seed data. A
 * dropped index is invisible to an ORM and to every functional test — right up
 * until a report starts taking thirty seconds in production.
 */
class FlywayMigrationIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private List<String> tableNames() {
        return jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_type = 'BASE TABLE'",
                String.class);
    }

    private List<String> indexNames(String table) {
        return jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND tablename = ?",
                String.class, table);
    }

    private boolean constraintExists(String name) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_constraint WHERE conname = ?", Integer.class, name);
        return count != null && count > 0;
    }

    // ----------------------------------------------------------------- history --

    @Test
    @DisplayName("every migration applied successfully")
    void migrationsApplied() {
        List<String> versions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = true "
                        + "AND version IS NOT NULL ORDER BY installed_rank",
                String.class);

        assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6", "7");

        Integer failed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = false", Integer.class);
        assertThat(failed).isZero();
    }

    // ------------------------------------------------------------------ tables --

    @Test
    @DisplayName("creates every application table")
    void allTablesExist() {
        assertThat(tableNames()).contains(
                "users", "categories", "transactions", "budgets",
                "import_jobs", "import_payloads", "import_row_errors",
                "outbox_events", "processed_events");
    }

    // ----------------------------------------------------------------- indexes --

    @Nested
    @DisplayName("indexes")
    class Indexes {

        @Test
        @DisplayName("transactions carry the indexes the query plans depend on")
        void transactionIndexes() {
            // Each of these backs a specific access path:
            //   user_date      -> the default listing and every date-ranged report
            //   user_category  -> category filters and the breakdown report
            //   user_type      -> income/expense totals
            //   user_dedupe    -> duplicate detection during import
            //   external_ref   -> the idempotency guarantee itself
            assertThat(indexNames("transactions")).contains(
                    "idx_transactions_user_date",
                    "idx_transactions_user_category_date",
                    "idx_transactions_user_type_date",
                    "idx_transactions_user_dedupe",
                    "uq_transactions_user_external_ref",
                    "idx_transactions_import_job");
        }

        @Test
        @DisplayName("email uniqueness is enforced case-insensitively")
        void emailIndex() {
            assertThat(indexNames("users")).contains("uq_users_email_lower");
        }

        @Test
        @DisplayName("budgets use two partial unique indexes, not one")
        void budgetPartialIndexes() {
            // A single UNIQUE (user_id, category_id, period_month) would not stop a
            // second overall budget, because SQL treats NULLs as distinct.
            assertThat(indexNames("budgets")).contains(
                    "uq_budgets_user_overall_month",
                    "uq_budgets_user_category_month");
        }

        @Test
        @DisplayName("categories separate system and user name uniqueness")
        void categoryPartialIndexes() {
            assertThat(indexNames("categories")).contains(
                    "uq_categories_user_name",
                    "uq_categories_system_name");
        }

        @Test
        @DisplayName("the outbox poller's index is partial, covering only unpublished rows")
        void outboxPartialIndex() {
            assertThat(indexNames("outbox_events")).contains("idx_outbox_unpublished");

            String definition = jdbc.queryForObject(
                    "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_outbox_unpublished'",
                    String.class);

            // The WHERE clause is what keeps the index tiny once millions of rows
            // have been published.
            assertThat(definition).contains("WHERE (published_at IS NULL)");
        }
    }

    // ------------------------------------------------------------- constraints --

    @Nested
    @DisplayName("check constraints")
    class Constraints {

        @Test
        @DisplayName("exist on every table that needs one")
        void constraintsExist() {
            assertThat(constraintExists("ck_transactions_amount")).isTrue();
            assertThat(constraintExists("ck_transactions_type")).isTrue();
            assertThat(constraintExists("ck_transactions_currency")).isTrue();
            assertThat(constraintExists("ck_budgets_amount")).isTrue();
            assertThat(constraintExists("ck_budgets_period")).isTrue();
            assertThat(constraintExists("ck_budgets_threshold")).isTrue();
            assertThat(constraintExists("ck_users_role")).isTrue();
            assertThat(constraintExists("ck_categories_system_ownership")).isTrue();
            assertThat(constraintExists("ck_import_jobs_status")).isTrue();
        }

        @Test
        @DisplayName("the database itself rejects a non-positive amount")
        void databaseRejectsNonPositiveAmount() {
            // The application validates this too, but the constraint is the real
            // guarantee: it holds for a direct psql session, a buggy migration or
            // a future service that forgets the check.
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO transactions
                        (user_id, type, amount, txn_date, description, dedupe_hash)
                    VALUES (1, 'EXPENSE', -100, '2026-03-01', 'Negative', ?)
                    """, "0".repeat(64)))
                    .hasMessageContaining("ck_transactions_amount");
        }

        @Test
        @DisplayName("the database rejects a budget that is not anchored to day 1")
        void databaseRejectsMidMonthBudget() {
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO budgets (user_id, period_month, amount)
                    VALUES (1, '2026-03-15', 1000)
                    """))
                    .hasMessageContaining("ck_budgets_period");
        }

        @Test
        @DisplayName("the database rejects a system category that also has an owner")
        void databaseRejectsContradictoryCategory() {
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO categories (user_id, name, type, system)
                    VALUES (1, 'Contradiction', 'EXPENSE', true)
                    """))
                    .hasMessageContaining("ck_categories_system_ownership");
        }
    }

    // -------------------------------------------------------------- seed data --

    @Nested
    @DisplayName("seed data")
    class SeedData {

        @Test
        @DisplayName("V7 seeds the system categories")
        void systemCategoriesSeeded() {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM categories WHERE system = true AND user_id IS NULL",
                    Integer.class);

            assertThat(count).isEqualTo(18);
        }

        @Test
        @DisplayName("seeds both income and expense categories")
        void bothTypesSeeded() {
            Integer income = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM categories WHERE system = true AND type = 'INCOME'",
                    Integer.class);
            Integer expense = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM categories WHERE system = true AND type = 'EXPENSE'",
                    Integer.class);

            assertThat(income).isEqualTo(4);
            assertThat(expense).isEqualTo(14);
        }

        @Test
        @DisplayName("no system category belongs to a user")
        void systemCategoriesAreUnowned() {
            Integer owned = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM categories WHERE system = true AND user_id IS NOT NULL",
                    Integer.class);

            assertThat(owned).isZero();
        }
    }

    // ------------------------------------------------------------ column types --

    @Test
    @DisplayName("money columns are exact NUMERIC(19,4), never floating point")
    void moneyColumnsAreNumeric() {
        // The one schema detail that would silently corrupt every figure in the
        // application if it were float8. Worth asserting explicitly.
        List<String> moneyColumns = List.of("transactions.amount", "budgets.amount");

        for (String qualified : moneyColumns) {
            String[] parts = qualified.split("\\.");
            String dataType = jdbc.queryForObject("""
                    SELECT data_type FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                    """, String.class, parts[0], parts[1]);

            assertThat(dataType).as(qualified).isEqualTo("numeric");

            Integer precision = jdbc.queryForObject("""
                    SELECT numeric_precision FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                    """, Integer.class, parts[0], parts[1]);
            Integer scale = jdbc.queryForObject("""
                    SELECT numeric_scale FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                    """, Integer.class, parts[0], parts[1]);

            assertThat(precision).as(qualified + " precision").isEqualTo(19);
            assertThat(scale).as(qualified + " scale").isEqualTo(4);
        }
    }

    @Test
    @DisplayName("timestamps are timezone-aware")
    void timestampsAreTimestamptz() {
        String dataType = jdbc.queryForObject("""
                SELECT data_type FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'transactions'
                  AND column_name = 'created_at'
                """, String.class);

        assertThat(dataType).isEqualTo("timestamp with time zone");
    }

    @Test
    @DisplayName("txn_date is a plain DATE, so reports have no time-zone boundary problem")
    void txnDateIsDate() {
        String dataType = jdbc.queryForObject("""
                SELECT data_type FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'transactions'
                  AND column_name = 'txn_date'
                """, String.class);

        assertThat(dataType).isEqualTo("date");
    }
}
