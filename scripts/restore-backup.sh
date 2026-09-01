#!/usr/bin/env bash
set -Eeuo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: RESTORE_CONFIRM=YES bash scripts/restore-backup.sh /path/to/backups/YYYYmmdd_HHMMSS" >&2
  exit 2
fi
if [[ "${RESTORE_CONFIRM:-}" != "YES" ]]; then
  echo "Refusing destructive restore. Re-run with RESTORE_CONFIRM=YES after verifying the backup path." >&2
  exit 3
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

dotenv_value() {
  local key="$1"
  [[ -f "$ROOT_DIR/.env" ]] || return 0
  sed -n "s/^${key}=//p" "$ROOT_DIR/.env" | tail -n 1 | sed -e 's/^"//' -e 's/"$//' -e "s/^'//" -e "s/'$//"
}

resolve_path() {
  local value="$1"
  [[ "$value" = /* ]] && printf '%s\n' "$value" || printf '%s/%s\n' "$ROOT_DIR" "${value#./}"
}

BACKUP_DIR="$(realpath "$1")"
DATA_ROOT="${DATA_ROOT:-$(dotenv_value DATA_ROOT)}"
DATA_ROOT="$(resolve_path "${DATA_ROOT:-./data}")"

[[ -f "$BACKUP_DIR/mysql.sql.gz" ]] || { echo "mysql.sql.gz not found" >&2; exit 4; }
if [[ -f "$BACKUP_DIR/SHA256SUMS" ]]; then
  (cd "$BACKUP_DIR" && sha256sum -c SHA256SUMS)
fi

mkdir -p "$DATA_ROOT/uploads" "$DATA_ROOT/logs" "$DATA_ROOT/nginx-logs"

echo "Stopping application containers; MySQL stays online for logical restore..."
docker compose stop frontend backend || true

# Recreate the configured application database before importing, avoiding
# duplicate-row/schema conflicts from the current database contents.
echo "Recreating MySQL application database..."
docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -e "DROP DATABASE IF EXISTS \`$MYSQL_DATABASE\`; CREATE DATABASE \`$MYSQL_DATABASE\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"'

echo "Restoring MySQL dump..."
gzip -dc "$BACKUP_DIR/mysql.sql.gz" | docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot "$MYSQL_DATABASE"'

if [[ -f "$BACKUP_DIR/uploads.tar.gz" ]]; then
  echo "Restoring uploads..."
  find "$DATA_ROOT/uploads" -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +
  tar -C "$DATA_ROOT" -xzf "$BACKUP_DIR/uploads.tar.gz"
fi

# Logs are diagnostic history, not application state. Restore only when present.
[[ -f "$BACKUP_DIR/backend-logs.tar.gz" ]] && tar -C "$DATA_ROOT" -xzf "$BACKUP_DIR/backend-logs.tar.gz"
[[ -f "$BACKUP_DIR/nginx-logs.tar.gz" ]] && tar -C "$DATA_ROOT" -xzf "$BACKUP_DIR/nginx-logs.tar.gz"

echo "Starting application containers..."
docker compose up -d

echo "Restore completed. Verify: docker compose ps && curl -fsS http://127.0.0.1:${FRONTEND_PORT:-8088}/api/health"
echo "config.tar.gz is intentionally NOT auto-restored because it may contain .env secrets; inspect/extract it manually if needed."
