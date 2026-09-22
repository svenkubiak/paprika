# Settings

`/admin/settings` covers configuration for your own superadmin account (password, 2FA) and instance-wide settings (default tenant, request-log retention). Most controls here are disabled unless you're signed in as a superadmin. To add or remove superadmins, see [Superadmins](/admin-ui/superadmins).

## Security

- **Superadmin password**: change the password of the account you're signed in with. Requires the current password and a new password of at least 16 characters. This changes your own password only, not any other superadmin's.
- **Two-factor authentication (TOTP)**: enable or disable a second sign-in factor **for your own account**. Uses standard TOTP (6-digit codes, 30-second period), so any authenticator app (Google Authenticator, Authy, 1Password, etc.) works:
  - **Enable**: confirm your current password, scan the displayed QR code (or enter the manual key) in an authenticator app, then confirm with a 6-digit code.
  - **Disable**: requires both your password and a current 6-digit code, a deliberate extra check since disabling 2FA reduces account security.
  - 2FA is **per superadmin account**. Turning it on or off here only affects the account you're signed in with, never another superadmin's, and there's no instance-wide switch that forces or removes it for everyone. Each superadmin decides for their own login, and you can see who has it enabled on the [Superadmins](/admin-ui/superadmins) page. There's no equivalent setting for tenant users.

## Tenants

- **Default tenant** — the tenant the superadmin lands in automatically after sign-in when no tenant is currently active, and the tenant used for anonymous/guest API requests. Leaving it unset (**"Use config fallback"**) falls back to the auto-created **Default** tenant if it still exists and is active. See [Concepts → Tenants](/concepts/tenants#the-default-tenant) for the full resolution order. This resets to unset automatically if the tenant you picked here is later deleted — you don't need to remember to clear it yourself.

## Request logs

- **Retention (days)** — how long request log entries are kept before automatic cleanup. Set to `0` to keep logs indefinitely. See [Request Logs](/admin-ui/request-logs) for what's actually recorded.
- **Log admin UI requests** — off by default. Operating the admin UI is itself a stream of HTTP requests (`/admin/…`, `/api/admin/…`, `/api/meta/…`, the login flow, the UI assets); logging them buries your API traffic under Paprika's own bookkeeping. Switch it on when you want an audit trail of admin activity. **Failed** admin requests (status ≥ 400) are logged either way, so a rejected superadmin login is never hidden by this setting.
- **Log user agent** — off by default. The user agent is personal data, so Paprika only stores it if you decide you need it.
- **Client IP address** — `off` (default), `truncated`, or `full`. `truncated` keeps the network and drops the host (IPv4 `/24`, IPv6 `/48`), which is enough to recognise abusive traffic without singling out a caller. Addresses are read from `X-Forwarded-For` / `X-Real-IP`, so a reverse proxy has to set them.

## Backup & Restore

Covered on its own page: [Backup & Restore](/admin-ui/backup-restore).
