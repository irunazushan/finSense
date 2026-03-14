# Prompt Lab

Streamlit tool for testing DeepSeek prompts with optional tool-calling simulation.

## Features

- Edit `system` and `user` prompt templates.
- Prefill prompts from `financial-coach-agent` templates.
- Run in two modes:
  - `Without tool calling`
  - `With tool calling` (dummy tools + local JSON dataset)
- Configure model settings:
  - `model`, `temperature`, `top_p`, `max_tokens`, `timeout`, `max tool iterations`
- Full run trace:
  - request payloads
  - response payloads
  - tool calls and tool results
  - latency and token usage
- Session run history (in-memory, no file persistence).

## Dummy Tool Contract

Supported dataset shapes:

- `{ "tools": { ... } }`
- `{ "adviceData": { "tools": { ... } } }`

Required arrays inside tools payload:

- `spendingByCategory`
- `monthlyDelta`
- `topMerchants`
- `spikes`

Implemented tool names:

- `getSpendingByCategory(userId, periodDays)`
- `getMonthlyDelta(userId, periodDays)`
- `getTopMerchants(userId, periodDays, limit)`
- `detectSpikes(userId, periodDays)`

## Local Run

```bash
cd tools/prompt-lab
python -m venv .venv
. .venv/Scripts/activate
pip install -r requirements.txt
streamlit run app.py
```

Open: `http://localhost:8501`

## Docker Run (Compose profile)

```bash
docker compose -f docker-compose.services.yml --profile prompt-lab up -d prompt-lab
```

Open: `http://localhost:8502`

## Environment Variables

- `DEEPSEEK_API_KEY`
- `LLM_API_BASE_URL` (default: `https://api.deepseek.com`)
- `LLM_MODEL` (default: `deepseek-chat`)
- `PROMPT_LAB_SERVER_PORT` (default in compose mapping: `8502`)

## Tests

```bash
python -m pytest tools/prompt-lab/tests -q
```
