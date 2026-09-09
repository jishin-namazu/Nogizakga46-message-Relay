#!/bin/sh
# Start the API first. Chromium and the media service start only after the
# API has completed initialization and serves /health.
npm start &
API_PID=$!

cleanup() {
  kill "$API_PID" 2>/dev/null || true
}
trap cleanup INT TERM EXIT

until node -e "fetch('http://127.0.0.1:${PORT:-8080}/health').then(r => { if (!r.ok) process.exit(1); }).catch(() => process.exit(1))"; do
  if ! kill -0 "$API_PID" 2>/dev/null; then
    wait "$API_PID"
    exit $?
  fi
  sleep 1
done

echo "API health check passed; starting monitor services"
npm run monitor
