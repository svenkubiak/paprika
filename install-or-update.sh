#!/bin/bash
set -euo pipefail

# ─────────────────────────────────────────────────────────────────────────────
#  Paprika – Install / Update Script
#
#  Run this script from the directory where Paprika should be installed.
#  The application will be placed in a 'paprika/' subdirectory at that location.
#
#  Usage:
#    cd /srv                   # choose your install location
#    curl -fsSL https://raw.githubusercontent.com/svenkubiak/paprika/main/install-or-update.sh \
#         | sudo bash
#
#  The service will be installed at: <current-directory>/paprika/
# ─────────────────────────────────────────────────────────────────────────────

GITHUB_REPO="svenkubiak/paprika"
APP_NAME="paprika"
TARGET_DIR="$(pwd)"
INSTALL_DIR="${TARGET_DIR}/paprika"
ENV_FILE="${INSTALL_DIR}/.env"
VERSION_FILE="${INSTALL_DIR}/.version"
SERVICE_FILE="/lib/systemd/system/${APP_NAME}.service"

# ── Preflight checks ──────────────────────────────────────────────────────────

if [ "$(id -u)" -ne 0 ]; then
    echo "Error: This script must be run as root (sudo)." >&2
    exit 1
fi

for cmd in curl openssl sha256sum dpkg-deb; do
    if ! command -v "$cmd" &>/dev/null; then
        echo "Error: Required command not found: ${cmd}" >&2
        exit 1
    fi
done

echo "Install location: ${INSTALL_DIR}"

# ── Architecture detection ────────────────────────────────────────────────────

ARCH=$(uname -m)
case "$ARCH" in
    x86_64)  DEB_ARCH="amd64" ;;
    aarch64) DEB_ARCH="arm64" ;;
    *)
        echo "Error: Unsupported architecture: ${ARCH}" >&2
        exit 1
        ;;
esac

echo "Architecture:     ${ARCH} (${DEB_ARCH})"

# ── Determine install or update ───────────────────────────────────────────────

IS_UPDATE=false
CURRENT_VERSION=""

if [ -f "$VERSION_FILE" ]; then
    IS_UPDATE=true
    CURRENT_VERSION=$(cat "$VERSION_FILE")
elif [ -d "$INSTALL_DIR" ]; then
    echo ""
    echo "Warning: ${INSTALL_DIR} exists but contains no version file." >&2
    read -r -p "Remove it and perform a fresh install? [y/N] " confirm
    case "$confirm" in
        [yY]*) rm -rf "$INSTALL_DIR" ;;
        *) echo "Aborted."; exit 0 ;;
    esac
fi

# ── Download latest release ───────────────────────────────────────────────────

echo ""
echo "Fetching latest release information from GitHub..."

RELEASE_JSON=$(curl -fsSL "https://api.github.com/repos/${GITHUB_REPO}/releases/latest")

DOWNLOAD_URL=$(echo "$RELEASE_JSON" \
    | grep '"browser_download_url"' \
    | grep "${DEB_ARCH}\.deb\"" \
    | head -n1 \
    | cut -d '"' -f 4)

CHECKSUM_URL=$(echo "$RELEASE_JSON" \
    | grep '"browser_download_url"' \
    | grep "${DEB_ARCH}\.deb\.sha256\"" \
    | head -n1 \
    | cut -d '"' -f 4)

if [ -z "$DOWNLOAD_URL" ]; then
    echo "Error: Could not find a .deb release asset for architecture ${DEB_ARCH}." >&2
    echo "       Check https://github.com/${GITHUB_REPO}/releases for available assets." >&2
    exit 1
fi

DEB_FILE=$(mktemp /tmp/paprika-XXXXXX.deb)
EXTRACT_TMP=$(mktemp -d /tmp/paprika-extract-XXXXXX)
trap 'rm -rf "$DEB_FILE" "$EXTRACT_TMP"' EXIT

echo "Downloading: ${DOWNLOAD_URL}"
curl -fsSL --progress-bar -o "$DEB_FILE" "$DOWNLOAD_URL"

# ── Checksum verification ─────────────────────────────────────────────────────

if [ -n "$CHECKSUM_URL" ]; then
    echo "Verifying checksum..."
    EXPECTED=$(curl -fsSL "$CHECKSUM_URL" | awk '{print $1}')
    ACTUAL=$(sha256sum "$DEB_FILE" | awk '{print $1}')
    if [ "$EXPECTED" != "$ACTUAL" ]; then
        echo "Error: Checksum mismatch – download may be corrupted or tampered with." >&2
        echo "  Expected: ${EXPECTED}" >&2
        echo "  Actual:   ${ACTUAL}" >&2
        exit 1
    fi
    echo "Checksum OK."
else
    echo "Warning: No .sha256 file found in this release – skipping integrity check." >&2
fi

NEW_VERSION=$(dpkg-deb -f "$DEB_FILE" Version 2>/dev/null || echo "unknown")

