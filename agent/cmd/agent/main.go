package main

import (
    "bytes"
    "context"
    "encoding/base64"
    "encoding/json"
    "fmt"
    "io"
    "log"
    "net"
    "net/http"
    "os"
    "os/exec"
    "regexp"
    "runtime"
    "strings"
    "sync"
    "time"

    "github.com/gorilla/websocket"
)

const version = "0.2.0"
const prompt = "myroboot> "

var safeUnit = regexp.MustCompile(`^[A-Za-z0-9_.@-]{1,100}$`)

type heartbeat struct {
    Hostname       string `json:"hostname"`
    OSName         string `json:"osName"`
    AgentVersion   string `json:"agentVersion"`
    PrivateIP      string `json:"privateIp"`
    DesktopSession string `json:"desktopSession"`
}

type wsMessage struct {
    Type      string `json:"type"`
    SessionID string `json:"sessionId,omitempty"`
    Data      string `json:"data,omitempty"`
    Cols      int    `json:"cols,omitempty"`
    Rows      int    `json:"rows,omitempty"`
    Message   string `json:"message,omitempty"`
}

type consoleSession struct {
    line strings.Builder
}

var (
    sessionsMu sync.Mutex
    sessions   = map[string]*consoleSession{}
    wsWriteMu  sync.Mutex
)

func main() {
    server := strings.TrimRight(os.Getenv("MYROBOOT_SERVER"), "/")
    agentID := strings.TrimSpace(os.Getenv("MYROBOOT_AGENT_ID"))
    token := strings.TrimSpace(os.Getenv("MYROBOOT_AGENT_TOKEN"))
    if server == "" || agentID == "" || token == "" {
        log.Fatal("MYROBOOT_SERVER, MYROBOOT_AGENT_ID and MYROBOOT_AGENT_TOKEN are required")
    }

    client := &http.Client{Timeout: 15 * time.Second}
    log.Printf("myroboot-agent %s starting, server=%s agentId=%s", version, server, agentID)
    go heartbeatLoop(client, server, agentID, token)
    realtimeLoop(server, agentID, token)
}

func heartbeatLoop(client *http.Client, server, agentID, token string) {
    for {
        if err := sendHeartbeat(client, server, agentID, token); err != nil {
            log.Printf("heartbeat failed: %v", err)
        }
        time.Sleep(30 * time.Second)
    }
}

func realtimeLoop(server, agentID, token string) {
    for {
        if err := runRealtime(server, agentID, token); err != nil {
            log.Printf("realtime channel disconnected: %v", err)
        }
        sessionsMu.Lock()
        sessions = map[string]*consoleSession{}
        sessionsMu.Unlock()
        time.Sleep(5 * time.Second)
    }
}

func runRealtime(server, agentID, token string) error {
    url := websocketURL(server) + "/api/remote/ws/agent"
    headers := http.Header{}
    headers.Set("X-Agent-Id", agentID)
    headers.Set("X-Agent-Token", token)
    dialer := websocket.Dialer{HandshakeTimeout: 15 * time.Second}
    conn, resp, err := dialer.Dial(url, headers)
    if err != nil {
        if resp != nil { return fmt.Errorf("websocket dial %s: %s", url, resp.Status) }
        return err
    }
    defer conn.Close()
    conn.SetReadLimit(1024 * 1024)
    log.Printf("realtime channel connected: %s", url)

    for {
        var msg wsMessage
        if err := conn.ReadJSON(&msg); err != nil { return err }
        switch msg.Type {
        case "terminal_open":
            openConsole(conn, msg.SessionID)
        case "terminal_input":
            handleConsoleInput(conn, msg.SessionID, msg.Data)
        case "terminal_close":
            closeConsole(conn, msg.SessionID)
        case "terminal_resize":
            // Reserved for the future PTY implementation. The diagnostic console is line based.
        }
    }
}

