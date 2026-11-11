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
| `PAPRIKA_STORAGE` | Absolute path to a local directory. With the filesystem backend this is where uploaded files and [pre-import snapshots](/admin-ui/backup-restore) are stored. With the S3 backend it holds only the snapshots; tenant files live in the bucket. Must be absolute in production — Paprika refuses to start on a relative path, and on a directory it cannot create or write to. | No — preset by the installer to a sensible path (`storage/` under the install directory); only change it if you know you need a different location. If you do, also adjust `ReadWritePaths` in the systemd unit (standalone) or the volume mount (Docker). |
| `PAPRIKA_STORAGE_BACKEND` | `filesystem` (the default) or `s3`. `s3` is S3-compatible object storage: Amazon S3 when `PAPRIKA_STORAGE_ENDPOINT` is empty, or another service that speaks the same API. Hetzner Object Storage uses the endpoint and region in the table below. | No — unset means `filesystem`, which is what an existing installation already does. |
| `PAPRIKA_STORAGE_BUCKET` | Bucket name. Read only when the backend is `s3`. One bucket for the whole installation; each tenant is a prefix inside it. | No. |
| `PAPRIKA_STORAGE_REGION` | Region of that bucket, for example `eu-central-1`, or `fsn1` for Hetzner Falkenstein. | No. |
| `PAPRIKA_STORAGE_ENDPOINT` | Optional. Empty means Amazon S3 at `https://s3.{region}.amazonaws.com`. Set it for any other S3-compatible service. | No. |
| `PAPRIKA_STORAGE_PATH_STYLE` | Optional. `true` puts the bucket in the path (`https://endpoint/bucket/key`). Leave it unset for Amazon S3 and Hetzner, which both answer virtual-hosted requests. | No — unset means `false`. |
| `PAPRIKA_LOG_PATH` | Directory for the rolling log files. Relative paths resolve against the working directory, which systemd sets to the install directory. | No — preset by the standalone installer to `logs/` under the install directory. If you change it, also adjust `ReadWritePaths` in the systemd unit. Docker leaves it unset: the container logs to stdout and to `/app/logs` inside the container. |
| `PERSISTENCE_MONGO_HOST` | MongoDB hostname. | Docker: preset to the bundled `mongodb` container. Standalone: **no** — left as `CHANGE_ME`, since you bring your own instance. |
| `PERSISTENCE_MONGO_PORT` | MongoDB port (default `27017`). | Docker: preset. Standalone: preset to `27017`. |
| `PERSISTENCE_MONGO_URL` | MongoDB connection string for a replica set, a sharded cluster or an SRV address such as MongoDB Atlas, e.g. `mongodb://mongo-1:27017,mongo-2:27017/?replicaSet=rs0`. Replaces `PERSISTENCE_MONGO_HOST` and `PERSISTENCE_MONGO_PORT`. Without credentials: username and password stay separate. | No, optional — neither installer sets it. |
| `PERSISTENCE_MONGO_USERNAME` | MongoDB username. | Docker: set from `MONGO_USERNAME` in `.env`, a fixed `paprika` user for a scoped application account — not the MongoDB root account. Standalone: **no** — left as `CHANGE_ME`, you must provide credentials for your own instance. |

`filesystem` keeps uploaded files under `PAPRIKA_STORAGE/<tenantId>/<fileId>`. `s3` uses that same relative path as the object key in one bucket. Paprika does not create the bucket. On startup it checks that the bucket answers; a failure names the bucket and the endpoint and refuses to start. The credentials need to list, read, write and delete objects in that bucket. Downloads still come through Paprika, under the same file routes as before.

Hetzner Object Storage is the same backend with the location endpoint and the location as the region:

| Location | `PAPRIKA_STORAGE_ENDPOINT` | `PAPRIKA_STORAGE_REGION` |
|---|---|---|
| Falkenstein | `https://fsn1.your-objectstorage.com` | `fsn1` |
| Nuremberg | `https://nbg1.your-objectstorage.com` | `nbg1` |
| Helsinki | `https://hel1.your-objectstorage.com` | `hel1` |

