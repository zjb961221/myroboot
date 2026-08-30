# MYROBOOT Remote Agent

Ubuntu 被控端 Agent。当前阶段提供：

- 每台服务器独立 Agent ID / Token
- Agent 主动通过 HTTPS 向控制端发送心跳
- 上报主机名、Ubuntu 版本、私网 IP、桌面会话类型
- systemd 自动启动和断线自动重试

## 编译

```bash
go build -trimpath -ldflags="-s -w" -o myroboot-agent ./cmd/agent
```

交叉编译 Ubuntu amd64：

```bash
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags="-s -w" -o myroboot-agent ./cmd/agent
```

## 安装

先在管理后台 `/admin/remote` 创建服务器，复制一次性配置，然后：

```bash
export MYROBOOT_SERVER='https://your-support-domain'
export MYROBOOT_AGENT_ID='...'
export MYROBOOT_AGENT_TOKEN='...'
sudo -E BIN_SOURCE=./myroboot-agent ./install.sh
```

检查：

```bash
systemctl status myroboot-agent
journalctl -u myroboot-agent -f
```

生产环境建议使用 HTTPS。下一阶段将在同一身份模型上增加 WSS 隧道、PTY 终端、文件传输和 Ubuntu 桌面共享。
