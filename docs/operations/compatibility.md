# Compatibility

Paprika versions its public surface. A release that changes the surface below says so in the release notes, in a `BREAKING CHANGE:` line on the commit, and in the version number. A patch release does not change it. Nothing in the list below changes silently.

The rules:

| Version | What it may change |
|---|---|
| Patch (`x.y.Z`) | Fixes only. The surface below stays as it is. |
| Minor (`x.Y.0`), while the major version is 0 | The surface below, and only together with a `BREAKING CHANGE:` line and a minor bump. |
| Minor (`x.Y.0`), from 1.0.0 on | Additive changes. The surface below stays as it is. |
| Major (`X.0.0`), from 1.0.0 on | The surface below. This is the only kind of release that breaks it. |

A security fix that has to break the surface ships as a breaking release, not as a quiet patch. Docker image tags use the same version as the `.deb` and the jar.

## What is covered

- **HTTP API.** Paths, methods, authentication and the documented success and error behavior of `/api/auth/**`, `/api/collections/**`, `/api/realtime`, `/api/realtime/subscribe`, `/health`, `/api/admin/**` and `/api/meta/**`.
- **Environment variables** listed in [Configuration](/installation/configuration). Renaming one, or changing what an existing variable means, is a breaking change. Adding a variable is not.
- **Request body limit.** `undertow.maxentitysize` and `form.maxfilesize` are 64 MiB (67,108,864 bytes). Raising or lowering that ceiling is a breaking change.
- **Backup archives.** Export writes manifest version 2. Import reads version 1 and version 2. Version 2 contains `system/api_keys.json` and `system/token_revocations.json`; version 1 does not, and restoring one leaves those two collections on the target as they are. Dropping support for a manifest version the importer still accepts is a breaking change. The archive replaces tenant files: a file that is not in the archive is deleted.
- **Records Paprika writes.** `id`, `createdAt` and `updatedAt` on collection records, one MongoDB database per tenant (`tenant_<id>`), and the system collections `tenants`, `users`, `settings`, `api_keys` and `token_revocations`.

## What is not covered

- The layout and wording of the admin UI.
- Log lines, request-log retention and the in-app telemetry screens.
- Running more than one Paprika process against the same data. Realtime clients and uploaded files live on the instance that accepted them. Scheduled cleanup runs in every process.
- Behavior listed as not implemented in `extensions.md`.
- The MongoDB, JDK and operating-system versions an image or `.deb` happens to be built with, beyond what the installation pages require.

Commercial production still needs a commercial license. That license is not yet offered. The [PolyForm Noncommercial License](https://github.com/svenkubiak/paprika/blob/main/LICENSE) covers noncommercial use. This page is the project's versioning policy. It is not a support commitment; response times and backports stay as [SUPPORT.md](https://github.com/svenkubiak/paprika/blob/main/SUPPORT.md) describes them.