Moving an existing directory into a bucket is a copy you run yourself (`rclone`, or `aws s3 sync` with that endpoint), then set `PAPRIKA_STORAGE_BACKEND=s3`. There is no migration inside Paprika. `PAPRIKA_STORAGE` stays a local directory either way: with `s3` it only holds [pre-import snapshots](/admin-ui/backup-restore).

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
| `smtp.port` | SMTP port (empty: `465` for SMTPS; `587` for STARTTLS). | No — prepared empty. |
| `smtp.username` | SMTP username (if the server requires auth). | No — prepared empty. |
| `smtp.password` | SMTP password (if the server requires auth). | No — prepared empty. |
| `smtp.authentication` | `true` when the SMTP server requires authentication (empty: `false`). | No — prepared empty. |
| `smtp.protocol` | `smtps` or `smtptls` (empty: `smtps`). Production refuses cleartext `smtp`. | No — prepared empty. |
| `smtp.from` | From address for outgoing emails (empty: `no-reply@localhost`, so set it once you send mail). | No — prepared empty. |
| `smtp.from_name` | Sender display name (empty: `Paprika`). | No — prepared empty. |
| `paprika.storage_access_key` | Access key for the S3 backend. Empty, or the file absent: ignored while the backend is `filesystem`. | No — not created by the installers. Add the file only when `PAPRIKA_STORAGE_BACKEND=s3`. |
| `paprika.storage_secret_key` | Secret key for the S3 backend. Same rule as the access key. | No. |

Who may read the files:

- **Docker**: `storage/` belongs to group `1000` as well, with `0770`, as Paprika writes the uploads there. Compose mounts `secrets/` as a whole into both containers, to `/run/secrets`, and `mongodb-secrets/` only into the `mongodb` container. A mount keeps the owner and mode the files and directories have on the host, so both directories and their files belong to group `1000`, which both containers run with: the directories `0750`, the files `0440` (the SMTP files `0640`, so the owner can fill them in), nothing for anyone else. Setting group `1000` needs root unless you are a member, so the installer asks for `sudo` if necessary. A new file in `secrets/` needs the same group and mode.
- **Standalone**: `root:paprika`, `0640`, in a directory with `0750` — like `.env`, Paprika can read them but not change them.

## What gets auto-generated, and what doesn't

Both install scripts generate the nine internal signing secrets and keys above (`application.secret` through `authentication.cookie.key`) with `openssl rand -hex 32` — 64 hex characters each. These protect JWTs, session cookies, flash cookies, and admin authentication cookies, and there's no reason to ever set them by hand.

Everything else depends on the install method:

- **Docker** generates two separate MongoDB credentials: a root password (`mongodb-secrets/mongo.root.password`, user `MONGO_ROOT_USERNAME`) that bootstraps the `mongodb` container and never leaves it, and the password of the scoped user Paprika actually authenticates with (`persistence.mongo.password`, user `MONGO_USERNAME`) — `readWriteAnyDatabase` + `dbAdminAnyDatabase`, created by [`mongo-init.js`](https://github.com/svenkubiak/paprika/blob/main/mongo-init.js) on first start from that same file, rather than the root account. There's nothing MongoDB-related left for you to configure.
- **Standalone** does not touch MongoDB configuration at all — fill in `PERSISTENCE_MONGO_HOST`, `PERSISTENCE_MONGO_PORT` and `PERSISTENCE_MONGO_USERNAME` in `.env` and the password in `secrets/persistence.mongo.password` for your own instance before starting the service. See [Standalone → MongoDB setup](./standalone#mongodb-setup) for the role this user needs.
- **SMTP** is never filled in by either installer, on any install method. The `smtp.*` files are prepared empty and stay that way until you opt in — SMTP is only needed if a tenant has password reset or email verification enabled, and most tenants won't need either right away.
- **S3 credentials** are not created by either installer. Add `paprika.storage_access_key` and `paprika.storage_secret_key` only when you set `PAPRIKA_STORAGE_BACKEND=s3`, and give them the same owner and mode as the other secret files.

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
