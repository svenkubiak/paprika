[![Latest](https://img.shields.io/github/v/tag/svenkubiak/paprika?label=ghcr.io&sort=semver)](https://ghcr.io/svenkubiak/paprika/paprika)
[![Release](https://img.shields.io/github/v/release/svenkubiak/paprika?label=release)](https://github.com/svenkubiak/paprika/releases/latest)
[![SemVer](https://img.shields.io/badge/SemVer-2.0.0-green)](https://semver.org/lang/de)
[![Release Build](https://github.com/svenkubiak/paprika/actions/workflows/release.yml/badge.svg)](https://github.com/svenkubiak/paprika/actions/workflows/release.yml)
[![Docs](https://img.shields.io/badge/docs-online-blue?logo=readthedocs&logoColor=white)](https://svenkubiak.github.io/paprika/)
[![Status](https://img.shields.io/badge/status-beta-orange)](https://github.com/svenkubiak/paprika#-paprika)
[![License](https://img.shields.io/badge/license-PolyForm%20Noncommercial-blue)](https://github.com/svenkubiak/paprika/blob/main/LICENSING.md)
[![Buy Me a Coffee](https://img.shields.io/badge/Buy%20Me%20A%20Coffee-%F0%9F%8D%BA-yellow)](https://buymeacoffee.com/svenkubiak)

# 🫑 Paprika

> [!WARNING]
> **Paprika is currently in beta and not yet recommended for production use.**
>
> All releases before 1.0.0 are considered beta. APIs, configuration, database structures, and Docker images can change at any time without notice, including breaking changes between minor versions. Commercial licensing is **not yet available**, so Paprika is currently for development, testing, and evaluation only.

Paprika is a source-available, self-hosted **Backend-as-a-Service (BaaS)** built on [mangoo I/O](https://github.com/svenkubiak/mangooio) and MongoDB. It gives you multi-tenant collections, a REST API, access rules, webhooks, and a modern admin UI, all without running a separate frontend process in production.

The goal is to keep things simple: run Paprika as a Docker container or install it as a standalone service via a `.deb` package, open the admin UI, and start building.

## 🧩 What Paprika can do

Paprika is **API-first**: every collection you define is immediately available as a REST API — no code generation, no deploy step. You define collections, fields, validation, permissions, files, and event integrations through the admin UI; Paprika handles everything else.

### Model application data

Define fields as **string, number, boolean, email, URL, date, time, date-time, select, JSON, relation, or file** — with type-specific validation, required/optional flags, default values, field-level indexes (including unique), and cross-collection relations with optional cascade-delete. Every record gets an ID plus `createdAt` and `updatedAt` automatically.

### Use a ready-made REST API

Every collection gets a consistent JSON API out of the box:

```
GET    /api/collections/{collection}
POST   /api/collections/{collection}
GET    /api/collections/{collection}/{id}
PATCH  /api/collections/{collection}/{id}
DELETE /api/collections/{collection}/{id}
```

Pagination, schema validation on writes, multipart file uploads, and predictable error responses are all included. List responses are automatically filtered by the collection's access rules — an owner-scoped list only ever returns the authenticated user's own records.

### Separate tenants and users

Each tenant has its own MongoDB database, its own users, and independent registration settings. Superadmins manage tenants from the admin UI; tenant users authenticate with JWTs through `/api/auth/login` and `/api/auth/refresh`. Superadmin access and tenant user access are completely separate identities. The `users` collection accepts custom fields alongside the locked core fields.

### Authenticate users securely

Paprika handles the full auth lifecycle: Argon2 password hashing, JWT access and refresh tokens, opt-in password reset and email verification per tenant, and TOTP two-factor authentication for superadmins. Auth flows are hookable, and reset or verification endpoints always return `200` to prevent account enumeration.

### Control access per operation

Each collection has independent rules for listing, viewing, creating, updating, and deleting records. An operation can be **public**, restricted to any **authenticated** user, scoped to the **owner** of a record, or fully **locked** — or expressed as a custom rule with access to `record.*`, `auth.*`, and `body.*` fields. List rules are compiled directly into the database query.

### Store files with records

File fields are part of the collection schema, not bolted on as a separate system. MIME type, file size, and count restrictions are set per field. Files follow the same access rules as the rest of the record and are cleaned up automatically when records are deleted or files are replaced.

### Subscribe to realtime changes

Clients can subscribe to a collection or a single record over Server-Sent Events. Each event carries an `action` (`create`, `update`, or `delete`) along with the record data. Tenant isolation and view rules are enforced before any event is sent.

### React to events with webhooks

Hooks fire on `beforeList`, `beforeView`, `beforeCreate`, `afterCreate`, `beforeUpdate`, `afterUpdate`, `beforeDelete`, and `afterDelete` — plus auth flows. Before-hooks are blocking and can reject or rewrite a request body. After-hooks run asynchronously. Global hooks can span all collections. All hooks support HMAC-SHA256 signatures and come with a one-click test action.

### Operate everything from the admin UI

The bundled Vue admin covers tenant management, schema editing, record browsing, access rules, webhook configuration, generated API docs, searchable request logs, backup and restore, and application settings. It ships inside the application binary — nothing extra to deploy or serve.

## 🚀 Installation

Paprika can be deployed two ways, both via a single install script that generates its own secrets:

- **Docker** — one command downloads a `compose.yml`, spins up Paprika alongside a bundled MongoDB container, and starts the stack. No separate database to install. Runs on any Linux, macOS, or Windows host with Docker Compose v2.
- **Standalone (`.deb`)** — installs Paprika as a hardened systemd service on Ubuntu 22.04+ or Debian 12+, with sandboxing applied out of the box. You bring your own MongoDB instance; the installer pauses and asks for its connection details before starting the service.

Either way, once the service is up, a one-time setup link is printed to the log to create your first superadmin — see [Initial Setup](https://svenkubiak.github.io/paprika/installation/initial-setup).

Full installation instructions, including update and service-management commands, are in the [documentation](https://svenkubiak.github.io/paprika/installation/docker).

## ⚙️ Configuration

Paprika is configured entirely through environment variables in a `.env` file. Both install scripts auto-generate the internal signing secrets (JWT, session, flash, and admin auth cookies), but not everything: Docker also generates a MongoDB root password, while the standalone install leaves MongoDB credentials for you to fill in, since it doesn't manage the database itself. SMTP is never auto-configured on either install method — it's optional and only needed if you enable password reset or email verification for a tenant.

Full variable reference is in the [documentation](https://svenkubiak.github.io/paprika/installation/configuration).

## 🏗️ Architecture

| Layer | Technology |
|---|---|
| Backend | Java 25, mangoo I/O 10.x |
| Database | MongoDB |
| Deployment | Docker Compose or standalone `.deb` |
| Admin UI | Vue 3, Vite, Nuxt UI 4 |
| API auth | JWT (tenant users) |
| Admin auth | Session cookie + optional TOTP 2FA |

## 💻 Local Development

Setup instructions for running backend, admin UI, and tests locally are in the [documentation](https://svenkubiak.github.io/paprika/contributing/local-development).

## 🤝 Contributing

### Commit messages

This project follows [Conventional Commits](https://www.conventionalcommits.org/). Please use one of these prefixes:

| Prefix | When to use |
|---|---|
| `feat:` | A new feature |
| `fix:` | A bug fix |
| `perf:` | A performance improvement |
| `refactor:` | Code change that is neither a feature nor a bug fix |
| `docs:` | Documentation only |
| `chore:` | Build, tooling, or dependency updates |

```
feat: add TOTP two-factor authentication for superadmin
fix: refresh token rejected after user email change
perf: add index on collection records for owner queries
refactor: simplify AuthService token parsing
docs: document webhook HMAC signature verification
chore: update mangooio to 10.11.4
```

Breaking changes go in the commit body with a `BREAKING CHANGE:` line:

```
feat: replace /api/auth/login form body with JSON

BREAKING CHANGE: /api/auth/login now expects Content-Type: application/json
instead of application/x-www-form-urlencoded.
```

The release notes are generated from these messages, so clear commits mean a clear changelog.

## 📄 License

Paprika uses a dual-licensing model, but there's only one version of the software. Everyone gets the same code, features, and fixes; the license just depends on how you use it.

**Free** under the [PolyForm Noncommercial License 1.0.0](LICENSE) for non-commercial use: personal and hobby projects, education, research, and nonprofits. Companies can also use it for free to evaluate, develop, and test.

**A commercial license** is required once you run Paprika in production for anything commercial, for example as the backend of a paid app or a live business system.

Not sure which applies to you? See [LICENSING.md](LICENSING.md) for the full breakdown with examples, or get in touch at sk@svenkubiak.de.

## 🔗 Links

- [Documentation](https://svenkubiak.github.io/paprika/), the admin UI guide and concepts (tenants, roles, collections)
- [mangoo I/O](https://github.com/svenkubiak/mangooio), the underlying Java web framework
- [Licensing](LICENSING.md)
- [Commercial License](COMMERCIAL-LICENSE.md)
- [Support policy](SUPPORT.md)
- [Security policy](SECURITY.md)
- [Contributing](CONTRIBUTING.md)