# ── Extract package ───────────────────────────────────────────────────────────
# dpkg-deb preserves the original directory structure under EXTRACT_TMP.
# jpackage uses --install-dir /opt, so app files land at EXTRACT_TMP/opt/paprika/.

dpkg-deb --extract "$DEB_FILE" "$EXTRACT_TMP"
EXTRACTED_APP="${EXTRACT_TMP}/opt/paprika"

if [ ! -d "$EXTRACTED_APP" ]; then
    echo "Error: Expected app directory not found inside .deb: ${EXTRACTED_APP}" >&2
    exit 1
fi

# ── Branch: Install vs. Update ────────────────────────────────────────────────

if [ "$IS_UPDATE" = true ]; then

    # ── Update ────────────────────────────────────────────────────────────────

    echo ""
    echo "  Installed version : ${CURRENT_VERSION}"
    echo "  Available version : ${NEW_VERSION}"
    echo ""

    if [ "$CURRENT_VERSION" = "$NEW_VERSION" ]; then
        echo "Already on the latest version (${CURRENT_VERSION})."
        read -r -p "Reinstall anyway? [y/N] " confirm
        case "$confirm" in
            [yY]*) ;;
            *) echo "Aborted."; exit 0 ;;
        esac
    else
        read -r -p "Update from ${CURRENT_VERSION} to ${NEW_VERSION}? [Y/n] " confirm
        case "$confirm" in
            [nN]*) echo "Aborted."; exit 0 ;;
        esac
    fi

    if [ ! -f "$ENV_FILE" ]; then
        echo ""
        echo "Warning: ${ENV_FILE} not found. The service may not start correctly after update." >&2
        read -r -p "Continue anyway? [y/N] " confirm
        case "$confirm" in
            [yY]*) ;;
            *) echo "Aborted."; exit 0 ;;
        esac
    fi

    echo ""
    echo "Stopping service..."
    systemctl stop "$APP_NAME" || true

    # Replace only the app directories from the new package.
    # .env, .version and storage/ are not part of the .deb and remain untouched.
    rm -rf "${INSTALL_DIR}/bin" "${INSTALL_DIR}/lib"
    cp -a "${EXTRACTED_APP}/bin" "${INSTALL_DIR}/bin"
    cp -a "${EXTRACTED_APP}/lib" "${INSTALL_DIR}/lib"

else

    # ── Fresh install ─────────────────────────────────────────────────────────

    echo ""
    echo "No existing installation found."
    echo "Installing version ${NEW_VERSION} to ${INSTALL_DIR} ..."
    echo ""

    mkdir -p "$TARGET_DIR"
    mv "$EXTRACTED_APP" "$INSTALL_DIR"

    # ── Generate .env ─────────────────────────────────────────────────────────

    gen_secret() { openssl rand -hex 32; }  # 64 hex chars
    gen_key()    { openssl rand -hex 32; }  # 64 hex chars

    echo "Generating secure environment configuration..."

    OLD_UMASK=$(umask)
    umask 077

    cat > "$ENV_FILE" << EOF
# ─────────────────────────────────────────────────────────────
#  Paprika – Production Environment Configuration
#  Generated: $(date -u +"%Y-%m-%dT%H:%M:%SZ")
#  Install:   ${INSTALL_DIR}
#
#  Permissions: root:paprika 640 – never world-readable
# ─────────────────────────────────────────────────────────────

# ── Application ───────────────────────────────
APPLICATION_SECRET=$(gen_secret)

# ── HTTP Connector ────────────────────────────
CONNECTOR_HTTP_HOST=0.0.0.0
CONNECTOR_HTTP_PORT=8080

# ── Token ─────────────────────────────────────
TOKEN_SECRET=$(gen_secret)
TOKEN_KEY=$(gen_key)

# ── Session Cookie ────────────────────────────
SESSION_COOKIE_SECRET=$(gen_secret)
SESSION_COOKIE_KEY=$(gen_key)

# ── Flash Cookie ──────────────────────────────
FLASH_COOKIE_SECRET=$(gen_secret)
FLASH_COOKIE_KEY=$(gen_key)

# ── Authentication Cookie ─────────────────────
AUTHENTICATION_COOKIE_SECRET=$(gen_secret)
AUTHENTICATION_COOKIE_KEY=$(gen_key)

# ── Storage ───────────────────────────────────
PAPRIKA_STORAGE=${INSTALL_DIR}/storage

# ── MongoDB ───────────────────────────────────
PERSISTENCE_MONGO_HOST=CHANGE_ME
PERSISTENCE_MONGO_PORT=27017
PERSISTENCE_MONGO_USERNAME=CHANGE_ME
PERSISTENCE_MONGO_PASSWORD=CHANGE_ME

