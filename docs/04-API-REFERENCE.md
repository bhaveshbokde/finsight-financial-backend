# 04 — API Reference

Base URL: `http://localhost:8080`
Interactive: <http://localhost:8080/swagger-ui.html>
Machine-readable: <http://localhost:8080/v3/api-docs>

Every example below is runnable. Set `$TOKEN` once:

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"asha@example.com","password":"Str0ngPassphrase"}' | jq -r .accessToken)
```

---

## Conventions

| | |
|---|---|
| Authentication | `Authorization: Bearer <accessToken>` on everything except `/auth/register`, `/auth/login`, `/auth/refresh` |
| Content type | `application/json`, except `POST /imports` which is `multipart/form-data` |
| Money | Decimal, 2 displayed fraction digits. `amount` is always **positive**; direction is `type`. |
| Dates | `yyyy-MM-dd`. Report ranges are **inclusive on both ends**. Defaults resolve in UTC. |
| Months | `yyyy-MM` |
| Paging | `?page=0&size=20&sort=field,direction`. Size capped at 200 (100 for report breakdowns). |
| Errors | One `ApiError` shape, always |

### Status codes

| Code | Meaning here |
|---|---|
| `200` | Success |
| `201` | Created — includes a `Location` header |
| `202` | **Accepted, not yet performed.** Import queued; poll for the result. |
| `204` | Success, no body |
| `400` | Could not parse or bind the input |
| `401` | Not authenticated, or token expired/invalid |
| `403` | Authenticated but lacking the authority |
| `404` | No such resource **or it belongs to another user** — these are deliberately indistinguishable |
| `409` | Conflict: duplicate, or a lost optimistic-lock race |
| `413` | Upload too large |
| `415` | Unsupported content type |
| `422` | Parsed fine, but a domain rule forbids it |
| `500` | Bug or unavailable dependency. Quote the `traceId`. |

### Error body

```json
{
  "timestamp": "2026-10-14T09:31:22.481Z",
  "status": 422,
  "code": "BUSINESS_RULE_VIOLATION",
  "message": "Category 'Salary' is INCOME and cannot be used for a EXPENSE record",
  "path": "/api/v1/transactions",
  "traceId": "0f6b1c2d9a3e4f58"
}
```

Branch on `code`, not on `message`. `traceId` is also returned as the
`X-Trace-Id` response header and appears in the server log for that request.

---

## Authentication

### `POST /api/v1/auth/register` — public

```bash
curl -s -X POST http://localhost:8080/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"asha@example.com","password":"Str0ngPassphrase","fullName":"Asha Iyer"}' | jq
```

| Field | Rules |
|---|---|
| `email` | required, well-formed, ≤ 254 chars, stored lower-case |
| `password` | required, 10–100 chars, ≥ 1 lowercase + 1 uppercase + 1 digit |
| `fullName` | required, 2–120 chars |

**`201`**

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9…",
  "refreshToken": "eyJhbGciOiJIUzI1NiJ9…",
  "tokenType": "Bearer",
  "expiresInSeconds": 1800,
  "userId": 1,
  "email": "asha@example.com",
  "fullName": "Asha Iyer",
  "role": "ROLE_USER"
}
```

**`409`** — email already registered (case-insensitively).
**`400`** — validation failed; `fieldErrors` names each field. The rejected
password value is replaced with `***`.

### `POST /api/v1/auth/login` — public

```bash
curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"asha@example.com","password":"Str0ngPassphrase"}' | jq
```

**`200`** — same body as register.

**`401`** — identical body and code (`INVALID_CREDENTIALS`) for a wrong password
and for an unknown email. Any difference would turn this endpoint into an
account-enumeration oracle.

### `POST /api/v1/auth/refresh` — public

```bash
curl -s -X POST http://localhost:8080/api/v1/auth/refresh \
  -H 'Content-Type: application/json' \
  -d "{\"refreshToken\":\"$REFRESH\"}" | jq
```

The refresh token goes in the **body**, not the `Authorization` header — the
JWT filter only ever accepts `ACCESS` tokens. Presenting a refresh token as a
bearer credential returns `401 TOKEN_INVALID`.

Unlike the per-request filter path, refresh re-reads the user from the database,
so a disabled account is rejected here.

### `GET /api/v1/auth/me`

```bash
curl -s http://localhost:8080/api/v1/auth/me -H "Authorization: Bearer $TOKEN" | jq
```

