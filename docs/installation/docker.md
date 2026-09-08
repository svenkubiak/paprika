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

1. Generates a `.env` file with all application secrets (see [Configuration](./configuration) for what each variable does) and a random MongoDB root password
2. Creates a `storage/` directory next to the `.env` file for persistent file uploads
3. Downloads `compose.yml` from this repository
4. Runs `docker compose up -d` to start both containers

`.env` is written with `chmod 600` and only if it doesn't already exist — running the script again on top of an existing install will not silently overwrite your secrets; it asks first.

Paprika is available at `http://localhost:8080` once MongoDB reports healthy, which usually takes about 30 seconds on first start. Change the exposed port by setting `HOST_PORT` in `.env` before starting the stack.

If you'd rather review the files before running anything:

```bash
mkdir /srv/paprika && cd /srv/paprika
curl -fsSL https://raw.githubusercontent.com/svenkubiak/paprika/main/compose.yml -o compose.yml
curl -fsSL https://raw.githubusercontent.com/svenkubiak/paprika/main/install-docker.sh -o install-docker.sh
bash install-docker.sh
```

## What the stack looks like

`compose.yml` defines two services:

- **`paprika`** — the application container, built from the published image, reading all configuration from `.env`. Its `storage/` volume for file uploads is bind-mounted from the host so it survives container recreation. The HTTP port is bound to `127.0.0.1` only, so it is not reachable from the network directly.
- **`mongodb`** — `mongodb/mongodb-community-server`, with its data stored in a named Docker volume managed by the daemon. `paprika` waits for MongoDB's healthcheck to pass before starting.

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

On update, the script does **not** touch `.env`, `storage/`, `mongodb/`, or your local `compose.yml` — it only runs `docker compose pull paprika` and `docker compose up -d paprika` against whatever is already in the current directory. This matters if you've hand-edited `compose.yml` after the initial setup — to put it behind a reverse-proxy network, add resource limits, pin a specific image tag, and so on — since those changes are preserved across updates. Only a *fresh* install (no running `paprika` container found) re-downloads `compose.yml` from this repository and would overwrite a local copy of that name.

If you want to intentionally pick up changes to the published `compose.yml` (for example after a MongoDB version bump), fetch it again by hand and re-apply your customizations:

```bash
curl -fsSL https://raw.githubusercontent.com/svenkubiak/paprika/main/compose.yml -o compose.yml.new
diff compose.yml compose.yml.new
```

## Next steps

After the service is up, follow [Initial Setup](./initial-setup) to create your first superadmin.
