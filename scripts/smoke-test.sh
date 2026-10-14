#!/usr/bin/env bash
#
# End-to-end smoke test against a running FinSight instance.
#
#   ./scripts/smoke-test.sh [base-url]        # default http://localhost:8080
#
# Exercises: health, registration, login, /me, categories, transaction CRUD,
# per-user data isolation, CSV import, import errors, and every report.
# Prints PASS/FAIL per check and exits non-zero if any check failed.
#
# Requires: curl, jq
#
set -uo pipefail

BASE="${1:-http://localhost:8080}"
PASS=0
FAIL=0

green() { printf '\033[32m%s\033[0m' "$1"; }
red()   { printf '\033[31m%s\033[0m' "$1"; }

check() {
  local label="$1" expected="$2" actual="$3"
  if [[ "$actual" == "$expected" ]]; then
    printf '  [%s] %-58s (%s)\n' "$(green PASS)" "$label" "$actual"
    PASS=$((PASS + 1))
  else
    printf '  [%s] %-58s expected %s, got %s\n' "$(red FAIL)" "$label" "$expected" "$actual"
    FAIL=$((FAIL + 1))
  fi
}

check_true() {
  local label="$1" condition="$2" detail="${3:-}"
  if [[ "$condition" == "true" ]]; then
    printf '  [%s] %-58s %s\n' "$(green PASS)" "$label" "$detail"
    PASS=$((PASS + 1))
  else
    printf '  [%s] %-58s %s\n' "$(red FAIL)" "$label" "$detail"
    FAIL=$((FAIL + 1))
  fi
}

# status-only request
status() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

command -v jq >/dev/null || { echo "jq is required: sudo apt install jq"; exit 2; }

SUFFIX="$(date +%s)$RANDOM"
EMAIL_A="smoke.a.${SUFFIX}@example.com"
EMAIL_B="smoke.b.${SUFFIX}@example.com"
PASSWORD="SmokeTest123Pass"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

echo
echo "FinSight smoke test against $BASE"
echo "========================================================================"

# ----------------------------------------------------------------- health --
echo
echo "Health"
check "GET /actuator/health" "200" "$(status "$BASE/actuator/health")"
HEALTH="$(curl -s "$BASE/actuator/health")"
check "health status is UP" "UP" "$(echo "$HEALTH" | jq -r '.status')"
check "GET /actuator/health/readiness" "200" "$(status "$BASE/actuator/health/readiness")"
check "OpenAPI document served" "200" "$(status "$BASE/v3/api-docs")"

# --------------------------------------------------------- authentication --
echo
echo "Authentication"
REG_A="$(curl -s -X POST "$BASE/api/v1/auth/register" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL_A\",\"password\":\"$PASSWORD\",\"fullName\":\"Smoke User A\"}")"
TOKEN_A="$(echo "$REG_A" | jq -r '.accessToken // empty')"
check_true "register user A" "$([[ -n "$TOKEN_A" ]] && echo true || echo false)" "token issued"

check "register with duplicate email -> 409" "409" \
  "$(status -X POST "$BASE/api/v1/auth/register" -H 'Content-Type: application/json' \
     -d "{\"email\":\"$EMAIL_A\",\"password\":\"$PASSWORD\",\"fullName\":\"Smoke User A\"}")"

check "register with weak password -> 400" "400" \
  "$(status -X POST "$BASE/api/v1/auth/register" -H 'Content-Type: application/json' \
     -d "{\"email\":\"weak.${SUFFIX}@example.com\",\"password\":\"short\",\"fullName\":\"Weak\"}")"

LOGIN="$(curl -s -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL_A\",\"password\":\"$PASSWORD\"}")"
check_true "login user A" "$([[ -n "$(echo "$LOGIN" | jq -r '.accessToken // empty')" ]] && echo true || echo false)" ""

check "login with wrong password -> 401" "401" \
  "$(status -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
     -d "{\"email\":\"$EMAIL_A\",\"password\":\"WrongPassword123\"}")"

check "login with unknown email -> 401" "401" \
  "$(status -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
     -d "{\"email\":\"nobody.${SUFFIX}@example.com\",\"password\":\"$PASSWORD\"}")"

