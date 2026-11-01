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

# The release commit and the next SNAPSHOT land on the branch the release is made from
BRANCH="$(git symbolic-ref --quiet --short HEAD)" || fail "Not on a branch (detached HEAD)."
if [[ "$BRANCH" != "main" ]]; then
    echo -e "${YELLOW}Releasing from branch '${BRANCH}', not from main.${NC}"
    read -rp "Continue? [y/N]: " CONTINUE_BRANCH
    if [[ ! "$CONTINUE_BRANCH" =~ ^[yY]$ ]]; then
        fail "Release aborted."
    fi
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

SNAPSHOT_BASE="${CURRENT_VERSION%-SNAPSHOT}"

# A release is MAJOR.MINOR.PATCH, a beta a pre-release of it such as 1.0.0-Beta1
RELEASE_PATTERN='^[0-9]+\.[0-9]+\.[0-9]+$'
BETA_PATTERN='^[0-9]+\.[0-9]+\.[0-9]+-[0-9A-Za-z.]+$'

if [[ ! "$SNAPSHOT_BASE" =~ $RELEASE_PATTERN ]]; then
    fail "Version '${CURRENT_VERSION}' is not valid SemVer 2.0.0 (MAJOR.MINOR.PATCH-SNAPSHOT)."
fi

# Tags pushed from elsewhere count as well, so no beta number is handed out twice
if ! git fetch --quiet --tags origin; then
    echo -e "${YELLOW}Could not fetch the tags from origin, using the local ones.${NC}"
fi

echo -e "Current  : ${YELLOW}${CURRENT_VERSION}${NC}"
echo -e "  [r] Release ${SNAPSHOT_BASE}: also tagged latest, which every installation without a pinned version gets"
echo -e "  [b] Beta of ${SNAPSHOT_BASE}: a pre-release, latest stays where it is"

# An explicit answer, no default: a stray Enter must not publish a release as latest
RELEASE_TYPE=""
while [[ "$RELEASE_TYPE" != "r" && "$RELEASE_TYPE" != "b" ]]; do
    read -rp "Release or beta? [r/b]: " RELEASE_TYPE
    RELEASE_TYPE="$(echo "$RELEASE_TYPE" | tr '[:upper:]' '[:lower:]')"
done

if [[ "$RELEASE_TYPE" == "r" ]]; then
    PROPOSED="$SNAPSHOT_BASE"
else
    LAST_BETA="$(git tag --list "${SNAPSHOT_BASE}-Beta*" \
        | grep -E "^${SNAPSHOT_BASE//./\\.}-Beta[0-9]+$" \
        | sed 's/.*-Beta//' | sort -n | tail -1 || true)"
    PROPOSED="${SNAPSHOT_BASE}-Beta$(( ${LAST_BETA:-0} + 1 ))"
fi

read -rp "Confirm release version [${PROPOSED}]: " INPUT_VERSION
RELEASE_VERSION="${INPUT_VERSION:-$PROPOSED}"

if [[ "$RELEASE_TYPE" == "r" && ! "$RELEASE_VERSION" =~ $RELEASE_PATTERN ]]; then
    fail "A release is MAJOR.MINOR.PATCH, '${RELEASE_VERSION}' is not. Choose beta for a pre-release."
fi
if [[ "$RELEASE_TYPE" == "b" ]] && { [[ ! "$RELEASE_VERSION" =~ $BETA_PATTERN ]] || [[ "$RELEASE_VERSION" == *-SNAPSHOT ]]; }; then
    fail "A beta is MAJOR.MINOR.PATCH-suffix such as ${PROPOSED}, '${RELEASE_VERSION}' is not."
fi
if git rev-parse --quiet --verify "refs/tags/${RELEASE_VERSION}" >/dev/null; then
    fail "Tag '${RELEASE_VERSION}' already exists."
fi

# A beta is published like a release, but never as the latest one
RELEASE_BASE="${RELEASE_VERSION%%-*}"
PRERELEASE=false
if [[ "$RELEASE_TYPE" == "b" ]]; then
    PRERELEASE=true
fi

