#!/system/bin/sh
# Stable lock shared by installer, early cache recovery and late lifecycle.
set -eu
umask 077
ADB_BASE=${1:?adb base}
shift
LOCK="$ADB_BASE/pudding-eu-localization.lock"
[ -d "$ADB_BASE" ] && [ ! -L "$ADB_BASE" ] && [ ! -L "$LOCK" ] || exit 1
FLOCK_BIN=$(command -v flock 2>/dev/null || true)
FLOCK_BUSYBOX=
if [ -n "$FLOCK_BIN" ] && ! "$FLOCK_BIN" --help 2>&1 | grep -Eq 'FILE|command'; then FLOCK_BIN=; fi
if [ -z "$FLOCK_BIN" ]; then
  for candidate in "$ADB_BASE/ksu/bin/busybox" "$ADB_BASE/magisk/busybox" "$ADB_BASE/ap/bin/busybox"; do
    if [ -x "$candidate" ] && "$candidate" --list 2>/dev/null | grep -qx flock; then
      FLOCK_BUSYBOX="$candidate"
      break
    fi
  done
fi
[ -n "$FLOCK_BIN$FLOCK_BUSYBOX" ] || { echo 'flock unavailable; refusing unprotected changes' >&2; exit 1; }
# Android's bundled BusyBox has no -w. Separate contention from child failure,
# using only its documented -n FILE COMMAND API. Never retry a failed mutation.
run_locked() {
  if [ -n "$FLOCK_BIN" ]; then
    "$FLOCK_BIN" -n "$LOCK" sh -c 'export PUDDING_STATE_LOCKED=1; sh "$@"; rc=$?; [ "$rc" -eq 0 ] || exit 42' sh "$@"
  else
    "$FLOCK_BUSYBOX" flock -n "$LOCK" sh -c 'export PUDDING_STATE_LOCKED=1; sh "$@"; rc=$?; [ "$rc" -eq 0 ] || exit 42' sh "$@"
  fi
}
attempt=0
while :; do
  if run_locked "$@"; then exit 0; else result=$?; fi
  [ "$result" -ne 42 ] || exit 1
  attempt=$((attempt + 1))
  [ "$attempt" -lt 20 ] || exit 1
  sleep 1
done
