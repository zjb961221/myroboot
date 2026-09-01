# Remote operations security model

## Trust boundaries

- Browser authenticates to the MYROBOOT control plane with the normal admin session.
- Browser never connects directly to a mine-side host.
- Agent authenticates with an independent random Agent ID/token over the outbound realtime channel.
- Production transport must use HTTPS/WSS. Plain HTTP/WS is for isolated testing only.
- Agent token is stored only in `/etc/myroboot-agent/agent.env` with mode `0600`; the server stores only its SHA-256 digest.

## File manager policy

The file manager intentionally does not expose the whole filesystem.

Readable roots: `/home`, `/opt`, `/tmp`, `/var/log`.
Writable roots: `/home`, `/opt`, `/tmp`.

`/etc`, `/root`, `/boot`, `/proc`, `/sys`, `/dev`, `/run` and other paths are not remotely browsable through the file API. Uploads are limited to 50 MiB per file in the current request/response implementation. File content is never written to the remote audit log; only operation type and normalized path are recorded.

The systemd sandbox keeps `ProtectSystem=strict` and makes only `/home`, `/opt`, and `/tmp` writable. This fixes uploads without removing the service sandbox.

## Terminal policy

The browser terminal uses a short-lived, one-time ticket. The Agent exposes only an allowlisted diagnostic command set. Terminal keystrokes and command output are not persisted in the audit log.

## Availability

Agent heartbeat and realtime WebSocket are independent. A heartbeat can remain healthy while the realtime channel reconnects. File operations fail fast when the realtime channel is unavailable or the Agent version is too old, rather than waiting for a generic HTTP timeout.

The current file transfer implementation is appropriate for small/medium operational files. Large-file support should use chunked binary transfer with per-chunk acknowledgement, cancellation/resume, SHA-256 verification, backpressure, and transfer progress rather than Base64 JSON payloads.
