# Collection: Hooks

`/admin/collections/:collection/hooks` — configure HTTP webhooks that fire on this collection's CRUD lifecycle. For hooks that fire before *every* collection (and before auth), see [Global Hooks](/admin-ui/global-hooks) instead.

## Events

A hook is tied to exactly one event:

| Event | Timing | Blocking? |
|---|---|---|
| `beforeList` | Before a list query runs | Yes |
| `beforeView` | Before a single record is returned | Yes |
| `beforeCreate` | Before a record is inserted | Yes |
| `afterCreate` | After a record is inserted | No |
| `beforeUpdate` | Before a record is patched | Yes |
| `afterUpdate` | After a record is patched | No |
| `beforeDelete` | Before a record is deleted | Yes |
| `afterDelete` | After a record is deleted | No |

**Before-hooks are blocking**: Paprika waits for a response and can reject or modify the request based on it. **After-hooks run asynchronously** — they've already succeeded by the time the hook fires, so they can't affect the response the API client already received; they're for side effects (notifications, sync to another system, analytics), not validation.

## Auth events (users only)

The tenant [`users` collection](/admin-ui/tenant-users) has six extra events that the event dropdown only offers there. They hook into the auth flows instead of a record's CRUD lifecycle:

| Event | Fires on | Blocking? |
|---|---|---|
| `beforeRegister` | `POST /api/auth/register` | Yes |
| `afterRegister` | `POST /api/auth/register` | No |
| `beforeLogin` | `POST /api/auth/login` | Yes |
| `afterLogin` | `POST /api/auth/login` | No |
| `beforeRefresh` | `POST /api/auth/refresh` | Yes |
| `afterRefresh` | `POST /api/auth/refresh` | No |

A blocking auth hook that returns `{ "continue": false, ... }` rejects the sign-up, login, or refresh with the error you hand back, and a `beforeRegister` hook can rewrite the body before the account is created, just like a `beforeCreate` hook. The plaintext password is deliberately kept out of the envelope: login and refresh deliveries never include it, and register redacts it before sending. For these events `context.collection` is `users`, `context.recordId` points at the account where one exists, and `data.body` is the raw auth payload.

