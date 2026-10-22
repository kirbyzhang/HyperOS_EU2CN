#!/system/bin/sh
# Reparse module-managed system APKs at next boot; retain all app data/updates.
MODDIR=${0%/*}
sh "$MODDIR/package-records.sh" restore "$MODDIR" "${NVBASE:-/data/adb}" || exit 1
sh "$MODDIR/lifecycle.sh" restore "$MODDIR" "${NVBASE:-/data/adb}"
