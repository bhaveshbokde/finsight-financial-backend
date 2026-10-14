#!/usr/bin/env bash
#
# Generates a synthetic CSV large enough to take the asynchronous import path
# (more rows than finsight.csv-import.async-threshold-rows, default 50).
#
#   ./scripts/generate-large-csv.sh 500 samples/large-import.csv
#
set -euo pipefail

ROWS="${1:-500}"
OUT="${2:-samples/large-import.csv}"

CATEGORIES=(Groceries Dining Transport Utilities Entertainment Shopping Healthcare Subscriptions Travel Education)
MERCHANTS=("Big Bazaar" "DMart" "Uber" "MSEDCL" "PVR Cinemas" "Decathlon" "Apollo Pharmacy" "Netflix" "IRCTC" "Coursera")

mkdir -p "$(dirname "$OUT")"
printf 'date,type,amount,description,merchant,category\n' > "$OUT"

# One salary row per month so the reports have income to divide by.
for month in 01 02 03 04 05 06; do
  printf '2026-%s-01,INCOME,95000.00,Monthly salary,Lentra Technologies,Salary\n' "$month" >> "$OUT"
done

for ((i = 1; i <= ROWS; i++)); do
  month=$(printf '%02d' $(( (i % 6) + 1 )))
  day=$(printf '%02d' $(( (i % 28) + 1 )))
  cat_index=$(( i % ${#CATEGORIES[@]} ))
  amount=$(( (i * 37) % 9000 + 100 ))
  cents=$(printf '%02d' $(( (i * 13) % 100 )))

  printf '2026-%s-%s,EXPENSE,%d.%s,Synthetic transaction %d,%s,%s\n' \
    "$month" "$day" "$amount" "$cents" "$i" \
    "${MERCHANTS[$cat_index]}" "${CATEGORIES[$cat_index]}" >> "$OUT"
done

echo "Wrote $OUT"
echo "  data rows : $(( ROWS + 6 ))"
echo "  size      : $(du -h "$OUT" | cut -f1)"
echo "  sha256    : $(sha256sum "$OUT" | cut -d' ' -f1)"
