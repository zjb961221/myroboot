#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

STAMP="$(date +%Y%m%d_%H%M%S)"
BACKUP_ROOT="${BACKUP_ROOT:-$ROOT_DIR/backups}"
TARGET="$BACKUP_ROOT/$STAMP"
mkdir -p "$TARGET"
chmod 700 "$BACKUP_ROOT" "$TARGET" 2>/dev/null || true

echo "[1/3] dumping MySQL..."
docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump -uroot --single-transaction --quick --routines --events --triggers --hex-blob --set-gtid-purged=OFF "$MYSQL_DATABASE"' | gzip -9 > "$TARGET/mysql.sql.gz"

echo "[2/3] archiving uploads and logs..."
DATA_ROOT="${DATA_ROOT:-$ROOT_DIR/data}"
[[ -d "$DATA_ROOT/uploads" ]] && tar -C "$DATA_ROOT" -czf "$TARGET/uploads.tar.gz" uploads
[[ -d "$DATA_ROOT/logs" ]] && tar -C "$DATA_ROOT" -czf "$TARGET/backend-logs.tar.gz" logs
[[ -d "$DATA_ROOT/nginx-logs" ]] && tar -C "$DATA_ROOT" -czf "$TARGET/nginx-logs.tar.gz" nginx-logs

echo "[3/3] archiving deployment configuration..."
CONFIG_ITEMS=(docker-compose.yml .env .env.example frontend/nginx.conf backend/src/main/resources/application.yml deploy/mysql/my.cnf)
EXISTING=()
for item in "${CONFIG_ITEMS[@]}"; do
  [[ -e "$item" ]] && EXISTING+=("$item")
done
if ((${#EXISTING[@]})); then
  tar -czf "$TARGET/config.tar.gz" "${EXISTING[@]}"
  chmod 600 "$TARGET/config.tar.gz" 2>/dev/null || true
fi

sha256sum "$TARGET"/* > "$TARGET/SHA256SUMS"
chmod 600 "$TARGET"/* 2>/dev/null || true

echo "Backup completed: $TARGET"
echo "Important: this is still on the same server. Copy backups off-host for real disaster recovery."