ME="$(curl -s "$BASE/api/v1/auth/me" -H "Authorization: Bearer $TOKEN_A")"
check "GET /auth/me returns the right account" "$EMAIL_A" "$(echo "$ME" | jq -r '.email')"
check_true "/auth/me does not leak the password hash" \
  "$([[ "$(echo "$ME" | jq 'has("passwordHash")')" == "false" ]] && echo true || echo false)" ""

REFRESH_TOKEN="$(echo "$REG_A" | jq -r '.refreshToken')"
check "refresh token works" "200" \
  "$(status -X POST "$BASE/api/v1/auth/refresh" -H 'Content-Type: application/json' \
     -d "{\"refreshToken\":\"$REFRESH_TOKEN\"}")"
check "refresh token rejected as a bearer credential -> 401" "401" \
  "$(status "$BASE/api/v1/transactions" -H "Authorization: Bearer $REFRESH_TOKEN")"

check "protected endpoint without a token -> 401" "401" "$(status "$BASE/api/v1/transactions")"
check "protected endpoint with a garbage token -> 401" "401" \
  "$(status "$BASE/api/v1/transactions" -H "Authorization: Bearer not.a.real.token")"

# -------------------------------------------------------------- categories --
echo
echo "Categories"
CATS="$(curl -s "$BASE/api/v1/categories" -H "Authorization: Bearer $TOKEN_A")"
CAT_COUNT="$(echo "$CATS" | jq 'length')"
check_true "system categories seeded by Flyway" \
  "$([[ "$CAT_COUNT" -ge 18 ]] && echo true || echo false)" "$CAT_COUNT returned"

GROCERIES_ID="$(echo "$CATS" | jq -r '.[] | select(.name=="Groceries") | .id')"
check_true "Groceries category present" \
  "$([[ -n "$GROCERIES_ID" ]] && echo true || echo false)" "id=$GROCERIES_ID"

NEW_CAT="$(curl -s -X POST "$BASE/api/v1/categories" -H "Authorization: Bearer $TOKEN_A" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Smoke Test Category","type":"EXPENSE","color":"#123456"}')"
NEW_CAT_ID="$(echo "$NEW_CAT" | jq -r '.id')"
check_true "create a user category" "$([[ -n "$NEW_CAT_ID" ]] && echo true || echo false)" "id=$NEW_CAT_ID"
check "duplicate category name -> 409" "409" \
  "$(status -X POST "$BASE/api/v1/categories" -H "Authorization: Bearer $TOKEN_A" \
     -H 'Content-Type: application/json' \
     -d '{"name":"Smoke Test Category","type":"EXPENSE"}')"
check "system category is not editable -> 404" "404" \
  "$(status -X PUT "$BASE/api/v1/categories/$GROCERIES_ID" -H "Authorization: Bearer $TOKEN_A" \
     -H 'Content-Type: application/json' -d '{"name":"Hijacked","type":"EXPENSE"}')"

# ------------------------------------------------------------ transactions --
echo
echo "Transactions"
TXN="$(curl -s -X POST "$BASE/api/v1/transactions" -H "Authorization: Bearer $TOKEN_A" \
  -H 'Content-Type: application/json' \
  -d "{\"type\":\"EXPENSE\",\"amount\":1249.50,\"txnDate\":\"2026-03-14\",\
\"description\":\"Smoke groceries\",\"merchant\":\"Big Bazaar\",\"categoryId\":$GROCERIES_ID}")"
TXN_ID="$(echo "$TXN" | jq -r '.id')"
check_true "create an EXPENSE transaction" "$([[ -n "$TXN_ID" ]] && echo true || echo false)" "id=$TXN_ID"
check "amount round-trips exactly" "1249.50" "$(echo "$TXN" | jq -r '.amount')"
check "category name is returned" "Groceries" "$(echo "$TXN" | jq -r '.categoryName')"

curl -s -X POST "$BASE/api/v1/transactions" -H "Authorization: Bearer $TOKEN_A" \
  -H 'Content-Type: application/json' \
  -d '{"type":"INCOME","amount":95000.00,"txnDate":"2026-03-01","description":"Smoke salary"}' >/dev/null

check "negative amount -> 400" "400" \
  "$(status -X POST "$BASE/api/v1/transactions" -H "Authorization: Bearer $TOKEN_A" \
     -H 'Content-Type: application/json' \
     -d '{"type":"EXPENSE","amount":-10,"txnDate":"2026-03-14","description":"Negative"}')"
