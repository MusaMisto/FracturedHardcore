#!/usr/bin/env bash
# Rolling world backup for a Fabric dedicated server.
# Requires: enable-rcon=true and rcon.password set in server.properties, and `mcrcon` on PATH (https://github.com/Tiiffi/mcrcon).
# Usage:  scripts/backup.sh <server-dir> <backup-dir> [keep=48]
# Cron (hourly, keep two days):  0 * * * * /path/to/scripts/backup.sh /srv/mc /srv/backups 48 >> /srv/backups/backup.log 2>&1
set -euo pipefail

SERVER_DIR="${1:?server dir}"
BACKUP_DIR="${2:?backup dir}"
KEEP="${3:-48}"

prop() { grep -E "^$1=" "$SERVER_DIR/server.properties" | head -1 | cut -d= -f2- || true; }
RCON_PORT="$(prop rcon.port)"; RCON_PORT="${RCON_PORT:-25575}"
RCON_PASS="$(prop rcon.password)"
LEVEL="$(prop level-name)"; LEVEL="${LEVEL:-world}"

rcon() { mcrcon -H 127.0.0.1 -P "$RCON_PORT" -p "$RCON_PASS" "$@"; }

mkdir -p "$BACKUP_DIR"
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT="$BACKUP_DIR/$LEVEL-$STAMP.tar.gz"

# Pause chunk saving and flush everything (including <world>/data/hcheart/players.dat) to disk first.
rcon "save-off" "save-all flush" >/dev/null
trap 'rcon "save-on" >/dev/null || true' EXIT
sleep 5

if [ -f "$SERVER_DIR/logs/hcheart-audit.log" ]; then
	tar -C "$SERVER_DIR" -czf "$OUT" "$LEVEL" "logs/hcheart-audit.log"
else
	tar -C "$SERVER_DIR" -czf "$OUT" "$LEVEL"
fi

# Rotate: keep the newest $KEEP archives.
ls -1t "$BACKUP_DIR"/"$LEVEL"-*.tar.gz | tail -n +$((KEEP + 1)) | xargs -r rm -f
echo "$(date -Iseconds) backup written: $OUT"