```json
{ "id": 1, "email": "asha@example.com", "fullName": "Asha Iyer",
  "role": "ROLE_USER", "enabled": true, "createdAt": "2026-10-14T06:00:00Z" }
```

No `passwordHash`, no `version`.

---

## Categories

### `GET /api/v1/categories`

Returns the caller's own categories **plus** the 18 shared system categories.

```bash
curl -s "http://localhost:8080/api/v1/categories" -H "Authorization: Bearer $TOKEN" | jq
curl -s "http://localhost:8080/api/v1/categories?type=EXPENSE" -H "Authorization: Bearer $TOKEN" | jq
curl -s "http://localhost:8080/api/v1/categories?includeArchived=true" -H "Authorization: Bearer $TOKEN" | jq
```

```json
[
  { "id": 5, "name": "Groceries", "type": "EXPENSE", "color": "#EF6C00",
    "system": true, "archived": false, "editable": false },
  { "id": 23, "name": "Gym & Fitness", "type": "EXPENSE", "color": "#3F51B5",
    "system": false, "archived": false, "editable": true }
]
```

`editable` is computed per caller: system categories are read-only for everyone,
a user's own category is editable only by them. Returning it saves the client
from re-deriving the rule.

Not paginated — a user has tens of categories, not thousands.

### `POST /api/v1/categories`

```bash
curl -s -X POST http://localhost:8080/api/v1/categories \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"Gym & Fitness","type":"EXPENSE","color":"#3F51B5"}' | jq
```

**`201`** with `Location`. **`409`** if the caller already has that name.

### `PUT /api/v1/categories/{id}`

Replaces `name`, `type` and `color`. A system category returns **`404`** — it is
not visible as an editable resource at all, so no separate "read-only" error is
needed.

### `DELETE /api/v1/categories/{id}`

**Archives** (soft delete). Existing transactions keep their category assignment,
so last year's report still reads correctly. Idempotent: archiving twice is
`204`, not an error.

---

## Transactions

### `GET /api/v1/transactions`

```bash
# Default: newest first, page 0, size 20
curl -s "http://localhost:8080/api/v1/transactions" -H "Authorization: Bearer $TOKEN" | jq

# Date range (inclusive both ends)
curl -s "http://localhost:8080/api/v1/transactions?from=2026-03-01&to=2026-03-31" \
  -H "Authorization: Bearer $TOKEN" | jq

# Combined filters, sorted and paged
curl -s "http://localhost:8080/api/v1/transactions?\
type=EXPENSE&categoryIds=5&categoryIds=7&minAmount=500&maxAmount=5000&\
search=amazon&page=0&size=50&sort=amount,desc" \
  -H "Authorization: Bearer $TOKEN" | jq

# Transactions with no category
curl -s "http://localhost:8080/api/v1/transactions?uncategorized=true" \
  -H "Authorization: Bearer $TOKEN" | jq

# Only rows that came from a CSV import
curl -s "http://localhost:8080/api/v1/transactions?source=CSV_IMPORT" \
  -H "Authorization: Bearer $TOKEN" | jq
```

| Parameter | Type | Notes |
|---|---|---|
| `from` / `to` | date | inclusive; max span 3660 days |
| `type` | enum | `INCOME` \| `EXPENSE` |
| `categoryIds` | repeatable | matches any |
| `uncategorized` | boolean | cannot be combined with `categoryIds` → `422` |
| `minAmount` / `maxAmount` | decimal | inclusive |
| `search` | string | case-insensitive substring of description **or** merchant |
| `source` | enum | `MANUAL` \| `CSV_IMPORT` |
| `page`, `size`, `sort` | | size capped at 200 |

```json
{
  "content": [
    { "id": 42, "type": "EXPENSE", "amount": 1249.50, "currency": "INR",
      "txnDate": "2026-03-14", "description": "Weekly groceries",
      "merchant": "Big Bazaar", "categoryId": 5, "categoryName": "Groceries",
      "source": "MANUAL", "createdAt": "2026-03-14T08:12:00Z" }
  ],
  "page": 0, "size": 20, "totalElements": 137, "totalPages": 7,
  "first": true, "last": false, "empty": false
}
```

The default sort is `txnDate DESC, id DESC`. The `id` tiebreaker is what makes
paging deterministic when several transactions share a date — without it, page 2
can repeat or skip a row.

