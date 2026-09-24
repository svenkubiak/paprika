# Tenants

Paprika is multi-tenant at its core. A **tenant** is an isolated space with its own collections, its own tenant users, and its own registration settings — everything a tenant's API consumers see is scoped to that tenant alone.

## Isolation: one database per tenant

Every tenant gets its own dedicated MongoDB database, named `tenant_<tenantId>`. This isn't a shared collection filtered by a `tenantId` column — it's a separate physical database per tenant, on the same MongoDB cluster. Deleting a tenant drops its entire database in one operation, including its `users` collection, all data collections, all field/rule/hook definitions, and all request logs — and also removes its uploaded files, which live on disk under `<PAPRIKA_STORAGE>/<tenantId>/`, outside MongoDB. Both cleanups happen as part of the same delete operation, so nothing is left behind on disk.

A tenant has:

- **`name`** — display name shown in the admin UI.
- **`slug`** — the identifier used when logging in or registering (`POST /api/auth/login`, `POST /api/auth/register`). Must be lowercase letters, numbers, and hyphens only (no underscores, no uppercase) and is unique across the whole instance.
- **`databaseName`** — derived automatically from the tenant id (`tenant_<tenantId>`), shown read-only in the Tenants list.
- **`status`** — `active` by default; only active tenants are resolved for login, registration, and the default-tenant fallback. There's currently no way to change a tenant's status from the admin UI — every tenant created there is `active`.
- **`registrationEnabled`** — whether `POST /api/auth/register` accepts new self-service signups for this tenant.

::: tip First tenant is created automatically
On first start, Paprika creates a tenant named **Default** with slug **`default`** if none exists yet. This is also the fallback tenant used by [the default tenant setting](#the-default-tenant) when nothing else is configured.
:::

## Tenant users vs. the superadmin

Tenant users are completely separate from the superadmin account. They live inside the tenant's own database, authenticate with a JWT access/refresh token pair via `/api/auth/login` and `/api/auth/refresh`, and only ever talk to `/api/collections/*`. They have no access to the admin UI at all. See [Roles & Permissions](/concepts/roles-and-permissions) for how their access to data is controlled.

## How a request resolves to a tenant

There's no subdomain-based routing. Instead, Paprika resolves which tenant's database to use per request based on who's calling:

- **Tenant user with a bearer JWT** — the tenant is embedded in the token's `tid` claim; it's fixed for the token's lifetime.
- **Superadmin in the admin UI** — the tenant currently "active" in their session, set by [switching tenants](/admin-ui/tenants) from the Tenants page or the sidebar tenant selector.
- **Login / registration requests** — the client sends an explicit `tenant` slug in the request body. Registration always requires it. Login can resolve the tenant from the username alone, but only within the limits below — send `tenant` and you never depend on them.
- **Anonymous / guest requests** — fall back to the configured **default tenant** (see below).

### Logging in without a tenant slug

`POST /api/auth/login` accepts a request without `tenant`. Paprika then has to find out which
tenant the username belongs to, and that means **one database query per active tenant** — a
single unauthenticated request whose cost grows with your customer count. That convenience
therefore has limits, and they are deliberate:

| Situation | What the client gets |
|---|---|
| The username exists in exactly one active tenant | A normal login |
| The username exists in several tenants | `401 Invalid username or password` |
| The username exists nowhere | `401 Invalid username or password` |
| The instance has **more than 25 active tenants** | `401 Invalid username or password`, for every slug-less login |

The three failure rows answer identically on purpose. Which tenants know a username is
information about somebody else's user base, and an unauthenticated caller must not be able to
read it out of a status code — nor out of the response time, which is why a failed login costs a
password hash whether or not the account exists. The reason a particular login failed is in the
server log, not in the response.

::: warning The 25-tenant cap is a cliff, not a slowdown
Creating the 26th active tenant stops the lookup for the **whole instance**: from then on every
login without a `tenant` slug is answered with a 401, including logins that worked the day
before. There is no partial degradation and no warning to the client — only a `WARN` line in the
server log.

So treat the slug-less login as a convenience for development and single-tenant installs. **Any
client that is meant to keep working should always send `tenant`**, and if you operate an
instance that will grow, make that a rule before you approach the limit rather than after.
:::

## The tenant switcher

A superadmin's admin session can only have one **active tenant** at a time. All tenant-scoped pages — Tenant Users, Request Logs, and every Collection page — require an active tenant, and redirect to the Tenants page if none is selected. Switching is done either from the sidebar dropdown or by clicking **Select** on a row in the [Tenants page](/admin-ui/tenants).

Superadmins bypass a tenant's own collection access rules entirely once they've switched into it — see the "admin bypass" note in [Roles & Permissions](/concepts/roles-and-permissions).

## The default tenant

Under **Settings → Tenants**, you can pick a **default tenant**. It's used in two places:

- It's the tenant a superadmin lands in automatically after signing in, if no tenant is currently active.
- It's the tenant used for anonymous/guest API requests when no tenant can otherwise be resolved.

Resolution order: the tenant configured in Settings (if it's still `active` — a deleted or deactivated tenant is skipped), otherwise the auto-created **Default** (`default`) tenant if it exists and is active, otherwise there is no default and guest requests are rejected.

There's nothing stopping you from [deleting](/admin-ui/tenants#deleting-a-tenant) whichever tenant is currently configured as default — including the auto-created **Default** tenant itself. Doing so automatically clears the default tenant setting (so it doesn't keep pointing at a tenant that no longer exists), which means the resolution above falls straight through to "no default" unless the `default`-slug tenant still exists separately.

## Self-registration

Each tenant independently controls whether `POST /api/auth/register` is open. Toggle it under [Auth settings](/admin-ui/auth-settings). When disabled (the default), new tenant users can only be created by a superadmin.
