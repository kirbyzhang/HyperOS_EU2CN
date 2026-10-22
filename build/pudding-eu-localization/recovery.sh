#!/system/bin/sh
# Installed outside the module so a disabled/unmounted module can restore its own overrides.
ADB_BASE=${0%/service.d/*}
count=0
while [ "$(getprop sys.boot_completed)" != 1 ] && [ "$count" -lt 120 ]; do
  sleep 2
  count=$((count + 1))
done
helper="$ADB_BASE/pudding-eu-localization-state/boot-tasks.sh"
[ -f "$helper" ] || exit 0
sh "$helper" recover "$ADB_BASE/modules/HyperOS_EU2CN" "$ADB_BASE"
