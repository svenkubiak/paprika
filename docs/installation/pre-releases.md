# Pre-releases

A pre-release such as `1.0.0-Beta1` lets you try the next version before it is released. It is built and published like a release — a Docker image and `.deb` packages for x86 and arm64 — but marked as a pre-release: neither the Docker image tag `latest` nor the latest GitHub release ever points to one, so no installation gets it by accident. Install one only on purpose, and preferably not in production.

Available pre-releases are listed on the [releases page](https://github.com/svenkubiak/paprika/releases), marked *Pre-release*, and as image tags in the [container registry](https://github.com/svenkubiak/paprika/pkgs/container/paprika).

The commands below use `1.0.0-Beta1`; use the version you want in both places of a command.

::: tip Take the install script from the tag of the pre-release
Every command downloads the install script from the tag of the pre-release, not from `main`. The script on `main` belongs to the latest release and does not know what a newer version needs.
:::

## Docker

Install into an empty directory:

```bash
mkdir /srv/paprika && cd /srv/paprika
curl -fsSL https://raw.githubusercontent.com/svenkubiak/paprika/1.0.0-Beta1/install-docker.sh \
  | bash -s -- --version 1.0.0-Beta1
```

The script does not start the stack. Fill in SMTP in `secrets/` if you need it, then start it and find the setup link for the first superadmin:

```bash
docker compose up -d
docker compose logs -f paprika | grep --line-buffered setup
```

The stack runs the image `ghcr.io/svenkubiak/paprika:1.0.0-Beta1`, with the `compose.yml` and `mongo-init.js` of the same tag. The script keeps the version in `.env` as `PAPRIKA_VERSION`: an update without `--version` stays on it.

Update to the next pre-release, from the same directory:

```bash
cd /srv/paprika
curl -fsSL https://raw.githubusercontent.com/svenkubiak/paprika/1.0.0-Beta2/install-docker.sh \
  | bash -s -- --version 1.0.0-Beta2
```

Once the release is out, move to it the same way with `--version 1.0.0`. To follow the latest release from then on, remove the `PAPRIKA_VERSION` line from `.env`.

See [Docker](./docker) for everything else about the stack.

## Standalone (.deb)

Install from the install directory:

```bash
mkdir -p /opt/paprika && cd /opt/paprika
curl -fsSL https://raw.githubusercontent.com/svenkubiak/paprika/1.0.0-Beta1/install-or-update.sh \
  | sudo bash -s -- --version 1.0.0-Beta1
```

Then fill in `PERSISTENCE_MONGO_HOST` and `PERSISTENCE_MONGO_USERNAME` in `.env`, write the MongoDB password into `secrets/persistence.mongo.password`, and start the service:

```bash
sudo systemctl start paprika
```

Update to the next pre-release:

```bash
cd /opt/paprika
curl -fsSL https://raw.githubusercontent.com/svenkubiak/paprika/1.0.0-Beta2/install-or-update.sh \
  | sudo bash -s -- --version 1.0.0-Beta2
sudo systemctl start paprika
```

A pre-release counts as older than its release, so `--version 1.0.0` moves to the release once it is out. While the installed pre-release is newer than the latest release, an update without `--version` changes nothing.

See [Standalone (.deb)](./standalone) for everything else about the installation.

## Good to know

- **Going back is a downgrade.** From a pre-release back to an earlier release, data written by the newer version may be unreadable for the older one. Back up the database before you install a pre-release.
- **Installations from before secret files stop.** If the install directory still keeps its secrets in `.env`, the script changes nothing and points to the steps for moving them into files: [Docker](./docker#moving-secrets-from-env-to-files), [Standalone](./standalone#moving-secrets-from-env-to-files). A fresh directory or machine avoids that.
- **Docker may ask for your password.** The script gives the secret files and `storage/` group `1000`, which the containers run with; that needs root unless you are a member of the group, so it asks through `sudo`.
