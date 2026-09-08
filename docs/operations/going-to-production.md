# Going to Production

Paprika ships as a plain HTTP application. It doesn't terminate TLS, it doesn't rate limit, and it doesn't filter by client IP. Those are jobs for the layer in front of it, and Paprika expects that layer to be there. **Run Paprika behind a reverse proxy and never expose its HTTP port directly to the internet.** The proxy handles TLS, throttling, and access control; Paprika handles the application.

This page walks through the hardening you should have in place before a Paprika instance is reachable from the outside. The [installation section in the README](https://github.com/svenkubiak/paprika#-installation) covers getting it running; this is what to do around it.

::: warning Beta
Paprika is still in beta and not yet recommended for production. Treat everything below as the baseline you'd want *before* that changes, not a sign that it's ready.
:::

## Put it behind a reverse proxy

The setup Paprika is built for looks like this:

```
Internet ──HTTPS──> nginx / Caddy ──HTTP──> Paprika (127.0.0.1:8080) ──> MongoDB (private)
```

The rule that never changes: the only thing allowed to open a connection to Paprika is your proxy. How you enforce that depends on where the two run.

### Same host

If the proxy and Paprika sit on the same machine, bind Paprika to the loopback interface. Then the port isn't reachable from the network at all, only from the proxy next to it. With the Docker or `.deb` install that's a line in your `.env`:

```
CONNECTOR_HTTP_HOST=127.0.0.1
CONNECTOR_HTTP_PORT=8080
```

If both run as containers, bind Paprika to the container network instead of publishing the port on the host.

### Separate hosts

Running the proxy and Paprika on different hosts is a perfectly normal setup, and it needs a bit more care, because the hop between them now crosses a network as plain HTTP. Two things to get right:

- **Lock the port down to the proxy.** Bind Paprika to its private interface, not a public one, and use a firewall or security group so only the proxy's address can reach `CONNECTOR_HTTP_PORT`. Nothing else on the network should be able to talk to it.
- **Encrypt the hop.** Since Paprika only speaks plain HTTP today, it can't secure that link itself. Put the traffic on a private, encrypted transport: a WireGuard tunnel, a cloud provider's private network, or a service mesh between the two hosts. That keeps the same "browser to proxy is HTTPS, proxy to app is trusted" story even when the app is a network hop away.

A private network you fully control (a VPC where only the proxy can route to Paprika) is often enough on its own. Reach for a WireGuard tunnel or mTLS when the hop crosses anything you don't completely trust.

### TLS is not optional for the admin UI

The admin session and authentication cookies are set with `Secure` and `SameSite=Strict` in production. A browser will not send a `Secure` cookie over plain HTTP, so the admin UI simply won't work unless it's served over HTTPS.

Paprika currently serves plain HTTP only. The underlying mangoo I/O framework could terminate TLS, but Paprika doesn't expose that today, so the encryption has to happen in front of it. Terminate TLS at the proxy with a real certificate (Let's Encrypt via Caddy is automatic; with nginx use certbot or your own cert) and keep Paprika on plain HTTP behind it. Even if HTTPS becomes an option for Paprika itself later, a reverse proxy is still where the rate limiting, the IP restriction, and the header handling belong, so it stays part of the setup either way.

### Forward the right headers

Terminate TLS at the proxy and pass these upstream so Paprika sees the original request correctly:

- `Host` the original host header.
- `X-Forwarded-Proto` set to `https`, so the app knows the outside connection was encrypted.
- `X-Forwarded-For` / `X-Real-IP` the real client IP.

Strip any inbound `X-Forwarded-*` headers a client might send before you set your own. A client that can spoof `X-Forwarded-For` can poison whatever you build on top of it (logs, an IP allowlist further up the chain). Both examples below do this by setting the values explicitly rather than appending to them.

## Secrets

For a standard install there's nothing to do here: both install scripts generate a fresh set of secrets into `.env` on first run, and in production every secret is read from those environment variables. The placeholder values in the bundled `config.yaml` (`defaultTokenSecretForLocalDevOnly...`) are for local development only and are never used when `APPLICATION_MODE=prod`, since the prod config pulls each one from the environment.

You only need to touch this if you install Paprika outside the scripts and set the environment yourself. In that case generate each secret separately:

```bash
openssl rand -hex 32
```

