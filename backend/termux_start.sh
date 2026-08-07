#!/data/data/com.termux/files/usr/bin/bash
set -e
if [ -f .env ]; then
  set -a
  . ./.env
  set +a
fi
exec uvicorn app:app --host 127.0.0.1 --port 8000
