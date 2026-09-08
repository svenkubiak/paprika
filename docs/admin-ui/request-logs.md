# Request Logs

`/admin/logs` — a paginated, searchable table of API requests made against the currently active tenant. Requires an [active tenant](/concepts/tenants); switch tenants to see a different tenant's logs.

## What's recorded

Each entry has a timestamp, HTTP method, URL, status code, and error message (if any). This is **metadata only** — request/response bodies are never logged, so sensitive payloads never end up here.

## Filtering

- **Search** matches against URL, method, or error message.
- **Status filter** narrows to all requests, success only, or errors only.
- Results are paginated (25/50/100 per page).

## Retention

How long entries are kept before automatic cleanup is controlled under [Settings → Request logs](/admin-ui/settings#request-logs) (default 7 days; set to 0 to keep indefinitely).
