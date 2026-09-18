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

**The list** shows each key's name, its non-secret prefix, the bound user, when it was last used
(updated at most once per minute, so it never slows a request down), its expiry and its status.

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
  guards and approval gates keep working;
- the `users` collection keeps its credential and role protections.

The switch is only available **while creating** the key. There is no way to turn it on or off
afterwards: revoke the key and issue a new one, which is the safe path anyway.

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
