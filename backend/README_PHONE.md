# RPG OS Backend 1.0.1

Domyślny model tekstowy: `gpt-5.2`.
Domyślny model obrazów: `gpt-image-1`.

Endpointy:
- GET /health
- GET /v1/openai/check
- POST /v1/gm/turn
- POST /v1/images/generate
- POST /v1/images/edit

Tryb diagnostyczny bez klucza:
`RPGOS_OFFLINE_MOCK=1 uvicorn app:app --host 127.0.0.1 --port 8000`