func openConsole(conn *websocket.Conn, sessionID string) {
    if sessionID == "" { return }
    sessionsMu.Lock()
    sessions[sessionID] = &consoleSession{}
    sessionsMu.Unlock()
    banner := "MYROBOOT Remote Diagnostic Console\r\n" +
        "当前为受控运维终端，只提供只读诊断命令。输入 help 查看可用命令。\r\n\r\n" + prompt
    sendOutput(conn, sessionID, banner)
}

func handleConsoleInput(conn *websocket.Conn, sessionID, data string) {
    sessionsMu.Lock()
    session := sessions[sessionID]
    sessionsMu.Unlock()
    if session == nil { return }

    for _, r := range data {
        switch r {
        case '\r', '\n':
            line := strings.TrimSpace(session.line.String())
            session.line.Reset()
            sendOutput(conn, sessionID, "\r\n")
            if line == "" {
                sendOutput(conn, sessionID, prompt)
                continue
            }
            if line == "exit" {
                closeConsole(conn, sessionID)
                return
            }
            output := executeSafeCommand(line)
            if output != "" {
                sendOutput(conn, sessionID, output)
                if !strings.HasSuffix(output, "\n") { sendOutput(conn, sessionID, "\r\n") }
            }
            sendOutput(conn, sessionID, prompt)
        case 3: // Ctrl+C
            session.line.Reset()
            sendOutput(conn, sessionID, "^C\r\n"+prompt)
        case 8, 127:
            current := []rune(session.line.String())
            if len(current) > 0 {
                session.line.Reset()
                session.line.WriteString(string(current[:len(current)-1]))
                sendOutput(conn, sessionID, "\b \b")
            }
        default:
            if r >= 32 && r != 127 {
                session.line.WriteRune(r)
                sendOutput(conn, sessionID, string(r))
            }
        }
    }
}

func closeConsole(conn *websocket.Conn, sessionID string) {
    sessionsMu.Lock()
    delete(sessions, sessionID)
    sessionsMu.Unlock()
    _ = writeWS(conn, wsMessage{Type: "terminal_closed", SessionID: sessionID})
}

func executeSafeCommand(line string) string {
    fields := strings.Fields(line)
    if len(fields) == 0 { return "" }
    switch fields[0] {
    case "help":
        return "可用命令:\r\n  help\r\n  clear\r\n  hostname\r\n  uptime\r\n  uname\r\n  df\r\n  free\r\n  ip addr\r\n  ip route\r\n  docker ps\r\n  systemctl status <service>\r\n  journalctl <service>\r\n  exit\r\n"
    case "clear":
        return "\x1b[2J\x1b[H"
    case "hostname":
        host, _ := os.Hostname()
        return host + "\r\n"
    case "uptime":
        if len(fields) == 1 { return runFixed("uptime") }
    case "uname":
        if len(fields) == 1 { return runFixed("uname", "-a") }
    case "df":
        if len(fields) == 1 { return runFixed("df", "-h") }
    case "free":
        if len(fields) == 1 { return runFixed("free", "-h") }
    case "ip":
        if len(fields) == 2 && fields[1] == "addr" { return runFixed("ip", "addr") }
        if len(fields) == 2 && fields[1] == "route" { return runFixed("ip", "route") }
    case "docker":
        if len(fields) == 2 && fields[1] == "ps" { return runFixed("docker", "ps", "--no-trunc") }
    case "systemctl":
        if len(fields) == 3 && fields[1] == "status" && safeUnit.MatchString(fields[2]) {
            return runFixed("systemctl", "status", fields[2], "--no-pager", "--full")
        }
    case "journalctl":
        if len(fields) == 2 && safeUnit.MatchString(fields[1]) {
            return runFixed("journalctl", "-u", fields[1], "-n", "200", "--no-pager", "--output=short-iso")
        }
    }
    return "该命令当前未开放。输入 help 查看允许的只读诊断命令。\r\n"
}

