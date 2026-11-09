# Configuration

Paprika is configured in two places. Settings that are not secret are environment variables; both install scripts write them into a `.env` file next to the application (Docker: alongside `compose.yml`; standalone: in the install directory), which the container or the systemd unit reads at startup. Secrets and the SMTP settings are files: one file per value in a `secrets/` directory next to `.env`, named like the config key it sets. Paprika reads each file at startup.

Files keep secrets out of the environment, where `docker inspect`, the systemd unit and every child process could see them.

## Environment variables

| Variable | Description | Auto-generated |
|---|---|---|
| `APPLICATION_MODE` | Set to `prod` in production, `dev` for local development. | No — Docker and standalone both set this to `prod` by default. |
| `APPLICATION_SECRETS_PATH` | Directory of the secret files. Paprika reads `/run/secrets` if it is not set. | Standalone: preset to `secrets/` under the install directory. Docker leaves it unset: Compose mounts the secret files to `/run/secrets`. |
| `CONNECTOR_HTTP_HOST` | Bind address. Use `0.0.0.0` to listen on all interfaces, or `127.0.0.1` to only accept connections from a local reverse proxy. | No. Both install methods default to `127.0.0.1`, so Paprika only accepts connections from the local machine out of the box. Set it to `0.0.0.0` if Paprika needs to be reachable directly from the network rather than through a local reverse proxy. |
| `CONNECTOR_HTTP_PORT` | HTTP port (default `8080`). | No — preset default. For Docker, change the externally exposed port via `HOST_PORT` instead. |
| `PAPRIKA_STORAGE` | Absolute path to the file storage directory. Must be absolute in production — Paprika refuses to start on a relative path, and on a directory it cannot create or write to. | No — preset by the installer to a sensible path (`storage/` under the install directory); only change it if you know you need a different location. If you do, also adjust `ReadWritePaths` in the systemd unit (standalone) or the volume mount (Docker). |
| `PAPRIKA_LOG_PATH` | Directory for the rolling log files. Relative paths resolve against the working directory, which systemd sets to the install directory. | No — preset by the standalone installer to `logs/` under the install directory. If you change it, also adjust `ReadWritePaths` in the systemd unit. Docker leaves it unset: the container logs to stdout and to `/app/logs` inside the container. |
| `PERSISTENCE_MONGO_HOST` | MongoDB hostname. | Docker: preset to the bundled `mongodb` container. Standalone: **no** — left as `CHANGE_ME`, since you bring your own instance. |
| `PERSISTENCE_MONGO_PORT` | MongoDB port (default `27017`). | Docker: preset. Standalone: preset to `27017`. |
| `PERSISTENCE_MONGO_URL` | MongoDB connection string for a replica set, a sharded cluster or an SRV address such as MongoDB Atlas, e.g. `mongodb://mongo-1:27017,mongo-2:27017/?replicaSet=rs0`. Replaces `PERSISTENCE_MONGO_HOST` and `PERSISTENCE_MONGO_PORT`. Without credentials: username and password stay separate. | No, optional — neither installer sets it. |
| `PERSISTENCE_MONGO_USERNAME` | MongoDB username. | Docker: set from `MONGO_USERNAME` in `.env`, a fixed `paprika` user for a scoped application account — not the MongoDB root account. Standalone: **no** — left as `CHANGE_ME`, you must provide credentials for your own instance. |

## Secret files

Each file holds one value as it is, without quotes or encoding; a trailing line break is ignored. An empty file counts as not set.