# ─── 5. Set Maven version ─────────────────────────────────────────────────────
step "5/9  Setting Maven version to ${RELEASE_VERSION}"
mvn versions:set -DnewVersion="$RELEASE_VERSION" -DgenerateBackupPoms=false -q
ok

# ─── 6. Build release artifact ────────────────────────────────────────────────
step "6/9  Building release artifact"
# Same tests as step 2. The package build is what produces the jar that gets tagged,
# so it runs them again instead of packaging with -DskipTests.
mvn clean package -q || fail "Maven package failed."

# ─── 7. Commit, tag, push ─────────────────────────────────────────────────────
step "7/9  Committing, tagging and pushing"
git add pom.xml
git commit -m "Release ${RELEASE_VERSION}"
git tag -a "${RELEASE_VERSION}" -m "Release ${RELEASE_VERSION}"
git push origin "${BRANCH}"
git push origin "${RELEASE_VERSION}"
ok

# ─── 8. Build and push Docker image ──────────────────────────────────────────
step "8/9  Building and pushing Docker image (linux/arm64, linux/amd64)"
git checkout "${RELEASE_VERSION}"

IMAGE="${REGISTRY}:${RELEASE_VERSION}"
IMAGE_LATEST="${REGISTRY}:latest"
BUILDER="paprika-release"

# latest is what every installation without a pinned version pulls, so a pre-release never gets it
IMAGE_TAGS=(-t "${IMAGE}")
if [[ "$PRERELEASE" == false ]]; then
    IMAGE_TAGS+=(-t "${IMAGE_LATEST}")
fi

# The default docker driver builds one platform. A container builder publishes
# both architectures under the same tag as one manifest list.
if ! docker buildx inspect "${BUILDER}" >/dev/null 2>&1; then
    docker buildx create --name "${BUILDER}" --driver docker-container >/dev/null
fi
docker buildx inspect --bootstrap "${BUILDER}" >/dev/null

docker buildx build \
    --builder "${BUILDER}" \
    --pull \
    --no-cache \
    --platform linux/arm64,linux/amd64 \
    -f Dockerfile \
    "${IMAGE_TAGS[@]}" \
    --push \
    .

git checkout "${BRANCH}"
ok

# ─── 9. Prepare next SNAPSHOT ────────────────────────────────────────────────
step "9/9  Preparing next SNAPSHOT"
# After a pre-release, development continues towards its release
if [[ "$PRERELEASE" == true ]]; then
    NEXT_SNAPSHOT="${RELEASE_BASE}-SNAPSHOT"
else
    IFS='.' read -r MAJOR MINOR PATCH <<< "$RELEASE_VERSION"
    NEXT_SNAPSHOT="${MAJOR}.${MINOR}.$((PATCH + 1))-SNAPSHOT"
fi

echo -e "Proposed : ${YELLOW}${NEXT_SNAPSHOT}${NC}"
read -rp "Confirm next SNAPSHOT version [${NEXT_SNAPSHOT}]: " INPUT_NEXT
NEXT_SNAPSHOT="${INPUT_NEXT:-$NEXT_SNAPSHOT}"

if [[ ! "$NEXT_SNAPSHOT" =~ ^[0-9]+\.[0-9]+\.[0-9]+-SNAPSHOT$ ]]; then
    fail "Version '${NEXT_SNAPSHOT}' is not valid SemVer 2.0.0 SNAPSHOT (MAJOR.MINOR.PATCH-SNAPSHOT)."
fi

mvn versions:set -DnewVersion="$NEXT_SNAPSHOT" -DgenerateBackupPoms=false -q
git add pom.xml
git commit -m "Prepare ${NEXT_SNAPSHOT}"
git push origin "${BRANCH}"
ok

echo -e "\n${GREEN}${BOLD}Release ${RELEASE_VERSION} complete.${NC}"
if [[ "$PRERELEASE" == true ]]; then
    echo -e "  Docker : ${IMAGE} (linux/arm64, linux/amd64), pre-release, latest unchanged"
else
    echo -e "  Docker : ${IMAGE} and latest (linux/arm64, linux/amd64)"
fi
echo -e "  Tag    : ${RELEASE_VERSION}"
echo -e "  Next   : ${NEXT_SNAPSHOT}"
