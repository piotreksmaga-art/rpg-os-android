#!/data/data/com.termux/files/usr/bin/bash
set -e
export RPGOS_OFFLINE_MOCK=1
exec uvicorn app:app --host 127.0.0.1 --port 8000