A `search` value such as `' OR 1=1 --` is bound as a parameter by the Criteria
API and compared as a literal string; it matches nothing and alters nothing.

### `POST /api/v1/transactions`

```bash
curl -s -X POST http://localhost:8080/api/v1/transactions \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"type":"EXPENSE","amount":1249.50,"txnDate":"2026-03-14",
       "description":"Weekly groceries","merchant":"Big Bazaar",
       "notes":"Shared with flatmates","categoryId":5}' | jq
```

| Field | Rules |
|---|---|
| `type` | required, `INCOME` \| `EXPENSE` |
| `amount` | required, **> 0**, ≤ 4 decimal places |
| `txnDate` | required, `yyyy-MM-dd` |
| `description` | required, ≤ 255 |
| `merchant` | optional, ≤ 120 |
| `notes` | optional, ≤ 500 |
| `categoryId` | optional; must be visible to the caller and of a matching type |

| Failure | Code |
|---|---|
| `amount` ≤ 0, or > 4 decimals | `400 VALIDATION_FAILED` |
| Category not visible to the caller | `404 RESOURCE_NOT_FOUND` |
| EXPENSE under an INCOME category | `422 BUSINESS_RULE_VIOLATION` |

### `GET` / `PUT` / `DELETE /api/v1/transactions/{id}`

```bash
curl -s "http://localhost:8080/api/v1/transactions/42" -H "Authorization: Bearer $TOKEN" | jq

curl -s -X PUT "http://localhost:8080/api/v1/transactions/42" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"type":"EXPENSE","amount":1500.00,"txnDate":"2026-03-14",
       "description":"Weekly groceries (corrected)","categoryId":5}' | jq

curl -s -o /dev/null -w '%{http_code}\n' -X DELETE \
  "http://localhost:8080/api/v1/transactions/42" -H "Authorization: Bearer $TOKEN"   # 204
```

`PUT` is a full replacement — omitted optional fields become null. **`409
CONCURRENT_MODIFICATION`** if another request modified the row since this one
read it; re-read and retry.

Another user's id returns **`404`**, never `403`.

---

## Budgets

### `POST /api/v1/budgets`

```bash
# Category budget
curl -s -X POST http://localhost:8080/api/v1/budgets \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"month":"2026-03","amount":10000.00,"categoryId":5,"alertThresholdPct":80}' | jq

# Overall budget — omit categoryId
curl -s -X POST http://localhost:8080/api/v1/budgets \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"month":"2026-03","amount":60000.00}' | jq
```

```json
{
  "id": 3, "month": "2026-03",
  "categoryId": 5, "categoryName": "Groceries",
  "amount": 10000.00, "spent": 7350.75, "remaining": 2649.25,
  "utilisationPct": 73.51, "alertThresholdPct": 80, "status": "ON_TRACK"
}
```

`spent` is **measured on every read** from the month's transactions, never
accumulated in a counter. Status thresholds:

| | |
|---|---|
| `EXCEEDED` | `spent >= amount` |
| `WARNING` | `utilisationPct >= alertThresholdPct` (and not exceeded) |
| `ON_TRACK` | otherwise |

"At or above" the threshold rather than strictly above, so a budget sitting
exactly on its alert point does alert.

| Failure | Code |
|---|---|
| Budget already exists for that scope and month | `409` |
| `month` not `yyyy-MM` | `400` |
| `categoryId` is an INCOME category | `422` |

### `GET` / `PUT` / `DELETE /api/v1/budgets/{id}`, `GET /api/v1/budgets?month=`

```bash
curl -s "http://localhost:8080/api/v1/budgets?month=2026-03" -H "Authorization: Bearer $TOKEN" | jq
```

`month` defaults to the current UTC month.

`PUT` changes only `amount` and `alertThresholdPct`. Changing the month or the
category returns **`422`** — that is semantically a different budget, so the API
makes you delete and recreate rather than silently rewriting history.

---

## CSV imports

### `POST /api/v1/imports`

```bash
curl -s -X POST http://localhost:8080/api/v1/imports \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@samples/transactions-sample.csv" | jq
```

**Expected format.** `date`, `type`, `amount`, `description` are required;
`merchant` and `category` are optional.

```csv
date,type,amount,description,merchant,category
2026-03-01,EXPENSE,1249.50,Weekly groceries,Big Bazaar,Groceries
```

