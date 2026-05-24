#!/usr/bin/env sh
set -eu

APP_DIR="${APP_DIR:-/opt/robot-agent}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
ENV_FILE="${ENV_FILE:-.env}"

cd "$APP_DIR"

if [ ! -f "$ENV_FILE" ]; then
  echo "Environment file not found: $APP_DIR/$ENV_FILE" >&2
  exit 1
fi

if [ -n "${GHCR_USERNAME:-}" ] && [ -n "${GHCR_TOKEN:-}" ]; then
  printf '%s' "$GHCR_TOKEN" | docker login ghcr.io -u "$GHCR_USERNAME" --password-stdin >/dev/null
fi

docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" pull
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d --remove-orphans --no-build
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" ps
