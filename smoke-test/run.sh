#!/usr/bin/env bash
# Starts a MongoDB replica set (3 nodes), two Paprika instances on one storage directory and nginx in front of them,
# then runs the scenarios of SmokeTest.java. Requires Java 25, Docker with the nginx:1.27-alpine image and a mongod
# binary (MONGOD, defaults to the one cached by the embedded MongoDB of the test suite).
set -euo pipefail
cd "$(dirname "$0")"

ROOT="$(cd .. && pwd)"
WORK="$PWD/target/run"
MONGOD="${MONGOD:-$(find "$HOME/.embedmongo" -name mongod -type f 2>/dev/null | head -1)}"
NGINX_IMAGE="${NGINX_IMAGE:-nginx:1.27-alpine}"
[ -x "$MONGOD" ] || { echo "No mongod binary found, set MONGOD"; exit 1; }

cleanup() {
    docker rm -f paprika-smoke-nginx >/dev/null 2>&1 || true
    for pidfile in "$WORK"/*.pid; do
        [ -f "$pidfile" ] && kill "$(cat "$pidfile")" 2>/dev/null || true
    done
}
trap cleanup EXIT

rm -rf "$WORK" && mkdir -p "$WORK/storage"

echo "Building Paprika"
(cd "$ROOT" && mvn -o -q compile dependency:build-classpath -Dmdep.outputFile="$WORK/cp.txt" -Dmdep.includeScope=runtime)
CP="$ROOT/target/classes:$(cat "$WORK/cp.txt")"

echo "Starting the replica set"
for port in 27217 27218 27219; do
    mkdir -p "$WORK/db-$port"
    "$MONGOD" --replSet rs0 --port "$port" --dbpath "$WORK/db-$port" --bind_ip localhost --logpath "$WORK/mongod-$port.log" &
    echo $! > "$WORK/mongod-$port.pid"
done
sleep 2
java -cp "$CP" SmokeTest.java init

# Both at once against the empty database, as a first deployment with two replicas would start
echo "Starting instances A (9201) and B (9202)"
for port in 9201 9202; do
    PAPRIKA_LOG_PATH="$WORK/logs-$port" java -Dapplication.mode=prod -Dapplication.config="$PWD/config.yaml" \
        -Dconnector.http.port="$port" -Dpaprika.storage="$WORK/storage" \
        -cp "$CP" io.mangoo.core.Application > "$WORK/app-$port.log" 2>&1 &
    echo $! > "$WORK/app-$port.pid"
done
for port in 9201 9202; do
    for _ in $(seq 1 120); do
        curl -fs "http://localhost:$port/@health/ready" >/dev/null 2>&1 && break
        sleep 0.5
    done
    curl -fs "http://localhost:$port/@health/ready" >/dev/null || { echo "Instance $port did not become ready"; tail -40 "$WORK/app-$port.log"; exit 1; }
done

echo "Starting nginx (9200)"
docker run -d --name paprika-smoke-nginx -p 9200:9200 -v "$PWD/nginx.conf:/etc/nginx/conf.d/default.conf:ro" "$NGINX_IMAGE" >/dev/null
for _ in $(seq 1 40); do
    curl -fs "http://localhost:9200/@health/ready" >/dev/null 2>&1 && break
    sleep 0.5
done

echo
java -cp "$CP" SmokeTest.java run "$WORK"
