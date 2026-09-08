#!/usr/bin/env bash
set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BOLD='\033[1m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REGISTRY="ghcr.io/svenkubiak/paprika"

step() { echo -e "\n${BOLD}==> $1${NC}"; }
fail() { echo -e "\n${RED}ERROR: $1${NC}" >&2; exit 1; }
ok()   { echo -e "${GREEN}done${NC}"; }

cd "$SCRIPT_DIR"

# ─── 1. Uncommitted changes ───────────────────────────────────────────────────
step "1/9  Checking working tree"
if ! git diff --quiet || ! git diff --cached --quiet; then
    fail "Uncommitted changes detected. Commit or stash them first."
fi
ok

# ─── 2. Maven build ───────────────────────────────────────────────────────────
step "2/9  Running mvn clean verify"
mvn clean verify || fail "Maven build failed."

# ─── 3. npm outdated ──────────────────────────────────────────────────────────
step "3/9  Checking npm dependencies"
OUTDATED=0

cd admin-ui
echo -e "${BOLD}admin-ui:${NC}"
if ! npm outdated; then OUTDATED=1; fi
cd "$SCRIPT_DIR"

cd docs
echo -e "${BOLD}docs:${NC}"
if ! npm outdated; then OUTDATED=1; fi
cd "$SCRIPT_DIR"

if [[ "$OUTDATED" -eq 1 ]]; then
    echo -e "\n${YELLOW}Outdated npm packages found (see above).${NC}"
    read -rp "Continue release anyway? [y/N]: " CONTINUE_RELEASE
    if [[ ! "$CONTINUE_RELEASE" =~ ^[yY]$ ]]; then
        fail "Release aborted due to outdated npm packages."
    fi
fi
ok

# ─── 4. Determine release version ─────────────────────────────────────────────
step "4/9  Determining release version"
CURRENT_VERSION="$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)"

if [[ ! "$CURRENT_VERSION" =~ -SNAPSHOT$ ]]; then
    fail "Current version '${CURRENT_VERSION}' is not a SNAPSHOT. Set a SNAPSHOT version first."
fi

RELEASE_VERSION="${CURRENT_VERSION%-SNAPSHOT}"

if [[ ! "$RELEASE_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    fail "Version '${RELEASE_VERSION}' is not valid SemVer 2.0.0 (MAJOR.MINOR.PATCH)."
fi

echo -e "Current  : ${YELLOW}${CURRENT_VERSION}${NC}"
echo -e "Proposed : ${YELLOW}${RELEASE_VERSION}${NC}"
read -rp "Confirm release version [${RELEASE_VERSION}]: " INPUT_VERSION
RELEASE_VERSION="${INPUT_VERSION:-$RELEASE_VERSION}"

if [[ ! "$RELEASE_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    fail "Version '${RELEASE_VERSION}' is not valid SemVer 2.0.0 (MAJOR.MINOR.PATCH)."
fi

# ─── 5. Set Maven version ─────────────────────────────────────────────────────
step "5/9  Setting Maven version to ${RELEASE_VERSION}"
mvn versions:set -DnewVersion="$RELEASE_VERSION" -DgenerateBackupPoms=false -q
ok

# ─── 6. Build release artifact ────────────────────────────────────────────────
step "6/9  Building release artifact"
mvn clean package -DskipTests -q || fail "Maven package failed."

# ─── 7. Commit, tag, push ─────────────────────────────────────────────────────
step "7/9  Committing, tagging and pushing"
git add pom.xml
git commit -m "Release ${RELEASE_VERSION}"
git tag -a "${RELEASE_VERSION}" -m "Release ${RELEASE_VERSION}"
git push origin main
git push origin "${RELEASE_VERSION}"
ok

# ─── 8. Build and push Docker image ──────────────────────────────────────────
step "8/9  Building Docker image from tag v${RELEASE_VERSION}"
git checkout "${RELEASE_VERSION}"

IMAGE="${REGISTRY}:${RELEASE_VERSION}"
IMAGE_LATEST="${REGISTRY}:latest"

docker build \
    --pull \
    --no-cache \
    -f Dockerfile \
    -t "${IMAGE}" \
    -t "${IMAGE_LATEST}" \
    .

docker push "${IMAGE}"
docker push "${IMAGE_LATEST}"

git checkout main
ok

# ─── 9. Prepare next SNAPSHOT ────────────────────────────────────────────────
step "9/9  Preparing next SNAPSHOT"
IFS='.' read -r MAJOR MINOR PATCH <<< "$RELEASE_VERSION"
NEXT_SNAPSHOT="${MAJOR}.${MINOR}.$((PATCH + 1))-SNAPSHOT"

echo -e "Proposed : ${YELLOW}${NEXT_SNAPSHOT}${NC}"
read -rp "Confirm next SNAPSHOT version [${NEXT_SNAPSHOT}]: " INPUT_NEXT
NEXT_SNAPSHOT="${INPUT_NEXT:-$NEXT_SNAPSHOT}"

if [[ ! "$NEXT_SNAPSHOT" =~ ^[0-9]+\.[0-9]+\.[0-9]+-SNAPSHOT$ ]]; then
    fail "Version '${NEXT_SNAPSHOT}' is not valid SemVer 2.0.0 SNAPSHOT (MAJOR.MINOR.PATCH-SNAPSHOT)."
fi

mvn versions:set -DnewVersion="$NEXT_SNAPSHOT" -DgenerateBackupPoms=false -q
git add pom.xml
git commit -m "Prepare ${NEXT_SNAPSHOT}"
git push origin main
ok

echo -e "\n${GREEN}${BOLD}Release ${RELEASE_VERSION} complete.${NC}"
echo -e "  Docker : ${IMAGE}"
echo -e "  Tag    : ${RELEASE_VERSION}"
echo -e "  Next   : ${NEXT_SNAPSHOT}"
