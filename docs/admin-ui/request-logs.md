# Request Logs

`/admin/logs` — a paginated, searchable table of the requests made against the currently active tenant. Requires an [active tenant](/concepts/tenants); switch tenants to see a different tenant's logs.

## What's recorded

Every route of your API is logged, not just the collection API: auth endpoints included. Each entry has a timestamp, HTTP method, path, status code, execution time, and error message (if any). Requests without a tenant scope (the login flow, for example) are kept with the [default tenant](/admin-ui/settings).

**Admin UI traffic is not logged by default.** Clicking through the admin UI produces a constant stream of `/admin/…`, `/api/admin/…` and `/api/meta/…` calls that would bury the traffic of the API you actually want to look at, so Paprika drops them. Turn **Log admin UI requests** on under [Settings → Request logs](/admin-ui/settings#request-logs) when you want an audit trail of admin activity instead. Failed admin requests (status ≥ 400) are logged regardless of the setting, because a rejected superadmin login is a security event and not UI noise.

The same applies to scripted use of the management API (a schema export in CI, for example): it runs against `/api/meta/**` and is therefore only logged with the setting switched on.

This is **metadata only** — request and response bodies are never logged, so payloads never end up here. The **path is stored without its query string**, because query values carry filter arguments and those are user data.

Two entry types exist:

- **`request`** — one per HTTP request.
- **`hook`** — one per asynchronous after-hook. Those run once the response has already been sent, so they cannot be part of the request entry; they carry the same **Request ID**, which is how you tie them back together.

Use the **All entries / Requests / Async hooks** filter to switch between them. Async hook rows are tinted and prefixed with `↳`; the link button in their **Hooks** cell searches for their request id and thereby pulls the original request and all of its async hooks together.

## How to read the table

The table header has two levels, because half of the columns describe the incoming call and the other half the outgoing hook calls it caused:

| Group | Columns | Belongs to |
|---|---|---|
| **Request** | Timestamp, Method, URL, Status, User | the incoming API call |
| **Timing** | Paprika, Hooks, Total | the split of the measured time |
| **Hooks** | Executions | the hook endpoints Paprika called |

The error message itself is **not** a column. The status code carries the outcome in the overview (hover it for a preview of the message), and the full error lives in the detail sidebar, where it is shown as the status code, what that code means, and the message the API returned — `403 Forbidden – the credential was valid but not allowed to do this` says considerably more than a bare `attest_required` squeezed into a table cell.

The sidebar also labels where the error came from: `from request` for an error Paprika produced itself, `from hook "<name>"` for a call a hook rejected, and `from hook` on an async hook entry. That removes the usual ambiguity of whether an error describes the call or one of its hooks.

## Timing and hooks

A blocking hook is a remote call Paprika waits for, so its latency is part of your API response time. Both the table and the detail sidebar (click any row) therefore split the measurement into three columns:

- **Paprika** — what Paprika itself needed, hook waiting time excluded.
- **Hooks** — the sum of all blocking hook calls of that request.
- **Total** — Paprika + hooks, i.e. the time the client actually waited. Measured before the first filter runs.

Below that, **Hook executions** lists every blocking hook call with its event, target host, HTTP status, duration, and outcome:

| Outcome | Meaning |
|---|---|
| `continued` | the hook allowed the operation |
| `blocked` | the hook rejected it (the rejecting hook is also named as **hookBlockedBy**) |
| `issuedToken` | a `beforeLogin` hook issued a token for another user |
| `failed` | the hook did not answer, or answered unusably, and the operation was aborted |
| `failedOpen` | same, but the hook has **Fail open** enabled, so the operation continued |

Having **Paprika** and **Hooks** next to **Total** makes "our API got slow" and "someone's hook got slow" two distinguishable statements.

One request can call several hooks. The **Executions** column therefore shows how many (`3 hooks`), one coloured dot per call — green for `continued`, amber for `blocked`/`failedOpen`, red for `failed`, blue for `issuedToken` — and whether the request as a whole was `continued` or `blocked`. Clicking the count unfolds the row and lists every single call with its event, target, outcome and duration, without having to open the sidebar.

The wording is the same everywhere: a hook either **continued** the request or **blocked** it. "Fired" only ever said that a hook ran at all, which left open the part that matters.

## Filtering

- **Search** matches against URL, method, error message, or request id.
- **Status filter** narrows to all requests, success only, or errors only.
- **Hook filter** narrows to requests where a hook **continued** the call or **blocked** it. The two are disjoint: a blocked request does not show up under "continued".
- **Type filter** separates requests from async hook entries.
- Results are paginated (25/50/100 per page).

## Live mode

The **Live** switch above the table is off by default. Turned on, the page asks every three seconds for entries newer than its top row and adds them on top; the newest entry is always the first one, as in the paged view. All active filters keep applying, so a live tail of errors only is just the error filter plus the switch.

What it does *not* do:

- It does not page. Live mode returns to page one and disables the pager, because a delta added on top of page five would misrepresent what you are looking at. Turn it off to browse history.
- It does not move the table while a detail sidebar is open, and it stops asking while the browser tab is hidden. Reopening the tab fetches immediately.
- It stops itself on error — an expired admin session or an unreachable server turns the switch off instead of retrying every three seconds.

The polling requests themselves are excluded from the log, so watching the log does not produce log entries. Each poll is one indexed read bounded to what is newer than the top row, not a full page read, and it skips the total count — so an open live tail is cheap even on a large log. The reads go through the same authenticated, tenant-scoped endpoint as the table itself: live mode can never show more than the normal list would.

## Personal data

The log is designed to be usable without collecting personal data, because as the operator you are the controller for everything it stores:

- **Always logged:** method, path, status, timings, hook metadata, the authenticated user id and role (or the API key name), and the error message. None of it is collected about a person beyond the account already known to the system.
- **Never logged:** request/response bodies, query strings, hook payloads, credentials, or headers other than the ones named below.
- **Opt-in only:** the **user agent** and the **client IP address**, both off by default. Enable them under [Settings → Request logs](/admin-ui/settings#request-logs) when you have a reason — abuse detection and incident analysis being the usual ones — and note that this is your decision to justify under Art. 6(1)(f) GDPR.

The IP address can be stored in two forms. `truncated` (recommended) keeps the network and drops the host: IPv4 loses its last octet (`203.0.113.7` → `203.0.113.0`), IPv6 everything below the /48 prefix. `full` stores the address as sent. Addresses are read from `X-Forwarded-For` / `X-Real-IP` only, so they require a reverse proxy that sets these headers.

The strongest data protection lever remains the retention: entries older than the configured number of days are deleted automatically.

## Retention

How long entries are kept before automatic cleanup is controlled under [Settings → Request logs](/admin-ui/settings#request-logs) (default 7 days; set to 0 to keep indefinitely).
