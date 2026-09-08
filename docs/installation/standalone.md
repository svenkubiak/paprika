# Standalone (.deb)

The standalone package installs Paprika as a hardened systemd service on Ubuntu or Debian. Unlike the Docker setup, it doesn't bundle MongoDB — you provide a reachable instance and hand its connection details to the installer.

## Prerequisites

- Ubuntu 22.04+ or Debian 12+
- `amd64` or `arm64` architecture (the script detects this automatically via `uname -m`)
- `curl`, `openssl`, `sha256sum`, and `dpkg-deb` available on the host
- A running MongoDB instance reachable from this host, plus a username and password for it
- Root access (the script must run via `sudo`)

## Install

Create the directory Paprika should live in and run the installer from it — the service is installed directly into that directory:

```bash
mkdir -p /opt/paprika
cd /opt/paprika
curl -fsSL https://raw.githubusercontent.com/svenkubiak/paprika/main/install-or-update.sh | sudo bash
```

The script:

1. Verifies it's running as root and that all required tools are present
2. Detects the CPU architecture and fetches the matching `.deb` asset from the latest GitHub release
3. Verifies the download's SHA-256 checksum before touching anything else
4. Extracts the package and installs it into the current directory
5. Generates a `.env` file with all application secrets (see [Configuration](./configuration)) — **except the MongoDB connection**, which is left as `CHANGE_ME` placeholders
6. Since the script is piped from `curl` it cannot wait for interactive input — it exits and asks you to edit `.env` and fill in your MongoDB host, username, and password, then run the same command again to complete setup
7. Creates a dedicated, unprivileged `paprika` system user
8. Locks down file ownership and permissions (`root:paprika`, `640`/`750`) so the app can read its own files but not modify them, with only `storage/` fully owned by the `paprika` user
9. Installs and enables a systemd unit with sandboxing hardening (`ProtectSystem=strict`, `PrivateDevices`, capability dropping, etc.) applied out of the box
10. Starts the service

By default the generated `.env` sets `CONNECTOR_HTTP_HOST=127.0.0.1`, so Paprika only listens on the loopback interface. To make it reachable from the network directly, change that value in `.env` and restart the service.

## Update

Running the same command again from the install directory detects the currently installed version via `.version`, shows you what's available, and asks for confirmation before replacing the application binaries. `.env`, `.version`, and `storage/` are never touched by an update:

```bash
curl -fsSL https://raw.githubusercontent.com/svenkubiak/paprika/main/install-or-update.sh | sudo bash
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

## Next steps

After the service is up, follow [Initial Setup](./initial-setup) to create your first superadmin.
