# Remote operations architecture

Phase 1 establishes the trust boundary before any shell or desktop control is enabled.

- Browser administrators manage registered Ubuntu agents at `/admin/remote`.
- Each agent receives an opaque `agent_id` and a 256-bit random token.
- Only SHA-256 token hashes are stored in MySQL; the clear token is returned once.
- Ubuntu agents initiate outbound HTTPS heartbeats to the control server.
- Agents are considered online when a valid heartbeat was received in the last 90 seconds.
- Agent lifecycle changes are written to `remote_audit_log`.
- Existing ticket/FAQ/user tables are not modified by this module.

Planned next layers reuse the same authenticated agent identity: WSS tunnel -> PTY terminal -> file transfer -> desktop relay. Do not expose SSH/VNC/RDP directly to the public network.
