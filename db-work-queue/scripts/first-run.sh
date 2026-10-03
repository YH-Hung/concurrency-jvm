#!/usr/bin/env bash
# Bash 3.2 compatible. Starts a local demo DB, then processes exactly one fresh batch.
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
demo="$root/work-queue-demo"
worker_pid=''
step_pid=''

cleanup() {
    result=$?
    trap - EXIT INT TERM
    # These are only children launched by this invocation. Share one shutdown allowance.
    deadline=$((SECONDS + 35))
    for pid in "$step_pid" "$worker_pid"; do
        if [ -n "$pid" ]; then kill -TERM "$pid" 2>/dev/null || true; fi
    done
    for pid in "$step_pid" "$worker_pid"; do
        if [ -n "$pid" ]; then
            while kill -0 "$pid" 2>/dev/null && [ "$SECONDS" -lt "$deadline" ]; do sleep 0.2; done
            if kill -0 "$pid" 2>/dev/null; then kill -KILL "$pid" 2>/dev/null || true; fi
            wait "$pid" 2>/dev/null || true
        fi
    done
    exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

run_step() {
    "$@" &
    step_pid=$!
    local result=0
    wait "$step_pid" || result=$?
    step_pid=''
    return "$result"
}
fail() { printf '%s\n' "$*" >&2; exit 1; }
for command in java docker openssl; do command -v "$command" >/dev/null || fail "Required command missing: $command"; done
java -version 2>&1 | grep -Eq 'version "25([.\"-])' || fail 'Use JDK 25 to run this example.'
docker compose version >/dev/null || fail 'Docker Compose is required.'

mkdir -p "$demo/target/first-run"
run_dir=$(mktemp -d "$demo/target/first-run/run.XXXXXX")
printf 'Run files: %s\n' "$run_dir"
printf 'Building the example…\n'
run_step "$root/mvnw" -f "$root/pom.xml" -pl work-queue-demo -am package -DskipTests >"$run_dir/build.log" 2>&1 || fail "Build failed; see $run_dir/build.log"

if [ ! -f "$demo/.env" ]; then
    (umask 077; password=$(openssl rand -hex 24); printf 'DB2INST1_PASSWORD=%s\n' "$password" > "$demo/.env")
fi
chmod 600 "$demo/.env"
# Parse only this value. Never execute an environment file as shell code.
password=$(sed -n 's/^DB2INST1_PASSWORD=//p' "$demo/.env")
[[ "$password" =~ ^[a-fA-F0-9]{48}$ ]] || fail 'The demo .env must contain one DB2INST1_PASSWORD with 48 hexadecimal characters. Preserve its original value when reusing the data volume.'
export DB2INST1_PASSWORD="$password" WORKQUEUE_DB_PASSWORD="$password" SPRING_DATASOURCE_PASSWORD="$password"
unset password
export WORKQUEUE_JDBC_URL='jdbc:db2://127.0.0.1:50000/WORKQ' WORKQUEUE_DB_USER='db2inst1' WORKQUEUE_NAMESPACE='demo'
compose=(docker compose --project-name workqueue-first-run --env-file "$demo/.env" -f "$demo/compose.yml")
printf 'Starting local Db2 (the first start can take 5–10 minutes)…\n'
run_step "${compose[@]}" up -d >"$run_dir/database.log" 2>&1 || fail "Db2 startup failed; check Docker, memory and port 50000. See $run_dir/database.log"
limit=${FIRST_RUN_DB_TIMEOUT_SECONDS:-900}
[[ "$limit" =~ ^[0-9]+$ ]] && [ "$limit" -gt 0 ] && [ "$limit" -le 900 ] || fail 'FIRST_RUN_DB_TIMEOUT_SECONDS must be 1–900.'
ready_deadline=$((SECONDS + limit))
while :; do
    "${compose[@]}" exec -T db2 timeout 10s su - db2inst1 -c 'db2 connect to WORKQ >/dev/null && db2 -x "VALUES 1" >/dev/null' >>"$run_dir/database.log" 2>&1 &
    step_pid=$!
    while kill -0 "$step_pid" 2>/dev/null; do
        [ "$SECONDS" -lt "$ready_deadline" ] || fail "Db2 did not become query-ready within ${limit}s; see $run_dir/database.log"
        sleep 0.2
    done
    result=0
    wait "$step_pid" || result=$?
    step_pid=''
    [ "$result" -eq 0 ] && break
    [ "$SECONDS" -lt "$ready_deadline" ] || fail "Db2 did not become query-ready within ${limit}s; see $run_dir/database.log"
    sleep 1
done
jar="$demo/target/work-queue-demo.jar"
# Command-line URL/namespace win over inherited Spring datasource settings; credentials stay in the environment.
java_command=(java -jar "$jar" '--spring.datasource.url=jdbc:db2://127.0.0.1:50000/WORKQ' '--spring.datasource.username=db2inst1' '--workqueue.expected-namespace=demo')
run_step "${java_command[@]}" --demo.mode=migrate >"$run_dir/migrate.log" 2>&1 || fail "Migration failed; see $run_dir/migrate.log"
run_step "${java_command[@]}" --demo.mode=seed "--demo.batch-file=$run_dir/batch.ids" >"$run_dir/seed.log" 2>&1 || fail "Seed failed; see $run_dir/seed.log"
printf 'Processing a fresh batch; worker log: %s\n' "$run_dir/worker.log"
"${java_command[@]}" --demo.mode=worker >"$run_dir/worker.log" 2>&1 &
worker_pid=$!
"${java_command[@]}" --demo.mode=verify "--demo.batch-file=$run_dir/batch.ids" >"$run_dir/verify.log" 2>&1 &
step_pid=$!
while kill -0 "$step_pid" 2>/dev/null; do
    kill -0 "$worker_pid" 2>/dev/null || fail "Worker exited before verification finished; see $run_dir/worker.log"
    sleep 0.2
done
result=0
wait "$step_pid" || result=$?
step_pid=''
cat "$run_dir/verify.log"
[ "$result" -eq 0 ] || fail 'Batch verification failed.'
printf 'Batch complete. Stopping this worker; Db2 remains running for inspection.\n'
