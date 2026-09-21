#!/data/data/com.termux/files/usr/bin/bash
# Install: copy this file to ~/.termux/boot/hotspot-listener.sh (requires the
# separate Termux:Boot app - see the README) so the listener starts
# automatically on every reboot without opening Termux manually.

termux-wake-lock

LISTENER="$HOME/hotspot_listener.py"

# Restart the listener if it's already somehow running (e.g. after a
# Termux:Boot re-trigger) rather than stacking duplicate listeners on the
# same port.
pkill -f "hotspot_listener.py" 2>/dev/null

nohup python3 -u "$LISTENER" >> "$HOME/hotspot_listener.log" 2>&1 &
