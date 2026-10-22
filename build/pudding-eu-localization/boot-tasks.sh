#!/system/bin/sh
# Bounded retries; each attempt rechecks selection/disable/remove and takes its own lock.
ACTION=${1:-recover}
MODULE=${2:-/data/adb/modules/HyperOS_EU2CN}
ADB_BASE=${3:-/data/adb}
STATE="$ADB_BASE/pudding-eu-localization-state"
HELPER="$STATE/lifecycle.sh"
[ -f "$HELPER" ] || HELPER="$MODULE/lifecycle.sh"
attempt=1
while [ "$attempt" -le 6 ]; do
  if sh "$HELPER" "$ACTION" "$MODULE" "$ADB_BASE"; then exit 0; fi
  [ ! -d "$STATE" ] || printf '%s action=%s attempt=%s/6 status=retry_pending\n' "$(date '+%F %T')" "$ACTION" "$attempt" >> "$STATE/lifecycle.log"
  [ "$attempt" -lt 6 ] || break
  sleep 10
  attempt=$((attempt + 1))
done
[ ! -d "$STATE" ] || printf '%s action=%s status=retry_exhausted; inspect lifecycle.log\n' "$(date '+%F %T')" "$ACTION" >> "$STATE/lifecycle.log"
exit 1