check "zero amount -> 400" "400" \
  "$(status -X POST "$BASE/api/v1/transactions" -H "Authorization: Bearer $TOKEN_A" \
     -H 'Content-Type: application/json' \
     -d '{"type":"EXPENSE","amount":0,"txnDate":"2026-03-14","description":"Zero"}')"
check "EXPENSE under an INCOME category -> 422" "422" \
  "$(status -X POST "$BASE/api/v1/transactions" -H "Authorization: Bearer $TOKEN_A" \
     -H 'Content-Type: application/json' \
     -d "{\"type\":\"EXPENSE\",\"amount\":100,\"txnDate\":\"2026-03-14\",\"description\":\"Mismatch\",\
\"categoryId\":$(echo "$CATS" | jq -r '.[] | select(.name=="Salary") | .id')}")"

check "GET one transaction" "200" \
  "$(status "$BASE/api/v1/transactions/$TXN_ID" -H "Authorization: Bearer $TOKEN_A")"

LIST="$(curl -s "$BASE/api/v1/transactions?from=2026-03-01&to=2026-03-31&size=10" \
  -H "Authorization: Bearer $TOKEN_A")"
check_true "filtered + paginated list" \
  "$([[ "$(echo "$LIST" | jq -r '.totalElements')" -ge 2 ]] && echo true || echo false)" \
  "totalElements=$(echo "$LIST" | jq -r '.totalElements')"

check "from after to -> 400" "400" \
  "$(status "$BASE/api/v1/transactions?from=2026-03-31&to=2026-03-01" -H "Authorization: Bearer $TOKEN_A")"

UPDATED="$(curl -s -X PUT "$BASE/api/v1/transactions/$TXN_ID" -H "Authorization: Bearer $TOKEN_A" \
  -H 'Content-Type: application/json' \
  -d "{\"type\":\"EXPENSE\",\"amount\":1500.00,\"txnDate\":\"2026-03-14\",\
\"description\":\"Smoke groceries (edited)\",\"categoryId\":$GROCERIES_ID}")"
check "update changes the amount" "1500.00" "$(echo "$UPDATED" | jq -r '.amount')"

# ------------------------------------------------------- data isolation --
echo
echo "User-data isolation"
REG_B="$(curl -s -X POST "$BASE/api/v1/auth/register" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL_B\",\"password\":\"$PASSWORD\",\"fullName\":\"Smoke User B\"}")"
TOKEN_B="$(echo "$REG_B" | jq -r '.accessToken')"

check "user B cannot GET user A's transaction -> 404" "404" \
  "$(status "$BASE/api/v1/transactions/$TXN_ID" -H "Authorization: Bearer $TOKEN_B")"
check "user B cannot UPDATE user A's transaction -> 404" "404" \
  "$(status -X PUT "$BASE/api/v1/transactions/$TXN_ID" -H "Authorization: Bearer $TOKEN_B" \
     -H 'Content-Type: application/json' \
     -d '{"type":"EXPENSE","amount":1,"txnDate":"2026-03-14","description":"Hijack"}')"
check "user B cannot DELETE user A's transaction -> 404" "404" \
  "$(status -X DELETE "$BASE/api/v1/transactions/$TXN_ID" -H "Authorization: Bearer $TOKEN_B")"
check "user B cannot see user A's category -> 404" "404" \
  "$(status "$BASE/api/v1/categories/$NEW_CAT_ID" -H "Authorization: Bearer $TOKEN_B")"

B_LIST="$(curl -s "$BASE/api/v1/transactions" -H "Authorization: Bearer $TOKEN_B")"
check "user B's transaction list is empty" "0" "$(echo "$B_LIST" | jq -r '.totalElements')"

B_SUMMARY="$(curl -s "$BASE/api/v1/reports/summary?from=2026-03-01&to=2026-03-31" \
  -H "Authorization: Bearer $TOKEN_B")"
check "user B's report shows zero income (no cache bleed)" "0.00" \
  "$(echo "$B_SUMMARY" | jq -r '.totalIncome')"

# ----------------------------------------------------------------- budgets --
echo
echo "Budgets"
BUDGET="$(curl -s -X POST "$BASE/api/v1/budgets" -H "Authorization: Bearer $TOKEN_A" \
  -H 'Content-Type: application/json' \
  -d "{\"month\":\"2026-03\",\"amount\":10000.00,\"categoryId\":$GROCERIES_ID,\"alertThresholdPct\":80}")"