That produces 64 hex characters, which is the minimum length Paprika enforces. It covers `APPLICATION_SECRET`, `TOKEN_SECRET` / `TOKEN_KEY`, `SESSION_COOKIE_SECRET` / `SESSION_COOKIE_KEY`, `AUTHENTICATION_COOKIE_SECRET` / `AUTHENTICATION_COOKIE_KEY`, and `FLASH_COOKIE_SECRET` / `FLASH_COOKIE_KEY`. Don't reuse a secret across instances, don't commit them, and keep `.env` readable only by the Paprika process. See the [configuration table in the README](https://github.com/svenkubiak/paprika#%EF%B8%8F-configuration) for the full list.

## Rate limit the auth endpoints

Paprika does no rate limiting of its own, so a login endpoint will happily accept as many attempts per second as your proxy lets through. Throttle the authentication routes at the proxy. These are the ones worth protecting:

| Path | Why |
|---|---|
| `/api/auth/login` | Tenant user login, password guessing target. |
| `/api/auth/register` | Tenant self-registration, abuse and spam target. |
| `/api/auth/refresh` | Token refresh. |
| `/authenticate`, `/api/admin/login`, `/api/admin/login/2fa` | Superadmin login and second factor. |
| `/api/admin/setup`, `/api/admin/token`, `/api/admin/token/2fa` | Initial setup and programmatic admin tokens. |

A handful of requests per second per IP with a small burst is plenty for real users and cuts brute force down hard. Tune to taste; the numbers in the examples are a sane starting point, not a law.

## Restrict the admin UI by IP

The public API and the admin UI share one process but split cleanly by path. Everything a tenant app needs is under a few prefixes; everything else is the admin area, which only you and your team should ever reach. Following the same idea [PocketBase suggests for its admin UI](https://pocketbase.io), lock the admin UI down to a known IP range (your office, a VPN, a bastion) and leave the app API open.

**Keep public** (your tenant apps call these):

- `/api/auth/...`
- `/api/collections/...`
- `/api/realtime`, `/api/realtime/subscribe`

**Restrict to trusted IPs** (the admin UI and its APIs):

- `/`, `/login`, `/setup`, `/authenticate`, `/logout`
- `/admin/...`
- `/api/admin/...`
- `/api/meta/...`
- `/assets/...` (the admin UI's own static files)

This is defense in depth, not the only thing holding the door shut: the admin UI still needs a superadmin session behind it. But it means a stolen or brute-forced password is useless unless the attacker is also on your network.

## Turn on MFA for the superadmin

There is exactly one superadmin identity and it can do everything. Enable TOTP two-factor for it under [Settings → Security](/admin-ui/settings#security) right after setup. Once it's on, a password alone is no longer enough to reach the admin UI. Combined with the IP restriction above, an attacker would need your network *and* your password *and* your second factor.

## nginx example

```nginx
# Throttle zones, keyed on the real client IP.
limit_req_zone $binary_remote_addr zone=paprika_auth:10m rate=5r/s;

# Who is allowed into the admin UI.
geo $paprika_admin_allowed {
    default         0;
    10.0.0.0/8      1;      # your VPN / internal range
    203.0.113.10/32 1;      # a specific office IP
}

server {
    listen 443 ssl http2;
    server_name paprika.example.com;

    ssl_certificate     /etc/letsencrypt/live/paprika.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/paprika.example.com/privkey.pem;

    # Send browsers straight back to HTTPS on the next visit.
    add_header Strict-Transport-Security "max-age=63072000; includeSubDomains" always;

    # Never trust inbound forwarding headers.
    proxy_set_header Host              $host;
    proxy_set_header X-Real-IP         $remote_addr;
    proxy_set_header X-Forwarded-For   $remote_addr;
    proxy_set_header X-Forwarded-Proto https;

    # Public tenant API, open to the world but rate limited on auth.
    location /api/auth/ {
        limit_req zone=paprika_auth burst=10 nodelay;
        proxy_pass http://127.0.0.1:8080;
    }

    location /api/collections/ {
        proxy_pass http://127.0.0.1:8080;
    }

    location /api/realtime {
        proxy_pass http://127.0.0.1:8080;
        proxy_buffering off;                 # let SSE stream through
        proxy_read_timeout 1h;
    }

    # Admin UI, restricted to trusted IPs.
    location / {
        if ($paprika_admin_allowed = 0) { return 403; }
        limit_req zone=paprika_auth burst=10 nodelay;   # also covers /authenticate, /api/admin/login, ...
        proxy_pass http://127.0.0.1:8080;
    }
}

# Send plain HTTP to HTTPS.
server {
    listen 80;
    server_name paprika.example.com;
    return 301 https://$host$request_uri;
}
```

The trailing `location /` block catches everything that isn't the public API, including `/admin`, `/api/admin`, `/api/meta`, `/login`, and `/assets`, so a single IP gate covers the whole admin UI. Keep the `/api/auth/` and `/api/collections/` blocks above it, since nginx matches prefix locations by longest match regardless of order but it reads more clearly this way.

## Caddy example

```
paprika.example.com {
    # Matcher for the admin UI: everything except the public API.
    @admin {
        not path /api/auth/* /api/collections/* /api/realtime*
    }

    # Restrict the admin UI to trusted IPs.
    @blocked_admin {
        not path /api/auth/* /api/collections/* /api/realtime*
        not remote_ip 10.0.0.0/8 203.0.113.10/32
    }
    respond @blocked_admin 403

    # Rate limit the auth routes. Needs the caddy-ratelimit plugin
    # (github.com/mholt/caddy-ratelimit); it is not in the standard Caddy build.
    rate_limit {
        zone paprika_auth {
            match {
                path /api/auth/* /authenticate /api/admin/login* /api/admin/setup /api/admin/token*
            }
            key    {remote_host}
            events 5
            window 1s
        }
    }

    header Strict-Transport-Security "max-age=63072000; includeSubDomains"

    reverse_proxy 127.0.0.1:8080 {
        header_up Host              {host}
        header_up X-Real-IP         {remote_host}
        header_up X-Forwarded-For   {remote_host}
        header_up X-Forwarded-Proto https
    }
}
```

Caddy gets you automatic Let's Encrypt certificates out of the box, so there's no certificate path to manage. The rate limiting directive comes from a community module; if you'd rather not build a custom Caddy binary, put nginx in front for the throttling or drop that block and rely on the IP restriction alone.

## Email (SMTP)

Paprika sends the tenant-user recovery emails (password reset, email verification) itself, over a single instance-wide SMTP configuration. If you don't enable those features for any tenant, you can skip this. If you do, configure SMTP through environment variables:

| Variable | Description |
|---|---|
| `SMTP_HOST` | SMTP server hostname. Leave empty to disable sending (tokens are still issued, no email goes out). |
| `SMTP_PORT` | SMTP port (for example `587` for STARTTLS, `465` for SMTPS). |
| `SMTP_USERNAME` / `SMTP_PASSWORD` | Credentials, if the server requires auth. |
| `SMTP_AUTHENTICATION` | `true` when the server requires authentication. |
| `SMTP_PROTOCOL` | `smtp` or `smtps`. |
| `SMTP_FROM` | The From address (for example `no-reply@example.com`). |
| `SMTP_FROM_NAME` | Display name for the sender (defaults to `Paprika`). |

There is one sender identity for the whole instance, so make sure the From domain's SPF and DKIM records allow this server to send, or the mails will land in spam. The link inside each email points at the tenant's own app, configured per tenant on the [Tenants](/admin-ui/tenants) editor, not at Paprika. Recovery is best-effort: a failed send is logged and never blocks the API response, which is also why the request endpoints always answer `200`.

## Health endpoint

Paprika exposes a health endpoint at `/health` that your load balancer, uptime monitor, or orchestrator can poll. It checks whether the application is running and whether the database connection is healthy.

```
GET /health
```

A healthy response looks like this:

```json
{"db": true, "status": "ok"}
```

If the database is not reachable, the endpoint returns HTTP 503:

```json
{"db": false, "status": "degraded"}
```

The endpoint has no authentication. Keep it accessible only to internal systems; if you are using an IP restriction for the admin UI, make sure your monitoring can still reach it. A typical approach is to allow the health path through at the proxy before the IP gate:

```nginx
location /health {
    proxy_pass http://127.0.0.1:8080;
}
```

The Docker setup includes a healthcheck on the `paprika` container that polls `/health` every 30 seconds. You can use the same endpoint in whatever tooling you prefer.

## A few more things worth doing

- **Lock down MongoDB.** The production config runs with authentication on (`auth: true`). Keep the database on a private network the outside can't reach, use a dedicated user with access to just the Paprika databases, and never publish the Mongo port. Everything a tenant stores lives here, one database per tenant.
- **Protect the storage directory.** `PAPRIKA_STORAGE` holds uploaded files. Put it on a volume only the Paprika process can read and write, and include it in your backups.
- **Take backups.** Use the [Backup & Restore](/admin-ui/backup-restore) tooling, and back up MongoDB and the storage directory on the infrastructure side too. Test a restore once in a while so you know it works before you need it.
- **Keep it updated.** Re-running the install script updates an existing installation while leaving your `.env` and `storage/` alone. Stay current for security fixes while Paprika is in beta.
- **Mind the setup link.** The one-time superadmin setup token is printed to the application log and expires after 30 minutes. Complete setup promptly, and don't leave that log where others can read it.
- **Set a log retention.** API request logs are kept according to the retention you configure under [Settings](/admin-ui/settings#request-logs). Pick a window that fits your needs instead of keeping everything forever.

Paprika already sends a strict Content Security Policy (including `frame-ancestors 'none'`, which blocks framing and clickjacking), so you don't need to add one at the proxy. Adding HSTS as shown above is a good complement; avoid setting a second CSP header, since two policies only ever combine to be more restrictive and will fight the one the app ships.
