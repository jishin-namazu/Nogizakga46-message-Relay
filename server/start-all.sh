#!/bin/sh
# Start both API server and monitor in the same container

# Start API server in background
npm start &

# Start monitor in foreground
npm run monitor
