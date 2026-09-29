# Auth settings

**Auth settings** is the per-tenant configuration page for how tenant users register, reset their passwords, and verify their email addresses. Find it in the sidebar under a tenant's section — it requires an [active tenant](/concepts/tenants#the-tenant-switcher).

All three features are **off by default**. Enable only what your app needs. Changes take effect immediately without a restart.

## Self-registration

Controls whether `POST /api/auth/register` accepts new sign-ups for this tenant. When enabled, anyone with the tenant slug can create an account without superadmin involvement. When disabled, new users can only be created by a superadmin through the [Users](/admin-ui/tenant-users) tab.

Self-registration bypasses the `users` collection's create rule — that rule gates the data-plane endpoint (`POST /api/collections/users`), not the auth endpoint. See [Tenant Users — Rules tab](/admin-ui/tenant-users#rules-tab) for details.

## Password reset

Enables `POST /api/auth/password/forgot` and `POST /api/auth/password/reset` for this tenant. Paprika sends the reset email itself over the instance [SMTP settings](/operations/going-to-production#email-smtp).

**Reset link URL** — the page in your app that accepts the token. Paprika builds the email link from this URL: if the URL contains a `{token}` placeholder it is substituted directly (`https://app.example.com/reset/{token}`), otherwise the token is appended as a query parameter (`https://app.example.com/reset?token=…`). The URL must be set for emails to go out; without it the token is issued but no email is sent.

The full flow:

1. Your app calls `POST /api/auth/password/forgot` with the user's `email` and `tenant` slug.
2. Paprika issues a token (30-minute TTL, single-use) and emails the link to the user.
3. The user follows the link into your app, which calls `POST /api/auth/password/reset` with the `token` and new `password`.
4. Paprika validates the token, sets the new password (16-character minimum), and invalidates the token.

The request endpoint always answers `200` regardless of whether the account exists, to prevent account enumeration. The confirm endpoint returns a single generic error for any invalid, expired, or already-used token.

## Email verification

Enables `POST /api/auth/verify/request` and `POST /api/auth/verify/confirm` for this tenant. Confirming a token sets `emailVerified = true` on the user record.

**Verification link URL** — same mechanics as the reset URL above: set it to a page in your app that reads the token and calls the confirm endpoint. Without it, no email is sent.

The full flow:

1. Your app calls `POST /api/auth/verify/request` with the user's `email` and `tenant` slug.
2. Paprika issues a token (30-minute TTL, single-use) and emails the verification link to the user.
3. The user follows the link into your app, which calls `POST /api/auth/verify/confirm` with the `token`.
4. Paprika validates the token, sets `emailVerified = true`, and invalidates the token.

**Require for login** — when enabled, any login attempt where the user's `emailVerified` is still `false` is rejected before a token is issued. Use this to enforce verification as a precondition for access. This option is only available when email verification is enabled and has no effect if verification is off.

`emailVerified` is readable through the data plane (your app and [rules](/admin-ui/collection-rules) can inspect it) but is never client-writable and is not part of the editable schema.

### Mobile and native apps

The verification link URL works with deep links. Set it to a Universal Link (iOS) or App Link (Android) that your app is registered to handle, or a custom URL scheme (`myapp://verify?token={token}`). The app extracts the token and calls `POST /api/auth/verify/confirm` — the API call itself is a plain HTTP request that works identically on any platform.

## API keys

API keys authenticate a **machine** as an existing tenant user - a backend job, a middleware, an
integration - without a password login and without a second factor. Manage them in the **API
keys** card at the bottom of this page (it needs an active tenant).

**Creating a key** asks for three things:

- **Name** - who uses it, e.g. `middleware-prod`. It shows up in the key list and in the
  [request log](/admin-ui/request-logs) of every request made with it.
- **Bound user** - the tenant user the key authenticates as. The key inherits exactly that user's
  permissions, no more and no less.
- **Expires** - optional. Without it the key is valid until revoked.
- **Bypass collection rules** - off by default. Switch it on only for a trusted backend service
  (see below).
- **Bypass hooks** - off by default. Switch it on only for the service a hook itself calls back
  into Paprika (see below).
- **Allowed source ranges** - optional. CIDR ranges the key may be presented from; empty means
  anywhere, which is the default and the old behaviour (see
  [Restricting a key to a source](#restricting-a-key-to-a-source)).

The plaintext key is shown **once**, right after creating it, with a copy button:

```
pk_3Yb1f…
```

Paprika stores only a SHA-256 hash of it, so there is no way to display it again - no
"show key" endpoint exists. Lose it and you revoke the key and create a new one.

**Using a key** - send it as a bearer token, exactly where an access token would go:

```http
GET /api/collections/posts
Authorization: Bearer pk_3Yb1f…
```

Everything under `/api/auth/*` and `/api/collections/*` accepts it, including
`GET /api/auth/me` (returns the bound user) and `POST /api/auth/issue-token` (works if the bound
user is a token issuer). There is no login step and no refresh: the key *is* the credential.

A key is never *exchanged* for a token. The two mechanisms are independent: a key answers **how**
a caller proves an identity, [trusted token issuance](#trusted-token-issuance) answers **for
whom** a session is minted. They meet only in that `issue-token` accepts a key as the caller's
credential, next to an access token - which is exactly what lets a middleware work without any
password at all.

**The list** shows each key's name, its non-secret prefix, the bound user, its source binding
(the number of ranges, with the ranges themselves in the tooltip, or `anywhere`), when it was
last used (updated at most once per minute, so it never slows a request down), its expiry and
its status.

Two ways to get rid of a key, and the difference matters:

| | What happens | When to use it |
|---|---|---|
| **Revoke** | The key stops authenticating immediately, its entry stays in the list marked `revoked` | Retiring a key, rotating one out, reacting to a leak - you keep the record of what existed and when it was last used |
| **Delete** | The key stops working *and* its record is removed from the list | Housekeeping: a mistyped key, a test key, a service that is gone for good and should stop cluttering the list |

Revoke is the safer default. Deleting a key that is still active cuts off whoever holds it without
leaving any trace that it ever existed, so the dialog warns about exactly that; revoke first if
you want the history. Deleting the bound user revokes its keys (the records stay), and deleting
the tenant removes them.

### Bypass collection rules

The four rule presets (*No access*, *Public*, *Signed in*, *Own records*) cannot express "this one
caller, and nobody else". A backend service that has to work on records of *all* users, while the
collections stay *No access* for clients, therefore gets its own switch: a key created with
**Bypass collection rules** is not checked against the rules on `/api/collections/**` at all.

Such a key is marked with a red **bypasses rules** badge in the key list, and the request log
shows a **rules bypassed** badge on every request made with it.

What stays true for a bypassing key:

- it cannot reach the admin API (`/api/meta/**`, `/api/admin/**`) - no schema, rules, hooks,
  tenants, backups or settings;
- it cannot reach another tenant, and an unknown collection is still a `404`;
- it cannot be bound to a superadmin;
- **hooks still fire** - a blocking `beforeCreate` stops it like any other caller, so field
  guards and approval gates keep working, unless the key also carries
  [Bypass hooks](#bypass-hooks);
- the `users` collection keeps its credential and role protections.

The switch is only available **while creating** the key. There is no way to turn it on or off
afterwards: revoke the key and issue a new one, which is the safe path anyway.

That is the line between the three per-key fields, and it is worth stating once: `bypassRules`
and `bypassHooks` **give** reach, so they can only be set while creating the key - a credential
already in someone's hands must never grow. [`allowedCidrs`](#restricting-a-key-to-a-source)
**takes** reach away, so it is editable at any time - it can only ever make the key work in fewer
places, and the addresses it names change on their own when a host moves.

::: danger Whoever holds a bypassing key has all the data
A rule-bypassing key reads and writes every record of every collection of this tenant, across all
users - but it has no access to the tenant's configuration. Issue one per service, keep it in that
service's environment variables, and use an ordinary key whenever the service fits inside the
rules of its user.
:::

::: danger Whoever holds the key is the bound user
A key carries every permission the bound user's [rules](/admin-ui/collection-rules) grant,
including the ability to mint sessions for other users if that user is listed under
[token issuers](/admin-ui/tenants#editing-a-tenant). Keys never grant superadmin rights and never
bypass rules, but within the bound identity they are complete. Bind them to a dedicated service
account, keep them server-side, and rotate them.
:::

### Bypass hooks

A global `beforeRequest` hook is regularly used as an **external authorizer**: Paprika asks a
service on every request whether the caller may proceed. If that service answers by asking
*Paprika* something - "is this device registered?" - and uses its own API key to do so, its lookup
runs straight back into the hook it came from:

```
Client → Paprika  ──beforeRequest──►  Service
                                        │  GET /api/collections/device_attestations?filter=…
                                        ▼
                                      Paprika  ──beforeRequest──►  Service
                                                                     │ (recognises itself,
                                                                     ▼  lets it through)
```

The inner call has exactly one possible outcome - the service recognises its own identity - so the
roundtrip is decided before it starts. It costs a worker on each side and nothing else, right up
to the moment the outer request's time budget runs out on a lookup that was never in doubt: the
guard reads the failed lookup as "device unknown" and answers `403`, and the registration that
would heal the state runs through the same path.

A key created with **Bypass hooks** runs **no hook at all** - neither the global `beforeRequest`
hooks nor the collection hooks, on collection routes, auth routes and file routes alike. Its
requests behave as if the tenant had no hooks configured.

Such a key is marked with an orange **bypasses hooks** badge in the key list, and the request log
shows a **hooks bypassed** badge on every request made with it. That badge is the point: without
it, an entry with no hook execution is indistinguishable from one whose hook silently failed.

This is a per-key flag rather than a blanket rule for API keys, on purpose. Another tenant may be
using hooks precisely to log or narrow **machine** writes, and exempting every key would be a
silent loss for them. And a second, less trusted key would inherit the exemption of the first,
even though the hook service can tell the two apart by the bound user today.

What the flag does **not** weaken: rules. A hook-free key is still checked against the collection
rules unless it *also* carries [Bypass collection rules](#bypass-collection-rules) - the two flags
are independent, and either one alone leaves the other line of defence in place.

Like the rule bypass, the switch is only available **while creating** the key, and issuing one is
written to the server log.

::: warning Your authorizer stops seeing this caller
If a hook is what enforces device attestation, tenancy checks or an approval gate, a hook-free key
is outside all of it. Issue one only for the service the hook itself calls, keep it bound to that
service's own user, and use an ordinary key for everything else that service does.
:::

### Restricting a key to a source

An API key is a bearer credential with a single factor, no expiry unless you set one, and - with
[Bypass collection rules](#bypass-collection-rules) - the reach of a whole tenant. It is used
machine to machine, which means from a small and known set of addresses; it is stolen somewhere
else entirely, and then used from there. **Allowed source ranges** closes that gap: a key with
ranges is only accepted when it arrives from one of them.

Set them while creating the key, or later with the network button next to a key in the list. One
CIDR range per line, IPv4 and IPv6 alike:

```
10.200.0.0/24
2a01:4f8:c17:c74c::1/128
```

A bare address without a prefix length is accepted as a shorthand and stored as `/32` or `/128`.
Host bits below the prefix are masked off, so `10.200.0.7/24` is saved - and shown back - as
`10.200.0.0/24`. Both happen when the key is saved, not on every request. A range Paprika cannot
parse is rejected with a `400` and nothing is written: a malformed entry that was quietly dropped
would leave you believing you had excluded a network you had not.

Only CIDR ranges are accepted. No hostnames, no wildcards, no geo lookup - all three would have
to be resolved while a request is being authenticated, which would put an external service, its
latency and its outages into the authentication path.

Over the API this is `PATCH /api/meta/tenants/{tenantId}/api-keys/{keyId}` with
`{"allowedCidrs": [...]}`; an empty list lifts the binding. It is the only field of an existing
key that route accepts - a body naming `bypassRules`, `bypassHooks`, `name`, `userId` or
`expiresAt` is answered with a `400` rather than quietly ignored, so a `204` can never be read
as "the flag is set now".

**A rejected key looks exactly like an invalid one.** Same `401`, same body, same
`WWW-Authenticate` header. A distinct error would tell whoever presented the key that the secret
itself is good and only the location is wrong, which is precisely what someone holding a stolen
key would like to learn. The [request log](/admin-ui/request-logs) does say it - the entry for
such a request names the key and the reason - so you are not left guessing why a key you know is
valid stopped working.

::: danger The address checked is the peer of the connection, not `X-Forwarded-For`
Paprika compares the ranges against the address of the TCP connection the request arrived on. It
does **not** read `X-Forwarded-For`, `X-Real-IP` or `Forwarded`, and it never will: those are
written by whoever sends the request, so a binding that honoured them could be lifted by the very
caller it is meant to keep out, by adding one header.

The consequence follows directly: **if the key reaches Paprika through your reverse proxy, the
proxy is what you are binding to**, and the restriction does nothing about where the real caller
sits. It works when the calling machine reaches Paprika directly - over the internal network, a
private interface or the loopback - which is the normal shape of a server-to-server integration
and the case this feature is for. The request log's `clientIp` field is a separate thing with a
separate purpose: it *is* read from the proxy headers, because a log entry is a record, not an
authorization decision.
:::

#### Block API keys on the public vHost

There is a measure that sits in front of this one, on your side, and it is worth taking
deliberately. If a key is only ever used server to server - the normal case - the calling machine
reaches Paprika over the internal network, not through the public vHost. Then nothing legitimate
ever presents a key on the public host, and you can refuse them there outright:

```nginx
# Paprika accepts an API key as `Authorization: Bearer pk_…` on every route. If you use keys
# server-to-server only, turn them away here: a key coming through the front door is either a
# mistake or stolen.
# 401 rather than 403 - the caller does not learn whether the key would have been valid.
if ($http_authorization ~* "^Bearer\s+pk_") { return 401; }
```

This is optional - there are setups where an integration legitimately comes in over the public
host, and then this rule would break it. But if you use API keys at all, decide it consciously:
the block costs one line and takes the entire internet away from a key that has leaked.

`allowedCidrs` is the same idea inside Paprika, and it keeps working on the day the proxy config
is wrong. The two together are defense in depth, not redundancy: the proxy rule decides which
door a key may come through, the ranges decide which machine may hold it.

Keys cannot be bound to a superadmin: that would be a cross-tenant bypass credential, which is
deliberately not part of this feature.

## Trusted token issuance

Sometimes a user is authenticated **outside** Paprika — a middleware verifies an Apple Sign-In
identity token, a SAML assertion, or a magic link — and afterwards needs a Paprika session for
exactly that user. No password is involved, and the middleware must not know one.

`POST /api/auth/issue-token` covers that case (the caller's bearer token may be an access token
or an [API key](#api-keys) of an allowlisted user):

```http
POST /api/auth/issue-token
Authorization: Bearer <access token of a tenant user listed in tokenIssuers>
Content-Type: application/json

{ "userId": "<id of a user of the same tenant>" }
```

The response is identical to a normal login response (`accessToken`, `refreshToken`, `tokenType`,
`expiresIn`), so a caller needs no special parsing. The issued token belongs to the **target**
user: `GET /api/auth/me` with it returns that user's record.

Two conditions must both hold:

1. The caller presents a valid bearer token of a **tenant user** of that tenant. Guests and
   superadmin tokens are rejected — a superadmin token carries no unambiguous tenant-user identity.
2. The caller's own user ID is listed in the tenant's **token issuers** setting, maintained by a
   superadmin on the [Tenants](/admin-ui/tenants#editing-a-tenant) page. Empty (the default for
   every existing tenant) means nobody can, and the endpoint answers `403`.

The tenant is taken **only** from the caller's bearer token; there is no `tenant` field in the
body, so the endpoint can never cross a tenant boundary.

| Situation | Response |
|---|---|
| No or invalid bearer token, guest, or superadmin token | `401 {"error":"Unauthorized"}` with `WWW-Authenticate: Bearer` |
| Caller not listed in token issuers (or the list is empty) | `403 {"error":"Forbidden"}` |
| `userId` missing or blank | `400` (bean validation) |
| Target user unknown, inactive, or in another tenant | `404 {"error":"User not found"}` — deliberately the same answer for all three, so the endpoint can't be used to enumerate users across tenants |
| Target user is the caller | allowed, no special case |

A successful issue fires the `afterLogin` hook with the **target** user's id, exactly like a login
does, so existing hook-based bookkeeping keeps working.

Because nothing in a client app ever calls this route, it is a good candidate for being reachable
from your own network only - see
[Going to production](/operations/going-to-production#keep-the-token-issuing-endpoint-off-the-public-internet)
for nginx and Caddy snippets.

::: danger Security assumption
Anyone listed in token issuers can assume the identity of **every** user of that tenant. Treat
that access token like a master key: give it to a dedicated service account of your own backend,
store it server-side only, and never ship it into a mobile or browser client.
:::

This replaces the older workaround of answering a `beforeLogin` hook with
`{"continue": false, "issueTokenFor": {"userId": "…"}}`. That path still works unchanged (see
[Roles & Permissions](/concepts/roles-and-permissions#trusted-token-issuance)), but it puts the
whole authorization decision into a hook behind a public endpoint, which is why the explicit
endpoint exists.
