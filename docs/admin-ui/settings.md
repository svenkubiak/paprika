# Settings

`/admin/settings` covers instance-wide settings: the default tenant and what the request log keeps. Most controls here are disabled unless you're signed in as a superadmin.

Everything that belongs to **your own account** — password, two-factor authentication, email address, profile picture, sign-in alerts — lives on [Profile](/admin-ui/profile) instead, reachable through the avatar in the top right corner. To add or remove superadmins, see [Superadmins](/admin-ui/superadmins).

## Tenants

- **Default tenant** — the tenant the superadmin lands in automatically after sign-in when no tenant is currently active, and the tenant used for anonymous/guest API requests. Leaving it unset (**"Use config fallback"**) falls back to the auto-created **Default** tenant if it still exists and is active. See [Concepts → Tenants](/concepts/tenants#the-default-tenant) for the full resolution order. This resets to unset automatically if the tenant you picked here is later deleted — you don't need to remember to clear it yourself.

## Request logs

- **Retention (days)** — how long request log entries are kept before automatic cleanup. Set to `0` to keep logs indefinitely. See [Request Logs](/admin-ui/request-logs) for what's actually recorded.
- **Log admin UI requests** — off by default. Operating the admin UI is itself a stream of HTTP requests (`/admin/…`, `/api/admin/…`, `/api/meta/…`, the login flow, the UI assets); logging them buries your API traffic under Paprika's own bookkeeping. Switch it on when you want an audit trail of admin activity. **Failed** admin requests (status ≥ 400) are logged either way, so a rejected superadmin login is never hidden by this setting.
- **Log user agent** — off by default. The user agent is personal data, so Paprika only stores it if you decide you need it.
- **Client IP address** — `off` (default), `truncated`, or `full`. `truncated` keeps the network and drops the host (IPv4 `/24`, IPv6 `/48`), which is enough to recognise abusive traffic without singling out a caller. Addresses are read from `X-Forwarded-For` / `X-Real-IP`, so a reverse proxy has to set them.

## Backup & Restore

Covered on its own page: [Backup & Restore](/admin-ui/backup-restore).
