# Standalone (.deb)

The standalone package installs Paprika as a hardened systemd service on Ubuntu or Debian. Unlike the Docker setup, it doesn't bundle MongoDB — you provide a reachable instance and hand its connection details to the installer.

## Prerequisites

- Ubuntu 22.04+ or Debian 12+
- `amd64` or `arm64` architecture (the script detects this automatically via `uname -m`)
- `curl`, `openssl`, `sha256sum`, and `dpkg-deb` available on the host
- A running MongoDB instance reachable from this host, plus a username and password for it
- Root access (the script must run via `sudo`)

## Install

Choose the directory Paprika should live under — the service is installed to `<that-directory>/paprika/`, not a fixed system path:

```bash
cd /srv
curl -fsSL https://raw.githubusercontent.com/svenkubiak/paprika/main/install-or-update.sh | sudo bash
```

The script:

1. Verifies it's running as root and that all required tools are present
2. Detects the CPU architecture and fetches the matching `.deb` asset from the latest GitHub release
3. Verifies the download's SHA-256 checksum before touching anything else
4. Extracts the package and moves it into place at `<install-dir>/paprika/`
5. Generates a `.env` file with all application secrets (see [Configuration](./configuration)) — **except the MongoDB connection**, which is left as `CHANGE_ME` placeholders
6. Pauses and asks you to edit `.env` and fill in your MongoDB host, port, username, and password before it will continue
7. Creates a dedicated, unprivileged `paprika` system user
8. Locks down file ownership and permissions (`root:paprika`, `640`/`750`) so the app can read its own files but not modify them, with only `storage/` fully owned by the `paprika` user
9. Installs and enables a systemd unit with sandboxing hardening (`ProtectSystem=strict`, `PrivateDevices`, capability dropping, etc.) applied out of the box
10. Starts the service

## Update

Running the same command again against an existing install detects the currently installed version via `<install-dir>/paprika/.version`, shows you what's available, and asks for confirmation before replacing the application binaries. `.env`, `.version`, and `storage/` are never touched by an update:

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
