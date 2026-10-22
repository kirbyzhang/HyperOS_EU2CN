#!/system/bin/sh
# Run before PackageManager scans APKs after disable/uninstall. Never reboot.
ADB_BASE=${0%/post-fs-data.d/*}
MODULE="$ADB_BASE/modules/HyperOS_EU2CN"
STATE="$ADB_BASE/pudding-eu-localization-state"
if [ "${PUDDING_STATE_LOCKED:-}" != 1 ]; then
  sh "$STATE/state-lock.sh" "$ADB_BASE" "$0" "$@"
  exit $?
fi
[ -f "$STATE/package-records.sh" ] || exit 0
# Install/option transitions are consumed before the first APK scan, including
# paths removed from the new selection. Keep pending on any validation failure.
if [ -f "$STATE/cache-pending.tsv" ]; then
  PUDDING_CACHE_TARGETS="$STATE/cache-pending.tsv" sh "$STATE/package-records.sh" restore "$STATE" "$ADB_BASE" >> "$STATE/lifecycle.log" 2>&1 || exit 1
  rm -f "$STATE/cache-pending.tsv"
fi
if [ -d "$MODULE" ] && [ ! -e "$MODULE/disable" ] && [ ! -e "$MODULE/remove" ]; then
  if [ -f "$STATE/cache-restored" ]; then
    sh "$STATE/package-records.sh" restore "$STATE" "$ADB_BASE" >> "$STATE/lifecycle.log" 2>&1 || exit 1
  fi
  rm -f "$STATE/cache-restored"
  exit 0
fi
[ ! -f "$STATE/cache-restored" ] || exit 0
if sh "$STATE/package-records.sh" restore "$STATE" "$ADB_BASE" >> "$STATE/lifecycle.log" 2>&1; then
  : > "$STATE/cache-restored"
else
  exit 1
fi
