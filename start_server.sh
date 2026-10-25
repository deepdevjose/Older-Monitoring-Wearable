#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

: "${SENSOR_API_KEY:?Define SENSOR_API_KEY (ver .env.example)}"
export SENSOR_API_KEY

exec uvicorn main:app \
  --host 10.238.118.117 \
  --port 8000 \
  --ssl-keyfile certs/server.key \
  --ssl-certfile certs/server.crt \
  --log-level info
