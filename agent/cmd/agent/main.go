package main

import (
    "bytes"
    "encoding/json"
    "fmt"
    "io"
    "log"
    "net"
    "net/http"
    "os"
    "os/exec"
    "runtime"
    "strings"
    "time"
)

const version = "0.1.0"

type heartbeat struct {
    Hostname       string `json:"hostname"`
    OSName         string `json:"osName"`
    AgentVersion   string `json:"agentVersion"`
    PrivateIP      string `json:"privateIp"`
    DesktopSession string `json:"desktopSession"`
}

func main() {
    server := strings.TrimRight(os.Getenv("MYROBOOT_SERVER"), "/")
    agentID := strings.TrimSpace(os.Getenv("MYROBOOT_AGENT_ID"))
    token := strings.TrimSpace(os.Getenv("MYROBOOT_AGENT_TOKEN"))
    if server == "" || agentID == "" || token == "" {
        log.Fatal("MYROBOOT_SERVER, MYROBOOT_AGENT_ID and MYROBOOT_AGENT_TOKEN are required")
    }
    client := &http.Client{Timeout: 15 * time.Second}
    log.Printf("myroboot-agent %s starting, server=%s agentId=%s", version, server, agentID)
    for {
        if err := sendHeartbeat(client, server, agentID, token); err != nil {
            log.Printf("heartbeat failed: %v", err)
        }
        time.Sleep(30 * time.Second)
    }
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
