#!/usr/bin/env bash
# Local development: Postgres (Docker, :5435), the Spring Boot API (:8080) and the React console (:5174).
#   ./scripts/dev.sh           start everything (builds the backend first)
#   ./scripts/dev.sh stop      stop the API and console (Postgres keeps running; `docker compose down` stops it)
#   ./scripts/dev.sh status    show what is running
# Options (environment variables):
#   SIMULATOR_ENABLED=false    turn off the demo traffic generator (on by default locally)
#   SKIP_BUILD=true            start the last built jar without rebuilding
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .run

API_PORT=8080
UI_PORT=5174

listening() { lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1; }
stop_pid() {
  local f=".run/$1.pid"
  if [ -f "$f" ]; then
    local pid; pid=$(cat "$f")
    pkill -P "$pid" 2>/dev/null || true   # child processes (npm -> node)
    kill "$pid" 2>/dev/null || true
    rm -f "$f"
  fi
}

case "${1:-start}" in
  stop)
    stop_pid backend
    stop_pid vite
    echo "Stopped the API and console. Postgres is still running (docker compose down to stop it)."
    exit 0 ;;
  status)
    pg=$(docker compose ps -a --format '{{.State}}' db 2>/dev/null); printf 'Postgres :5435  %s\n' "${pg:-not created}"
    api=$(curl -s -m 3 "localhost:$API_PORT/actuator/health" || true)
    if [ -n "$api" ]; then printf 'API      :%s  %s\n' "$API_PORT" "$api"
    elif listening "$API_PORT"; then printf 'API      :%s  port in use but not answering (database down, or still starting?)\n' "$API_PORT"
    else printf 'API      :%s  not running\n' "$API_PORT"; fi
    printf 'Console  :%s  %s\n' "$UI_PORT" "$(listening "$UI_PORT" && echo running || echo 'not running')"
    exit 0 ;;
  start) ;;
  *) echo "usage: $0 [start|stop|status]"; exit 1 ;;
esac

for port in "$API_PORT" "$UI_PORT"; do
  if listening "$port"; then
    echo "Port $port is already in use:"
    lsof -nP -iTCP:"$port" -sTCP:LISTEN | awk 'NR>1{print "  " $1 " (PID " $2 ")"}'
    echo "Run ./scripts/dev.sh stop, or stop that process, then try again."
    exit 1
  fi
done

echo "==> Postgres"
docker compose up -d db
until docker compose exec -T db pg_isready -U paysettle -d paysettle >/dev/null 2>&1; do sleep 1; done

if [ "${SKIP_BUILD:-false}" != "true" ]; then
  echo "==> Building the backend"
  ./gradlew -q :backend:bootJar
fi
cp backend/build/libs/paysettle.jar .run/paysettle.jar   # run a copy, so a rebuild doesn't touch the running jar

echo "==> Starting the API on :$API_PORT"
PORT=$API_PORT SIMULATOR_ENABLED=${SIMULATOR_ENABLED:-true} \
  nohup java -jar .run/paysettle.jar > .run/backend.log 2>&1 &
echo $! > .run/backend.pid

echo "==> Starting the console on :$UI_PORT"
[ -d frontend/node_modules ] || (cd frontend && npm install --no-audit --no-fund)
(
  cd frontend
  VITE_API_TARGET="http://localhost:$API_PORT" nohup npx vite --port "$UI_PORT" --strictPort > ../.run/vite.log 2>&1 &
  echo $! > ../.run/vite.pid
)

printf 'Waiting for the API'
for _ in $(seq 1 90); do
  curl -sf "localhost:$API_PORT/actuator/health" >/dev/null && break
  if ! kill -0 "$(cat .run/backend.pid)" 2>/dev/null; then
    echo; echo "The API failed to start. Last lines of .run/backend.log:"; tail -20 .run/backend.log; exit 1
  fi
  printf '.'; sleep 1
done
echo

cat <<MSG

Console:  http://localhost:$UI_PORT   (API key: local-operator-key or local-analyst-key)
API:      http://localhost:$API_PORT   (health: /actuator/health, metrics: /actuator/prometheus)
Logs:     .run/backend.log, .run/vite.log
Stop:     ./scripts/dev.sh stop
MSG
