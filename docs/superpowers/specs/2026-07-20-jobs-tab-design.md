# Jobs tab — design

2026-07-20. Goal: a third tab that lists the gateway's scheduled jobs and lets the
user add, edit, and delete them via the API.

## Server contract (verified live 2026-07-20, gateway 0.18.0)

The gateway exposes a jobs admin API under `/api/jobs` (same `/api` prefix and bearer
auth as sessions). Note: `GET /v1/capabilities` reports `jobs_admin: false` yet the
endpoints exist and work — the flag is not trustworthy, so the app feature-gates on a
successful `GET /api/jobs` instead of the capability flag.

- `GET /api/jobs` → `{"jobs": [Job, …]}` (no pagination)
- `GET /api/jobs/{id}` → `{"job": Job}`; malformed id → 400 `{"error":"Invalid job ID format"}`
- `POST /api/jobs` `{name, prompt, schedule}` → `{"job": Job}`; missing name → 400.
  A create ignores `enabled:false` — jobs are always born enabled.
- `PATCH /api/jobs/{id}` — partial: `name`, `prompt`, `schedule` (string), `enabled`
  (bool) → `{"job": Job}` with recomputed `next_run_at`
- `DELETE /api/jobs/{id}` → `{"ok": true}`; deleted id then 404s
- `POST /api/jobs/{id}/pause` / `/resume` / `/run` also exist (not used in v1 —
  `PATCH enabled` covers pause semantics the UI needs)

Job fields the app consumes: `id` (12 hex), `name`, `prompt`, `schedule`
(`{kind, display, expr?/minutes?}`), `schedule_display`, `enabled`, `state`
(`scheduled`/`paused`), `next_run_at`/`last_run_at`/`created_at` (ISO-8601 **strings
with offset** — unlike sessions' float epochs), `last_status` (`ok`/error),
`last_error`, `repeat.completed`.

Schedule input is a free string; the server validates and normalizes it. Accepted
forms (from the server's own 500 error text): duration one-shot (`30m`, `2h`),
interval (`every 30m`), cron (`0 9 * * *`), ISO timestamp one-shot.

Error bodies on `/api/jobs` are `{"error": "<string>"}` — NOT the OpenAI object
envelope `/v1/*` uses. An invalid schedule returns **500** with a helpful usage
message, so the error mapper must surface string bodies (today it silently drops
them) and the edit screen must show `ApiError.Server` detail inline.

## Approach

Considered: (a) Room-cached like threads with a syncer, (b) fetch-in-ViewModel only,
(c) in-memory repository. Chose **(c)**: jobs are low-volume admin data with no
offline-reading requirement, so Room adds cost without value; but a repository (vs
per-VM fetches) gives the list and edit screens one shared source of truth and keeps
wire types out of the UI, matching spec 01 layering.

## Components

- **DTOs** (`data/remote/Dto.kt`): `JobDto`, `JobScheduleDto`, `JobListDto`,
  `JobEnvelopeDto`, `JobCreateDto{name,prompt,schedule}`,
  `JobPatchDto{name?,prompt?,schedule?,enabled?}` (defaults omitted so PATCH stays
  partial).
- **Client** (`HermesClient` + `OkHttpHermesClient` + fakes): `listJobs()`,
  `createJob(dto)`, `updateJob(id, dto)`, `deleteJob(id)`. `mapHttpFailure` learns
  the string-`error` body shape.
- **Domain** (`domain/model/Job.kt`): `Job(id, name, prompt, scheduleDisplay,
  enabled, nextRunAt: Long?, lastRunAt: Long?, lastStatus, lastError, runsCompleted)`
  — ISO timestamps parsed to epoch millis so `TimeFormat` applies unchanged.
- **Repository** (`data/repo/JobsRepository`): `StateFlow<JobsState>` (`jobs`,
  `loaded`, `lastError`), `refresh()`, `create/update/delete` — each mutation calls
  the API and folds the returned job back into the flow (no optimistic writes; the
  server normalizes schedules, so its echo is the truth). Auth failures route to
  `ConnectionManager.onAuthFailure` like ThreadRepository.
- **UI**:
  - `JobsTab = LemanTab("jobs", "jobs", TablerIcons.Clock)` (new Tabler clock glyph),
    inserted between threads and config; Threads/Config screens already forward
    unknown tab ids.
  - Route `jobs` → `JobsScreen`: ScreenFrame; readout `N · M paused`; rows show
    name, `schedule_display`, next-run time label, state tone (paused = faint,
    error last_status = danger); tap → edit. Bottom: accent "new job" affordance
    (NewThreadField pattern) → route `job/new`. Refresh on entry; empty/error lines
    per spec 04.
  - Route `job/{jobId}` → `JobEditScreen` (also handles `new`): name + schedule
    PromptFields, multiline prompt box (NewThreadScreen composer pattern), enabled
    ToggleRow (edit only), primary save button, danger delete with two-tap confirm
    (edit only). API errors (e.g. invalid schedule) shown as inline danger line.
    Successful save/delete pops back to the list.
- **ViewModels**: `JobsViewModel` (repo state + ConnectionManager → UI state),
  `JobEditViewModel` (field states, save/delete, `done` SharedFlow for nav — the
  NewThreadViewModel pattern).

## Error handling

NotConfigured/offline → EmptyLine "can't reach gateway · check config". 401 → same
auth-failed row as threads. Mutation failures keep the edit screen open with the
server's message. Delete failure shows inline on the edit screen.

## Testing

- `DtoTest`: decode a captured `/api/jobs` fixture (incl. unknown fields).
- `OkHttpHermesClientTest` (MockWebServer): four endpoints — paths, methods, bodies,
  envelope unwrapping; string error body mapping.
- `JobsRepositoryTest`: refresh/create/update/delete fold into state; error paths.
- ViewModel tests over `FakeHermesClient`-backed repository: list building, save
  routes create-vs-patch, two-tap delete, inline error on invalid schedule.
- Screenshot goldens re-recorded if the new tab shifts existing screens.

## Out of scope (v1)

Run-now, pause/resume verbs (covered by enabled toggle), execution history,
`skills`/`model`/`deliver`/`origin` editing, offline cache.
