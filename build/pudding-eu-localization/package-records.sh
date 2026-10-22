#!/system/bin/sh
# Precise PackageManager parse-cache invalidation at APK transitions. No app data.
set -eu
umask 077
ACTION=${1:?install or restore}
MODULE=${2:?module path}
ADB_BASE=${3:-/data/adb}
PREVIOUS=${4:-$MODULE}
if [ "${PUDDING_STATE_LOCKED:-}" != 1 ]; then
  sh "${0%/*}/state-lock.sh" "$ADB_BASE" "$0" "$@"
  exit $?
fi
CACHE=${PUDDING_PACKAGE_CACHE:-/data/system/package_cache}
TARGETS=${PUDDING_CACHE_TARGETS:-$MODULE/cache-targets.tsv}
case "$ACTION" in install|restore) ;; *) exit 1 ;; esac
[ -f "$TARGETS" ] && [ ! -L "$TARGETS" ] || exit 1
[ -d "$CACHE" ] || exit 0
[ ! -L "$CACHE" ] && [ "$(readlink -f "$CACHE")" = "$CACHE" ] || exit 1
BACKUPS="$ADB_BASE/pudding-package-cache-backups"
[ ! -L "$BACKUPS" ] || exit 1
mkdir -p "$BACKUPS"
chmod 700 "$BACKUPS"
BACKUP=
result=0
PM_READY=$(getprop sys.boot_completed 2>/dev/null || true)
if [ "$ACTION" = install ]; then : > "$MODULE/cache-pending.tsv"; fi
while IFS='|' read -r pkg relative expected_sha expected_code key marker; do
  [ -n "$pkg" ] || continue
  case "$relative" in system/*/*.apk) ;; *) exit 1 ;; esac
  case "$relative" in *..*|*//*|*[!a-zA-Z0-9_./-]*) exit 1 ;; esac
  case "$pkg$key$marker$expected_sha$expected_code" in *[!a-zA-Z0-9_.-]*) exit 1 ;; esac
  selected=no
  old_selected=no
  [ ! -f "$MODULE/$relative" ] || selected=yes
  [ ! -f "$PREVIOUS/$relative" ] || old_selected=yes
  if [ "$ACTION" = restore ] && [ -f "$MODULE/cache-enrolled" ]; then selected=yes; fi
  [ "$selected$old_selected" != nono ] || continue
  paths=
  if [ "$PM_READY" = 1 ]; then paths=$(pm path "$pkg" 2>/dev/null || true); fi
  # Active APK updates remain owned by the Android installer.
  case "$paths" in *package:/data/app/*) echo "PRESERVED_USER_UPDATE|$pkg"; continue ;; esac
  if [ "$ACTION" = install ] && [ "$selected" = yes ]; then
    [ ! -L "$MODULE/$relative" ] || exit 1
    [ "$(sha256sum "$MODULE/$relative" | cut -d ' ' -f1)" = "$expected_sha" ] || exit 1
    current_code=
    if [ "$PM_READY" = 1 ]; then current_code=$(dumpsys package "$pkg" 2>/dev/null | awk '/^[[:space:]]*versionCode=/ && !seen++ {sub(/^[[:space:]]*versionCode=/, ""); sub(/ .*/, ""); print}' || true); fi
    old_sha=
    [ "$old_selected" != yes ] || old_sha=$(sha256sum "$PREVIOUS/$relative" | cut -d ' ' -f1)
    if [ "$current_code" = "$expected_code" ] && [ "$old_sha" = "$expected_sha" ]; then continue; fi
  fi
  if [ "$ACTION" = install ]; then
    printf '%s|%s|%s|%s|%s|%s\n' "$pkg" "$relative" "$expected_sha" "$expected_code" "$key" "$marker" >> "$MODULE/cache-pending.tsv"
  fi
  for dir in "$CACHE"/*; do
    [ -d "$dir" ] && [ ! -L "$dir" ] || continue
    file="$dir/$key"
    [ -f "$file" ] && [ ! -L "$file" ] || continue
    [ "$(readlink -f "$file")" = "$file" ] || { result=1; continue; }
    # Require the package's UTF16LE identity in the exact flags=16 cache key.
    od -An -v -tx1 "$file" | tr -d ' \n' | grep -q "$marker" || { echo "UNVERIFIED_CACHE|$pkg"; result=1; continue; }
    if [ -z "$BACKUP" ]; then BACKUP=$(mktemp -d "$BACKUPS/transition-XXXXXXXX"); fi
    name="${dir##*/}-$key"
    sha=$(sha256sum "$file" | cut -d ' ' -f1)
    cp -p "$file" "$BACKUP/$name"
    [ "$(sha256sum "$BACKUP/$name" | cut -d ' ' -f1)" = "$sha" ] || exit 1
    cmp -s "$file" "$BACKUP/$name" || exit 1
    # Recheck update state immediately before touching the system parse cache.
    paths=
    if [ "$PM_READY" = 1 ]; then paths=$(pm path "$pkg" 2>/dev/null || true); fi
    case "$paths" in *package:/data/app/*) echo "PRESERVED_USER_UPDATE|$pkg"; continue ;; esac
    printf '%s|%s|%s|%s\n' "$ACTION" "$pkg" "$file" "$sha" >> "$BACKUP/manifest.txt"
    rm -f -- "$file"
    echo "PARSE_CACHE_REBUILD_PENDING|$pkg|$BACKUP"
  done
done < "$TARGETS"
exit "$result"
