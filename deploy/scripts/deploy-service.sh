#!/usr/bin/env sh
set -eu

SERVICE_NAME="${1:?Usage: deploy-service.sh <frontend|java-backend|python-ai|all|middleware>}"
APP_DIR="${APP_DIR:-/opt/robot-agent}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
ENV_FILE="${ENV_FILE:-.env}"

cd "$APP_DIR"

if [ ! -f "$COMPOSE_FILE" ]; then
  echo "Compose file not found: $APP_DIR/$COMPOSE_FILE" >&2
  exit 1
fi

if [ ! -f "$ENV_FILE" ]; then
  echo "Environment file not found: $APP_DIR/$ENV_FILE" >&2
  exit 1
fi

if [ -n "${GHCR_USERNAME:-}" ] && [ -n "${GHCR_TOKEN:-}" ]; then
  printf '%s' "$GHCR_TOKEN" | docker login ghcr.io -u "$GHCR_USERNAME" --password-stdin >/dev/null
fi

case "$SERVICE_NAME" in
  all)
    docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" pull
    docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d --remove-orphans --no-build
    docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" ps
    ;;
  middleware)
    docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d --no-build mysql redis pgvector
    docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" ps mysql redis pgvector
    ;;
  frontend|java-backend|python-ai)
    docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" pull "$SERVICE_NAME"
    docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d --no-deps --no-build "$SERVICE_NAME"
    docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" ps "$SERVICE_NAME"
    ;;
  *)
    echo "Unsupported service: $SERVICE_NAME" >&2
    exit 1
    ;;
esac
