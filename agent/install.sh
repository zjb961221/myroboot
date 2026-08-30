#!/usr/bin/env bash
set -euo pipefail

BIN_SOURCE=${BIN_SOURCE:-./myroboot-agent}
INSTALL_BIN=${INSTALL_BIN:-/usr/local/bin/myroboot-agent}
CONFIG_DIR=${CONFIG_DIR:-/etc/myroboot-agent}
ENV_FILE="$CONFIG_DIR/agent.env"
SERVICE_FILE=/etc/systemd/system/myroboot-agent.service

if [[ $EUID -ne 0 ]]; then
  echo "请使用 root 执行：sudo $0" >&2
  exit 1
fi
if [[ ! -f "$BIN_SOURCE" ]]; then
  echo "未找到 Agent 二进制：$BIN_SOURCE" >&2
  echo "先在 agent 目录执行：go build -o myroboot-agent ./cmd/agent" >&2
  exit 1
fi
: "${MYROBOOT_SERVER:?请设置 MYROBOOT_SERVER，例如 https://support.example.com}"
: "${MYROBOOT_AGENT_ID:?请设置 MYROBOOT_AGENT_ID}"
: "${MYROBOOT_AGENT_TOKEN:?请设置 MYROBOOT_AGENT_TOKEN}"

install -m 0755 "$BIN_SOURCE" "$INSTALL_BIN"
install -d -m 0700 "$CONFIG_DIR"
cat > "$ENV_FILE" <<EOF
MYROBOOT_SERVER=$MYROBOOT_SERVER
MYROBOOT_AGENT_ID=$MYROBOOT_AGENT_ID
MYROBOOT_AGENT_TOKEN=$MYROBOOT_AGENT_TOKEN
EOF
chmod 0600 "$ENV_FILE"
cat > "$SERVICE_FILE" <<'EOF'
[Unit]
Description=MYROBOOT Remote Agent
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
EnvironmentFile=/etc/myroboot-agent/agent.env
ExecStart=/usr/local/bin/myroboot-agent
Restart=always
RestartSec=5
NoNewPrivileges=true
ProtectHome=read-only
ProtectSystem=strict
PrivateTmp=true
ReadWritePaths=/tmp

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable --now myroboot-agent
systemctl --no-pager --full status myroboot-agent || true

echo "安装完成。日志：journalctl -u myroboot-agent -f"
