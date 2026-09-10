# Docker

The Docker setup runs Paprika and MongoDB together via Docker Compose. It's the fastest way to get an instance running: one command downloads a `compose.yml`, generates a `.env` with all application secrets, and brings the stack up. No separate database installation, no manual configuration.

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

1. Generates a `.env` file with all application secrets (see [Configuration](./configuration) for what each variable does) and two separate MongoDB credentials — a root password used only to bootstrap the `mongodb` container, and a scoped application password Paprika itself authenticates with (see [What the stack looks like](#what-the-stack-looks-like) below)
2. Creates a `storage/` directory next to the `.env` file for persistent file uploads
3. Downloads `compose.yml` and `mongo-init.js` from this repository
4. Runs `docker compose up -d` to start both containers

`.env` is written with `chmod 600` and only if it doesn't already exist — running the script again on top of an existing install will not silently overwrite your secrets; it asks first.

Paprika is available at `http://localhost:8080` once MongoDB reports healthy, which usually takes about 30 seconds on first start. Change the exposed port by setting `HOST_PORT` in `.env` before starting the stack.

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

- **`paprika`** — the application container, built from the published image, reading all configuration from `.env`. Its `storage/` volume for file uploads is bind-mounted from the host so it survives container recreation. The HTTP port is bound to `127.0.0.1` only, so it is not reachable from the network directly.
- **`mongodb`** — `mongodb/mongodb-community-server`, with its data stored in a named Docker volume managed by the daemon. `paprika` waits for MongoDB's healthcheck to pass before starting.

`paprika` does **not** authenticate as the MongoDB root user. `MONGO_INITDB_ROOT_USERNAME`/`MONGO_INITDB_ROOT_PASSWORD` only bootstrap the `mongodb` container and back its healthcheck — they're never passed into the `paprika` container. On first start (when the data volume is empty), MongoDB runs the bind-mounted `mongo-init.js`, which creates the scoped user Paprika actually connects as, with `readWriteAnyDatabase` and `dbAdminAnyDatabase` — enough to create, read, write, and drop the per-tenant databases Paprika provisions dynamically, but without the root account's ability to create other users or administer the server itself. This limits the blast radius if the `paprika` application container is ever compromised: an attacker who obtains its MongoDB credentials cannot create a backdoor account or reconfigure the server, though — since tenant isolation lives entirely in the application layer, not in MongoDB roles — they could still read and write every tenant's data.

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

On update, the script does **not** touch `.env`, `storage/`, `mongodb/`, or your local `compose.yml`/`mongo-init.js` — it only runs `docker compose pull paprika` and `docker compose up -d paprika` against whatever is already in the current directory. This matters if you've hand-edited `compose.yml` after the initial setup — to put it behind a reverse-proxy network, add resource limits, pin a specific image tag, and so on — since those changes are preserved across updates. Only a *fresh* install (no running `paprika` container found) re-downloads `compose.yml` and `mongo-init.js` from this repository and would overwrite local copies of those names.

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

Then set `PERSISTENCE_MONGO_USERNAME=paprika` and `PERSISTENCE_MONGO_PASSWORD=<a-new-strong-password>` in `.env` and run `docker compose up -d paprika`.
:::

## Next steps

After the service is up, follow [Initial Setup](./initial-setup) to create your first superadmin.
