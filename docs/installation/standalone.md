# Standalone (.deb)

The standalone package installs Paprika as a hardened systemd service on Ubuntu or Debian. Unlike the Docker setup, it doesn't bundle MongoDB — you provide a reachable instance and hand its connection details to the installer.

## Prerequisites

- Ubuntu 22.04+ or Debian 12+
- `amd64` or `arm64` architecture (the script detects this automatically via `uname -m`)
- `curl`, `openssl`, `sha256sum`, and `dpkg-deb` available on the host
- A running MongoDB instance reachable from this host — if you don't have one yet, [install MongoDB](https://www.mongodb.com/docs/manual/installation/) first and make sure it's reachable before proceeding
- A user on that instance with the scoped role Paprika needs (see [MongoDB setup](#mongodb-setup) below) — Paprika does not create this user itself
- Root access (the script must run via `sudo`)

## MongoDB setup

Paprika creates its own database (holding tenants, settings, and the superadmin account) plus a **separate MongoDB database per tenant**, provisioned on demand whenever a tenant is created — the database names aren't known ahead of time, so a role scoped to a single named database isn't enough. Instead, create a user authenticated against the `admin` database (`authSource=admin`) with `readWriteAnyDatabase` and `dbAdminAnyDatabase` — read/write plus the ability to create, index, and drop databases and collections across the deployment, without the user-management or server-administration rights a `root` account would also carry. Create it before starting the service, e.g. via `mongosh`:

```js
use admin
db.createUser({
  user: "paprika",
  pwd: "<a-strong-password>",
  roles: [
    { role: "readWriteAnyDatabase", db: "admin" },
    { role: "dbAdminAnyDatabase", db: "admin" }
  ]
})
```

You'll enter the username into `.env` as `PERSISTENCE_MONGO_USERNAME` and the password into the file `secrets/persistence.mongo.password` further down.

::: tip Enter the password as it is
Paprika hands username and password to the MongoDB driver separately, not inside a connection string, so characters like `:`, `@`, `/`, `?`, `#` or `%` need no encoding — and must not be percent-encoded, since `%40` would be taken as those three characters. The password file holds the password and nothing else: no quotes, no `KEY=` prefix; a trailing line break is ignored. A long random password such as `openssl rand -hex 32` avoids the question entirely.
:::

::: warning This is still a broadly-scoped account
`readWriteAnyDatabase`/`dbAdminAnyDatabase` cover every database on the instance, not just Paprika's — tenant isolation in Paprika is enforced entirely in the application, not by MongoDB roles. Run MongoDB as its own instance dedicated to this Paprika installation if you can, rather than sharing it with unrelated applications or data.
:::

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
5. Generates all application secrets as files in `secrets/` (see [Configuration](./configuration#secret-files)) and a `.env` with the remaining settings — **except the MongoDB connection**: the host and username are left as `CHANGE_ME` placeholders, the password file and the SMTP files are prepared empty
6. Creates a dedicated, unprivileged `paprika` system user
7. Locks down file ownership and permissions (`root:paprika`, `640`/`750`) so the app can read its own files and secrets but not modify them, with only `storage/` fully owned by the `paprika` user
8. Installs and enables a systemd unit with sandboxing hardening (`ProtectSystem=strict`, `PrivateDevices`, capability dropping, etc.) applied out of the box
9. Prints instructions to fill in `.env` and the MongoDB password and to start the service — the service is **not** started automatically because MongoDB credentials are still required

The script never starts the service automatically — that's always a manual step so you can finish any remaining configuration first.

Once the script finishes, edit the generated `.env` and replace the `CHANGE_ME` placeholders with your MongoDB connection details, and write the MongoDB password into its file. **Make sure MongoDB is running and reachable before starting Paprika.** Then start the service manually:

```bash
nano /opt/paprika/.env                               # fill in PERSISTENCE_MONGO_HOST and _USERNAME
nano /opt/paprika/secrets/persistence.mongo.password # the password, nothing else
systemctl start paprika
```

To send mail, fill in the `smtp.*` files in `secrets/` the same way and restart the service; see [Configuration](./configuration#secret-files) for each file. With an empty `smtp.host`, no mail is sent.

The service is registered to start automatically on server reboot (`systemctl enable`) — you only need to start it manually once after the initial setup or after an update.

On first start against a fresh database, Paprika prints a one-time superadmin setup link to the log. Since the systemd journal is noisy on startup, filter for it directly instead of scrolling:

```bash
journalctl -u paprika -f | grep --line-buffered setup
```

The link is only valid for 30 minutes — see [Initial Setup](./initial-setup) for how to complete it.

By default the generated `.env` sets `CONNECTOR_HTTP_HOST=127.0.0.1`, so Paprika only listens on the loopback interface. To make it reachable from the network directly, change that value in `.env` before starting the service.

## Update

Running the same command again from the install directory detects the currently installed version via `.version` and compares it with the latest release:

- Already on the latest version: the script reports it and exits without changing anything.
- A newer version is available: it asks whether to install it. Declining opens a list of all released versions to pick from, so you can install an older one instead.

`.env`, `.version`, `secrets/`, and `storage/` are never touched by an update. An installation whose `.env` still holds the secrets is from before secret files: the script stops before downloading anything and points to [Moving secrets from .env to files](#moving-secrets-from-env-to-files). After the script finishes, start the service manually:

```bash
cd /opt/paprika
curl -fsSL https://raw.githubusercontent.com/svenkubiak/paprika/main/install-or-update.sh | sudo bash
systemctl start paprika
```

To install a specific version without any prompts — for example to reinstall the current one — pass `--version`:

```bash
cd /opt/paprika
curl -fsSL https://raw.githubusercontent.com/svenkubiak/paprika/main/install-or-update.sh | sudo bash -s -- --version 0.44.0
```

Installing a version older than the installed one is possible, but not supported: data written by a newer version may be unreadable for an older one. Back up your database first.

## Service management

```bash
# Check service status
systemctl status paprika

# Follow logs
journalctl -u paprika -f

# Restart after manual config changes
systemctl restart paprika
```

### Log files

Besides the journal, Paprika writes its own log to `logs/` under the install directory:

```bash
tail -f /opt/paprika/logs/paprika.log
```

The file rolls over daily and whenever it reaches 10 MB. Rolled files are gzipped as
`paprika-<date>-<n>.log.gz` in the same directory and deleted automatically once they are
older than seven days, so the directory stays bounded without a logrotate entry. Point
`PAPRIKA_LOG_PATH` somewhere else if you want the logs on a different volume — and add that
path to `ReadWritePaths` in `/lib/systemd/system/paprika.service` when you do, otherwise the
hardened unit will deny the writes.

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
4. Deletes the application files (`bin/`, `lib/`, `share/`, `logs/`, `secrets/`, `.env`, `.version`)
5. Asks whether to also delete the `storage/` directory — since this holds all application data, you're prompted to confirm. When run non-interactively (piped from `curl`), the storage directory is **not** removed automatically; delete it manually afterwards if no longer needed:

```bash
rm -rf /opt/paprika/storage
```

## Testing an unreleased version

`--deb` installs or updates from a local package instead of a GitHub release — everything else, from the secrets to the systemd unit, runs as for a release. The package names its version, so `--deb` does not combine with `--version`, and it has to match the architecture of the machine. There is no published checksum to verify a local package against.

Build the package on Linux from a checkout of the branch, with the profile of the target architecture (`package-deb-x86` or `package-deb-arm64`):

```bash
mvn -P package-deb-x86 -DskipTests package
```

Then run the script of the same checkout from the install directory:

```bash
cd /opt/paprika
sudo bash ~/paprika/install-or-update.sh --deb ~/paprika/target/dist/<package>.deb
```

## Moving secrets from .env to files

Installations from before secret files keep their secrets in `.env`. The install script does not migrate them: on such an installation it stops before downloading anything and changes nothing. Move the values once by hand — they stay the same, so nobody is signed out — and run the update afterwards:

```bash
cd /opt/paprika
systemctl stop paprika

mkdir -m 750 secrets
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
put persistence.mongo.password "$PERSISTENCE_MONGO_PASSWORD"
put smtp.host "${SMTP_HOST:-}"
put smtp.port "${SMTP_PORT:-}"
put smtp.username "${SMTP_USERNAME:-}"
put smtp.password "${SMTP_PASSWORD:-}"
put smtp.authentication "${SMTP_AUTHENTICATION:-}"
put smtp.protocol "${SMTP_PROTOCOL:-}"
put smtp.from "${SMTP_FROM:-}"
put smtp.from_name "${SMTP_FROM_NAME:-}"
chown -R root:paprika secrets && chmod 640 secrets/*

sed -i -E '/^(APPLICATION_SECRET|TOKEN_(SECRET|KEY)|(SESSION|FLASH|AUTHENTICATION)_COOKIE_(SECRET|KEY)|PERSISTENCE_MONGO_PASSWORD|#?SMTP_[A-Z_]+)=/d' .env
echo "APPLICATION_SECRETS_PATH=/opt/paprika/secrets" >> .env
```

Then run the [update](#update) as usual. `. ./.env` reads `.env` as shell code: if an SMTP value contains spaces or quotes, check the `smtp.*` files afterwards.

## Next steps

After the service is up, follow [Initial Setup](./initial-setup) to create your first superadmin.
