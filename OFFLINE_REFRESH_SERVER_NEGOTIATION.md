# Offline Refresh Server Negotiation Contract

## Goal
Support one client refresh flow that works in both cases:
1. Client is already connected (command channel available).
2. Client is not connected (standalone HTTP refresh only).

The Android client now treats HTTP refresh as the primary standalone path and uses command-channel refresh only when available.

## Required server capabilities

### 1. HTTP refresh endpoints
Server must support at least one of these refresh endpoints for a recording:

1. Token endpoint:
- `POST /api/transfers/{session_token}/refresh`

2. Generic endpoint:
- `POST /api/transfers/refresh`

For compatibility, supporting both is strongly recommended.

### 2. Accepted request body fields
Server should accept this body shape (field aliases included for compatibility):

```json
{
  "recording_id": "12163322",
  "mediaFileID": "12163322",
  "correlation_id": "uuid",
  "reason": "forced_fresh_manifest",
  "bytes_transferred": 1598486013,
  "session_token": "optional-token",
  "ng_client_id": "optional-client-id",
  "clientId": "optional-client-id"
}
```

Notes:
1. `session_token` may be omitted on generic endpoint attempts.
2. Server should not require command channel identity to accept refresh.
3. Unknown fields should be ignored, not rejected.

### 3. Success response contract (HTTP 2xx)
On success, server should return a JSON ACK equivalent to command-channel `TRANSFER_SESSION_ACK` with at minimum:

1. `recording_id` (or `mediaFileID`)
2. `session_token` (new token when reauth occurs)
3. `download_url` (or `download_path`)
4. `offline_metadata_url` (preferred absolute URL)
5. `offline_metadata_path` (relative path alias)
6. `offline_inline_level` (`core` or `full`)
7. `offline` object (manifest v1 block)

Recommended additional fields:
1. `resume_from_offset`
2. `reconnect_grace_seconds`
3. `expires_in_seconds`
4. `accepted_policy`
5. `recent_reason_codes`

### 4. Error semantics
Use actionable status/error semantics:

1. `404` token endpoint means token is stale/unknown.
2. Generic endpoint should still be able to issue a new session for known `recording_id`.
3. `400` should include machine-readable error code in body (for example `TRANSFER_TOKEN_INVALID`, `SESSION_EXPIRED`, `BAD_REQUEST_SCHEMA`).

If generic endpoint returns `400` for both payload variants (with and without `session_token`), standalone refresh fails.

### 5. Offline manifest requirements
Returned `offline` manifest must be parseable as manifest v1.

For artwork refresh support:
1. If artwork exists, include entries in `offline.artwork` (top-level) and/or `offline.assets.images`.
2. Each artwork item must include a resolvable `url`.
3. Person/cast artwork is valid and should be returned as provided.

## Negotiation behavior expected by client

### Connected mode
1. Client may post command-channel refresh request.
2. Server responds via command event `TRANSFER_SESSION_ACK`.
3. Client merges ACK and fetches full manifest + selected sidecars.

### Standalone mode
1. Client attempts token endpoint.
2. If token endpoint fails, client attempts generic endpoint (without token and with token payload variants).
3. On any HTTP ACK success, client continues normal manifest + sidecar flow.
4. If all HTTP attempts fail and no command channel exists, refresh is marked failed.

## Compatibility checklist for server team

1. Accept `recording_id` and `mediaFileID` aliases.
2. Accept generic refresh without requiring valid old token.
3. Return fresh `session_token` and `offline_metadata_url` in HTTP ACK.
4. Return `offline` manifest block in ACK when possible.
5. Provide structured error code for non-2xx responses.
6. Keep command-channel ACK behavior unchanged for connected clients.

## Observed current gap
Recent runtime evidence shows:
1. `POST /api/transfers/{token}/refresh` -> `404`
2. `POST /api/transfers/refresh` -> `400` (with and without `session_token`)

This indicates standalone HTTP refresh is currently unsupported for this request contract and must be aligned to the above contract.