BUDGET_ID="$(echo "$BUDGET" | jq -r '.id')"
check_true "create a category budget" "$([[ -n "$BUDGET_ID" ]] && echo true || echo false)" "id=$BUDGET_ID"
check "budget reports measured spend" "1500.00" "$(echo "$BUDGET" | jq -r '.spent')"
check "budget utilisation computed" "15.00" "$(echo "$BUDGET" | jq -r '.utilisationPct')"
check "budget status" "ON_TRACK" "$(echo "$BUDGET" | jq -r '.status')"

check "duplicate budget for the same scope -> 409" "409" \
  "$(status -X POST "$BASE/api/v1/budgets" -H "Authorization: Bearer $TOKEN_A" \
     -H 'Content-Type: application/json' \
     -d "{\"month\":\"2026-03\",\"amount\":5000,\"categoryId\":$GROCERIES_ID}")"
check "malformed month -> 400" "400" \
  "$(status -X POST "$BASE/api/v1/budgets" -H "Authorization: Bearer $TOKEN_A" \
     -H 'Content-Type: application/json' -d '{"month":"March 2026","amount":5000}')"

OVERALL="$(curl -s -X POST "$BASE/api/v1/budgets" -H "Authorization: Bearer $TOKEN_A" \
  -H 'Content-Type: application/json' -d '{"month":"2026-03","amount":1000.00}')"
check "overall budget marked EXCEEDED when spend passes the cap" "EXCEEDED" \
  "$(echo "$OVERALL" | jq -r '.status')"

# ----------------------------------------------------------------- reports --
echo
echo "Reports"
SUMMARY="$(curl -s "$BASE/api/v1/reports/summary?from=2026-03-01&to=2026-03-31" \
  -H "Authorization: Bearer $TOKEN_A")"
check "summary total income" "95000.00" "$(echo "$SUMMARY" | jq -r '.totalIncome')"
check "summary total expense" "1500.00" "$(echo "$SUMMARY" | jq -r '.totalExpense')"
check "summary net cash flow" "93500.00" "$(echo "$SUMMARY" | jq -r '.netCashFlow')"

EMPTY="$(curl -s "$BASE/api/v1/reports/summary?from=2019-01-01&to=2019-01-31" \
  -H "Authorization: Bearer $TOKEN_A")"
check "empty period reports zero, not null" "0.00" "$(echo "$EMPTY" | jq -r '.totalIncome')"
check "empty period net is zero" "0.00" "$(echo "$EMPTY" | jq -r '.netCashFlow')"

BOUNDARY="$(curl -s "$BASE/api/v1/reports/summary?from=2026-03-14&to=2026-03-14" \
  -H "Authorization: Bearer $TOKEN_A")"
check "single-day range is inclusive on both ends" "1500.00" \
  "$(echo "$BOUNDARY" | jq -r '.totalExpense')"

MONTHLY="$(curl -s "$BASE/api/v1/reports/monthly?from=2026-01&to=2026-06" \
  -H "Authorization: Bearer $TOKEN_A")"
check "monthly series is dense (6 months requested)" "6" "$(echo "$MONTHLY" | jq -r '.months | length')"
check "months with no activity appear as zero" "0.00" \
  "$(echo "$MONTHLY" | jq -r '.months[] | select(.month=="2026-01") | .totalIncome')"

BREAKDOWN="$(curl -s "$BASE/api/v1/reports/categories?type=EXPENSE&from=2026-03-01&to=2026-03-31" \
  -H "Authorization: Bearer $TOKEN_A")"
check "category breakdown grand total" "1500.00" "$(echo "$BREAKDOWN" | jq -r '.grandTotal')"
check "single category accounts for 100%" "100.00" \
  "$(echo "$BREAKDOWN" | jq -r '.categories.content[0].percentageOfTotal')"

check "budget-utilisation report" "200" \
  "$(status "$BASE/api/v1/reports/budget-utilisation?month=2026-03" -H "Authorization: Bearer $TOKEN_A")"