# ── Email (SMTP) ──────────────────────────────
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

    umask "$OLD_UMASK"

    echo ".env written to ${ENV_FILE}"
    echo ""
    echo "  ┌─────────────────────────────────────────────────────────────┐"
    echo "  │  ACTION REQUIRED before continuing:                         │"
    echo "  │                                                             │"
    echo "  │    nano ${ENV_FILE}"
    echo "  │                                                             │"
    echo "  │  Replace all CHANGE_ME values with your MongoDB             │"
    echo "  │  connection details.                                        │"
    echo "  └─────────────────────────────────────────────────────────────┘"
    echo ""

    while grep -q "CHANGE_ME" "$ENV_FILE"; do
        read -r -p "Press ENTER once all CHANGE_ME values have been replaced..."
        if grep -q "CHANGE_ME" "$ENV_FILE"; then
            echo "  Still found CHANGE_ME in ${ENV_FILE} – please complete the configuration."
        fi
    done

fi

# ── System user ───────────────────────────────────────────────────────────────

if ! id -u paprika > /dev/null 2>&1; then
    useradd --system --no-create-home --shell /bin/false paprika
fi

# ── Permissions ───────────────────────────────────────────────────────────────
# root:paprika ownership: the app can read its own files but not modify them.
# Only the storage directory is fully owned by the app user.

chown root:paprika "$INSTALL_DIR"
chmod 750 "$INSTALL_DIR"

find "$INSTALL_DIR" -mindepth 1 \
    -not -path "${INSTALL_DIR}/storage*" \
    -not -name ".env" \
    -not -name ".version" \
    -exec chown root:paprika {} \;

find "$INSTALL_DIR" -mindepth 1 -type d \
    -not -path "${INSTALL_DIR}/storage*" \
    -exec chmod 750 {} \;

find "$INSTALL_DIR" -mindepth 1 -type f \
    -not -path "${INSTALL_DIR}/storage*" \
    -not -name ".env" \
    -not -name ".version" \
    -exec chmod 640 {} \;

chmod 750 "${INSTALL_DIR}/bin/${APP_NAME}" 2>/dev/null || true

mkdir -p "${INSTALL_DIR}/storage"
chown paprika:paprika "${INSTALL_DIR}/storage"
chmod 750 "${INSTALL_DIR}/storage"

chown root:paprika "$ENV_FILE" 2>/dev/null || true
chmod 640 "$ENV_FILE" 2>/dev/null || true

# ── Version file ──────────────────────────────────────────────────────────────

echo "$NEW_VERSION" > "$VERSION_FILE"
chown root:paprika "$VERSION_FILE"
chmod 640 "$VERSION_FILE"

# ── systemd service ───────────────────────────────────────────────────────────
# Generated with the actual install path so paths are always correct.

cat > "$SERVICE_FILE" << EOF
[Unit]
Description=Paprika Application
After=network.target
StartLimitIntervalSec=60
StartLimitBurst=3

[Service]
Type=simple
User=paprika
Group=paprika
WorkingDirectory=${INSTALL_DIR}
EnvironmentFile=${INSTALL_DIR}/.env
ExecStart=${INSTALL_DIR}/bin/${APP_NAME}
Restart=on-failure
RestartSec=10
StandardOutput=journal
StandardError=journal
SyslogIdentifier=${APP_NAME}

# ── Process hardening ─────────────────────────────────────────
NoNewPrivileges=yes
LockPersonality=yes
RestrictRealtime=yes
RestrictSUIDSGID=yes
ProtectClock=yes
ProtectHostname=yes
SystemCallArchitectures=native

# ── Filesystem hardening ──────────────────────────────────────
PrivateTmp=yes
PrivateDevices=yes
ProtectSystem=strict
ProtectHome=yes
ProtectKernelTunables=yes
ProtectKernelModules=yes
ProtectKernelLogs=yes
ProtectControlGroups=yes
ReadWritePaths=${INSTALL_DIR}/storage

# ── Network hardening ─────────────────────────────────────────
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX

# ── Capability hardening ──────────────────────────────────────
CapabilityBoundingSet=
AmbientCapabilities=

# ── File creation mask ────────────────────────────────────────
UMask=0027

# NOTE: MemoryDenyWriteExecute is intentionally NOT set.
# The JVM JIT compiler must write executable memory pages at runtime.

[Install]
WantedBy=multi-user.target
EOF

chmod 644 "$SERVICE_FILE"

systemctl daemon-reload
systemctl enable "$APP_NAME"

if [ "$IS_UPDATE" = true ]; then
    systemctl restart "$APP_NAME" || true
    echo ""
    echo "────────────────────────────────────────────"
    echo " Update complete: ${CURRENT_VERSION} → ${NEW_VERSION}"
else
    systemctl start "$APP_NAME" || true
    echo ""
    echo "────────────────────────────────────────────"
    echo " Installation complete: ${NEW_VERSION}"
    echo " Installed to: ${INSTALL_DIR}"
fi

echo ""
echo " Check service status:"
echo "   systemctl status ${APP_NAME}"
echo "   journalctl -u ${APP_NAME} -f"
echo "────────────────────────────────────────────"
