#!/system/bin/sh
MODDIR=${0%/*}
MARKERS="$MODDIR/markers"
LOG="$MODDIR/localization-status.log"

count=0
while [ "$(getprop sys.boot_completed)" != "1" ] && [ "$count" -lt 120 ]; do
  sleep 2
  count=$((count + 1))
done

if sh "$MODDIR/boot-tasks.sh" apply "$MODDIR" "${NVBASE:-/data/adb}"; then
  mipush_status='lifecycle_checked (see /data/adb/pudding-eu-localization-state/lifecycle.log)'
else
  mipush_status='lifecycle_failed_or_busy; original backups retained'
fi

if [ -f "$MARKERS/ThemeStore" ]; then
  mkdir -p /sdcard/MIUI/theme 2>/dev/null
  touch /sdcard/MIUI/theme/.nomedia 2>/dev/null
fi

if [ -f "$MODDIR/diagnostics.sh" ]; then
  . "$MODDIR/diagnostics.sh"
  {
    echo "=== environment ==="
    diag_environment
    echo "mipush=$mipush_status"
    echo "=== packages ==="
    diag_selected_packages
    echo "=== overlays ==="
    diag_overlays
    echo "=== hooks ==="
    diag_hooks
    echo "=== package/overlay errors ==="
    logcat -d -b system -t 1200 2>/dev/null       | grep -E -i 'signature|reconcile|PackageManager|PuddingEU|idmap|overlay'       | tail -n 120
  } > "$LOG"
else
  {
    echo "checked=$(date '+%F %T')"
    echo "diagnostics=missing"
    echo "mipush=$mipush_status"
  } > "$LOG"
fi
