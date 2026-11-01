#!/bin/bash
set -euo pipefail

# ─────────────────────────────────────────────────────────────────────────────
#  Paprika – Docker Install / Update Script
#
#  Run this script from the directory where Paprika should be set up.
#  It will generate the secrets under ./secrets, create a .env file,
#  download compose.yml, and start the stack.
#
#  Usage:
#    mkdir /srv/paprika && cd /srv/paprika
#    curl -fsSL https://raw.githubusercontent.com/svenkubiak/paprika/main/install-docker.sh | bash
# ─────────────────────────────────────────────────────────────────────────────

GITHUB_RAW="https://raw.githubusercontent.com/svenkubiak/paprika/main"
DOCS_URL="https://docs.getpaprika.dev/installation/docker"
ENV_FILE=".env"
SECRETS_DIR="secrets"
MONGO_SECRETS_DIR="mongodb-secrets"
COMPOSE_FILE="compose.yml"
MONGO_INIT_FILE="mongo-init.js"

# Both containers run with this group (Paprika as uid 33, MongoDB as uid 1000). Compose mounts the
# secret directories with the owner and mode they have on the host, so the group is what lets them read.
CONTAINER_GID=1000

# Generated once, never edited by hand. ./secrets is mounted into both containers.
GENERATED_SECRETS=(
    application.secret
    token.secret
    token.key
    session.cookie.secret
    session.cookie.key
    flash.cookie.secret
    flash.cookie.key
    authentication.cookie.secret
    authentication.cookie.key
    persistence.mongo.password
)

# The MongoDB root password, in a directory only the MongoDB container mounts: Paprika never sees it.
MONGO_ROOT_PASSWORD_FILE="${MONGO_SECRETS_DIR}/mongo.root.password"

# Prepared empty: SMTP is optional, an empty file counts as not set.
SMTP_SETTINGS=(
    smtp.host
    smtp.port
    smtp.username
    smtp.password
    smtp.authentication
    smtp.protocol
    smtp.from
    smtp.from_name
)

# ── Interactive input ─────────────────────────────────────────────────────────
# This script is usually run as "curl ... | bash", so stdin is the script
# itself - reading a prompt from stdin would swallow the rest of the script.
# Always talk to the controlling terminal instead.

# ask <variable-name> <prompt>; without a terminal the answer is empty, i.e. the default
ask() {
    local __reply=""
    if ! { true > /dev/tty; } 2>/dev/null; then
        printf -v "$1" '%s' ""
        return
    fi
    printf '%s' "$2" > /dev/tty
    IFS= read -r __reply < /dev/tty || true
    printf -v "$1" '%s' "$__reply"
}

# Installations before Paprika 1.0 kept their secrets in .env; there is no automatic migration.
legacy_layout_abort() {
    echo "" >&2
    echo "Error: This installation keeps its secrets in ${ENV_FILE}." >&2
    echo "       Paprika now reads them from files in ./${SECRETS_DIR}; this script does not" >&2
    echo "       migrate an existing installation. Move the values by hand as described in:" >&2
    echo "       ${DOCS_URL}#moving-secrets-from-env-to-files" >&2
    exit 1
}

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

    if [ ! -f "${SECRETS_DIR}/application.secret" ]; then
        legacy_layout_abort
    fi

    CURRENT=$(docker inspect --format '{{index .Config.Labels "org.opencontainers.image.version"}}' paprika 2>/dev/null || echo "unknown")

    echo ""
    echo "Running installation found (version: ${CURRENT})."
    ask confirm "Pull latest image and restart? [Y/n] "
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

if [ -f "$ENV_FILE" ] && grep -q "^APPLICATION_SECRET=" "$ENV_FILE"; then
    legacy_layout_abort
fi

gen_secret() { openssl rand -hex 32; }  # 64 hex chars

# ── Generate secrets ──────────────────────────────────────────────────────────

GENERATE_SECRETS=true
if [ -n "$(ls -A "$SECRETS_DIR" 2>/dev/null)$(ls -A "$MONGO_SECRETS_DIR" 2>/dev/null)" ]; then
    echo "Warning: ./${SECRETS_DIR} or ./${MONGO_SECRETS_DIR} already contains secrets."
    echo "         Regenerating them signs everyone out, and MongoDB keeps the old"
    echo "         password if its data volume already exists."
    ask confirm "Regenerate all secrets? [y/N] "
    if [[ "${confirm:-n}" =~ ^[yY] ]]; then
        rm -rf "$SECRETS_DIR" "$MONGO_SECRETS_DIR"
    else
        echo "Keeping existing secrets."
        GENERATE_SECRETS=false
    fi
