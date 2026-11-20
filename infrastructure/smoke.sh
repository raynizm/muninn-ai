#!/bin/sh
# Smoke test for the compose stack. Run after `docker compose -f infrastructure/docker-compose.yml up -d --build`.
# Checks that the backend answers and that the first-run setup token is in its log.
set -eu

BASE_URL="${MUNINN_URL:-http://localhost:${MUNINN_PORT:-8080}}"
COMPOSE="docker compose -f $(dirname "$0")/docker-compose.yml"

echo "waiting for $BASE_URL/api/setup/status ..."
i=0
until body=$(curl -sf "$BASE_URL/api/setup/status"); do
  i=$((i + 1))
  if [ "$i" -ge 60 ]; then
    echo "FAIL: backend did not answer" >&2
    exit 1
  fi
  sleep 2
done

if [ "$body" != '{"complete":false}' ]; then
  echo "FAIL: expected {\"complete\":false}, got $body" >&2
  exit 1
fi

if ! $COMPOSE logs backend 2>&1 | grep -q "SETUP TOKEN"; then
  echo "FAIL: no setup token in backend log" >&2
  exit 1
fi

echo "OK: backend up, setup incomplete, setup token logged"