| File | Description | Auto-generated |
|---|---|---|
| `application.secret` | 64-character secret for internal signing. | Yes, by both install scripts. |
| `token.secret` | 64-character secret for JWT encryption. | Yes, by both install scripts. |
| `token.key` | 64-character key for JWT signing. | Yes, by both install scripts. |
| `session.cookie.secret` | 64-character secret for session cookies. | Yes, by both install scripts. |
| `session.cookie.key` | 64-character key for session cookies. | Yes, by both install scripts. |
| `flash.cookie.secret` | 64-character secret for flash cookies. | Yes, by both install scripts. |
| `flash.cookie.key` | 64-character key for flash cookies. | Yes, by both install scripts. |
| `authentication.cookie.secret` | 64-character secret for admin auth cookies. | Yes, by both install scripts. |
| `authentication.cookie.key` | 64-character key for admin auth cookies. | Yes, by both install scripts. |
| `persistence.mongo.password` | MongoDB password of `PERSISTENCE_MONGO_USERNAME`, as it is — not percent-encoded. See [Standalone → MongoDB setup](./standalone#mongodb-setup). | Docker: yes, a random password for the scoped application user; the `mongodb` container creates that user with it. Standalone: **no** — prepared empty, Paprika does not start until you fill it in. |
| `mongo.root.password` | Docker only: password of the MongoDB root user that bootstraps the `mongodb` container. It lives in a directory of its own, `mongodb-secrets/`, which only the `mongodb` container mounts: Paprika never sees it. | Yes, by the Docker install script. |
| `smtp.host` | SMTP host for tenant-user recovery emails. Empty: no mail is sent. | No — prepared empty by both install scripts. SMTP is entirely optional and only needed if you enable password reset or email verification for a tenant. |
| `smtp.port` | SMTP port (empty: `587`; `465` for SMTPS). | No — prepared empty. |
| `smtp.username` | SMTP username (if the server requires auth). | No — prepared empty. |
| `smtp.password` | SMTP password (if the server requires auth). | No — prepared empty. |
| `smtp.authentication` | `true` when the SMTP server requires authentication (empty: `false`). | No — prepared empty. |
| `smtp.protocol` | `smtp` or `smtps` (empty: `smtp`). | No — prepared empty. |
| `smtp.from` | From address for outgoing emails (empty: `no-reply@localhost`, so set it once you send mail). | No — prepared empty. |
| `smtp.from_name` | Sender display name (empty: `Paprika`). | No — prepared empty. |

Who may read the files:

- **Docker**: `storage/` belongs to group `1000` as well, with `0770`, as Paprika writes the uploads there. Compose mounts `secrets/` as a whole into both containers, to `/run/secrets`, and `mongodb-secrets/` only into the `mongodb` container. A mount keeps the owner and mode the files and directories have on the host, so both directories and their files belong to group `1000`, which both containers run with: the directories `0750`, the files `0440` (the SMTP files `0640`, so the owner can fill them in), nothing for anyone else. Setting group `1000` needs root unless you are a member, so the installer asks for `sudo` if necessary. A new file in `secrets/` needs the same group and mode.
- **Standalone**: `root:paprika`, `0640`, in a directory with `0750` — like `.env`, Paprika can read them but not change them.

## What gets auto-generated, and what doesn't

Both install scripts generate the nine internal signing secrets and keys above (`application.secret` through `authentication.cookie.key`) with `openssl rand -hex 32` — 64 hex characters each. These protect JWTs, session cookies, flash cookies, and admin authentication cookies, and there's no reason to ever set them by hand.

Everything else depends on the install method:

- **Docker** generates two separate MongoDB credentials: a root password (`mongodb-secrets/mongo.root.password`, user `MONGO_ROOT_USERNAME`) that bootstraps the `mongodb` container and never leaves it, and the password of the scoped user Paprika actually authenticates with (`persistence.mongo.password`, user `MONGO_USERNAME`) — `readWriteAnyDatabase` + `dbAdminAnyDatabase`, created by [`mongo-init.js`](https://github.com/svenkubiak/paprika/blob/main/mongo-init.js) on first start from that same file, rather than the root account. There's nothing MongoDB-related left for you to configure.
- **Standalone** does not touch MongoDB configuration at all — fill in `PERSISTENCE_MONGO_HOST`, `PERSISTENCE_MONGO_PORT` and `PERSISTENCE_MONGO_USERNAME` in `.env` and the password in `secrets/persistence.mongo.password` for your own instance before starting the service. See [Standalone → MongoDB setup](./standalone#mongodb-setup) for the role this user needs.
- **SMTP** is never filled in by either installer, on any install method. The `smtp.*` files are prepared empty and stay that way until you opt in — SMTP is only needed if a tenant has password reset or email verification enabled, and most tenants won't need either right away.

## Applying changes

Paprika reads `.env` and the secret files at startup, not continuously. After editing them:

```bash
# Docker, after a change to a secret file
docker compose restart paprika

# Docker, after a change to .env
docker compose up -d

# Standalone
systemctl restart paprika
```

`docker compose up -d` only recreates a container whose configuration changed; a changed secret file alone does not, so restart Paprika for it.

Do not reuse secrets across separate Paprika instances — each installation should have its own independently generated set. Several instances of *one* installation are the exception: they need the same secrets, see [Running several instances](/operations/going-to-production#running-several-instances).
