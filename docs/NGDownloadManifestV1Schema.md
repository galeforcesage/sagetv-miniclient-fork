# NG Download Manifest V1 Schema

Status: Canonical client/server contract for offline detail rendering.

## Transfer ACK Fields (Two-Step Delivery)

In `CMD_DOWNLOAD_REQUEST` payloads (`type=TRANSFER_SESSION_ACK`), the server may provide:
- `offline_metadata_url`: absolute HTTP URL to full manifest.
- `offline_metadata_path`: relative path to full manifest (resolve against control-plane base).
- `offline_inline_level`: `full` or `core`.

Client rules:
- Treat inline `offline` object as first-paint data.
- If either `offline_metadata_url` or `offline_metadata_path` exists, fetch full manifest immediately in background.
- If fetched payload parses as `manifest_version = 1`, replace the entire local offline snapshot with fetched data.
- If fetch fails, keep inline rendering and retry with bounded backoff without failing download flow.
- `core` means fetch is urgent for complete metadata/art.
- `full` still permits fetch for consistency/refresh.

## Top-level object

Required fields:
- manifest_version: integer, must be 1
- title: string
- metadata: object
- credits: array
- assets: object

Optional fields:
- recording_id: string
- subtitle: string
- runtime_ms: integer (milliseconds)

Unknown fields:
- Allowed at top level and in nested objects.
- Client must ignore for behavior but preserve for diagnostics.

## metadata object

Purpose:
- Flat key/value metadata map for detail rendering and filtering.

Common keys:
- original_air_date: string
- aired_on: string
- air_date: string
- categories: string[]
- rated: string
- show_id: string
- season_number: integer
- episode_number: integer
- run_time_minutes: integer
- channel_name: string
- channel: string
- network: string
- station: string
- recording_file_size: integer
- format: string
- audio_format_summary: string
- description: string
- summary: string
- overview: string

Notes:
- This is intentionally open-ended.
- New keys do not require client protocol changes.

## credits array

Each element:
- person_id: string (optional)
- person_name: string (required for display)
- role_name: string (required for grouping)
- image_url: string (optional)

Notes:
- Client groups by role_name dynamically.
- No role-specific protocol fields are required.

## assets object

images:
- Array of image descriptors.

Image descriptor fields:
- kind: one of thumbnail, poster, fanart, banner, person
- url: string
- person_id: string (required when kind=person)

captions, comskip, transcript:
- Object or array containing one or more entries with url.
- Unknown additional fields are allowed and preserved.

## Client behavior requirements

- Reject manifest if manifest_version is missing or not 1.
- Use hero fallback chain: thumbnail -> poster -> fanart.
- Render known metadata first, then render remaining metadata keys alphabetically as More Details.
- Group credits by role_name, alphabetical names per role.
- Hide empty sections.
- Log parse failures as manifest_parse_failure with reason.
- Log missing recommended fields (thumbnail, rated) as warnings.