func runFixed(name string, args ...string) string {
    ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
    defer cancel()
    cmd := exec.CommandContext(ctx, name, args...)
    out, err := cmd.CombinedOutput()
    if len(out) > 1024*1024 { out = out[:1024*1024] }
    text := strings.ReplaceAll(string(out), "\n", "\r\n")
    if ctx.Err() == context.DeadlineExceeded { return text + "\r\n命令执行超时。\r\n" }
    if err != nil && text == "" { return "执行失败: " + err.Error() + "\r\n" }
    return text
}

func sendOutput(conn *websocket.Conn, sessionID, text string) {
    encoded := base64.StdEncoding.EncodeToString([]byte(text))
    _ = writeWS(conn, wsMessage{Type: "terminal_output", SessionID: sessionID, Data: encoded})
}

func writeWS(conn *websocket.Conn, value any) error {
    wsWriteMu.Lock()
    defer wsWriteMu.Unlock()
    return conn.WriteJSON(value)
}

func websocketURL(server string) string {
    if strings.HasPrefix(server, "https://") { return "wss://" + strings.TrimPrefix(server, "https://") }
    if strings.HasPrefix(server, "http://") { return "ws://" + strings.TrimPrefix(server, "http://") }
    return server
}

func sendHeartbeat(client *http.Client, server, agentID, token string) error {
    host, _ := os.Hostname()
    payload := heartbeat{Hostname: host, OSName: readOSName(), AgentVersion: version, PrivateIP: privateIP(), DesktopSession: desktopSession()}
    body, _ := json.Marshal(payload)
    req, err := http.NewRequest(http.MethodPost, server+"/api/remote/agent/heartbeat", bytes.NewReader(body))
    if err != nil { return err }
    req.Header.Set("Content-Type", "application/json")
    req.Header.Set("X-Agent-Id", agentID)
    req.Header.Set("X-Agent-Token", token)
    res, err := client.Do(req)
    if err != nil { return err }
    defer res.Body.Close()
    if res.StatusCode/100 != 2 {
        b, _ := io.ReadAll(io.LimitReader(res.Body, 2048))
        return fmt.Errorf("server returned %s: %s", res.Status, strings.TrimSpace(string(b)))
    }
    return nil
}

func readOSName() string {
    b, err := os.ReadFile("/etc/os-release")
    if err != nil { return runtime.GOOS }
    for _, line := range strings.Split(string(b), "\n") {
        if strings.HasPrefix(line, "PRETTY_NAME=") { return strings.Trim(strings.TrimPrefix(line, "PRETTY_NAME="), "\"") }
    }
    return runtime.GOOS
}

func privateIP() string {
    ifaces, err := net.Interfaces(); if err != nil { return "" }
    for _, iface := range ifaces {
        if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 { continue }
        addrs, _ := iface.Addrs()
        for _, addr := range addrs {
            var ip net.IP
            switch v := addr.(type) { case *net.IPNet: ip=v.IP; case *net.IPAddr: ip=v.IP }
            if ip != nil && !ip.IsLoopback() && ip.To4()!=nil && ip.IsPrivate() { return ip.String() }
        }
    }
    return ""
}

func desktopSession() string {
    if v := strings.TrimSpace(os.Getenv("XDG_SESSION_TYPE")); v != "" { return v }
    if _, err := exec.LookPath("loginctl"); err != nil { return "unknown" }
    out, err := exec.Command("loginctl", "list-sessions", "--no-legend").Output(); if err != nil { return "unknown" }
    for _, line := range strings.Split(string(out), "\n") {
        fields := strings.Fields(line); if len(fields)==0 { continue }
        kind, err := exec.Command("loginctl", "show-session", fields[0], "-p", "Type", "--value").Output(); if err != nil { continue }
        t := strings.TrimSpace(string(kind)); if t=="wayland" || t=="x11" { return t }
    }
    return "none"
}
