#!/bin/bash
# EverStatus sleep prevention agent
# Installed at: ~/Library/Application Support/EverStatus/everstatus-agent.sh
# Managed by:   ~/Library/LaunchAgents/com.everstatus.agent.plist  (user-space, no admin)
#
# Watches a heartbeat file written by the EverStatus Java app every 60 s.
# While the file is fresh, runs caffeinate to prevent system sleep.
# When the file goes stale (> 180 s) or is removed, caffeinate is stopped.
#
# Coverage (runs as current user — same as caffeinate):
#   AC power:      prevents all sleep including lid-close  ✓
#   Battery power: prevents idle sleep; lid-close is enforced by macOS 10.14+ kernel  ⚠

CONTROL_FILE="$HOME/.everstatus.active"
STALENESS_SECS=180
POLL_SECS=5
CAFFEINATE_PID=""

is_file_fresh() {
    [ -f "$CONTROL_FILE" ] || return 1
    local mtime now age
    mtime=$(stat -f %m "$CONTROL_FILE" 2>/dev/null) || return 1
    now=$(date +%s)
    age=$(( now - mtime ))
    [ "$age" -lt "$STALENESS_SECS" ]
}

prevent_sleep() {
    if [ -z "$CAFFEINATE_PID" ] || ! kill -0 "$CAFFEINATE_PID" 2>/dev/null; then
        /usr/bin/caffeinate -d -i -m -s &
        CAFFEINATE_PID=$!
    fi
}

allow_sleep() {
    if [ -n "$CAFFEINATE_PID" ] && kill -0 "$CAFFEINATE_PID" 2>/dev/null; then
        kill "$CAFFEINATE_PID" 2>/dev/null
        wait "$CAFFEINATE_PID" 2>/dev/null
        CAFFEINATE_PID=""
    fi
}

cleanup() { allow_sleep; exit 0; }
trap cleanup TERM INT

while true; do
    if is_file_fresh; then
        prevent_sleep
    else
        allow_sleep
    fi
    sleep "$POLL_SECS"
done
