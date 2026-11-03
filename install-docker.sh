#!/bin/bash
set -euo pipefail

# ─────────────────────────────────────────────────────────────────────────────
#  Paprika – Docker Install / Update Script
#
#  Run this script from the directory where Paprika should be set up.
#  It will create a .env file, download compose.yml, and start the stack.
#
#  Usage:
#    mkdir /srv/paprika && cd /srv/paprika
#    curl -fsSL https://raw.githubusercontent.com/svenkubiak/paprika/main/install-docker.sh | bash
# ─────────────────────────────────────────────────────────────────────────────

GITHUB_RAW="https://raw.githubusercontent.com/svenkubiak/paprika/main"
ENV_FILE=".env"
COMPOSE_FILE="compose.yml"
MONGO_INIT_FILE="mongo-init.js"

# ── Preflight checks ──────────────────────────────────────────────────────────

for cmd in curl openssl docker; do
    if ! command -v "$cmd" &>/dev/null; then
        echo "Error: Required command not found: ${cmd}" >&2
        exit 1
    fi
done

if ! docker compose version &>/dev/null; then
    echo "Error: 'docker compose' (v2) is required but not found." >&2
    exit 1
fi

if ! docker info &>/dev/null; then
    echo "Error: Cannot connect to Docker. Is the daemon running and do you have access?" >&2
    exit 1
fi

# Image user (Dockerfile: useradd --uid 33 --gid 1000 appuser). The bind-mounted
# storage directory is created on the host, so it keeps the installing user's
# owner and umask, often root:root and 700. Paprika then refuses to start,
# because that user can neither read nor write /app/storage. Keep these two
# values in sync with the Dockerfile.
CONTAINER_UID=33
CONTAINER_GID=1000

ensure_storage_writable() {
    mkdir -p storage

    # Skip the chown on later updates once the directory is already the
    # container user. chown -R walks every uploaded file.
    local owner
    owner="$(stat -c '%u' storage 2>/dev/null || true)"
    if [ "$owner" = "$CONTAINER_UID" ]; then
        return 0
    fi

    echo "Making storage/ readable and writable for the container user (${CONTAINER_UID}:${CONTAINER_GID})..."

    if [ "$(id -u)" -eq 0 ]; then
        chown -R "${CONTAINER_UID}:${CONTAINER_GID}" storage
        chmod 750 storage
    elif command -v sudo >/dev/null 2>&1 && { [ -t 0 ] || sudo -n true >/dev/null 2>&1; }; then
        sudo chown -R "${CONTAINER_UID}:${CONTAINER_GID}" storage
        sudo chmod 750 storage
    else
        echo "Error: storage/ is not accessible by uid ${CONTAINER_UID}, and this script cannot chown it." >&2
        echo "Re-run as root, or run:" >&2
        echo "  sudo chown -R ${CONTAINER_UID}:${CONTAINER_GID} storage && sudo chmod 750 storage" >&2
        exit 1
    fi
}

# ── Determine install or update ───────────────────────────────────────────────

IS_UPDATE=false
if docker compose ps --quiet paprika 2>/dev/null | grep -q .; then
    IS_UPDATE=true
fi

# ── Branch: Install vs. Update ────────────────────────────────────────────────

if [ "$IS_UPDATE" = true ]; then

    # ── Update ────────────────────────────────────────────────────────────────

    CURRENT=$(docker inspect --format '{{index .Config.Labels "org.opencontainers.image.version"}}' paprika 2>/dev/null || echo "unknown")

    echo ""
    echo "Running installation found (version: ${CURRENT})."
    read -r -p "Pull latest image and restart? [Y/n] " confirm
    case "$confirm" in
        [nN]*) echo "Aborted."; exit 0 ;;
    esac

    echo ""
    echo "Pulling latest image..."
    docker compose pull paprika

    # A directory left owned by root (the usual result of the first install)
    # still makes the restarted container exit. Correct the owner, not the files.
    ensure_storage_writable

    echo "Restarting service..."
    docker compose up -d paprika

    echo ""
    echo "────────────────────────────────────────────"
    echo " Update complete."
    echo ""
    echo " Check logs:"
    echo "   docker compose logs -f paprika"
    echo "────────────────────────────────────────────"
    exit 0

fi

# ── Fresh install ─────────────────────────────────────────────────────────────

echo ""
echo "No running installation found – performing fresh install."
echo "Install location: $(pwd)"
echo ""

# ── Generate .env ─────────────────────────────────────────────────────────────

gen_secret() { openssl rand -hex 32; }  # 64 hex chars
gen_key()    { openssl rand -hex 32; }  # 64 hex chars