Password reset and email verification are **not** hooks. Paprika sends those emails itself over SMTP, see [Password reset and email verification](/admin-ui/tenant-users#password-reset-and-email-verification).

Self-registration through `/api/auth/register` fires only the register events, never `beforeCreate`/`afterCreate`. Creating a user from the admin Data tab or through `POST /api/collections/users` is the opposite: it fires the create events, not the register ones. So one new account never triggers both pairs.

## Configuring a hook

- **URL** and **method** — where the request is sent (default `POST`). Must be `http` or `https`. The URL's host is resolved and checked, both at save time and again on every dispatch: it can't point at loopback, link-local, private/site-local, or multicast addresses (IPv4 and IPv6, including the `fc00::/7` Unique Local Address range) — so a hook can't target `localhost`, `127.0.0.1`, or an internal-network IP — unless the tenant's **webhook allowlist** (configured in [Tenants → Editing a tenant](/admin-ui/tenants#editing-a-tenant)) explicitly lists that exact `host:port`. This is a per-tenant escape hatch, not an instance-wide switch: on a shared multi-tenant instance, one tenant allowlisting a host never affects what any other tenant can reach. Point hooks at a publicly reachable endpoint (or a tunnel like ngrok during local development) unless you've deliberately allowlisted a local target. Paprika never follows HTTP redirects when delivering a hook — an allowed host that responds with a redirect can't be used to reach a target that wouldn't otherwise pass this check; a `3xx` response is treated like any other non-2xx response.
- **Timeout** — how long Paprika waits for a response, in milliseconds. Defaults to 5000ms for before-hooks and 30000ms for after-hooks (since after-hooks don't block the client); the maximum allowed value is **30000ms** regardless of event type.
- **Secret** — used to sign every delivery with HMAC-SHA256, sent as the `X-Paprika-Signature` header, so your endpoint can verify the request actually came from Paprika (see [Verifying the signature](#verifying-the-signature) below).
- **Priority** — lower runs first when multiple hooks share the same event on the same collection.
- **Enabled** — toggle a hook off without deleting it.
- **Include schema** — attach the collection's field definitions (`schema.fields`) to the payload, useful if your hook needs to know field types without a separate lookup.
- **Fail open** — if enabled, a failed, timed-out, or non-2xx hook response is treated as "allow the request anyway" instead of rejecting it. Leave this off for hooks that enforce business rules; turn it on for hooks that are best-effort (e.g. analytics pings) where an outage shouldn't block API traffic.

## The request Paprika sends

Every hook delivery is an HTTP request with:

```
Content-Type: application/json
X-Paprika-Event: beforeCreate
X-Paprika-Collection: posts
X-Paprika-Delivery-Id: 9f2c...          (unique per delivery, for idempotency/dedup on your side)
X-Paprika-Signature: sha256=<hex-hmac>  (see below)
```

...and a JSON body shaped like this (example for `beforeUpdate` on a `posts` collection):

```json
{
  "paprika": {
    "version": 1,
    "event": "beforeUpdate",
    "deliveryId": "9f2c...",
    "timestamp": "2026-09-06T10:15:30.123Z"
  },
  "context": {
    "collection": "posts",
    "recordId": "0198a3f2-7b1c-7d4e-9f0a-1c2d3e4f5a6b",
    "auth": { "id": "user-abc", "role": "user" },
    "http": { "method": "PATCH", "path": "/api/collections/posts/0198a3f2-...", "headers": { "Content-Type": ["application/json"] } }
  },
  "data": {
    "body": { "title": "New title" },
    "record": { "id": "0198a3f2-...", "title": "Old title", "owner": "user-abc", "createdAt": "...", "updatedAt": "..." }
  },
  "schema": null
}
```

- **`data.body`** is the incoming request payload (`null` for `beforeList`/`beforeView`/`beforeDelete`, which have no submitted body).
- **`data.record`** is the record as it stood *before* this operation for before-hooks (`null` for `beforeCreate`/`beforeList`, since nothing exists yet); for after-hooks it's the record *after* the change (the just-created, just-updated, or just-deleted record).
- **`context.recordId`** is populated even for `beforeCreate` — Paprika assigns the record's id before running before-hooks and includes it in both `context.recordId` and `data.body.id`, so a `beforeCreate` hook already knows the final id of the record about to be inserted.
- **`schema`** is `null` unless **Include schema** is enabled on the hook.
- **`context.http.headers`** only contains a fixed, non-sensitive subset of the incoming request headers: `Content-Type`, `User-Agent`, `Accept`, `Accept-Language` and `X-Request-Id`. Credential-carrying headers such as `Cookie`, `Authorization` and `X-Api-Key` are never forwarded, since a hook URL may point at a third party. If your endpoint needs a secret, configure it as a static outgoing header on the hook instead.

### Verifying the signature

`X-Paprika-Signature` is `sha256=<hex>`, where `<hex>` is the HMAC-SHA256 of the **exact raw JSON body bytes**, keyed with the hook's secret. Recompute it over the raw request body you received (don't re-serialize the parsed JSON, since that can change key order/whitespace and produce a different signature) and compare against the header before trusting the payload.

## Blocking-hook response contract

For before-hooks, Paprika reads your endpoint's JSON response to decide what happens next:

- **No body, or a 2xx response with no `continue`/`data` fields** → the operation proceeds unchanged.
- **`{ "continue": false, "error": { "status": 422, "message": "title too short" } }`** → the operation is aborted; the API client receives the given status (defaulting to 400) and the `error` object as the response body.
- **`{ "data": { "body": { ...replacement fields... } } }`** on a 2xx response → the operation proceeds, but with this body instead of the client's original submission (used to inject/normalize fields before create/update).
- A **non-2xx** response with no recognizable `continue`/`error` shape, a timeout, or a network error → treated as hook failure: the operation is aborted with `502` unless **Fail open** is enabled, in which case it proceeds unchanged.

### Chaining multiple hooks

For a given request, hooks run in this order, each able to see the (possibly already-modified) body from the previous one:

1. Matching **global `beforeRequest` hooks** (see [Global Hooks](/admin-ui/global-hooks)), in priority order.
2. This collection's hooks for the specific event (e.g. `beforeCreate`), in priority order.

The first hook in the chain that aborts stops everything after it. If several hooks return a modified body, each next hook (and ultimately the real create/update) sees the cumulative result, not just the original request.

## Testing a hook

The **Test** action on each row sends a sample payload to the configured URL immediately (no real record is created/changed) and shows the HTTP status, latency, computed signature, and response body — useful for verifying your endpoint before relying on it in production.

## File operations don't trigger hooks

Uploading, downloading, or deleting a file on a `FILE` field does **not** fire any of the events above — only standard record create/update/delete on the [Data tab](/admin-ui/collection-data) do. This is a known gap, not an access-control gap: file downloads and deletes still fully respect the collection's [Rules](/admin-ui/collection-rules).
