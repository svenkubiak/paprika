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
#  All secrets are auto-generated.
#  MONGO_ROOT_USERNAME / MONGO_ROOT_PASSWORD bootstrap the MongoDB
#  container only (mongo-init.js uses them to create the scoped
#  user below); they are never passed to the Paprika app.
#  MONGO_USERNAME / MONGO_PASSWORD are the scoped user
#  (readWriteAnyDatabase + dbAdminAnyDatabase, no user/server
#  administration rights) the Paprika app actually authenticates as.
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
MONGO_USERNAME=paprika
MONGO_PASSWORD=$(gen_secret)

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