#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

dotenv_value() {
  local key="$1"
  [[ -f "$ROOT_DIR/.env" ]] || return 0
  sed -n "s/^${key}=//p" "$ROOT_DIR/.env" | tail -n 1 | sed -e 's/^"//' -e 's/"$//' -e "s/^'//" -e "s/'$//"
}

DATA_ROOT="${DATA_ROOT:-$(dotenv_value DATA_ROOT)}"
DATA_ROOT="${DATA_ROOT:-./data}"
[[ "$DATA_ROOT" = /* ]] || DATA_ROOT="$ROOT_DIR/${DATA_ROOT#./}"
mkdir -p "$DATA_ROOT/mysql" "$DATA_ROOT/uploads" "$DATA_ROOT/logs" "$DATA_ROOT/nginx-logs"

find_volume() {
  local suffix="$1"
  docker volume ls --format '{{.Name}}' | awk -v s="$suffix" '$0 == s || $0 ~ ("_" s "$") {print; exit}'
}

copy_volume() {
  local suffix="$1"
  local target="$2"
  local volume
  volume="$(find_volume "$suffix" || true)"
  if [[ -z "$volume" ]]; then
    echo "[skip] no Docker named volume found for $suffix"
    return 0
  fi

  local mountpoint
  mountpoint="$(docker volume inspect -f '{{.Mountpoint}}' "$volume")"
  if [[ -z "$mountpoint" || ! -d "$mountpoint" ]]; then
    echo "[error] cannot resolve mountpoint for volume $volume" >&2
    exit 1
  fi

  if find "$target" -mindepth 1 -print -quit | grep -q .; then
    echo "[error] target is not empty: $target" >&2
    echo "        refusing to merge automatically; move/backup it first." >&2
    exit 1
  fi

  echo "[copy] $volume -> $target"
  cp -a "$mountpoint"/. "$target"/
}

echo "Using DATA_ROOT=$DATA_ROOT"
echo "Stopping MYROBOOT containers before copying persistent data..."
docker compose down

copy_volume mysql_data "$DATA_ROOT/mysql"
copy_volume upload_data "$DATA_ROOT/uploads"
copy_volume log_data "$DATA_ROOT/logs"

# MySQL image normally owns /var/lib/mysql as uid/gid 999. Preserve copied
# ownership; for a fresh empty directory, give MySQL permission up front.
if [[ -z "$(find "$DATA_ROOT/mysql" -mindepth 1 -print -quit)" ]]; then
  chown 999:999 "$DATA_ROOT/mysql" || true
fi

chmod 700 "$DATA_ROOT/mysql" 2>/dev/null || true
chmod 750 "$DATA_ROOT/uploads" "$DATA_ROOT/logs" "$DATA_ROOT/nginx-logs" 2>/dev/null || true

echo
echo "Migration copy complete. Named volumes were NOT deleted."
echo "Start the new bind-mount deployment with: docker compose up -d --build"
echo "Verify the application and data first; only then consider removing old named volumes manually."
