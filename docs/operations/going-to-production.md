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
| `/api/auth/issue-token` | Authenticated, but mints sessions for arbitrary users - throttle it and see [keeping it internal](#keep-the-token-issuing-endpoint-off-the-public-internet). |
| `/api/auth/password/forgot`, `/api/auth/verify/request` | Unauthenticated and send an email, so they're a spam and enumeration target. |
| `/api/auth/password/reset`, `/api/auth/verify/confirm` | Redeem a one-time token, guessing target. |
| `/authenticate`, `/api/admin/login`, `/api/admin/login/2fa` | Superadmin login and second factor. |
| `/api/admin/setup`, `/api/admin/token`, `/api/admin/token/2fa` | Initial setup and programmatic admin tokens. |

The examples below throttle all of `/api/auth/` in one go, which covers every row above that starts with that prefix, plus one block for the superadmin credential endpoints.

A handful of requests per second per IP with a small burst is plenty for real users and cuts brute force down hard. Tune to taste; the numbers in the examples are a sane starting point, not a law.

::: warning Never throttle the admin UI as a whole
Throttle the credential endpoints, not `location /`. Opening the admin UI is not one request: the
document, `boot.js`, the entry chunk, a dozen preloaded chunks, the stylesheet, the favicon, the
web manifest, the route chunk and the first API calls all leave the browser inside the same
second - well over twenty requests. A zone like `rate=5r/s burst=10` answers everything past the
eleventh with nginx's own **503**, and since `rate=5r/s` drains the bucket for seconds, the next
reload can get a 503 for the document itself.

That is exactly what you see right after a restart or an update: every hashed filename changed, so
nothing comes from the browser cache and the full burst hits the proxy. It looks like Paprika is
down when it is only the rate limit. See [503 from the proxy after a
restart](#getting-a-503-from-the-proxy-after-a-restart-or-update).
:::

## Cap the concurrent realtime connections

A rate limit counts requests. It does not count what a request costs, and it does not count
connections that stay open — so it is the wrong tool for `/api/realtime`.

`GET /api/realtime` opens a Server-Sent-Events stream. The connection is accepted and registered
**before** anything is authenticated: a client gets its `clientId` first and authenticates
afterwards, when it calls `POST /api/realtime/subscribe` with a token. Paprika puts no ceiling on
how many of those streams one client may hold open, and the proxy is told to keep them alive for
an hour (`proxy_read_timeout 1h`, which SSE needs). Without a connection limit, an
unauthenticated client can therefore park as many open streams as it can afford file descriptors
for, and each one costs a socket on the proxy, a socket on the app, and an entry in the client
registry.

Limit concurrent connections per IP at the proxy, in addition to the request rate:

- **nginx** — `limit_conn_zone` plus a `limit_conn` in the `/api/realtime` location (in the
  [example below](#nginx-example)). Ten per IP is generous for a browser app: one tab is one
  stream. Raise it if many of your users sit behind one NAT, lower it if they do not.
- **Caddy** — there is no built-in equivalent. Either add a rate-limiting plugin, put nginx in
  front, or cap it at the firewall (`iptables`/`nftables` `connlimit`).

The same `limit_conn` is worth having on the rest of the API as a blunt backstop; the realtime
path is the one where it is not optional.

::: tip Check what is actually open
`ss -tn state established '( sport = :443 )' | wc -l` on the proxy host tells you how many
connections are being held. On the Paprika side, the scheduled `purgeStaleClients` task logs how
many dead realtime clients it dropped — at `DEBUG` level, so turn that on while you are sizing
the limit.
:::

## Use API keys for machine access

Do not let a backend log in with a username and password. `/api/auth/login` has to stay public
for real users, which makes it the most brute-forcible surface of the installation, a password
cannot be scoped or revoked on its own, and a machine has no second factor - so protecting a
machine account with MFA is not an option either.

Create an [API key](/admin-ui/auth-settings#api-keys) instead, bound to a dedicated tenant user
whose [rules](/admin-ui/collection-rules) allow exactly what that service needs, and send it as
`Authorization: Bearer pk_…`. Keys are named, individually revocable, carry an optional expiry,
and their last use is visible in the admin UI.

Operational hints:

- **One key per service and per environment.** Revoking then affects one consumer, and the
  request log names which key was used.
- **Rotate by overlap:** create the new key, deploy it, then revoke the old one. Both work at the
  same time, so a rotation needs no downtime. Set an expiry if you want rotation to be enforced
  rather than remembered.
- **Revoke, don't delete, when retiring a key.** Both stop it working, but a revoked key keeps its
  entry - name, bound user, last use - which is what you want to look at after an incident or
  during a review. Delete is for housekeeping once that history is no longer interesting.
- **Store keys like passwords:** in the consumer's secret store, never in a repository, never in
  a mobile or browser client. Whoever holds the key *is* the bound user.
- **A leaked key is revoked, not rotated in place.** Revocation is immediate.

A key never grants superadmin rights and never reaches the admin API; for administrative
automation use `POST /api/admin/token` instead.

### If the service needs to bypass the rules

A service that works across user boundaries while the collections stay *No access* for clients
needs a [rule-bypassing key](/admin-ui/auth-settings#bypass-collection-rules). Treat it as the
most sensitive credential your application tier holds:

- **Exactly one bypassing key per service** (and per environment). Never share one between two
  consumers - revocation and the request log then no longer tell you who did what.
- **Prefer an ordinary key.** Only switch the bypass on if the service genuinely cannot live
  inside the rules of its user; an `Own records` service account covers more cases than it looks.
- **Rotate by revoke and re-issue.** The flag cannot be flipped on an existing key, and there is
  no way to read a key back, so rotation is: create the new key, deploy it, revoke the old one.
  Keep the revoked entry around - for a credential this powerful the record of who held it and
  when it was last used is worth more than a tidy list.
- **Environment variables only.** Never in a repository, a build artefact, a container image
  layer you push, or anything that reaches a client. Whoever holds it can read and write all data
  of that tenant.
- **Keep it away from the public listener.** A bypassing key is used server-to-server; if that
  traffic can stay inside your network, let it.

It still cannot reach `/api/meta/**` or `/api/admin/**`, so a leaked bypassing key cannot reshape
schemas, rules, hooks or tenants - it is a data breach, not a takeover. That is the line the
feature is built on.

## Keep the token-issuing endpoint off the public internet

`POST /api/auth/issue-token` mints an access/refresh token pair for *any* user of the tenant,
without a password, for callers listed under
[token issuers](/admin-ui/tenants#editing-a-tenant). That is the strongest permission in the
system below superadmin, and nothing in a mobile app or a browser ever needs to call it - only
your own backend does. So unlike the rest of the tenant API, this one route can be taken off the
public internet entirely.

### How this relates to API keys

These are two independent things that meet in exactly one place, and it is worth being precise
about it:

- An **[API key](/admin-ui/auth-settings#api-keys)** answers *how* a caller proves an identity.
  It replaces a password login and is sent straight on the normal routes
  (`Authorization: Bearer pk_…`). There is no exchange step: the key **is** the credential.
- **`/api/auth/issue-token`** answers *for whom* a session is minted: an authorized caller gets
  tokens for a different user.

The overlap is that `issue-token` needs some bearer credential from its caller, and that may be
either an access token or an API key of the issuer user - the key is presented as proof at the
entrance, it is not traded in for the tokens that come out:

```
your backend ──Bearer pk_… (API key = how it proves who it is)──▶ POST /api/auth/issue-token
                                                                  { "userId": "<app user>" }
                                                                            │
                       accessToken / refreshToken for <app user> ◀──────────┘
                                    │
                                    ▼  (handed to the app, which uses them normally)
```

That difference decides what a proxy can do:

- `issue-token` is **one route, called only by your backend** → it can be gated by path.
- An API key used for data access travels on `/api/collections/*`, which has to stay public for
  real users → **no path gate is possible** there. Such a key is protected by its entropy, by the
  rules of the bound user, and by being revocable. If your backend only ever calls `issue-token`,
  its key never touches a public route in the first place.

Paprika-side, `/api/auth/issue-token` already refuses anyone who is not an allowlisted tenant user
(`401`/`403`), so the proxy rule below is defense in depth: if the route is only reachable from
your own network, a stolen issuer credential is worthless from the outside.

Pick whichever fits your deployment:

- **Middleware on the same host or in the same private network** - do not expose the route
  publicly at all; let the middleware talk to Paprika directly (`127.0.0.1:8080` or the internal
  service address) and return `403` for it on the public listener.
- **Middleware elsewhere** - allowlist its egress IPs on that one location instead of blocking
  it outright.

Both examples below carry this as a commented-out block, so you can switch it on deliberately
rather than discover it the hard way. If you comment it in, make sure your own backend is inside
the allowed range first - an issuer that cannot reach the route will fail closed.

## Restrict the admin UI by IP

The public API and the admin UI share one process but split cleanly by path. Everything a tenant app needs is under a few prefixes; everything else is the admin area, which only you and your team should ever reach. Following the same idea [PocketBase suggests for its admin UI](https://pocketbase.io), lock the admin UI down to a known IP range (your office, a VPN, a bastion) and leave the app API open.

**Keep public** (your tenant apps call these):

- `/api/auth/...` (except `/api/auth/issue-token`, see [above](#keep-the-token-issuing-endpoint-off-the-public-internet))
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
limit_req_zone  $binary_remote_addr zone=paprika_auth:10m rate=5r/s;

# Concurrent connections, not requests per second. This is what bounds /api/realtime: an SSE
# stream is one long-lived connection that is accepted before anything is authenticated, and
# the request rate limit above never sees it again. See "Cap the concurrent realtime
# connections" above.
limit_conn_zone $binary_remote_addr zone=paprika_conn:10m;

# 429 says "you are going too fast", 503 (the default) says "the app is down" - and a monitoring
# system or a user should be able to tell those apart.
limit_req_status  429;
limit_conn_status 429;

# Who is allowed into the admin UI.
geo $paprika_admin_allowed {
    default         0;
    10.0.0.0/8      1;      # your VPN / internal range
    203.0.113.10/32 1;      # a specific office IP
}

upstream paprika {
    # An IP literal on purpose: a hostname like localhost can resolve to both ::1 and 127.0.0.1,
    # which makes this a two server group. nginx then takes a failing address out of rotation for
    # fail_timeout, and once both are out it answers "no live upstreams" with a 503 - for seconds
    # after Paprika is already back up. max_fails=0 switches that bookkeeping off, which is what
    # you want for a single instance: there is nothing to fail over to anyway.
    server 127.0.0.1:8080 max_fails=0;

    keepalive 32;
}

server {
    listen 443 ssl;
    http2 on;
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

    # Required for the keepalive pool above.
    proxy_http_version 1.1;
    proxy_set_header Connection "";

    # A GET that hits a keepalive connection the restarting app just closed gets one more try on
    # a fresh connection instead of turning into an error page. Writes are not retried, which is
    # nginx's default and the right call - a POST may already have been processed.
    proxy_next_upstream error timeout;
    proxy_next_upstream_tries 2;

    # Machine-only route: issues a session for any user of the tenant. Uncomment to keep it
    # off the public internet. An exact-match location wins over the /api/auth/ prefix below,
    # so the rest of the auth API stays public.
    #
    # location = /api/auth/issue-token {
    #     # Your own backend only - internal network, VPN, the middleware's egress IP.
    #     allow 10.0.0.0/8;
    #     allow 203.0.113.20/32;
    #     deny  all;
    #
    #     limit_req zone=paprika_auth burst=10 nodelay;
    #     proxy_pass http://paprika;
    # }

    # Public tenant API, open to the world but rate limited on auth.
    location /api/auth/ {
        limit_req zone=paprika_auth burst=20 nodelay;
        proxy_pass http://paprika;
    }

    location /api/collections/ {
        # A blunt backstop, not a throttle: uploads and long polls should not let one IP occupy
        # the worker pool. 50 is far above what a normal client needs.
        limit_conn paprika_conn 50;

        # File uploads go through here. nginx defaults to 1m, which caps every upload at one
        # megabyte no matter what the field's maxSize says. 4m matches Undertow's own limit
        # (undertow.maxentitysize, 4 MiB), which is the real ceiling - a larger value here only
        # moves the rejection from a clean 413 to a dropped connection.
        client_max_body_size 4m;

        proxy_pass http://paprika;
    }

    location /api/realtime {
        # One tab is one stream, so ten per IP is generous for a browser app. Raise it if your
        # users share a NAT. Without it, an unauthenticated client can hold open as many streams
        # as it likes for an hour each - the request rate limit does not see them.
        limit_conn paprika_conn 10;

        proxy_pass http://paprika;
        proxy_buffering off;                 # let SSE stream through
        proxy_read_timeout 1h;
    }

    # Superadmin credentials: the only admin paths worth throttling. A regex location wins over
    # the prefix locations, so this has to stay narrow - it must not catch the UI's own assets.
    location ~ ^/(authenticate|api/admin/(login(/2fa)?|setup|token(/2fa)?))$ {
        if ($paprika_admin_allowed = 0) { return 403; }
        limit_req zone=paprika_auth burst=10 nodelay;
        proxy_pass http://paprika;
    }

    # The admin UI bundle. Deliberately not rate limited: one page load is 20+ files. The file
    # names are content hashed, so they can be cached forever; index.html is sent no-store by
    # Paprika, which is what makes a new version show up after a deploy.
    location ~ ^/assets/(js|css)/.+-[A-Za-z0-9_-]{6,}\.(js|css)$ {
        if ($paprika_admin_allowed = 0) { return 403; }
        proxy_pass http://paprika;

        # add_header in a location replaces the inherited ones, so HSTS is repeated here.
        add_header Strict-Transport-Security "max-age=63072000; includeSubDomains" always;
        add_header Cache-Control "public, max-age=31536000, immutable" always;
    }

    # Admin UI, restricted to trusted IPs. No throttle here - see the warning above.
    location / {
        if ($paprika_admin_allowed = 0) { return 403; }

        # A backup restore is a file upload on this path, and nginx's 1m default would reject
        # anything bigger with a 413. 4m is as far as it goes: Undertow refuses a larger request
        # body. See Backup & Restore for what that means for the size of an instance.
        client_max_body_size 4m;

        proxy_pass http://paprika;
    }
}

# Send plain HTTP to HTTPS.
server {
    listen 80;
    server_name paprika.example.com;
    return 301 https://$host$request_uri;
}
```

If your middleware runs on the same host, the cleanest variant is to not proxy the route at all - `location = /api/auth/issue-token { deny all; }` - and let the middleware call `http://127.0.0.1:8080/api/auth/issue-token` directly, bypassing the proxy.

The trailing `location /` block catches everything that isn't the public API, including `/admin`, `/api/admin`, `/api/meta`, `/login`, and the unhashed `/assets/boot.js`, so a single IP gate covers the whole admin UI. Keep the `/api/auth/` and `/api/collections/` blocks above it, since nginx matches prefix locations by longest match regardless of order but it reads more clearly this way. The two regex locations are the exception to that rule: regex matches are tried before the prefix match wins, which is why the throttled one is written to match only the superadmin credential paths.

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

    # Machine-only route: issues a session for any user of the tenant. Uncomment to keep it
    # off the public internet - your own backend (internal network or the middleware's egress
    # IP) stays allowed, everyone else gets a 403 before Paprika ever sees the request.
    #
    # @issue_token_external {
    #     path /api/auth/issue-token
    #     not remote_ip 10.0.0.0/8 203.0.113.20/32
    # }
    # respond @issue_token_external 403

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

    # Caddy has no built-in equivalent of nginx's limit_conn, so the open SSE streams on
    # /api/realtime are not capped here. Add a plugin that can do it, put nginx in front, or
    # cap concurrent connections per source at the firewall - see "Cap the concurrent realtime
    # connections" above for why this is not optional.

    # Caddy has no request body limit by default either. Undertow refuses a body over 4 MiB,
    # so there is nothing to raise - only something to know when an upload or a backup restore
    # comes back as a dropped request.
    request_body {
        max_size 4MB
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

Caddy gets you automatic Let's Encrypt certificates out of the box, so there's no certificate path to manage. The rate limiting directive comes from a community module; if you'd rather not build a custom Caddy binary, put nginx in front for the throttling or drop that block and rely on the IP restriction alone. The same caveat applies to concurrent connections: capping the realtime streams needs a module, a fronting nginx, or a firewall rule.

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

## Getting a 503 from the proxy after a restart or update

The symptom: you restart Paprika or install a new version, open the admin UI, and get a 503 - but the page is the *proxy's* error page, not Paprika's. A plain reload keeps showing it, a hard reload finally brings up the UI or the login page.

If the 503 page comes from nginx, Paprika never answered that request, so the cause is in the proxy config. There are two, and both are easy to rule out:

**1. The rate limit covers the admin UI.** This is the common one. `limit_req` answers with 503 by default, and one cold load of the admin UI is 20+ requests in the same second (document, `boot.js`, entry chunk, preloaded chunks, stylesheet, favicon, manifest, route chunk, first API calls). A zone of `rate=5r/s burst=10` therefore 503s a fresh page load *by design*, and because the bucket refills at five per second, the reload right after it can lose the document request too. It shows up after a restart or an update because the content hash in every filename changed, so the browser cache is empty and the whole burst goes to the proxy.

Check it with:

```bash
grep -rn "limit_req " /etc/nginx/
grep -c "limiting requests" /var/log/nginx/error.log
```

If a `limit_req` sits in `location /` - which earlier versions of the nginx example in this page did - move it to the auth and admin-credential locations as shown in the [nginx example](#nginx-example). Setting `limit_req_status 429` on top makes the difference between "too fast" and "app is down" visible in the browser and in your monitoring.

**2. The upstream is still marked dead.** nginx answers `no live upstreams` with a 503, not a 502. That happens when the proxy target is a server group in which every address is currently marked failed - and `proxy_pass http://localhost:8080` is such a group whenever `localhost` resolves to both `::1` and `127.0.0.1`. During the restart the requests fail, both addresses get taken out for `fail_timeout` (10 seconds by default), and every request in that window gets an instant 503, even though Paprika is already accepting connections again. Waiting out those seconds is precisely what "it works after the hard reload" feels like.

Check it with:

```bash
grep "no live upstreams" /var/log/nginx/error.log
```

The fix is an explicit upstream with `max_fails=0` and an IP literal, as in the example above. For a single instance there is nothing to fail over to, so there is no reason for nginx to keep a "this one is dead" flag at all.

A 502 (not 503) during the first seconds after a start is different and harmless: the app has not bound its port yet. The admin UI handles that case itself - `boot.js` polls `/health` and reloads once the server answers - so it resolves without a reload from you. Only an error page produced by the proxy can't be recovered from inside the browser, which is why the two items above have to be fixed in the proxy.

## A few more things worth doing

- **Lock down MongoDB.** The production config runs with authentication on (`auth: true`). Keep the database on a private network the outside can't reach, use a dedicated user with access to just the Paprika databases, and never publish the Mongo port. Everything a tenant stores lives here, one database per tenant.
- **Protect the storage directory.** `PAPRIKA_STORAGE` holds uploaded files. Put it on a volume only the Paprika process can read and write, and include it in your backups.
- **Take backups.** Use the [Backup & Restore](/admin-ui/backup-restore) tooling, and back up MongoDB and the storage directory on the infrastructure side too. Test a restore once in a while so you know it works before you need it.
- **Keep it updated.** Re-running the install script updates an existing installation while leaving your `.env` and `storage/` alone. Stay current for security fixes while Paprika is in beta.
- **Mind the setup link.** The one-time superadmin setup token is printed to the application log and expires after 30 minutes. Complete setup promptly, and don't leave that log where others can read it.
- **Set a log retention.** API request logs are kept according to the retention you configure under [Settings](/admin-ui/settings#request-logs). Pick a window that fits your needs instead of keeping everything forever.

Paprika already sends a strict Content Security Policy (including `frame-ancestors 'none'`, which blocks framing and clickjacking), so you don't need to add one at the proxy. Adding HSTS as shown above is a good complement; avoid setting a second CSP header, since two policies only ever combine to be more restrictive and will fight the one the app ships.
