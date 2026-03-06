# Transaction Tester

Streamlit tool for generating synthetic transactions and publishing them to Kafka topic `raw-transactions`.

## Features

- Generate `users_count * tx_per_user` events with exact payload contract:
  - `transactionId`, `userId`, `amount`, `description`, `merchantName`, `mccCode`, `timestamp`
- Configure category mix with:
  - explicit per-category counts
  - optional random fill for remaining volume
- Optional ambiguous low-signal events to exercise ML to LLM fallback path.
- Optional post-send verification through Core API:
  - `GET /api/v1/users/{userId}/transactions`
  - aggregated status and category summary
- Works in both runtimes:
  - host (`localhost:29092`, `http://localhost:8080`)
  - Docker network (`kafka:9092`, `http://core:8080`)

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