Header matching is case-insensitive, tolerates whitespace and a UTF-8 BOM, and
accepts aliases: `txn_date`/`transaction_date`/`posted_date`,
`memo`/`narration`/`particulars`, `payee`/`vendor`, `value`, `tag`.

| Accepted | |
|---|---|
| Dates | `yyyy-MM-dd`, `dd/MM/yyyy`, `dd-MM-yyyy`, `yyyy/MM/dd` — all **strictly** resolved, so `31/02/2026` is rejected rather than shifted |
| Types | `INCOME`/`CREDIT`/`CR`/`IN`/`DEPOSIT`, `EXPENSE`/`DEBIT`/`DR`/`OUT`/`WITHDRAWAL` |
| Amounts | `1,249.50`, `₹1249.50`, `-1249.50`, `(1249.50)` — the sign is discarded, since direction comes from `type` |

`MM/dd/yyyy` is **not** accepted. It is indistinguishable from `dd/MM/yyyy` for
the first twelve days of a month, and silently filing a March transaction in
December is worse than rejecting the file.

**Two success codes:**

| Code | Meaning |
|---|---|
| `200` | Processed inline (≤ `async-threshold-rows`, default 50). Final counts are in the body. |
| `202` | Accepted and durably stored. Poll `GET /api/v1/imports/{id}` until `terminal` is true. |

```json
{
  "id": 7, "filename": "march-statement.csv",
  "status": "COMPLETED_WITH_ERRORS", "terminal": true,
  "totalRows": 33, "importedRows": 30, "duplicateRows": 1, "failedRows": 2,
  "sizeBytes": 2184, "previouslyUploaded": false,
  "createdAt": "2026-10-14T06:12:44Z", "completedAt": "2026-10-14T06:12:45Z"
}
```

Once terminal, `totalRows = importedRows + duplicateRows + failedRows`.

| `status` | Meaning |
|---|---|
| `ACCEPTED` | Stored and queued. Nothing imported yet. |
| `PROCESSING` | A consumer has claimed it. |
| `COMPLETED` | Every row imported or recognised as a duplicate. |
| `COMPLETED_WITH_ERRORS` | Processed to the end; good rows **are** imported, bad rows listed. |
| `FAILED` | Could not process at all. Nothing imported. |

**Idempotency.** Uploading the identical file again imports nothing:

```bash
curl -s -X POST http://localhost:8080/api/v1/imports -H "Authorization: Bearer $TOKEN" \
  -F "file=@samples/transactions-sample.csv" | jq '{importedRows, duplicateRows, previouslyUploaded}'
# { "importedRows": 0, "duplicateRows": 33, "previouslyUploaded": true }
```

Each row carries `external_ref = sha256(fileChecksum + ':' + rowNumber)` under a
unique index. Renaming the file changes nothing — only the content counts. Two
identical rows *within* one file are both imported, because their row numbers
differ.

| Rejection | Code |
|---|---|
| Empty file | `400 INVALID_CSV` |
| Header missing a required column | `400 INVALID_CSV` (names the columns) |
| Header with no data rows | `400 INVALID_CSV` |
| Not `.csv` | `400 INVALID_CSV` |
| Unsupported content type | `415` |
| Over 5 MiB or 50 000 rows | `413 FILE_TOO_LARGE` |

A path-traversal filename such as `../../etc/passwd.csv` is sanitised to
`passwd.csv`.

### `GET /api/v1/imports/{id}` — poll status

```bash
# Poll until terminal
until curl -s "http://localhost:8080/api/v1/imports/7" -H "Authorization: Bearer $TOKEN" \
      | jq -e '.terminal' >/dev/null; do sleep 1; done
```

### `GET /api/v1/imports/{id}/errors`

```bash
curl -s "http://localhost:8080/api/v1/imports/7/errors?size=50" -H "Authorization: Bearer $TOKEN" | jq
```

```json
{
  "content": [
    { "rowNumber": 4, "errorCode": "INVALID_DATE",
      "message": "Could not read '31/02/2026' as a date. Use yyyy-MM-dd (for example 2026-03-14).",
      "rawRow": "31/02/2026,EXPENSE,500.00,Invalid date,Some Shop,Dining" }
  ],
  "page": 0, "size": 50, "totalElements": 2, "totalPages": 1
}
```

`rowNumber` counts the header as line 1, matching what a spreadsheet shows.