if [ -f "$ENV_FILE" ]; then
    echo "Warning: ${ENV_FILE} already exists."
    read -r -p "Overwrite and regenerate all secrets? [y/N] " confirm
    if [[ ! "${confirm:-n}" =~ ^[yY] ]]; then
        echo "Keeping existing .env."
        if ! grep -q "^MONGO_ROOT_USERNAME=" "$ENV_FILE"; then
            echo "Adding missing MONGO_ROOT_USERNAME/MONGO_ROOT_PASSWORD (MongoDB now bootstraps with a separate root user)..."
            {
                echo ""
                echo "# ── MongoDB root bootstrap (added by installer on update) ─────"
                echo "MONGO_ROOT_USERNAME=root"
                echo "MONGO_ROOT_PASSWORD=$(gen_secret)"
            } >> "$ENV_FILE"
        fi
    else
        rm "$ENV_FILE"
    fi
fi

if [ ! -f "$ENV_FILE" ]; then
    echo "Generating secure configuration..."

    old_umask=$(umask)
    umask 077

    cat > "$ENV_FILE" << EOF
# ─────────────────────────────────────────────────────────────
#  Paprika – Docker Environment Configuration
#  Generated: $(date -u +"%Y-%m-%dT%H:%M:%SZ")
#
#  All secrets are auto-generated. compose.yml loads this file into
#  the paprika container and then clears MONGO_ROOT_* and HOST_PORT,
#  so the root password never reaches the app.
#  MONGO_ROOT_USERNAME / MONGO_ROOT_PASSWORD bootstrap the MongoDB
#  container only (mongo-init.js uses them to create the scoped
#  user below).
#  PERSISTENCE_MONGO_USERNAME / PERSISTENCE_MONGO_PASSWORD are the
#  scoped user (readWriteAnyDatabase + dbAdminAnyDatabase, no
#  user/server administration rights) the Paprika app authenticates as.
# ─────────────────────────────────────────────────────────────

# ── Host port (external) ──────────────────────────────────────
HOST_PORT=8080

# ── Application ───────────────────────────────────────────────
APPLICATION_SECRET=$(gen_secret)

# ── Token ─────────────────────────────────────────────────────
TOKEN_SECRET=$(gen_secret)
TOKEN_KEY=$(gen_key)

# ── Session Cookie ────────────────────────────────────────────
SESSION_COOKIE_SECRET=$(gen_secret)
SESSION_COOKIE_KEY=$(gen_key)

# ── Flash Cookie ──────────────────────────────────────────────
FLASH_COOKIE_SECRET=$(gen_secret)
FLASH_COOKIE_KEY=$(gen_key)

# ── Authentication Cookie ─────────────────────────────────────
AUTHENTICATION_COOKIE_SECRET=$(gen_secret)
AUTHENTICATION_COOKIE_KEY=$(gen_key)

# ── MongoDB ───────────────────────────────────────────────────
MONGO_ROOT_USERNAME=root
MONGO_ROOT_PASSWORD=$(gen_secret)
PERSISTENCE_MONGO_USERNAME=paprika
PERSISTENCE_MONGO_PASSWORD=$(gen_secret)

# ── Email (SMTP) ──────────────────────────────────────────────
#  Optional. Only needed if you enable password reset or email
#  verification for a tenant. Leave SMTP_HOST unset to disable
#  sending. Make sure SPF/DKIM allow the SMTP_FROM domain.
#SMTP_HOST=smtp.example.com
#SMTP_PORT=587
#SMTP_USERNAME=
#SMTP_PASSWORD=
#SMTP_AUTHENTICATION=true
#SMTP_PROTOCOL=smtp
#SMTP_FROM=no-reply@example.com
#SMTP_FROM_NAME=Paprika
EOF

    umask "$old_umask"
    chmod 600 "$ENV_FILE"
    echo ".env written."
fi

# ── Create data directories ───────────────────────────────────────────────────

mkdir -p storage mongodb
ensure_storage_writable

# ── Download compose file ─────────────────────────────────────────────────────

echo ""
echo "Downloading ${COMPOSE_FILE} and ${MONGO_INIT_FILE}..."
curl -fsSL -o "$COMPOSE_FILE" "${GITHUB_RAW}/${COMPOSE_FILE}"
curl -fsSL -o "$MONGO_INIT_FILE" "${GITHUB_RAW}/${MONGO_INIT_FILE}"

# ── Start stack ───────────────────────────────────────────────────────────────

echo ""
echo "Starting stack..."
docker compose up -d

HOST_PORT=$(grep "^HOST_PORT=" "$ENV_FILE" | cut -d= -f2)

echo ""
echo "────────────────────────────────────────────"
echo " Installation complete."
echo ""
echo " Paprika is starting at http://localhost:${HOST_PORT}"
echo " MongoDB is initialising – first start may take ~30 seconds."
echo ""
echo " Check logs:"
echo "   docker compose logs -f"
echo "────────────────────────────────────────────"