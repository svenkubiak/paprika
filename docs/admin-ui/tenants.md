# Tenants

`/admin/tenants` — **superadmin only**. This is where tenants are created, edited, switched into, and deleted. Background on what a tenant is and how isolation works lives in [Concepts → Tenants](/concepts/tenants).

## The tenants table

Lists every tenant with its name, slug, database name, and status. Clicking a row (or its **Edit** button) opens the tenant editor; each row also has **Select** (switch the admin session into that tenant) and **Delete**.

## Creating a tenant

**New tenant** opens the editor with:

- **Name** — display name.
- **Slug** — normalized automatically as you type to lowercase letters, numbers, and hyphens (matching the backend's validation); used for login/registration. Must be unique across the whole instance, not just within this tenant.

Creating a tenant also creates its dedicated MongoDB database and seeds it with the tenant's internal `users` collection.

## Editing a tenant

Name and slug can be changed later. The database name is derived from the tenant's id and is not editable.

- **Webhook allowlist** — `host:port` combinations (comma-separated) this tenant's [collection hooks](/admin-ui/collection-hooks) and [global hooks](/admin-ui/global-hooks) may target even though they're loopback or private-network addresses, which are blocked by default to prevent one tenant's hooks from reaching another tenant's or the host's internal services. There's no format enforcement — a malformed entry simply never matches, it doesn't break saving. The check applies both when a hook is saved and every time it fires, so shrinking the allowlist takes effect on the next dispatch even for hooks that were already saved.

Self-registration, password reset, and email verification are **not** configured here — they're per-tenant toggles on the [Auth settings](/admin-ui/auth-settings) page, available once you've switched into that tenant.

## Switching tenants

Clicking **Select** on a tenant makes it the active tenant for the current admin session and takes you to the Dashboard. This is the same tenant switcher available in the sidebar dropdown. See [Concepts → Tenants](/concepts/tenants#the-tenant-switcher) for what "active tenant" controls.

## Deleting a tenant

::: danger Irreversible
Deleting a tenant permanently removes its **entire database** in one operation — every collection, every record, every tenant user, every rule and hook definition — and also deletes its uploaded files on disk (see [Concepts → Tenants](/concepts/tenants#isolation-one-database-per-tenant)). There is no undo, and no confirmation beyond the one dialog shown.
:::

There's no protection against deleting the tenant currently configured as the [default tenant](/concepts/tenants#the-default-tenant) — including the auto-created **Default** tenant — or against deleting the last remaining tenant. If the tenant you're deleting is the configured default, the delete dialog shows an extra warning, and Paprika automatically clears the default tenant setting so it doesn't keep pointing at a deleted tenant; pick a new default under [Settings](/admin-ui/settings#tenants) afterward if you still want one.
