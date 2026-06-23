#!/usr/bin/env bash
# One command to run JobMatch Engine: Spring Boot API (:8080) + Next.js UI (:3000).
#   ./run.sh
# Ctrl-C stops both.
set -e
cd "$(dirname "$0")"

JAR="target/jobmatch-engine-0.1.0.jar"

echo "==> Freeing ports 8080 / 3000"
lsof -ti:8080 | xargs kill 2>/dev/null || true
lsof -ti:3000 | xargs kill 2>/dev/null || true

echo "==> Building backend (skip if jar exists; delete target/ to force rebuild)"
[ -f "$JAR" ] || mvn -q -DskipTests package

echo "==> Starting Spring Boot API on :8080"
java -jar "$JAR" > /tmp/jobmatch-api.log 2>&1 &
API_PID=$!

echo "==> Installing frontend deps (first run only)"
[ -d frontend/node_modules ] || (cd frontend && npm install)

echo "==> Starting Next.js UI on :3000"
(cd frontend && npm run dev > /tmp/jobmatch-ui.log 2>&1) &
UI_PID=$!

trap 'echo; echo "==> Stopping"; kill $API_PID $UI_PID 2>/dev/null; exit 0' INT TERM

echo -n "==> Waiting for API"
for i in $(seq 1 60); do
  grep -q "Started JobMatch" /tmp/jobmatch-api.log 2>/dev/null && break
  echo -n "."; sleep 1
done
echo " ready"

echo -n "==> Waiting for UI"
for i in $(seq 1 60); do
  grep -qE "Ready in|Local:" /tmp/jobmatch-ui.log 2>/dev/null && break
  echo -n "."; sleep 1
done
echo " ready"

echo ""
echo "  ✅ App:  http://localhost:3000   (API: http://localhost:8080)"
echo "  Logs:    /tmp/jobmatch-api.log  /tmp/jobmatch-ui.log"
echo "  Ctrl-C to stop both."
( command -v open >/dev/null && open http://localhost:3000 ) || true
wait
