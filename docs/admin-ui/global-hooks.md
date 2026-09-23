# Global Hooks

`/admin/global-hooks` — **superadmin only**, requires an [active tenant](/concepts/tenants). Configures tenant-wide `beforeRequest` hooks: they run before *every* matching collection operation, and before the auth flows (login, register, refresh) — unlike [collection hooks](/admin-ui/collection-hooks), which are scoped to one collection and one specific lifecycle event.

Global hooks always run **before** any collection-specific hooks for the same request.

## Scope

Each global hook applies either:

- **to all collections**, or
- **to a specific list of target collections**, chosen explicitly.

::: tip Scope doesn't apply to auth flows
The collection scope above only matters for collection operations. For `login`/`register`/`refresh`, **every enabled global hook fires regardless of its target-collection setting** — a hook scoped to just `posts` still runs before a login request. If you only want a hook to run for collection operations and never for auth, that's not currently configurable — it always sees both.
:::

## File routes: off by default

A global hook runs before every matching collection operation and before the auth flows — but **not** before the file routes (`GET`/`DELETE /api/collections/{collection}/{id}/files/{field}[/{fileId}]`) unless you switch that on per hook.

**Also guard file routes** (`includeFileRoutes`) extends the hook to those four routes. It is off by default, and deliberately so: an existing hook was written for collection and auth deliveries, and a guard built to reject what it does not recognize would otherwise start blocking **every download** the moment it sees a new route type. With `failOpen: false`, an unreachable hook would turn every image into a `502`.

With the switch on, a file request behaves like any other collection route:

- the **collection scope applies** — a hook limited to three collections does not see downloads from the others;
- `priority`, `timeout`, `failOpen` and `forwardHeaders` are the same settings the hook already uses;
- the envelope carries `event: beforeRequest`, `context.collection`, `context.recordId` (the `{id}` from the path) and `context.http.method`/`path`, while `data.body` and `data.record` are `null`. The record is **not** loaded: `beforeRequest` is the upfront filter, not the lifecycle event. A hook that needs the record belongs on `beforeView`/`beforeUpdate`.
- a rejection (`continue: false`) answers the file request with the hook's status and body; the file is neither delivered nor deleted. A body returned by the hook is ignored here — a download has no request body to mutate.
- an unknown collection is still a plain `404`, and no hook runs for it: a hook must not become an oracle for which collections exist.

Every delivery shows up in the [request log](/admin-ui/request-logs) detail view with its event, outcome and duration, so the cost is visible per request.

::: warning One hook roundtrip per file request
A `beforeRequest` hook that itself calls the Paprika API (a guard looking up a device or license record, for example) creates **nested calls inside a request Paprika is holding open**. With this switch on, that applies to every file request too — and an image list fires many of them in parallel.

Experience from operating a tenant: without a cache in the hook, this does not fit into Paprika's 5-second budget for blocking hooks and ends in `failed` → `502` on a cold start. **If you switch this on, the hook needs its own cache and a timeout below Paprika's budget.**
:::

The switch only exists for `beforeRequest`: [collection hooks](/admin-ui/collection-hooks) never receive it (the API drops it), and a definition that carries it on another event is rejected when it is saved.

## Configuration

The same fields as collection hooks — URL, method, timeout, HMAC secret, priority, enabled flag, include-schema option, fail-open behavior — configured once and applied across the chosen scope. See [Collection Hooks](/admin-ui/collection-hooks#configuring-a-hook) for what each option means, including the URL restrictions (no `localhost`/private-network targets unless allowlisted per tenant, see [Tenants → Editing a tenant](/admin-ui/tenants#editing-a-tenant)) and the 30-second timeout ceiling.

## Forwarding request headers

A `beforeRequest` hook is an external authorization decision, so it often needs to see the headers the client sent to legitimize itself (a device proof, a request signature, an idempotency key, a custom auth scheme). By default it doesn't: `context.http.headers` carries only the fixed allowlist (`Content-Type`, `User-Agent`, `Accept`, `Accept-Language`, `X-Request-Id`).

**Forward request headers** (`forwardHeaders`) extends that list per hook. It is empty by default — nothing changes unless you configure it — and names are matched case-insensitively. Everything you list is **sent to the hook's configured URL**, which may be a third party, so list only what the target actually needs.

`Authorization`, `Cookie`, `Set-Cookie` and `Proxy-Authorization` are never forwardable: they authenticate the caller against Paprika itself, and a hook target that received one could impersonate them. Saving a hook that lists one of them is rejected with `400`, and a definition that carries one anyway (e.g. written straight into the database) still won't leak it at dispatch time.

Since these hooks also gate auth flows, the [request/response envelope](/admin-ui/collection-hooks#the-request-paprika-sends) looks slightly different for a login/register/refresh delivery: `context.collection` and `context.recordId` are `null`, and `data.body` is the raw login/register/refresh payload instead of a collection record body.

Forwarding works the same on file routes when **Also guard file routes** is on: the configured headers are in `context.http.headers`, the blocked ones never are.

## Testing

Same as collection hooks: **Test** sends a sample payload immediately and shows the resulting status, latency, and response, without affecting real data.

## When to use this instead of a collection hook

Reach for a global hook when the same logic needs to apply everywhere (e.g. request logging, a shared auth-side-effect, or a cross-cutting validation), instead of copy-pasting the same hook definition onto every collection individually.
