# Transaction Tester

Streamlit tool for generating synthetic transactions and publishing them to Kafka topic `raw-transactions`.

## Features

- Generate `users_count * tx_per_user` events with exact payload contract:
  - `transactionId`, `userId`, `amount`, `description`, `merchantName`, `mccCode`, `timestamp`
- Configure category mix with:
  - explicit per-category counts
  - optional random fill for remaining volume
- Optional target user UUID in Generator:
  - all generated events are sent to this exact `userId`
- Optional ambiguous low-signal events to exercise ML to LLM fallback path.
- Optional low-confidence classified events to exercise ML to LLM fallback path with defined categories.
- Optional post-send verification through Core API:
  - `GET /api/v1/users/{userId}/transactions`
  - aggregated status and category summary
- Transactions Explorer (read-only):
  - choose generated user IDs from current session or paste manual user UUID
  - server-side filters: `category`, `status`, `from`, `to`, `page`, `size`, load-all-pages
  - client-side filters: amount range, merchant contains, MCC exact, description contains
  - status/category aggregates on filtered result set
- Works in both runtimes:
  - host (`localhost:29092`, `http://localhost:8080`)
  - Docker network (`kafka:9092`, `http://core:8080`)

## Explorer Notes

- Explorer is read-only and does not mutate transactions.
- "Load all pages" iterates through Core pages (`size` up to 200) until the last page.
- Client-side filters are applied after server fetch and shown in the filtered table/aggregates.
- If Core returns empty/non-JSON/error body, explorer shows a descriptive API error message.

## Generator Signal Profiles

Generator now supports three profiles:

- `normal`:
  - regular category-aligned generation (often high ML confidence)
- `low_confidence`:
  - forces a valid MCC for selected category
  - injects conflicting keywords from other categories
  - intended to produce classified transactions with confidence below `0.9`
- `ambiguous`:
  - low-signal text with weak/no category evidence
  - may be classified as `UNDEFINED`

Controls in UI:

- `Inject ambiguous low-signal transactions` + `Ambiguous ratio`
- `Inject low-confidence classified transactions` + `Low-confidence ratio` (default `0.40`)

Validation:

- each ratio must be in `[0.0, 1.0]`
- `ambiguous_ratio + low_confidence_ratio <= 1.0`
- remaining share is generated as `normal`
- if a selected category has no MCC templates in the loaded rules, requested `low_confidence` events fall back to `normal` and a warning is shown in the summary

## Local Run

```bash
cd tools/transaction-tester
python -m venv .venv
. .venv/Scripts/activate
pip install -r requirements.txt
streamlit run app.py
```

Open: `http://localhost:8501`

## Docker Run (Compose profile)

```bash
docker compose -f docker-compose.services.yml --profile tester up -d tester
```

Open: `http://localhost:8501`

## Tests

```bash
python -m pytest tools/transaction-tester/tests -q
```
