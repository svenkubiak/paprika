# Docker

The Docker setup runs Paprika and MongoDB together via Docker Compose. It's the fastest way to get an instance running: one command downloads a `compose.yml` and generates all application secrets as files, and a second one brings the stack up. No separate database installation, no manual configuration.

## Prerequisites

- [Docker](https://docs.docker.com/get-docker/) with Docker Compose v2 (the `docker compose` subcommand, not the standalone `docker-compose` binary)
- `curl` and `openssl` available on the host running the install script
- A directory to store Paprika's persistent data (e.g. `/srv/paprika`)

## Install

```bash
mkdir /srv/paprika && cd /srv/paprika
curl -fsSL https://raw.githubusercontent.com/svenkubiak/paprika/main/install-docker.sh | bash
```

The script runs a set of preflight checks (`curl`, `openssl`, `docker`, and `docker compose` must all be present and the Docker daemon reachable), detects whether a `paprika` container is already running in the current directory, and branches into either a fresh install or an update (see below). On a **fresh install**, it:

1. Generates all application secrets as files in a `secrets/` directory (see [Configuration](./configuration#secret-files) for what each file does), including the scoped application password Paprika authenticates with to MongoDB. The SMTP settings are prepared as empty files. The MongoDB root password, used only to bootstrap the `mongodb` container, goes into a directory of its own, `mongodb-secrets/` (see [What the stack looks like](#what-the-stack-looks-like) below).
2. Creates a `storage/` directory for persistent file uploads
3. Gives `storage/`, both secret directories and their files group `1000`, which both containers run with: `0770` for `storage/`, which Paprika writes to, `0750` for the secret directories, `0440` for the secret files (`0640` for the SMTP files you fill in yourself). Setting that group needs root unless you are a member of it, so the script may ask for your password through `sudo`.
4. Writes a `.env` with the settings that are not secret: the host port and the two MongoDB usernames
5. Downloads `compose.yml` and `mongo-init.js` from this repository
6. Stops there: the stack is **not** started, so you can review the settings and fill in SMTP first. The script ends with the commands for the next steps.

`.env` is written with `chmod 600` and only if it doesn't already exist. Running the script again on top of an existing install will not silently overwrite your secrets: it asks first, and keeps them when it cannot ask. A directory whose `.env` still holds the secrets is an installation from before secret files; the script stops there instead of setting it up anew, see [Moving secrets from .env to files](#moving-secrets-from-env-to-files).

Start the stack when you are ready:

```bash
docker compose up -d
```

Paprika is available at `http://localhost:8080` once MongoDB reports healthy, which usually takes about 30 seconds on first start. Change the exposed port by setting `HOST_PORT` in `.env` before starting the stack. The one-time setup link for the first superadmin is in the log:

```bash
docker compose logs -f paprika | grep --line-buffered setup
```

If you'd rather review the files before running anything:

```bash
mkdir /srv/paprika && cd /srv/paprika
curl -fsSL https://raw.githubusercontent.com/svenkubiak/paprika/main/compose.yml -o compose.yml
curl -fsSL https://raw.githubusercontent.com/svenkubiak/paprika/main/mongo-init.js -o mongo-init.js
curl -fsSL https://raw.githubusercontent.com/svenkubiak/paprika/main/install-docker.sh -o install-docker.sh
bash install-docker.sh
```

## What the stack looks like

`compose.yml` defines two services:

- **`paprika`** — the application container, built from the published image, reading its settings from `.env` and its secrets from the files in `secrets/`, a directory Compose mounts to `/run/secrets` inside the container. The secrets never appear in the container's environment, so `docker inspect` doesn't show them. Its `storage/` volume for file uploads is bind-mounted from the host so it survives container recreation. The HTTP port is bound to `127.0.0.1` only, so it is not reachable from the network directly.
- **`mongodb`** — `mongodb/mongodb-community-server`, with its data stored in a named Docker volume managed by the daemon. `paprika` waits for MongoDB's healthcheck to pass before starting.

`paprika` does **not** authenticate as the MongoDB root user. The root user and `mongodb-secrets/mongo.root.password` only bootstrap the `mongodb` container and back its healthcheck — `mongodb-secrets/` is mounted into the `mongodb` container only, never into `paprika`. On first start (when the data volume is empty), MongoDB runs the bind-mounted `mongo-init.js`, which creates the scoped user Paprika actually connects as, with the password from `secrets/persistence.mongo.password` — the same file Paprika reads, as `secrets/` is mounted into both containers, with `readWriteAnyDatabase` and `dbAdminAnyDatabase` — enough to create, read, write, and drop the per-tenant databases Paprika provisions dynamically, but without the root account's ability to create other users or administer the server itself. This limits the blast radius if the `paprika` application container is ever compromised: an attacker who obtains its MongoDB credentials cannot create a backdoor account or reconfigure the server, though — since tenant isolation lives entirely in the application layer, not in MongoDB roles — they could still read and write every tenant's data.

Both containers restart automatically (`unless-stopped`) if the host reboots or a container crashes.

## Operating the stack

```bash
# Check container status
docker compose ps

# Follow logs
docker compose logs -f

# Stop everything (data is preserved)
docker compose down

# Stop and remove volumes too (destroys all data)
docker compose down -v
```

## Update

Running the same install command again on an existing installation shows the currently installed version and — after confirmation — pulls the latest image and restarts just the `paprika` container:

```bash
curl -fsSL https://raw.githubusercontent.com/svenkubiak/paprika/main/install-docker.sh | bash
```

On update, the script does **not** touch `.env`, `secrets/`, `mongodb-secrets/`, `storage/`, `mongodb/`, or your local `compose.yml`/`mongo-init.js` — it only runs `docker compose pull paprika` and `docker compose up -d paprika` against whatever is already in the current directory. This matters if you've hand-edited `compose.yml` after the initial setup — to put it behind a reverse-proxy network, add resource limits, pin a specific image tag, and so on — since those changes are preserved across updates. Only a *fresh* install (no running `paprika` container found) re-downloads `compose.yml` and `mongo-init.js` from this repository and would overwrite local copies of those names.

If you want to intentionally pick up changes to the published `compose.yml` (for example after a MongoDB version bump), fetch it again by hand and re-apply your customizations:

```bash
curl -fsSL https://raw.githubusercontent.com/svenkubiak/paprika/main/compose.yml -o compose.yml.new
diff compose.yml compose.yml.new
```

::: warning Existing installations won't automatically get the scoped MongoDB user
MongoDB only runs `mongo-init.js` when its data volume is empty — on an install that already has data, dropping in the new `compose.yml`/`mongo-init.js` and adding `MONGO_ROOT_USERNAME`/`MONGO_ROOT_PASSWORD` to `.env` will **not** retroactively create the scoped user or migrate `paprika` off the root account. To pick this up on an existing installation, either start over with a fresh volume (if you don't need to keep the data), or manually create the scoped user against your running instance and update `.env`:

```bash
docker compose exec mongodb mongosh -u <your-existing-root-username> -p <your-existing-root-password> --authenticationDatabase admin --eval '
db.getSiblingDB("admin").createUser({
  user: "paprika",
  pwd: "<a-new-strong-password>",
  roles: [
    { role: "readWriteAnyDatabase", db: "admin" },
    { role: "dbAdminAnyDatabase", db: "admin" }
  ]
})'
```

Then set `MONGO_USERNAME=paprika` in `.env`, write the password into `secrets/persistence.mongo.password` and run `docker compose up -d paprika`.
:::

## Moving secrets from .env to files

Installations from before secret files keep their secrets in `.env`. The install script does not migrate them: on such an installation it stops with a pointer to this section and changes nothing. Move the values once by hand — they stay the same, so nobody is signed out and MongoDB keeps working with its existing users:

```bash
cd /srv/paprika
docker compose down

mkdir -m 750 secrets mongodb-secrets
set -a; . ./.env; set +a
put() { printf '%s' "$2" > "secrets/$1"; }
put application.secret "$APPLICATION_SECRET"
put token.secret "$TOKEN_SECRET"
put token.key "$TOKEN_KEY"
put session.cookie.secret "$SESSION_COOKIE_SECRET"
put session.cookie.key "$SESSION_COOKIE_KEY"
put flash.cookie.secret "$FLASH_COOKIE_SECRET"
put flash.cookie.key "$FLASH_COOKIE_KEY"
put authentication.cookie.secret "$AUTHENTICATION_COOKIE_SECRET"
put authentication.cookie.key "$AUTHENTICATION_COOKIE_KEY"
put persistence.mongo.password "$MONGO_PASSWORD"
printf '%s' "$MONGO_ROOT_PASSWORD" > mongodb-secrets/mongo.root.password
put smtp.host "${SMTP_HOST:-}"
put smtp.port "${SMTP_PORT:-}"
put smtp.username "${SMTP_USERNAME:-}"
put smtp.password "${SMTP_PASSWORD:-}"
put smtp.authentication "${SMTP_AUTHENTICATION:-}"
put smtp.protocol "${SMTP_PROTOCOL:-}"
put smtp.from "${SMTP_FROM:-}"
put smtp.from_name "${SMTP_FROM_NAME:-}"
chmod 440 secrets/* mongodb-secrets/* && chmod 640 secrets/smtp.*
sudo chgrp -R 1000 secrets mongodb-secrets

sed -i -E '/^(APPLICATION_SECRET|TOKEN_(SECRET|KEY)|(SESSION|FLASH|AUTHENTICATION)_COOKIE_(SECRET|KEY)|MONGO_(ROOT_)?PASSWORD|#?SMTP_[A-Z_]+)=/d' .env

curl -fsSL https://raw.githubusercontent.com/svenkubiak/paprika/main/compose.yml -o compose.yml
curl -fsSL https://raw.githubusercontent.com/svenkubiak/paprika/main/mongo-init.js -o mongo-init.js
docker compose up -d
```

`. ./.env` reads `.env` as shell code: if an SMTP value contains spaces or quotes, check the `smtp.*` files afterwards. The new `compose.yml` replaces your local copy — re-apply your own changes to it, as described under [Update](#update).

## Installing a specific version

`--version` installs or updates to a specific version, also a pre-release such as `1.0.0-Beta1`: the stack then runs the image `ghcr.io/svenkubiak/paprika:<version>`, and a fresh install downloads `compose.yml` and `mongo-init.js` from the tag of that version. Take the script from the same tag, as the one on `main` belongs to the latest release:

```bash
mkdir /srv/paprika && cd /srv/paprika
curl -fsSL https://raw.githubusercontent.com/svenkubiak/paprika/1.0.0-Beta1/install-docker.sh \
  | bash -s -- --version 1.0.0-Beta1
```

The script keeps the version in `.env` as `PAPRIKA_VERSION`, so `compose.yml` and every later `docker compose` command use it. An update without `--version` stays on it — running a newer pre-release, the latest release could be older — and an update with `--version` moves to another one. Remove the line from `.env` to follow the latest release again. For trying a pre-release, see [Pre-releases](./pre-releases).

## Next steps

After the service is up, follow [Initial Setup](./initial-setup) to create your first superadmin.
