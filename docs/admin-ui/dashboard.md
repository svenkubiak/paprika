# Dashboard

The Dashboard (`/`) is the landing page after signing in. It's read-only — an overview, not a configuration page.

## Who sees what

Everyone signing into the admin UI is a superadmin (tenant users can't reach the admin UI at all), so the dashboard is always shown from the superadmin's perspective.

## Stat cards

Four cards summarize instance health at a glance:

- **Database** — whether the MongoDB connection is up.
- **API** — whether the API is responding to health checks.
- **Collections** — number of collections in the active tenant.
- **Records** — total record count across the active tenant's collections.

## Nudges

Two contextual banners can appear above the stat cards:

- **"Two-factor authentication is not enabled"** — shown if the superadmin account doesn't have TOTP 2FA configured yet. Links to [Profile](/admin-ui/profile) to enable it.
- **"No tenant selected"** — shown if no tenant is currently active. Links to the [Tenants page](/admin-ui/tenants) to create or select one. Collection data, users, and logs are all tenant-scoped, so this is usually the first thing to resolve on a fresh instance.

## Version

The running Paprika version is shown at the bottom of the sidebar, below **Log out** — so it's visible on every page, not just here. It's the version of the build itself, which is what to quote in a bug report or to check after an update. A `-SNAPSHOT` suffix means the instance runs a development build rather than a release.