fi

if [ "$GENERATE_SECRETS" = true ]; then
    echo "Generating secrets..."

    old_umask=$(umask)
    umask 077
    mkdir -p "$SECRETS_DIR" "$MONGO_SECRETS_DIR"

    for name in "${GENERATED_SECRETS[@]}"; do
        printf '%s' "$(gen_secret)" > "${SECRETS_DIR}/${name}"
    done
    for name in "${SMTP_SETTINGS[@]}"; do
        : > "${SECRETS_DIR}/${name}"
    done
    printf '%s' "$(gen_secret)" > "$MONGO_ROOT_PASSWORD_FILE"

    umask "$old_umask"
    echo "Secrets written to ./${SECRETS_DIR} and ./${MONGO_SECRETS_DIR}."
fi

# Files that do not exist yet, e.g. an SMTP setting added by a newer version, are prepared empty
for name in "${SMTP_SETTINGS[@]}"; do
    [ -f "${SECRETS_DIR}/${name}" ] || : > "${SECRETS_DIR}/${name}"
done

# Readable by the containers through their group and by the owner, by nobody else. The SMTP
# settings stay writable for the owner, who fills them in. The directories need the group as well,
# as Compose mounts them as a whole.
chmod 750 "$SECRETS_DIR" "$MONGO_SECRETS_DIR"
for name in "${GENERATED_SECRETS[@]}"; do
    chmod 440 "${SECRETS_DIR}/${name}"
done
for name in "${SMTP_SETTINGS[@]}"; do
    chmod 640 "${SECRETS_DIR}/${name}"
done
chmod 440 "$MONGO_ROOT_PASSWORD_FILE"

SECRET_PATHS=("$SECRETS_DIR" "$SECRETS_DIR"/* "$MONGO_SECRETS_DIR" "$MONGO_SECRETS_DIR"/*)
if ! chgrp "$CONTAINER_GID" "${SECRET_PATHS[@]}" 2>/dev/null; then
    echo ""
    echo "The containers read the secrets as group ${CONTAINER_GID}. Setting that group"
    echo "needs root, so sudo may ask for your password."
    if ! command -v sudo &>/dev/null || ! sudo chgrp "$CONTAINER_GID" "${SECRET_PATHS[@]}"; then
        echo "Error: Could not set group ${CONTAINER_GID} on the secrets. Run as root:" >&2
        echo "       chgrp -R ${CONTAINER_GID} $(pwd)/${SECRETS_DIR} $(pwd)/${MONGO_SECRETS_DIR}" >&2
        echo "       and start the script again." >&2
        exit 1
    fi
fi

# ── Generate .env ─────────────────────────────────────────────────────────────

if [ ! -f "$ENV_FILE" ]; then
    old_umask=$(umask)
    umask 077

    cat > "$ENV_FILE" << EOF
# ─────────────────────────────────────────────────────────────
#  Paprika – Docker Environment Configuration
#  Generated: $(date -u +"%Y-%m-%dT%H:%M:%SZ")
#
#  Secrets are not in this file: they are files in ./${SECRETS_DIR},
#  one per value, named like the config key they set.
#  MONGO_ROOT_USERNAME bootstraps the MongoDB container only
#  (with ./${MONGO_SECRETS_DIR}/mongo.root.password); it is never
#  passed to the Paprika app. MONGO_USERNAME is the scoped user
#  (readWriteAnyDatabase + dbAdminAnyDatabase, no user/server
#  administration rights) the Paprika app actually authenticates
#  as, with ./${SECRETS_DIR}/persistence.mongo.password.
# ─────────────────────────────────────────────────────────────

# ── Host port (external) ──────────────────────────────────────
HOST_PORT=8080

# ── MongoDB ───────────────────────────────────────────────────
MONGO_ROOT_USERNAME=root
MONGO_USERNAME=paprika
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
echo " Secrets:  $(pwd)/${SECRETS_DIR}"
echo "           $(pwd)/${MONGO_SECRETS_DIR} (MongoDB root password)"
echo " SMTP is optional and off. To send mail, fill in the smtp.*"
echo " files in ./${SECRETS_DIR} and run: docker compose restart paprika"
echo ""
echo " Check logs:"
echo "   docker compose logs -f"
echo "────────────────────────────────────────────"
