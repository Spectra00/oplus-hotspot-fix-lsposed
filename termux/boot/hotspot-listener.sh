#!/data/data/com.termux/files/usr/bin/bash
# Install: copy this file to ~/.termux/boot/hotspot-listener.sh (requires the
# separate Termux:Boot app - see the README) so the listener starts
# automatically on every reboot without opening Termux manually.

termux-wake-lock

LISTENER="$HOME/hotspot_listener.py"
LOG="$HOME/hotspot_listener.log"
SUPERVISOR_LOG="$HOME/hotspot_listener_supervisor.log"

# Restart the listener if it's already somehow running (e.g. after a
# Termux:Boot re-trigger) rather than stacking duplicate listeners on the
# same port. This also kills any previous supervisor loop below.
pkill -f "hotspot_listener.py" 2>/dev/null
pkill -f "hotspot_listener_supervisor" 2>/dev/null

# Android's OOM killer can reclaim a backgrounded process under memory
# pressure regardless of the wake lock above (the wake lock only prevents
# Doze/App Standby throttling, not outright low-memory reclaim) - so
# rather than trying to make the listener unkillable, just respawn it
# automatically if it ever dies, instead of staying dead until someone
# notices and manually restarts it.
nohup bash -c '
    # hotspot_listener_supervisor
    while true; do
        echo "supervisor: starting listener at $(date)" >> "'"$SUPERVISOR_LOG"'"
        python3 -u "'"$LISTENER"'" >> "'"$LOG"'" 2>&1
        echo "supervisor: listener exited at $(date), restarting in 2s" >> "'"$SUPERVISOR_LOG"'"
        sleep 2
    done
' >> "$SUPERVISOR_LOG" 2>&1 &
