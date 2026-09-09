# Standalone (.deb)

The standalone package installs Paprika as a hardened systemd service on Ubuntu or Debian. Unlike the Docker setup, it doesn't bundle MongoDB — you provide a reachable instance and hand its connection details to the installer.

## Prerequisites

- Ubuntu 22.04+ or Debian 12+
- `amd64` or `arm64` architecture (the script detects this automatically via `uname -m`)
- `curl`, `openssl`, `sha256sum`, and `dpkg-deb` available on the host
- A running MongoDB instance reachable from this host — if you don't have one yet, [install MongoDB](https://www.mongodb.com/docs/manual/installation/) first and make sure it's reachable before proceeding
- A manually created database named `paprika` on that instance, plus a user with `readWrite` access to it (see [MongoDB setup](#mongodb-setup) below) — Paprika does not create the database or user itself
- Root access (the script must run via `sudo`)

## MongoDB setup

Paprika expects a database named exactly `paprika` and a user authenticated against the `admin` database (`authSource=admin`) with `readWrite` access to it. Create both before starting the service, e.g. via `mongosh`:

```js
use admin
db.createUser({
  user: "paprika",
  pwd: "<a-strong-password>",
  roles: [ { role: "readWrite", db: "paprika" } ]
})
```

You'll enter this username and password into `.env` as `PERSISTENCE_MONGO_USERNAME` / `PERSISTENCE_MONGO_PASSWORD` further down.

## Install

Create the directory Paprika should live in and run the installer from it — the service is installed directly into that directory:

```bash
mkdir -p /opt/paprika
cd /opt/paprika
curl -fsSL https://raw.githubusercontent.com/svenkubiak/paprika/main/install-or-update.sh | sudo bash
```

The script completes the full installation in a single run:

1. Verifies it's running as root and that all required tools are present
2. Detects the CPU architecture and fetches the matching `.deb` asset from the latest GitHub release
3. Verifies the download's SHA-256 checksum before touching anything else
4. Extracts the package and installs it into the current directory
5. Generates a `.env` file with all application secrets (see [Configuration](./configuration)) — **except the MongoDB connection**, which is left as `CHANGE_ME` placeholders
6. Creates a dedicated, unprivileged `paprika` system user
7. Locks down file ownership and permissions (`root:paprika`, `640`/`750`) so the app can read its own files but not modify them, with only `storage/` fully owned by the `paprika` user
8. Installs and enables a systemd unit with sandboxing hardening (`ProtectSystem=strict`, `PrivateDevices`, capability dropping, etc.) applied out of the box
9. Prints instructions to fill in `.env` and start the service — the service is **not** started automatically because MongoDB credentials are still required

The script never starts the service automatically — that's always a manual step so you can finish any remaining configuration first.

Once the script finishes, edit the generated `.env` and replace the `CHANGE_ME` placeholders with your MongoDB connection details. **Make sure MongoDB is running and reachable before starting Paprika.** Then start the service manually:

```bash
nano /opt/paprika/.env   # fill in PERSISTENCE_MONGO_HOST, _USERNAME, _PASSWORD
systemctl start paprika
```

The service is registered to start automatically on server reboot (`systemctl enable`) — you only need to start it manually once after the initial setup or after an update.

On first start against a fresh database, Paprika prints a one-time superadmin setup link to the log. Since the systemd journal is noisy on startup, filter for it directly instead of scrolling:

```bash
journalctl -u paprika -f | grep --line-buffered setup
```

The link is only valid for 30 minutes — see [Initial Setup](./initial-setup) for how to complete it.

By default the generated `.env` sets `CONNECTOR_HTTP_HOST=127.0.0.1`, so Paprika only listens on the loopback interface. To make it reachable from the network directly, change that value in `.env` before starting the service.

## Update

Running the same command again from the install directory detects the currently installed version via `.version`, shows you what's available, and asks for confirmation before replacing the application binaries. `.env`, `.version`, and `storage/` are never touched by an update. After the script finishes, start the service manually:

```bash
curl -fsSL https://raw.githubusercontent.com/svenkubiak/paprika/main/install-or-update.sh | sudo bash
systemctl start paprika
```

## Service management

```bash
# Check service status
systemctl status paprika

# Follow logs
journalctl -u paprika -f

# Restart after manual config changes
systemctl restart paprika
```

## Uninstall

Run the installer with `--uninstall` from the directory Paprika is installed in:

```bash
cd /opt/paprika
curl -fsSL https://raw.githubusercontent.com/svenkubiak/paprika/main/install-or-update.sh | sudo bash -s -- --uninstall
```

The script:

1. Stops and disables the `paprika` systemd service
2. Removes the service unit file and reloads systemd
3. Removes the `paprika` system user
4. Deletes the application files (`bin/`, `lib/`, `.env`, `.version`)
5. Asks whether to also delete the `storage/` directory — since this holds all application data, you're prompted to confirm. When run non-interactively (piped from `curl`), the storage directory is **not** removed automatically; delete it manually afterwards if no longer needed:

```bash
rm -rf /opt/paprika/storage
```

## Next steps

After the service is up, follow [Initial Setup](./initial-setup) to create your first superadmin.