| `errorCode` | |
|---|---|
| `MISSING_COLUMN` | A required column was blank on this row |
| `INVALID_DATE` | Unparseable or impossible date |
| `INVALID_AMOUNT` | Non-numeric, zero, or too large |
| `INVALID_TYPE` | Not a recognised income/expense word |
| `MISSING_DESCRIPTION` | Blank description |
| `FIELD_TOO_LONG` | Exceeds the column width |
| `UNKNOWN_CATEGORY` | No such category for this account |
| `CATEGORY_TYPE_MISMATCH` | INCOME row filed under an EXPENSE category, or vice versa |

### `GET /api/v1/imports` — history, newest first

---

## Reports

All ranges inclusive on both ends. Defaults resolve in UTC. These are the cached
endpoints; a write by the same user evicts their entries immediately.

### `GET /api/v1/reports/summary`

```bash
curl -s "http://localhost:8080/api/v1/reports/summary?from=2026-03-01&to=2026-03-31" \
  -H "Authorization: Bearer $TOKEN" | jq
```

```json
{
  "from": "2026-03-01", "to": "2026-03-31",
  "totalIncome": 95000.00, "totalExpense": 61840.25,
  "netCashFlow": 33159.75, "transactionCount": 142,
  "expenseRatioPct": 65.09
}
```

A period with no transactions returns zeros, not nulls and not an error.
`expenseRatioPct` is `0.00` when income is zero — a month of spending before the
salary lands is a normal state, not a division-by-zero error.

### `GET /api/v1/reports/monthly`

```bash
curl -s "http://localhost:8080/api/v1/reports/monthly?from=2026-01&to=2026-12" \
  -H "Authorization: Bearer $TOKEN" | jq
```

Defaults to the trailing 12 months; maximum span 120 months.

The series is **dense**: every month in the range appears exactly once, with
zeros where there was no activity. SQL returns nothing for an empty month, and
leaving the client to detect gaps is how charts end up with misleading lines.

`averageMonthlyIncome` divides by the number of months **requested**, not the
number with activity — "average monthly spend over the last 12 months" means
dividing by 12.

### `GET /api/v1/reports/categories`

```bash
curl -s "http://localhost:8080/api/v1/reports/categories?type=EXPENSE&from=2026-03-01&to=2026-03-31&page=0&size=20" \
  -H "Authorization: Bearer $TOKEN" | jq
```

```json
{
  "from": "2026-03-01", "to": "2026-03-31", "type": "EXPENSE",
  "grandTotal": 61840.25,
  "categories": {
    "content": [
      { "categoryId": 5, "categoryName": "Groceries", "total": 18420.00,
        "percentageOfTotal": 29.78, "transactionCount": 34 },
      { "categoryId": null, "categoryName": "Uncategorized", "total": 2000.00,
        "percentageOfTotal": 3.23, "transactionCount": 4 }
    ],
    "page": 0, "size": 20, "totalElements": 12, "totalPages": 1
  }
}
```

`grandTotal` is the total across **all** categories, not just this page, so
`percentageOfTotal` means the same thing on page 2 as on page 1 and the values
across all pages add to 100.

Transactions with no category appear as `"Uncategorized"` with a null
`categoryId`. Dropping them would make the breakdown fail to sum to the period's
expense total — a discrepancy that is very hard to spot in a chart.

### `GET /api/v1/reports/budget-utilisation`

```bash
curl -s "http://localhost:8080/api/v1/reports/budget-utilisation?month=2026-03" \
  -H "Authorization: Bearer $TOKEN" | jq
```

Returns one entry per budget configured for the month, each with measured spend
and status. Empty list when no budgets exist. Identical shape to
`GET /api/v1/budgets?month=`, because both delegate to the same service — the two
endpoints can never disagree.

---

## Operational endpoints

| Endpoint | Auth |
|---|---|
| `GET /actuator/health` | public |
| `GET /actuator/health/liveness` · `/readiness` | public |
| `GET /actuator/info` | public |
| `GET /actuator/metrics` · `/prometheus` · `/flyway` | `ROLE_ADMIN` |

Health is public so an orchestrator can probe it without credentials; everything
else under `/actuator` exposes internals and requires the admin role.

```bash
curl -s http://localhost:8080/actuator/health | jq
curl -s http://localhost:8080/actuator/prometheus | grep finsight_
```
