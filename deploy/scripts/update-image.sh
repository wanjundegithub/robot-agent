#!/usr/bin/env sh
set -eu

KEY="${1:?Usage: update-image.sh <ENV_KEY> <IMAGE>}"
VALUE="${2:?Usage: update-image.sh <ENV_KEY> <IMAGE>}"
ENV_FILE="${ENV_FILE:-.env}"

if [ ! -f "$ENV_FILE" ]; then
  echo "Environment file not found: $ENV_FILE" >&2
  exit 1
fi

case "$KEY" in
  FRONTEND_IMAGE|JAVA_BACKEND_IMAGE|PYTHON_AI_IMAGE) ;;
  *)
    echo "Unsupported image key: $KEY" >&2
    exit 1
    ;;
esac

if grep -q "^${KEY}=" "$ENV_FILE"; then
  sed -i "s|^${KEY}=.*|${KEY}=${VALUE}|" "$ENV_FILE"
else
  printf '\n%s=%s\n' "$KEY" "$VALUE" >> "$ENV_FILE"
fi
