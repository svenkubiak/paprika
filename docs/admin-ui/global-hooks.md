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

## Configuration

The same fields as collection hooks — URL, method, timeout, HMAC secret, priority, enabled flag, include-schema option, fail-open behavior — configured once and applied across the chosen scope. See [Collection Hooks](/admin-ui/collection-hooks#configuring-a-hook) for what each option means, including the URL restrictions (no `localhost`/private-network targets unless allowlisted per tenant, see [Auth settings](/admin-ui/auth-settings#webhook-allowlist)) and the 30-second timeout ceiling.

Since these hooks also gate auth flows, the [request/response envelope](/admin-ui/collection-hooks#the-request-paprika-sends) looks slightly different for a login/register/refresh delivery: `context.collection` and `context.recordId` are `null`, and `data.body` is the raw login/register/refresh payload instead of a collection record body.

## Testing

Same as collection hooks: **Test** sends a sample payload immediately and shows the resulting status, latency, and response, without affecting real data.

## When to use this instead of a collection hook

Reach for a global hook when the same logic needs to apply everywhere (e.g. request logging, a shared auth-side-effect, or a cross-cutting validation), instead of copy-pasting the same hook definition onto every collection individually.
