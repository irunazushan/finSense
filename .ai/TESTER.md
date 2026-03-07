# Transaction Tester Context (for AI handoff)

<tester_project>
Python Streamlit tool at `tools/transaction-tester` for:
1) generating synthetic Kafka `raw-transactions`,
2) exploring stored transactions via Core API (read-only).
</tester_project>

<important_paths>
- `tools/transaction-tester/app.py` - Streamlit UI (Generator + Transactions Explorer tabs)
- `tools/transaction-tester/generator.py` - synthetic transaction generation
- `tools/transaction-tester/core_client.py` - Core API calls + pagination + filtering helpers (HTTP via `http.client`)
- `tools/transaction-tester/models.py` - dataclasses/contracts used by UI/client/generator
- `tools/transaction-tester/publisher.py` - Kafka producer
- `tools/transaction-tester/tests/` - pytest suite
</important_paths>

<runtime_modes>
- Host mode: Kafka `localhost:29092`, Core `http://localhost:8080`
- Docker mode: Kafka `kafka:9092`, Core `http://core:8080`
- Custom mode via env:
  - `TESTER_BOOTSTRAP_SERVERS`
  - `TESTER_CORE_BASE_URL`
  - `TESTER_TOPIC`
</runtime_modes>

<generator_flow>
- Generates payload contract:
  `{ transactionId, userId, amount, description, merchantName, mccCode, timestamp }`
- Topic: `raw-transactions`
- Supports:
  - `users_count * tx_per_user`
  - per-category counts + optional random fill
  - ambiguous low-signal transactions
  - optional fixed `target_user_id` (all generated tx for one user UUID)
- Category source of truth:
  - `classifier-service/classifier-rules.yaml`
  - enum alignment from `TransactionCategory.java`, excluding `UNDEFINED`
</generator_flow>

<explorer_flow>
- Uses Core endpoint:
  `GET /api/v1/users/{userId}/transactions`
- Server-side filters (query params):
  `category`, `status`, `from`, `to`, `page`, `size`
- Client-side filters (post-fetch):
  amount range, merchant contains, mcc exact, description contains
- "Load all pages":
  loops pages until final page (`len(items) < size`)
</explorer_flow>

<core_client_behavior>
- `fetch_user_transactions_page(...)` validates:
  - HTTP status
  - non-empty body
  - valid JSON array
- Transactions fetch transport is now `http.client` + `urllib.parse` + `json`.
- `session` argument is kept in signatures for compatibility, but ignored in current HTTP flow.
- Empty body error message includes full URL for diagnosis.
- `fetch_user_transactions_all(...)` tolerates empty body only on later pages
  (treats as pagination end), not on initial page.
</core_client_behavior>

<runbook>
Local (recommended):
```powershell
cd tools/transaction-tester
.\.venv\Scripts\python.exe -m streamlit run app.py --server.address 127.0.0.1 --server.port 8501
```

If behavior is inconsistent:
1) stop all tester processes/containers,
2) ensure exactly one app instance is running,
3) ensure `Run mode = Host` and `Core base URL = http://localhost:8080` for local Core.
</runbook>

<tests>
- Core client tests: `tools/transaction-tester/tests/test_core_client.py`
- Generator tests: `tools/transaction-tester/tests/test_generator.py`
- Typical run:
  `.\tools\transaction-tester\.venv\Scripts\python.exe -m pytest tools/transaction-tester/tests -q`
- Note: if core-client tests mock `requests.Session` for transaction fetch,
  they may need adaptation because fetch transport changed to `http.client`.
</tests>

<user_rules_note>
Repository-specific rule in `./.ai/RULES.md`:
- do not auto-edit without plan confirmation,
- edit one file at a time and show diff.
</user_rules_note>
