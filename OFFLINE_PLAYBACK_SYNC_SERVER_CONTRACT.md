# Offline Playback State Sync Server Contract

This document describes the server-side endpoint expected by the offline-capable Android miniclient when it reconciles local playback state with the SageTV server.

The current client implementation is best-effort. It posts completed local recordings after the NG client connects, before a user-initiated single-recording metadata refresh, when offline playback progress is saved, when the user toggles Watched in offline options, and when the user explicitly chooses Start at Beginning. New, queued, or in-progress downloads are excluded from bulk reconciliation. Until the server implements this endpoint, the client tolerates `404`, `405`, and `501` responses without disrupting playback.

## Endpoint

`POST /api/offline/playback-state-sync`

The endpoint should live on the same HTTP control-plane host and port used for offline manifests, transfer refresh, and companion sidecar files.

## Headers

The client sends:

- `Content-Type: application/json; charset=utf-8`
- `Accept: application/json`
- `X-SageTV-Offline-Sync: playback-state-v1`
- `X-Transfer-Token: <token>` when a download/session token is available
- `X-Correlation-ID: <correlation id>` when a correlation id is available
- `x-ng-client-id: <client id>` when the NG client id is available

The server should accept authentication from the session token in either the header or JSON body. The token must identify a valid offline/download session for the requested recording or media file.

## Request Body

```json
{
  "schema_version": 1,
  "updated_at_ms": 1780947600000,
  "reason": "ng_connect",
  "session_token": "optional-session-token",
  "correlation_id": "optional-correlation-id",
  "account_family": "optional-account-family",
  "client_id": "optional-client-id",
  "recordings": [
    {
      "media_file_id": "123456",
      "resume_position_ms": 1234567,
      "watched": false,
      "updated_at_ms": 1780947600000,
      "session_token": "optional-per-recording-session-token",
      "correlation_id": "optional-per-recording-correlation-id",
      "account_family": "optional-per-recording-account-family"
    }
  ]
}
```

Required fields:

- `schema_version`: currently `1`.
- `updated_at_ms`: client wall-clock timestamp in milliseconds.
- `recordings`: array of completed local recordings to reconcile.

Each `recordings[]` item requires:

- `media_file_id`: SageTV media file id for the downloaded item.
- `resume_position_ms`: non-negative playback position in milliseconds. A value below 3000 should be treated as `0`.
- `watched`: current offline watched state.
- `updated_at_ms`: client wall-clock timestamp in milliseconds.

Optional fields:

- `reason`: one of `ng_connect`, `metadata_refresh`, `playback_position`, `playback_position_final`, `watched_toggle`, or `start_at_beginning`. Servers should ignore unknown reason strings after logging them.
- `session_token`: download/offline session token, duplicated in the body for servers that do not read custom headers.
- `correlation_id`: client correlation id from the original offline download/session when available.
- `account_family`: account/profile grouping when available.
- `client_id`: NG client id when available.

## Validation

The server should:

- Require JSON and reject malformed requests with `400`.
- Require `schema_version == 1`; return `400` or `422` for unsupported versions.
- Require each item to have a known `media_file_id`; mark unknown items as failed or omitted from the authoritative response.
- Validate that the authenticated session/user/client is allowed to update every requested media file. A whole-request `401`/`403` is acceptable, but per-item rejection is preferred for mixed batches.
- Clamp each `resume_position_ms` to `[0, duration_ms]` when duration is known.
- Normalize positions below 3000 ms to `0` so trivial starts do not become resume prompts.
- Treat positions within roughly 10 seconds of the known duration as complete/end state. The server may clear resume position, mark watched, or follow existing SageTV watched semantics.
- Accept repeated identical requests as idempotent successes.

## Merge Rules

The client already avoids overwriting newer local resume state with older server manifest values. The server should do the symmetric stale-write protection.

Recommended rules:

- Store `resume_position_ms`, `watched`, `updated_at_ms`, `updated_by_client_id`, and `server_updated_at_ms` per media file/user scope.
- Watched is an OR-style merge: if either side says watched, authoritative watched should become `true`.
- Resume position is a max-style merge: authoritative `resume_position_ms` should be the higher of the server value and client value.
- If `reason == "start_at_beginning"`, accept `resume_position_ms = 0` as an explicit client-side reset only if the server also supports reset semantics. Otherwise the max-style rule will preserve the higher server value.
- If timestamps are absent or untrusted, the watched/max-position rules still produce an idempotent result.
- Do not let one user's playback state overwrite another user's playback state unless SageTV is configured with shared watched state.

## Response Body

For success, return `200 OK` with the server's authoritative state:

```json
{
  "ok": true,
  "server_updated_at_ms": 1780947600500,
  "recordings": [
    {
      "media_file_id": "123456",
      "resume_position_ms": 1234567,
      "watched": false,
      "server_updated_at_ms": 1780947600500
    }
  ]
}
```

The Android client applies returned authoritative values only when they win locally: `watched=true` is applied, and a returned resume position is applied only if it is higher than the current local position.

Useful non-success responses:

- `400`: malformed JSON, missing required field, unsupported schema.
- `401`: missing or expired session token.
- `403`: session/client is not allowed to update this media file.
- `404`: media file not found.
- `207`: optional mixed-result response for batches with per-item failures.
- `409`: stale update rejected. Include current authoritative state in the response body. With the watched/max-position merge rules this should be uncommon.
- `422`: value was understood but invalid, such as a negative resume position.
- `501`: endpoint intentionally not implemented on this server version.

## Manifest Refresh Integration

Offline manifest responses should include the same authoritative playback state so clients can seed or refresh local metadata:

```json
{
  "media_file_id": "123456",
  "resume_position_ms": 1234567,
  "watched": false
}
```

The Android client currently recognizes `resume_position_ms` and several common variants for resume state, and applies server resume positions only when they are later than the local offline position. Watched state is applied when present.

## Operational Notes

The endpoint should be fast and side-effect focused. It should not trigger remuxing, sidecar regeneration, or download refresh by itself. The server should log `media_file_id`, `client_id`, `reason`, accepted state, and rejection cause, but avoid logging session tokens or other secrets.
