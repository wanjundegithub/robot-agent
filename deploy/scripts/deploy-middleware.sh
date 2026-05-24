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

docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d --no-build mysql redis pgvector
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" ps mysql redis pgvector