# ------------------------------------------------------------- CSV import --
echo
echo "CSV import"
SAMPLE="$PROJECT_DIR/samples/transactions-sample.csv"
if [[ -f "$SAMPLE" ]]; then
  IMPORT="$(curl -s -X POST "$BASE/api/v1/imports" -H "Authorization: Bearer $TOKEN_A" \
    -F "file=@$SAMPLE")"
  JOB_ID="$(echo "$IMPORT" | jq -r '.id')"
  check_true "upload accepted" "$([[ -n "$JOB_ID" ]] && echo true || echo false)" "job id=$JOB_ID"
  check "33 data rows detected" "33" "$(echo "$IMPORT" | jq -r '.totalRows')"
  check "all rows imported" "33" "$(echo "$IMPORT" | jq -r '.importedRows')"
  check "no rows failed" "0" "$(echo "$IMPORT" | jq -r '.failedRows')"

  # IDEMPOTENCY: the identical file must import nothing the second time.
  REIMPORT="$(curl -s -X POST "$BASE/api/v1/imports" -H "Authorization: Bearer $TOKEN_A" \
    -F "file=@$SAMPLE")"
  check "re-upload imports 0 new rows" "0" "$(echo "$REIMPORT" | jq -r '.importedRows')"
  check "re-upload reports 33 duplicates" "33" "$(echo "$REIMPORT" | jq -r '.duplicateRows')"
  check "re-upload flagged as previously uploaded" "true" \
    "$(echo "$REIMPORT" | jq -r '.previouslyUploaded')"
else
  echo "  (skipped: $SAMPLE not found)"
fi

BAD="$PROJECT_DIR/samples/transactions-with-errors.csv"
if [[ -f "$BAD" ]]; then
  BAD_IMPORT="$(curl -s -X POST "$BASE/api/v1/imports" -H "Authorization: Bearer $TOKEN_A" \
    -F "file=@$BAD")"
  BAD_JOB="$(echo "$BAD_IMPORT" | jq -r '.id')"
  check "partial failure reported" "COMPLETED_WITH_ERRORS" "$(echo "$BAD_IMPORT" | jq -r '.status')"
  check_true "valid rows still imported" \
    "$([[ "$(echo "$BAD_IMPORT" | jq -r '.importedRows')" -gt 0 ]] && echo true || echo false)" \
    "imported=$(echo "$BAD_IMPORT" | jq -r '.importedRows')"
  check_true "bad rows rejected" \
    "$([[ "$(echo "$BAD_IMPORT" | jq -r '.failedRows')" -gt 0 ]] && echo true || echo false)" \
    "failed=$(echo "$BAD_IMPORT" | jq -r '.failedRows')"

  ERRORS="$(curl -s "$BASE/api/v1/imports/$BAD_JOB/errors" -H "Authorization: Bearer $TOKEN_A")"
  check_true "per-row errors are listed with codes" \
    "$([[ "$(echo "$ERRORS" | jq -r '.content | length')" -gt 0 ]] && echo true || echo false)" \
    "$(echo "$ERRORS" | jq -r '[.content[].errorCode] | unique | join(", ")')"

  check "user B cannot read user A's import errors -> 404" "404" \
    "$(status "$BASE/api/v1/imports/$BAD_JOB/errors" -H "Authorization: Bearer $TOKEN_B")"
fi

check "upload a non-CSV file -> 400" "400" \
  "$(printf 'not a csv' > /tmp/finsight-smoke.txt && \
     status -X POST "$BASE/api/v1/imports" -H "Authorization: Bearer $TOKEN_A" \
     -F 'file=@/tmp/finsight-smoke.txt')"
rm -f /tmp/finsight-smoke.txt

# --------------------------------------------------------------- cleanup --
echo
echo "Cleanup"
check "delete the test transaction -> 204" "204" \
  "$(status -X DELETE "$BASE/api/v1/transactions/$TXN_ID" -H "Authorization: Bearer $TOKEN_A")"
check "deleted transaction is gone -> 404" "404" \
  "$(status "$BASE/api/v1/transactions/$TXN_ID" -H "Authorization: Bearer $TOKEN_A")"

# ----------------------------------------------------------------- result --
echo
echo "========================================================================"
printf '  %s passed, %s failed\n' "$(green "$PASS")" "$([[ $FAIL -gt 0 ]] && red "$FAIL" || echo "$FAIL")"
echo "  Test accounts created: $EMAIL_A, $EMAIL_B"
echo "========================================================================"
echo

[[ $FAIL -eq 0 ]]
